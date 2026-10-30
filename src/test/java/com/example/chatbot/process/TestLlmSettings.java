package com.example.chatbot.process;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.command.ClientHttpException;
import java.util.Map;

/**
 * Sets the {@code CHATBOT_LLM} cluster variable on the test engine, as a user does once in Admin. The
 * models read model, region and endpoint from it. The values are dummies: the scripted agent never calls
 * an LLM.
 */
public final class TestLlmSettings {

  public static final Map<String, Object> VALUE =
      Map.of("model", "test-model", "region", "us-east-1", "endpoint", "http://llm.invalid/v1");

  private TestLlmSettings() {}

  public static void set(CamundaClient client, Map<String, Object> value) {
    delete(client);
    client
        .newGloballyScopedClusterVariableCreateRequest()
        .create(ChatbotProperties.DEFAULT_LLM_VARIABLE, value)
        .send()
        .join();
  }

  public static void delete(CamundaClient client) {
    try {
      client
          .newGloballyScopedClusterVariableDeleteRequest()
          .delete(ChatbotProperties.DEFAULT_LLM_VARIABLE)
          .send()
          .join();
    } catch (ClientHttpException e) {
      if (e.code() != 404) {
        throw e;
      }
    }
  }
}
