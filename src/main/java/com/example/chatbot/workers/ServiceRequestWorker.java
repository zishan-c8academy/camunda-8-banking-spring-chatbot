package com.example.chatbot.workers;

import com.example.chatbot.bank.DemoBank;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.annotation.Variable;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Agent tool "Open a service request" (job type {@code chatbot.service-request}). Returns the reference
 * number the agent gives to the customer.
 *
 * <p>Camunda 7: a JavaDelegate calling a ticketing system. Same idea; the result goes back as variables.
 */
@Component
public class ServiceRequestWorker {

  private final DemoBank bank;

  public ServiceRequestWorker(DemoBank bank) {
    this.bank = bank;
  }

  @JobWorker(type = "chatbot.service-request")
  public Map<String, Object> openServiceRequest(
      @Variable("customerId") String customerId,
      @Variable("category") String category,
      @Variable("details") String details) {
    Map<String, Object> result = new LinkedHashMap<>();
    if (details == null || details.isBlank()) {
      result.put("created", false);
      result.put("reason", "Not created. Ask the customer what they need, then try again.");
      return Map.of("serviceRequest", result);
    }
    DemoBank.ServiceRequest request =
        bank.openServiceRequest(customerId, category == null ? "other" : category, details.trim());
    result.put("created", true);
    result.put("reference", request.reference());
    result.put("category", request.category());
    result.put("status", request.status());
    result.put("expectedFirstResponse", "within 2 business days");
    return Map.of("serviceRequest", result);
  }
}
