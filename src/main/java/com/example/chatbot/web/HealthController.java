package com.example.chatbot.web;

import com.example.chatbot.process.ChatbotProperties;
import com.example.chatbot.process.LlmSettingsCheck;
import io.camunda.client.CamundaClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Health of the app and of its Camunda connection. Shows names and settings, never secrets. */
@RestController
public class HealthController {

  private final ObjectProvider<CamundaClient> client;
  private final ChatbotProperties properties;
  private final Environment environment;
  private final LlmSettingsCheck llmSettings;

  public HealthController(
      ObjectProvider<CamundaClient> client,
      ChatbotProperties properties,
      Environment environment,
      LlmSettingsCheck llmSettings) {
    this.client = client;
    this.properties = properties;
    this.environment = environment;
    this.llmSettings = llmSettings;
  }

  @GetMapping("/api/health")
  public Map<String, Object> health() {
    Map<String, Object> camunda = new LinkedHashMap<>();
    // Which cluster this app talks to: a run without the saas profile connects to localhost.
    camunda.put("profiles", List.of(environment.getActiveProfiles()));
    camunda.put("target", properties.target());
    camunda.put("llmProvider", properties.llmProvider());
    camunda.put("processFolder", properties.resourceFolder());
    camunda.put("processId", properties.processId());
    camunda.put("jobTypePrefix", properties.jobTypePrefix());
    camunda.put("messageName", properties.messageName());
    camunda.put("simulatedAgent", environment.acceptsProfiles(Profiles.of("simulate")));
    CamundaClient c = client.getIfAvailable();
    if (c == null) {
      camunda.put("connection", "disabled");
    } else {
      camunda.put("restAddress", c.getConfiguration().getRestAddress().toString());
      try {
        var topology = c.newTopologyRequest().requestTimeout(Duration.ofSeconds(3)).send().join();
        camunda.put("connection", "up");
        camunda.put("gatewayVersion", topology.getGatewayVersion());
        if (llmSettings.needed()) {
          camunda.put("llmClusterVariable", properties.llmVariable() + ": " + llmSettings.check(c));
        }
      } catch (RuntimeException e) {
        camunda.put("connection", "unreachable");
        camunda.put("error", e.getClass().getSimpleName());
      }
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("status", "UP");
    body.put("camunda", camunda);
    return body;
  }
}
