package com.example.chatbot.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

class ChatbotPropertiesTest {

  private static ChatbotProperties props(String target, String provider) {
    return new ChatbotProperties(
        target, provider, true, "CHATBOT_LLM", "x/{key}", Duration.ofMinutes(1),
        "chatbot-conversation", "chatbot.customer-message", "chatbot.");
  }

  @Test
  void targetAndProviderPickTheFolder() {
    assertThat(props("8.10", "bedrock").resourceFolder()).isEqualTo("bpmn/8.10-bedrock");
    assertThat(props("8.9", "openai").resourceFolder()).isEqualTo("bpmn/8.9-openai");
    assertThat(props("8.10", "gateway").resourceFolder()).isEqualTo("bpmn/8.10-gateway");
  }

  @Test
  void providerDecidesWhichLlmFieldsTheModelReads() {
    assertThat(props("8.10", "bedrock").llmFields()).containsExactly("model", "region");
    assertThat(props("8.9", "openai").llmFields()).containsExactly("model", "endpoint");
    assertThat(props("8.10", "gateway").llmFields()).containsExactly("model", "endpoint");
  }

  @Test
  void unknownValuesFailAtStartup() {
    assertThatThrownBy(() -> props("8.8", "bedrock")).hasMessageContaining("8.10 or 8.9");
    assertThatThrownBy(() -> props("8.10", "gemini")).hasMessageContaining("bedrock, openai or gateway");
    assertThatThrownBy(() -> props("8.9", "gateway")).hasMessageContaining("needs camunda.chatbot.target=8.10");
  }

  @Test
  void gatewayModeSwitchesNamesAndWorkerTypes() {
    MockEnvironment env = new MockEnvironment().withProperty("camunda.chatbot.llm-provider", "gateway");
    new ChatbotNamingEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication());
    assertThat(env.getProperty("camunda.chatbot.process-id")).isEqualTo("BankingChatbot_Conversation");
    assertThat(env.getProperty("camunda.chatbot.message-name")).isEqualTo("banking-chatbot.customer-message");
    assertThat(env.getProperty("camunda.chatbot.llm-variable")).isEqualTo("GENAI_GATEWAY");
    assertThat(env.getProperty("camunda.client.worker.override[chatbot.reply].type")).isEqualTo("banking-chatbot.reply");
  }

  @Test
  void explicitSettingsWinAndDefaultModeChangesNothing() {
    MockEnvironment env = new MockEnvironment()
        .withProperty("camunda.chatbot.llm-provider", "gateway")
        .withProperty("camunda.chatbot.process-id", "MyChatbot");
    new ChatbotNamingEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication());
    assertThat(env.getProperty("camunda.chatbot.process-id")).isEqualTo("MyChatbot");

    MockEnvironment plain = new MockEnvironment().withProperty("camunda.chatbot.llm-provider", "bedrock");
    new ChatbotNamingEnvironmentPostProcessor().postProcessEnvironment(plain, new SpringApplication());
    assertThat(plain.getProperty("camunda.chatbot.process-id")).isNull();
    assertThat(plain.getProperty("camunda.client.worker.override[chatbot.reply].type")).isNull();
  }
}
