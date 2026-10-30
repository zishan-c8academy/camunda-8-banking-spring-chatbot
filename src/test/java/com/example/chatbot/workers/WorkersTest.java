package com.example.chatbot.workers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.chatbot.bank.DemoBank;
import com.example.chatbot.conversation.ChatEvent;
import com.example.chatbot.conversation.ChatEventHub;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.exception.BpmnError;
import io.camunda.client.exception.JobError;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Plain unit tests: workers are ordinary Spring beans, so no engine is needed to test them. */
class WorkersTest {

  private DemoBank bank;
  private ChatEventHub hub;

  @BeforeEach
  void setUp() {
    bank = new DemoBank();
    hub = new ChatEventHub();
  }

  private static ActivatedJob job(String elementId, int retries) {
    ActivatedJob job = mock(ActivatedJob.class);
    when(job.getElementId()).thenReturn(elementId);
    when(job.getRetries()).thenReturn(retries);
    return job;
  }

  @Test
  @SuppressWarnings("unchecked")
  void accountsWorkerReturnsOnlyTheSignedInCustomersData() {
    Map<String, Object> result = new AccountsWorker(bank).getAccountSummary("C-1002");
    Map<String, Object> summary = (Map<String, Object>) result.get("accountSummary");
    assertThat(summary.get("customerName")).isEqualTo("Marc Gagnon");
    assertThat(summary.toString()).contains("5521").doesNotContain("4417");
  }

  @Test
  @SuppressWarnings("unchecked")
  void lockCardRefusesWithoutConfirmation() {
    Map<String, Object> result = new LockCardWorker(bank).lockCard(job("LockCard", 3), "C-1001", "4417", false);
    assertThat((Map<String, Object>) result.get("cardLock")).containsEntry("locked", false);
    assertThat(bank.card("C-1001", "4417")).get().extracting(DemoBank.Card::status).isEqualTo("ACTIVE");
  }

  @Test
  @SuppressWarnings("unchecked")
  void lockCardLocksAConfirmedCard() {
    Map<String, Object> result = new LockCardWorker(bank).lockCard(job("LockCard", 3), "C-1001", "4417", true);
    assertThat((Map<String, Object>) result.get("cardLock")).containsEntry("locked", true);
    assertThat(bank.card("C-1001", "4417")).get().extracting(DemoBank.Card::status).isEqualTo("LOCKED");
  }

  @Test
  void unknownCardIsABpmnError() {
    assertThatThrownBy(() -> new LockCardWorker(bank).lockCard(job("LockCard", 3), "C-1001", "0000", true))
        .isInstanceOf(BpmnError.class)
        .extracting(e -> ((BpmnError) e).getErrorCode())
        .isEqualTo("CARD_NOT_FOUND");
  }

  @Test
  void coreBankingOutageFailsTheJobWithOneRetryLess() {
    bank.setCoreBankingAvailable(false);
    assertThatThrownBy(() -> new LockCardWorker(bank).lockCard(job("LockCard", 3), "C-1001", "4417", true))
        .isInstanceOf(JobError.class)
        .extracting(e -> ((JobError) e).getRetries())
        .isEqualTo(2);
  }

  @Test
  @SuppressWarnings("unchecked")
  void serviceRequestGetsAReference() {
    Map<String, Object> result =
        new ServiceRequestWorker(bank).openServiceRequest("C-1003", "cheque-order", "25 cheques please");
    Map<String, Object> request = (Map<String, Object>) result.get("serviceRequest");
    assertThat(request).containsEntry("created", true);
    assertThat((String) request.get("reference")).matches("SR-\\d{4}-\\d{5}");
  }

  @Test
  @SuppressWarnings("unchecked")
  void serviceRequestWithoutDetailsIsNotCreated() {
    Map<String, Object> result = new ServiceRequestWorker(bank).openServiceRequest("C-1003", "other", " ");
    assertThat((Map<String, Object>) result.get("serviceRequest")).containsEntry("created", false);
    assertThat(bank.serviceRequests()).isEmpty();
  }

  @Test
  void replyWorkerPushesCleanTextToTheBrowser() {
    new ReplyWorker(hub).sendReply("chat-1", "<thinking>internal</thinking>Your balance is 10 dollars.", "answer");
    assertThat(hub.history("chat-1"))
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.type()).isEqualTo("reply");
              assertThat(e.text()).isEqualTo("Your balance is 10 dollars.");
            });
  }

  @Test
  void progressWorkerNamesTheToolStep() {
    new ProgressWorker(hub).announce(job("GetAccountSummary", 3), "chat-2");
    new ProgressWorker(hub).announce(job("SomethingNew", 3), "chat-2");
    assertThat(hub.history("chat-2"))
        .extracting(ChatEvent::text)
        .containsExactly("Checking your accounts", "Working on it");
  }

  @Test
  void closedWorkerEndsTheStream() {
    new ConversationClosedWorker(hub).closed("chat-3");
    assertThat(hub.history("chat-3")).extracting(ChatEvent::type).containsExactly("closed");
  }
}
