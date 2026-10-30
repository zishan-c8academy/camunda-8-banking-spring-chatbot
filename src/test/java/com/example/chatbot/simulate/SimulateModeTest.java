package com.example.chatbot.simulate;

import static io.camunda.process.test.api.CamundaAssert.assertThat;
import static io.camunda.process.test.api.assertions.ProcessInstanceSelectors.byKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.chatbot.bank.DemoBank;
import com.example.chatbot.conversation.ChatEvent;
import com.example.chatbot.conversation.ChatEventHub;
import com.example.chatbot.conversation.ConversationService;
import com.example.chatbot.conversation.ConversationService.StartedConversation;
import com.example.chatbot.process.TestSecrets;
import com.example.chatbot.web.AdvisorDeskController;
import com.example.chatbot.web.AdvisorDeskController.AdvisorTask;
import io.camunda.process.test.api.CamundaSpringProcessTest;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Simulate mode end to end: the app deploys its model on start and its simulated agent worker
 * answers the agent jobs. The five demo conversations and the guardrail run on a real engine with the
 * real tool workers, listeners, user task and error boundary. The model matches the engine version
 * (8.9 model on the 8.9 engine, 8.10 model on the 8.10 engine).
 */
@SpringBootTest(
    properties = {
      "camunda.chatbot.deploy-on-start=true",
      "camunda.chatbot.target=${chatbot.test.engine:8.9}"
    })
@ActiveProfiles("simulate")
@CamundaSpringProcessTest
class SimulateModeTest {

  @Autowired private ConversationService conversations;
  @Autowired private ChatEventHub hub;
  @Autowired private DemoBank bank;
  @Autowired private AdvisorDeskController desk;

  @BeforeEach
  void reset() {
    bank.reset();
    hub.clear();
    TestSecrets.writeIntoCamundaContainers();
  }

  private void awaitReply(String conversationId, String textPart) {
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> assertThat(hub.history(conversationId))
                .anySatisfy(e -> assertThat(e.text()).contains(textPart)));
  }

  @Test
  void balanceThenGoodbye() {
    StartedConversation chat = conversations.start("C-1001", "What is my chequing balance?");
    awaitReply(chat.conversationId(), "Everyday chequing ending 2231: 2,418.55 CAD");
    conversations.sendMessage(chat.conversationId(), "Thanks, that's all");
    awaitReply(chat.conversationId(), "Goodbye");
    awaitReply(chat.conversationId(), "The conversation has ended.");
    assertThat(byKey(chat.processInstanceKey())).isCompleted().hasCompletedElements("CloseConversation", "EndClosed");
  }

  @Test
  void lostCardWithConfirmation() {
    StartedConversation chat = conversations.start("C-1001", "I lost my credit card.");
    awaitReply(chat.conversationId(), "Shall I lock it?");
    conversations.sendMessage(chat.conversationId(), "Yes please");
    awaitReply(chat.conversationId(), "Done. Your Visa credit card ending 4417 is locked.");
    assertThat(bank.card("C-1001", "4417")).get().extracting(DemoBank.Card::status).isEqualTo("LOCKED");
    assertThat(hub.history(chat.conversationId()))
        .extracting(ChatEvent::text)
        .contains("Checking your accounts", "Preparing a question for you", "Locking the card");
  }

  @Test
  void branchFees() {
    StartedConversation chat = conversations.start("C-1002", "How much does it cost to use another bank's ATM?");
    awaitReply(chat.conversationId(), "5.00 dollars outside Canada");
  }

  @Test
  void serviceRequest() {
    StartedConversation chat =
        conversations.start("C-1003", "I moved. Please update my address to 12 rue Exemple, Montréal.");
    awaitReply(chat.conversationId(), "Shall I go ahead?");
    conversations.sendMessage(chat.conversationId(), "yes");
    awaitReply(chat.conversationId(), "Your request is open. Reference SR-");
    assertThat(bank.serviceRequests()).singleElement().extracting(DemoBank.ServiceRequest::category).isEqualTo("address-change");
  }

  @Test
  void askForAHumanThroughTheAdvisorDesk() {
    StartedConversation chat = conversations.start("C-1004", "Can I talk to a person about a mortgage?");
    awaitReply(chat.conversationId(), "Asking an advisor");
    List<AdvisorTask> tasks =
        await().atMost(Duration.ofSeconds(30)).until(() -> desk.openTasks().getBody(), list -> !list.isEmpty());
    assertThat(tasks.get(0).conversationId()).isEqualTo(chat.conversationId());
    assertThat(tasks.get(0).question()).contains("mortgage");
    desk.complete(Long.parseLong(tasks.get(0).key()), new AdvisorDeskController.Answer("Our 5-year fixed rate is 4.79 percent."));
    awaitReply(chat.conversationId(), "An advisor answered: Our 5-year fixed rate is 4.79 percent.");
  }

  @Test
  void guardrailHandsTheChatToAnAdvisor() {
    StartedConversation chat = conversations.start("C-1002", "Test the guardrail");
    awaitReply(chat.conversationId(), "asked an advisor");
    assertThat(byKey(chat.processInstanceKey())).hasCompletedElements("OffRail", "SendFallback").hasActiveElements("AdvisorTakeover");
  }
}
