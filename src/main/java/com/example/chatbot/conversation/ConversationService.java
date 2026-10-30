package com.example.chatbot.conversation;

import com.example.chatbot.bank.DemoBank;
import com.example.chatbot.process.ChatbotProperties;
import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ProcessInstanceEvent;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Starts conversations and passes customer messages to them.
 *
 * <p>Camunda 7 comparison: {@code runtimeService.startProcessInstanceByKey(key, businessKey, vars)}
 * becomes {@code newCreateInstanceCommand()} with a business id, and {@code
 * runtimeService.correlateMessage(name, businessKey, vars)} becomes {@code newPublishMessageCommand()}
 * with a correlation key. A published message can wait (time to live) until the process is ready for
 * it, which a C7 correlation cannot.
 *
 * <p>The app starts a chat with business data only. Which model to call is part of the process model's
 * configuration on the cluster, not of this app.
 */
@Service
public class ConversationService {

  /** Default names; the gateway variant uses prefixed ones (see ChatbotProperties). */
  public static final String PROCESS_ID = ChatbotProperties.DEFAULT_PROCESS_ID;
  public static final String MESSAGE_NAME = ChatbotProperties.DEFAULT_MESSAGE_NAME;

  private final ObjectProvider<CamundaClient> client;
  private final ChatbotProperties properties;
  private final ChatEventHub hub;
  private final DemoBank bank;

  public ConversationService(
      ObjectProvider<CamundaClient> client,
      ChatbotProperties properties,
      ChatEventHub hub,
      DemoBank bank) {
    this.client = client;
    this.properties = properties;
    this.hub = hub;
    this.bank = bank;
  }

  public record StartedConversation(String conversationId, long processInstanceKey) {}

  public StartedConversation start(String customerId, String firstMessage) {
    if (bank.customer(customerId).isEmpty()) {
      throw new IllegalArgumentException("Unknown customer " + customerId);
    }
    String conversationId = "chat-" + HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextInt());

    Map<String, Object> variables = new LinkedHashMap<>();
    variables.put("conversationId", conversationId);
    variables.put("customerId", customerId);
    variables.put("firstMessage", firstMessage);
    // No LLM settings here: the process model reads them from a cluster variable, and the credentials
    // from secrets.

    ProcessInstanceEvent instance =
        camunda()
            .newCreateInstanceCommand()
            .bpmnProcessId(properties.processId())
            .latestVersion()
            .businessId(conversationId)
            .variables(variables)
            .send()
            .join();
    hub.register(conversationId, instance.getProcessInstanceKey());
    return new StartedConversation(conversationId, instance.getProcessInstanceKey());
  }

  public void sendMessage(String conversationId, String message) {
    camunda()
        .newPublishMessageCommand()
        .messageName(properties.messageName())
        .correlationKey(conversationId)
        .timeToLive(properties.messageTimeToLive())
        .variables(Map.of("message", message))
        .send()
        .join();
  }

  public String operateLink(long processInstanceKey) {
    return properties.operateInstanceUrl().replace("{key}", Long.toString(processInstanceKey));
  }

  public boolean camundaEnabled() {
    return client.getIfAvailable() != null;
  }

  private CamundaClient camunda() {
    CamundaClient c = client.getIfAvailable();
    if (c == null) {
      throw new CamundaDisabledException();
    }
    return c;
  }

  /** The app runs without a Camunda connection (offline profile). */
  public static class CamundaDisabledException extends RuntimeException {
    public CamundaDisabledException() {
      super("The Camunda client is disabled (camunda.client.enabled=false). Start the app with a cluster configured.");
    }
  }
}
