package com.example.chatbot.workers;

import com.example.chatbot.bank.DemoBank;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.annotation.Variable;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Agent tool "Get the customer's accounts and cards" (job type {@code chatbot.accounts}).
 *
 * <p>Camunda 7: a JavaDelegate that read {@code execution.getVariable("customerId")} and called
 * {@code execution.setVariable(...)}. Here the method gets typed variables as parameters and returns the
 * variables to set. The job completes when the method returns. An exception (core banking down) fails
 * the job; Camunda retries it and raises an incident when retries run out.
 */
@Component
public class AccountsWorker {

  private final DemoBank bank;

  public AccountsWorker(DemoBank bank) {
    this.bank = bank;
  }

  @JobWorker(type = "chatbot.accounts")
  public Map<String, Object> getAccountSummary(@Variable("customerId") String customerId) {
    return Map.of("accountSummary", bank.summary(customerId));
  }
}
