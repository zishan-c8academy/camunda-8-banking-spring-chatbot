package com.example.chatbot.process;

import static io.camunda.process.test.api.CamundaAssert.assertThat;
import static io.camunda.process.test.api.assertions.ProcessInstanceSelectors.byKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.chatbot.bank.DemoBank;
import com.example.chatbot.conversation.ChatEvent;
import com.example.chatbot.conversation.ChatEventHub;
import com.example.chatbot.conversation.ConversationService;
import com.example.chatbot.conversation.ConversationService.StartedConversation;
import io.camunda.client.CamundaClient;
import io.camunda.process.test.api.CamundaProcessTestContext;
import io.camunda.process.test.api.CamundaSpringProcessTest;
import io.camunda.process.test.api.assertions.UserTaskSelectors;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Runs the conversation process in a real Camunda engine (Camunda Process Test, Testcontainers) with
 * the app's real job workers. Only the AI agent is scripted ({@link ScriptedAgent}), so no LLM is
 * needed.
 *
 * <p>Which models run depends on the engine (Maven profile, see {@link TestSecrets#engine()}). The 8.10
 * models reference credentials as {@code =camunda.secrets.NAME}, which only an 8.10 engine resolves, so
 * they run on the 8.10 engine only. The 8.9 models run on both engines: on 8.10 they show that an 8.9
 * model keeps working after a cluster upgrade.
 *
 * <p>Each model reads its LLM settings from the {@code CHATBOT_LLM} cluster variable, set on the engine
 * before every test ({@link TestLlmSettings}); the app passes none.
 *
 * <p>Camunda 7 comparison: replaces camunda-bpm-assert / Zeebe Process Test. CPT is the supported test
 * library from 8.8; Zeebe Process Test is removed in 8.10.
 */
@SpringBootTest(
    properties = {"camunda.chatbot.deploy-on-start=false"})
@CamundaSpringProcessTest
class ChatbotProcessTest {

  @Autowired private CamundaClient client;
  @Autowired private CamundaProcessTestContext processTestContext;
  @Autowired private ConversationService conversations;
  @Autowired private ChatEventHub hub;
  @Autowired private DemoBank bank;
  @Autowired private LlmSettingsCheck llmSettingsCheck;

  static Stream<Arguments> models() {
    Stream<Arguments> models89 =
        Stream.of(
            Arguments.of("bpmn/8.9-bedrock", "io.camunda.agenticai:aiagent-job-worker:1"),
            Arguments.of("bpmn/8.9-openai", "io.camunda.agenticai:aiagent-job-worker:1"));
    if (!"8.10".equals(TestSecrets.engine())) {
      return models89;
    }
    return Stream.concat(
        Stream.of(
            Arguments.of("bpmn/8.10-bedrock", "io.camunda.agenticai:aiagent:subprocess:2"),
            Arguments.of("bpmn/8.10-openai", "io.camunda.agenticai:aiagent:subprocess:2")),
        models89);
  }

  @BeforeEach
  void resetDemoData() {
    bank.reset();
    hub.clear();
    TestSecrets.writeIntoCamundaContainers();
    TestLlmSettings.set(client, TestLlmSettings.VALUE);
  }

  private ScriptedAgent deployWithAgent(String folder, String agentJobType) {
    ProcessDeployer.deploy(client, folder);
    ScriptedAgent agent = new ScriptedAgent();
    processTestContext.mockJobWorker(agentJobType).withHandler(agent);
    return agent;
  }

  private void awaitEvent(String conversationId, String type, String textPart) {
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () ->
                assertThat(hub.history(conversationId))
                    .anySatisfy(
                        e -> {
                          assertThat(e.type()).isEqualTo(type);
                          assertThat(e.text()).contains(textPart);
                        }));
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> toolResults(Map<String, Object> jobVariables) {
    return (List<Map<String, Object>>) jobVariables.get("toolCallResults");
  }

  @SuppressWarnings("unchecked")
  private static String userPrompt(Map<String, Object> jobVariables) {
    Map<String, Object> data = (Map<String, Object>) jobVariables.get("data");
    return (String) ((Map<String, Object>) data.get("userPrompt")).get("prompt");
  }

  @ParameterizedTest
  @MethodSource("models")
  void balanceQuestionThenGoodbye(String bpmn, String agentJobType) {
    ScriptedAgent agent =
        deployWithAgent(bpmn, agentJobType)
            .callTool("GetAccountSummary", Map.of())
            .reply("Your Everyday chequing account ending 2231 has 2,418.55 dollars.")
            .callTool("CloseConversation", Map.of())
            .reply("Goodbye, have a good day.");

    StartedConversation chat = conversations.start("C-1001", "What is my chequing balance?");

    awaitEvent(chat.conversationId(), "progress", "Checking your accounts");
    awaitEvent(chat.conversationId(), "reply", "2,418.55");
    assertThat(byKey(chat.processInstanceKey()))
        .isActive()
        .isWaitingForMessage(ConversationService.MESSAGE_NAME, chat.conversationId());

    conversations.sendMessage(chat.conversationId(), "Thanks, that's all.");

    awaitEvent(chat.conversationId(), "reply", "Goodbye");
    awaitEvent(chat.conversationId(), "closed", "ended");
    assertThat(byKey(chat.processInstanceKey()))
        .isCompleted()
        .hasCompletedElement("Agent", 2)
        .hasCompletedElements("GetAccountSummary", "CloseConversation", "EndClosed");

    // The agent got the account data as a tool result.
    Map<String, Object> afterTool = agent.jobs().get(1);
    assertThat(toolResults(afterTool))
        .anySatisfy(
            r -> {
              assertThat(r.get("name")).isEqualTo("GetAccountSummary");
              assertThat(r.get("content").toString()).contains("2231").contains("Visa credit");
            });
    // Turn 1 uses the first message; turn 2 uses the follow-up and gets the context of turn 1 back.
    assertThat(userPrompt(agent.jobs().get(0))).isEqualTo("What is my chequing balance?");
    Map<String, Object> turn2 = agent.jobs().get(2);
    assertThat(userPrompt(turn2)).isEqualTo("Thanks, that's all.");
    assertThat(turn2.get("agentContext")).isEqualTo(agent.returnedContexts().get(0));
    // The model settings come from the CHATBOT_LLM cluster variable; the app started the chat without any.
    Map<String, Object> turn1 = agent.jobs().get(0);
    assertThat(turn1).doesNotContainKey("llm");
    assertThat(String.valueOf(turn1.get("provider")))
        .contains("test-model")
        .contains(bpmn.contains("bedrock") ? "us-east-1" : "http://llm.invalid/v1");
  }

  @Test
  void llmSettingsCheckReportsTheClusterVariable() {
    // Default provider bedrock: the model reads model and region.
    assertThat(llmSettingsCheck.check(client)).isEqualTo(LlmSettingsCheck.OK);
    TestLlmSettings.set(client, Map.of("model", "test-model"));
    assertThat(llmSettingsCheck.check(client)).isEqualTo("missing region");
    TestLlmSettings.delete(client);
    assertThat(llmSettingsCheck.check(client)).isEqualTo("not found");
  }

  @ParameterizedTest
  @MethodSource("models")
  void lostCardIsLockedOnlyAfterTheCustomerConfirms(String bpmn, String agentJobType) {
    ScriptedAgent agent =
        deployWithAgent(bpmn, agentJobType)
            .callTool("GetAccountSummary", Map.of())
            .callTool("AskCustomer", Map.of("question", "Shall I lock your Visa card ending 4417?"))
            .callTool("LockCard", Map.of("cardLast4", "4417", "customerConfirmed", true))
            .reply("Your Visa card ending 4417 is locked.");

    StartedConversation chat = conversations.start("C-1001", "I lost my credit card");

    awaitEvent(chat.conversationId(), "reply", "Shall I lock your Visa card ending 4417?");
    assertThat(bank.card("C-1001", "4417")).get().extracting(DemoBank.Card::status).isEqualTo("ACTIVE");

    conversations.sendMessage(chat.conversationId(), "Yes please");

    awaitEvent(chat.conversationId(), "progress", "Locking the card");
    awaitEvent(chat.conversationId(), "reply", "is locked");
    assertThat(bank.card("C-1001", "4417")).get().extracting(DemoBank.Card::status).isEqualTo("LOCKED");
    assertThat(byKey(chat.processInstanceKey()))
        .hasCompletedElements("AskCustomer", "SendQuestion", "WaitForAnswer", "LockCard");

    assertThat(toolResults(agent.jobs().get(2)))
        .anySatisfy(
            r -> {
              assertThat(r.get("name")).isEqualTo("AskCustomer");
              assertThat(r.get("content").toString()).contains("Yes please");
            });
  }

  @ParameterizedTest
  @MethodSource("models")
  void unknownCardIsABusinessErrorTheAgentCanRead(String bpmn, String agentJobType) {
    ScriptedAgent agent =
        deployWithAgent(bpmn, agentJobType)
            .callTool("LockCard", Map.of("cardLast4", "0000", "customerConfirmed", true))
            .reply("I could not find a card ending 0000. Can you check the last four digits?");

    StartedConversation chat = conversations.start("C-1001", "Lock my card ending 0000, I confirm.");

    awaitEvent(chat.conversationId(), "reply", "could not find");
    assertThat(byKey(chat.processInstanceKey()))
        .hasCompletedElements("CardNotFound", "ExplainCardNotFound")
        .hasNoActiveIncidents();
    assertThat(toolResults(agent.jobs().get(1)))
        .anySatisfy(r -> assertThat(r.get("content").toString()).contains("No card with those last four digits"));
  }

  @ParameterizedTest
  @MethodSource("models")
  void branchFeesComeFromTheDecisionTable(String bpmn, String agentJobType) {
    ScriptedAgent agent =
        deployWithAgent(bpmn, agentJobType)
            .callTool("LookUpBranchHoursAndFees", Map.of("topic", "atm-fees"))
            .reply("Our ATMs are free; other ATMs in Canada cost 2.00 dollars.");

    StartedConversation chat = conversations.start("C-1002", "What does it cost to use another bank's ATM?");

    awaitEvent(chat.conversationId(), "progress", "Looking up branch hours and fees");
    awaitEvent(chat.conversationId(), "reply", "2.00 dollars");
    assertThat(toolResults(agent.jobs().get(1)))
        .anySatisfy(r -> assertThat(r.get("content").toString()).contains("5.00 dollars outside Canada"));
  }

  @ParameterizedTest
  @MethodSource("models")
  void serviceRequestReturnsAReferenceNumber(String bpmn, String agentJobType) {
    ScriptedAgent agent =
        deployWithAgent(bpmn, agentJobType)
            .callTool(
                "CreateServiceRequest",
                Map.of("category", "address-change", "details", "Moving to 12 rue Exemple, Montreal"))
            .reply("Done. Your reference is in the request.");

    StartedConversation chat = conversations.start("C-1002", "I moved, please update my address.");

    awaitEvent(chat.conversationId(), "reply", "Your reference");
    assertThat(bank.serviceRequests()).hasSize(1);
    String reference = bank.serviceRequests().get(0).reference();
    assertThat(toolResults(agent.jobs().get(1)))
        .anySatisfy(r -> assertThat(r.get("content").toString()).contains(reference));
  }

  @ParameterizedTest
  @MethodSource("models")
  void handOffToAdvisorWaitsForAPersonInTasklist(String bpmn, String agentJobType) {
    ScriptedAgent agent =
        deployWithAgent(bpmn, agentJobType)
            .callTool(
                "HandOffToAdvisor",
                Map.of("summary", "Customer asks for a mortgage rate.", "question", "What is your 5-year fixed rate?"))
            .reply("An advisor says: our 5-year fixed rate today is 4.79 percent.");

    StartedConversation chat = conversations.start("C-1003", "Can I talk to a person about mortgages?");

    awaitEvent(chat.conversationId(), "progress", "Asking an advisor");
    assertThat(UserTaskSelectors.byElementId("HandOffToAdvisor"))
        .isCreated()
        .hasCandidateGroup("advisors");
    processTestContext.completeUserTask(
        UserTaskSelectors.byElementId("HandOffToAdvisor"),
        Map.of("advisorAnswer", "Our 5-year fixed rate today is 4.79 percent."));

    awaitEvent(chat.conversationId(), "reply", "4.79");
    assertThat(toolResults(agent.jobs().get(1)))
        .anySatisfy(
            r -> {
              assertThat(r.get("name")).isEqualTo("HandOffToAdvisor");
              assertThat(r.get("content").toString()).contains("4.79 percent");
            });
  }

  @ParameterizedTest
  @MethodSource("models")
  void offRailAgentFallsBackToAnAdvisor(String bpmn, String agentJobType) {
    deployWithAgent(bpmn, agentJobType).offRail("MAXIMUM_NUMBER_OF_MODEL_CALLS_REACHED");

    StartedConversation chat = conversations.start("C-1001", "Explain every fee you have ever charged me.");

    awaitEvent(chat.conversationId(), "reply", "asked an advisor");
    assertThat(UserTaskSelectors.byElementId("AdvisorTakeover")).isCreated().hasCandidateGroup("advisors");
    processTestContext.completeUserTask(
        UserTaskSelectors.byElementId("AdvisorTakeover"),
        Map.of("advisorAnswer", "I will send you a full fee statement by email today."));

    awaitEvent(chat.conversationId(), "reply", "full fee statement");
    awaitEvent(chat.conversationId(), "closed", "ended");
    assertThat(byKey(chat.processInstanceKey()))
        .isCompleted()
        .hasCompletedElements("OffRail", "SendFallback", "AdvisorTakeover", "EndAdvisor")
        .hasVariable("agentErrorCode", "MAXIMUM_NUMBER_OF_MODEL_CALLS_REACHED");
  }

  @ParameterizedTest
  @MethodSource("models")
  void silenceClosesTheChatAfterFifteenMinutes(String bpmn, String agentJobType) {
    deployWithAgent(bpmn, agentJobType).reply("Our branches open at 9:30.");

    StartedConversation chat = conversations.start("C-1003", "When do branches open?");
    awaitEvent(chat.conversationId(), "reply", "9:30");
    // The event-based gateway stays active; the timer and the message catch are subscriptions on it.
    assertThat(byKey(chat.processInstanceKey())).hasActiveElements("WaitCustomer");

    processTestContext.increaseTime(Duration.ofMinutes(16));

    awaitEvent(chat.conversationId(), "reply", "15 minutes");
    awaitEvent(chat.conversationId(), "closed", "ended");
    assertThat(byKey(chat.processInstanceKey())).isCompleted().hasCompletedElements("EndTimeout");
    assertThat(hub.history(chat.conversationId()))
        .extracting(ChatEvent::type)
        .containsSubsequence("reply", "reply", "closed");
  }
}
