package com.example.chatbot.workers;

import com.example.chatbot.bank.DemoBank;
import com.example.chatbot.bank.DemoBank.CoreBankingUnavailableException;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.annotation.Variable;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.exception.CamundaError;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Agent tool "Lock a card" (job type {@code chatbot.lock-card}). Shows the three outcomes a worker has:
 *
 * <ul>
 *   <li>Business result: the card is locked, or not locked because the customer did not confirm. The
 *       job completes and the agent reads the result.
 *   <li>Business error: no such card. {@code CamundaError.bpmnError("CARD_NOT_FOUND")} is caught by the
 *       error boundary event on the task in the model. Camunda 7: {@code throw new BpmnError(...)}.
 *   <li>Technical error: core banking did not answer. {@code CamundaError.jobError(...)} fails the job
 *       with one retry fewer and a 10 second backoff; at zero retries Operate shows an incident.
 *       Camunda 7: a RuntimeException rolled back the transaction and the job executor retried it.
 * </ul>
 *
 * <p>The confirmation check is in code on purpose: the prompt asks the agent to confirm first, and the
 * worker refuses when it did not. Controls that must hold do not rely on the prompt alone.
 */
@Component
public class LockCardWorker {

  private final DemoBank bank;

  public LockCardWorker(DemoBank bank) {
    this.bank = bank;
  }

  @JobWorker(type = "chatbot.lock-card")
  public Map<String, Object> lockCard(
      ActivatedJob job,
      @Variable("customerId") String customerId,
      @Variable("cardLast4") String cardLast4,
      @Variable("customerConfirmed") Boolean customerConfirmed) {
    Map<String, Object> result = new LinkedHashMap<>();
    if (!Boolean.TRUE.equals(customerConfirmed)) {
      result.put("locked", false);
      result.put("reason", "Not locked. Ask the customer to confirm this card first.");
      return Map.of("cardLock", result);
    }
    try {
      DemoBank.Card card =
          bank.lockCard(customerId, cardLast4 == null ? "" : cardLast4.trim())
              .orElseThrow(
                  () ->
                      CamundaError.bpmnError(
                          "CARD_NOT_FOUND",
                          "No card ending " + cardLast4 + " for this customer",
                          Map.of("cardLast4", String.valueOf(cardLast4))));
      result.put("locked", true);
      result.put("card", card);
      result.put("note", "New purchases and withdrawals on this card are refused from now on.");
      return Map.of("cardLock", result);
    } catch (CoreBankingUnavailableException e) {
      throw CamundaError.jobError(
          "Core banking did not answer: " + e.getMessage(),
          null,
          job.getRetries() - 1,
          Duration.ofSeconds(10));
    }
  }
}
