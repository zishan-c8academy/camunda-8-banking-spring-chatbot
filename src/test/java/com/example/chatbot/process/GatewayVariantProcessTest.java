package com.example.chatbot.process;

import static io.camunda.process.test.api.CamundaAssert.assertThat;
import static io.camunda.process.test.api.assertions.ProcessInstanceSelectors.byKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.chatbot.bank.DemoBank;
import com.example.chatbot.conversation.ChatEventHub;
import com.example.chatbot.conversation.ConversationService;
import com.example.chatbot.conversation.ConversationService.StartedConversation;
import io.camunda.process.test.api.CamundaProcessTestContext;
import io.camunda.process.test.api.CamundaSpringProcessTest;
import io.camunda.process.test.api.assertions.UserTaskSelectors;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The gateway variant (chatbot-8.10-gateway.bpmn) with its prefixed names: process BankingChatbot_Conversation,
 * job types banking-chatbot.*, message banking-chatbot.customer-message, BankingChatbot_ DMN and form. The same worker code
 * serves these job types through the starter's worker overrides. The agent is scripted.
 *
 * <p>8.10 engine only: the gateway model reads its key with {@code =camunda.secrets.NAME}.
 */
@SpringBootTest(
    properties = {
      "camunda.chatbot.llm-provider=gateway",
      "camunda.chatbot.target=8.10",
      "camunda.chatbot.deploy-on-start=true"
    })
@CamundaSpringProcessTest
@EnabledIfSystemProperty(named = "chatbot.test.engine", matches = "8\\.10")
class GatewayVariantProcessTest {

  @Autowired private CamundaProcessTestContext processTestContext;
  @Autowired private ConversationService conversations;
  @Autowired private ChatEventHub hub;
  @Autowired private DemoBank bank;
  @Autowired private ChatbotProperties properties;

  @BeforeEach
  void reset() {
    bank.reset();
    hub.clear();
    TestSecrets.writeIntoCamundaContainers();
  }

  private void awaitText(String conversationId, String part) {
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(hub.history(conversationId)).anySatisfy(e -> assertThat(e.text()).contains(part)));
  }

  @Test
  void usesThePrefixedNames() {
    assertThat(properties.processId()).isEqualTo("BankingChatbot_Conversation");
    assertThat(properties.messageName()).isEqualTo("banking-chatbot.customer-message");
    assertThat(properties.jobTypePrefix()).isEqualTo("banking-chatbot.");
  }

  @Test
  void lostCardLoopAndHandoffRunWithPrefixedJobTypes() {
    ScriptedAgent agent = new ScriptedAgent()
        .callTool("GetAccountSummary", Map.of())
        .callTool("AskCustomer", Map.of("question", "Shall I lock your Visa card ending 4417?"))
        .callTool("LockCard", Map.of("cardLast4", "4417", "customerConfirmed", true))
        .reply("Your Visa card ending 4417 is locked.")
        .callTool("LookUpBranchHoursAndFees", Map.of("topic", "atm-fees"))
        .reply("Other ATMs in Canada cost 2.00 dollars.")
        .callTool("HandOffToAdvisor", Map.of("summary", "Wants a person.", "question", "Mortgage rates?"))
        .reply("An advisor says 4.79 percent.")
        .callTool("CloseConversation", Map.of())
        .reply("Goodbye.");
    processTestContext.mockJobWorker("io.camunda.agenticai:aiagent:subprocess:2").withHandler(agent);

    StartedConversation chat = conversations.start("C-1001", "I lost my credit card");
    awaitText(chat.conversationId(), "Checking your accounts");
    awaitText(chat.conversationId(), "Shall I lock");
    assertThat(byKey(chat.processInstanceKey()))
        .isWaitingForMessage("banking-chatbot.customer-message", chat.conversationId());
    conversations.sendMessage(chat.conversationId(), "yes");
    awaitText(chat.conversationId(), "is locked");
    assertThat(bank.card("C-1001", "4417")).get().extracting(DemoBank.Card::status).isEqualTo("LOCKED");

    conversations.sendMessage(chat.conversationId(), "What does another bank's ATM cost?");
    awaitText(chat.conversationId(), "2.00 dollars");
    assertThat(agent.jobs().get(5).get("toolCallResults").toString()).contains("5.00 dollars outside Canada");

    conversations.sendMessage(chat.conversationId(), "Can I talk to a person?");
    processTestContext.completeUserTask(
        UserTaskSelectors.byElementId("HandOffToAdvisor"), Map.of("advisorAnswer", "Our rate is 4.79 percent."));
    awaitText(chat.conversationId(), "4.79 percent");

    conversations.sendMessage(chat.conversationId(), "bye");
    awaitText(chat.conversationId(), "The conversation has ended.");
    assertThat(byKey(chat.processInstanceKey())).isCompleted().hasCompletedElements("EndClosed");
    // no llm variable in gateway mode: model and endpoint come from the cluster variable
    assertThat(agent.jobs().get(0)).doesNotContainKey("llm");
  }
}
