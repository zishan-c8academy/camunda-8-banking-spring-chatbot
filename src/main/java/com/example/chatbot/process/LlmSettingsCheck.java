package com.example.chatbot.process;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.client.CamundaClient;
import io.camunda.client.api.command.ClientHttpException;
import io.camunda.client.api.search.response.ClusterVariable;
import io.camunda.client.spring.event.CamundaClientCreatedSpringEvent;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Checks the cluster variable the process model reads its LLM settings from ({@code CHATBOT_LLM}, or
 * {@code GENAI_GATEWAY} for the gateway variant). The app never reads the settings for itself: the
 * engine resolves {@code camunda.vars.env.CHATBOT_LLM.model} when the agent's job is created. The check
 * only turns a missing variable into a startup warning and a health field, instead of an incident on the
 * agent at the first chat.
 *
 * <p>It reads the global variable. A tenant-scoped one is not seen, and the result says "not found".
 */
@Component
public class LlmSettingsCheck {

  public static final String OK = "ok";
  private static final Logger LOG = LoggerFactory.getLogger(LlmSettingsCheck.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  private final ChatbotProperties properties;
  private final Environment environment;

  public LlmSettingsCheck(ChatbotProperties properties, Environment environment) {
    this.properties = properties;
    this.environment = environment;
  }

  /** In simulate mode a worker in this app plays the agent, so no LLM settings are needed. */
  public boolean needed() {
    return !environment.acceptsProfiles(Profiles.of("simulate"));
  }

  @EventListener
  public void onClientCreated(CamundaClientCreatedSpringEvent event) {
    if (!needed()) {
      return;
    }
    String result = check(event.getClient());
    if (!OK.equals(result)) {
      LOG.warn(
          "Cluster variable {}: {}. Create it in Admin > Cluster variables with {}. Until then the agent gets no model and chats end in an incident.",
          properties.llmVariable(), result, example());
    }
  }

  /** {@code ok}, {@code not found}, {@code missing <fields>}, or {@code not checked (<reason>)}. */
  public String check(CamundaClient client) {
    try {
      ClusterVariable variable =
          client
              .newGloballyScopedClusterVariableGetRequest()
              .withName(properties.llmVariable())
              .requestTimeout(Duration.ofSeconds(5))
              .send()
              .join();
      JsonNode value = JSON.readTree(variable.getValue());
      List<String> missing = properties.llmFields().stream().filter(f -> value.path(f).asText("").isBlank()).toList();
      return missing.isEmpty() ? OK : "missing " + String.join(", ", missing);
    } catch (ClientHttpException e) {
      return e.code() == 404 ? "not found" : "not checked (HTTP " + e.code() + ")";
    } catch (Exception e) {
      return "not checked (" + e.getClass().getSimpleName() + ")";
    }
  }

  /** The value to create, with placeholders, for the selected provider. */
  public String example() {
    return "bedrock".equals(properties.llmProvider())
        ? "{\"model\": \"<Bedrock model or inference profile id>\", \"region\": \"<AWS region>\"}"
        : "{\"model\": \"<model name>\", \"endpoint\": \"<base URL ending in /v1>\"}";
  }
}
