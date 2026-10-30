package com.example.chatbot.process;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings under {@code camunda.chatbot.*}. See application.yaml for the environment variables.
 *
 * @param target which Camunda version the process model is built for: {@code 8.10} or {@code 8.9}
 * @param llmProvider {@code bedrock}, {@code openai} (any OpenAI-compatible endpoint) or {@code
 *     gateway} (an OpenAI-compatible gateway already configured on the cluster; 8.10 only)
 * @param deployOnStart deploy the matching BPMN, DMN and form when the app connects to Camunda
 * @param llmVariable cluster variable the process model reads its LLM settings from. The app only checks
 *     that it exists; it never passes LLM settings to the process
 * @param operateInstanceUrl link to a process instance in Operate; {@code {key}} is replaced
 * @param messageTimeToLive how long a customer message waits if the process is not ready for it yet
 * @param processId BPMN process id of the conversation process
 * @param messageName name of the message that carries a customer's chat message
 * @param jobTypePrefix prefix of the job types the workers serve ({@code chatbot.reply} and so on)
 */
@ConfigurationProperties(prefix = "camunda.chatbot")
public record ChatbotProperties(
    @DefaultValue("8.10") String target,
    @DefaultValue("bedrock") String llmProvider,
    @DefaultValue("true") boolean deployOnStart,
    @DefaultValue(DEFAULT_LLM_VARIABLE) String llmVariable,
    @DefaultValue("http://localhost:8080/operate/processes/{key}") String operateInstanceUrl,
    @DefaultValue("PT1M") Duration messageTimeToLive,
    @DefaultValue(DEFAULT_PROCESS_ID) String processId,
    @DefaultValue(DEFAULT_MESSAGE_NAME) String messageName,
    @DefaultValue(DEFAULT_JOB_TYPE_PREFIX) String jobTypePrefix) {

  public static final String DEFAULT_PROCESS_ID = "chatbot-conversation";
  public static final String DEFAULT_MESSAGE_NAME = "chatbot.customer-message";
  public static final String DEFAULT_JOB_TYPE_PREFIX = "chatbot.";
  public static final String DEFAULT_LLM_VARIABLE = "CHATBOT_LLM";
  public static final Set<String> TARGETS = Set.of("8.10", "8.9");
  public static final Set<String> PROVIDERS = Set.of("bedrock", "openai", "gateway");

  public ChatbotProperties {
    if (!TARGETS.contains(target)) {
      throw new IllegalArgumentException(
          "camunda.chatbot.target must be 8.10 or 8.9, got '" + target + "'");
    }
    if (!PROVIDERS.contains(llmProvider)) {
      throw new IllegalArgumentException(
          "camunda.chatbot.llm-provider must be bedrock, openai or gateway, got '" + llmProvider + "'");
    }
    if ("gateway".equals(llmProvider) && !"8.10".equals(target)) {
      throw new IllegalArgumentException("camunda.chatbot.llm-provider=gateway needs camunda.chatbot.target=8.10");
    }
  }

  /** The model and endpoint come from a cluster variable, not from this app. */
  public boolean isGateway() {
    return "gateway".equals(llmProvider);
  }

  /** The classpath folder with the BPMN, DMN and form for this target and provider, deployed together. */
  public String resourceFolder() {
    return "bpmn/" + target + "-" + llmProvider;
  }

  /** Fields the process model reads from the {@link #llmVariable} cluster variable. */
  public List<String> llmFields() {
    return "bedrock".equals(llmProvider) ? List.of("model", "region") : List.of("model", "endpoint");
  }
}
