package com.example.chatbot.process;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Sets the names the app uses for one process model variant, before the job workers start.
 *
 * <ul>
 *   <li>With {@code camunda.chatbot.llm-provider=gateway}, the defaults switch to the prefixed names of
 *       {@code chatbot-8.10-gateway.bpmn} (process {@code BankingChatbot_Conversation}, job types {@code
 *       banking-chatbot.*}). Values you set yourself still win.
 *   <li>When the job type prefix is not {@code chatbot.}, each worker's type is overridden through the
 *       starter's {@code camunda.client.worker.override[<type>].type} property, so the same
 *       {@code @JobWorker} code serves both names.
 * </ul>
 */
public class ChatbotNamingEnvironmentPostProcessor implements EnvironmentPostProcessor {

  /** Job types the app's workers serve, without the prefix. */
  public static final List<String> JOB_TYPES =
      List.of("accounts", "lock-card", "service-request", "reply", "progress", "closed");

  static final Map<String, Object> GATEWAY_DEFAULTS =
      Map.of(
          "camunda.chatbot.process-id", "BankingChatbot_Conversation",
          "camunda.chatbot.message-name", "banking-chatbot.customer-message",
          "camunda.chatbot.job-type-prefix", "banking-chatbot.",
          "camunda.chatbot.llm-variable", "GENAI_GATEWAY");

  @Override
  public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
    if ("gateway".equals(environment.getProperty("camunda.chatbot.llm-provider"))) {
      environment.getPropertySources().addLast(new MapPropertySource("chatbotGatewayNames", GATEWAY_DEFAULTS));
    }
    String prefix =
        environment.getProperty("camunda.chatbot.job-type-prefix", ChatbotProperties.DEFAULT_JOB_TYPE_PREFIX);
    if (!ChatbotProperties.DEFAULT_JOB_TYPE_PREFIX.equals(prefix)) {
      Map<String, Object> overrides = new LinkedHashMap<>();
      for (String type : JOB_TYPES) {
        overrides.put(
            "camunda.client.worker.override[" + ChatbotProperties.DEFAULT_JOB_TYPE_PREFIX + type + "].type",
            prefix + type);
      }
      environment.getPropertySources().addLast(new MapPropertySource("chatbotJobTypes", overrides));
    }
  }
}
