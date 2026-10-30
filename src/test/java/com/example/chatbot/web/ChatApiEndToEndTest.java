package com.example.chatbot.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.chatbot.process.ScriptedAgent;
import com.example.chatbot.process.TestLlmSettings;
import io.camunda.client.CamundaClient;
import io.camunda.process.test.api.CamundaProcessTestContext;
import io.camunda.process.test.api.CamundaSpringProcessTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The whole app over HTTP: deploy on start (here the 8.9 model with the OpenAI-compatible provider,
 * picked by properties), start a chat, read the server-sent events, send a follow-up. The engine is
 * real; only the AI agent is scripted.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "camunda.chatbot.target=8.9",
      "camunda.chatbot.llm-provider=openai",
      "camunda.chatbot.deploy-on-start=true"
    })
@CamundaSpringProcessTest
class ChatApiEndToEndTest {

  @LocalServerPort private int port;
  @Autowired private CamundaProcessTestContext processTestContext;
  @Autowired private CamundaClient client;

  private final HttpClient http = HttpClient.newHttpClient();

  private HttpResponse<String> post(String path, String json) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static String field(String json, String name) {
    int start = json.indexOf("\"" + name + "\":\"") + name.length() + 4;
    return json.substring(start, json.indexOf('"', start));
  }

  @Test
  void chatOverHttpWithServerSentEvents() throws Exception {
    TestLlmSettings.set(client, TestLlmSettings.VALUE);
    processTestContext
        .mockJobWorker("io.camunda.agenticai:aiagent-job-worker:1")
        .withHandler(
            new ScriptedAgent()
                .callTool("GetAccountSummary", java.util.Map.of())
                .reply("Your Unlimited chequing account ending 5521 has 834.12 dollars.")
                .callTool("CloseConversation", java.util.Map.of())
                .reply("Goodbye."));

    HttpResponse<String> health =
        http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/health")).build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(health.body())
        .contains("\"connection\":\"up\"")
        .contains("bpmn/8.9-openai")
        .contains("\"llmClusterVariable\":\"CHATBOT_LLM: ok\"");

    HttpResponse<String> started =
        post("/api/conversations", "{\"customerId\":\"C-1002\",\"message\":\"What is my balance?\"}");
    assertThat(started.statusCode()).isEqualTo(201);
    String conversationId = field(started.body(), "conversationId");
    assertThat(field(started.body(), "operateUrl")).endsWith("/processes/" + field(started.body(), "processInstanceKey"));

    List<String> lines = new CopyOnWriteArrayList<>();
    http.sendAsync(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/conversations/" + conversationId + "/events")).build(),
        HttpResponse.BodyHandlers.ofLines())
        .thenAccept(r -> {
          lines.add("status:" + r.statusCode());
          r.body().forEach(lines::add);
        })
        .exceptionally(e -> {
          lines.add("error:" + e);
          return null;
        });

    await().atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(lines).as("SSE lines").anyMatch(l -> l.contains("834.12")));
    assertThat(lines).anyMatch(l -> l.equals("event:progress"));
    // every data line is a JSON object the browser can parse, not a quoted string
    assertThat(lines.stream().filter(l -> l.startsWith("data:"))).allMatch(l -> l.startsWith("data:{\"type\":"));

    assertThat(post("/api/conversations/" + conversationId + "/messages", "{\"message\":\"Thanks, bye\"}").statusCode())
        .isEqualTo(202);
    await().atMost(Duration.ofSeconds(20)).until(() -> lines.stream().anyMatch(l -> l.equals("event:closed")));

    assertThat(post("/api/conversations/unknown/messages", "{\"message\":\"hi\"}").statusCode()).isEqualTo(404);
    assertThat(post("/api/conversations", "{\"customerId\":\"C-9999\",\"message\":\"hi\"}").statusCode()).isEqualTo(400);
  }
}
