package com.example.chatbot.simulate;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.chatbot.simulate.SimulatedAgent.CallTool;
import com.example.chatbot.simulate.SimulatedAgent.Reply;
import com.example.chatbot.simulate.SimulatedAgent.ToolResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SimulatedAgentTest {

  private final SimulatedAgent agent = new SimulatedAgent();

  @ParameterizedTest
  @CsvSource({
    "What is my chequing balance?,BALANCE",
    "I lost my credit card.,LOST_CARD",
    "How much does it cost to use another bank's ATM?,FEES",
    "I moved. Please update my address to 12 rue Exemple.,SERVICE_REQUEST",
    "Can I talk to a person about a mortgage?,ADVISOR",
    "Thanks that's all,CLOSE",
    "Test the guardrail,GUARDRAIL",
    "hello,HELP"
  })
  void recognisesTheDemoConversations(String message, SimulatedAgent.Intent expected) {
    assertThat(SimulatedAgent.intent(message.toLowerCase())).isEqualTo(expected);
  }

  @Test
  void lostCardAsksBeforeLocking() {
    var summary = Map.<String, Object>of(
        "cards", List.of(Map.of("network", "Debit", "last4", "9012", "status", "ACTIVE"),
                         Map.of("network", "Visa credit", "last4", "4417", "status", "ACTIVE")));
    var step1 = agent.next("I lost my credit card", List.of());
    assertThat(step1).isEqualTo(new CallTool("GetAccountSummary", Map.of()));

    var step2 = agent.next("I lost my credit card", List.of(new ToolResult("GetAccountSummary", summary)));
    assertThat(step2).isInstanceOf(CallTool.class);
    assertThat(((CallTool) step2).elementId()).isEqualTo("AskCustomer");
    assertThat(((CallTool) step2).arguments().get("question").toString()).contains("4417");

    var noAnswer = agent.next("I lost my credit card", List.of(
        new ToolResult("GetAccountSummary", summary), new ToolResult("AskCustomer", Map.of("answer", "no, not yet"))));
    assertThat(noAnswer).isInstanceOf(Reply.class);

    var yes = agent.next("I lost my credit card", List.of(
        new ToolResult("GetAccountSummary", summary), new ToolResult("AskCustomer", Map.of("answer", "Yes please"))));
    assertThat(yes).isEqualTo(new CallTool("LockCard", Map.of("cardLast4", "4417", "customerConfirmed", true)));
  }

  @Test
  void repliesOnlyWithDataFromToolResults() {
    var reply = agent.next("How much is an ATM withdrawal elsewhere?",
        List.of(new ToolResult("LookUpBranchHoursAndFees", Map.of("topic", "atm-fees", "answer", "2.00 dollars in Canada."))));
    assertThat(((Reply) reply).text()).contains("2.00 dollars in Canada.");
  }
}
