package com.example.chatbot.simulate;

import com.example.chatbot.simulate.SimulatedAgent.CallTool;
import com.example.chatbot.simulate.SimulatedAgent.OffRail;
import com.example.chatbot.simulate.SimulatedAgent.Reply;
import com.example.chatbot.simulate.SimulatedAgent.Step;
import com.example.chatbot.simulate.SimulatedAgent.ToolResult;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Simulate mode: takes the AI agent's jobs in place of the Camunda connectors runtime and answers them
 * with {@link SimulatedAgent} instead of a language model.
 *
 * <p>It speaks the same protocol as the AI Agent connector: to use a tool it completes the agent job with
 * an ad-hoc sub-process result that activates the tool element and passes {@code toolCall}; Camunda
 * runs the tool and creates the next agent job with {@code toolCallResults}; to answer it completes the
 * ad-hoc sub-process and sets {@code agent} with {@code responseText} and {@code context}. So the BPMN,
 * the tool workers, the listeners, the user tasks and the error boundary all run for real. Only the
 * model is replaced.
 *
 * <p>Never run this against a cluster that also runs the connectors runtime: both would take the jobs.
 */
@Component
@Profile("simulate")
public class SimulatedAgentWorker {

  private static final Logger LOG = LoggerFactory.getLogger(SimulatedAgentWorker.class);

  private final SimulatedAgent agent = new SimulatedAgent();
  /** Tool calls made in the current turn, per agent element instance. */
  private final Map<Long, List<Map<String, Object>>> turns = new ConcurrentHashMap<>();

  public SimulatedAgentWorker() {
    LOG.warn("Simulate mode is on: a scripted worker answers the AI agent jobs. No language model is called.");
  }

  /** Job type of the AI Agent Sub-process v2 template (Camunda 8.10). */
  @JobWorker(type = "io.camunda.agenticai:aiagent:subprocess:2", autoComplete = false, fetchAllVariables = true)
  public void agent810(JobClient client, ActivatedJob job) {
    handle(client, job);
  }

  /** Job type of the AI Agent Sub-process template shipped with Camunda 8.9. */
  @JobWorker(type = "io.camunda.agenticai:aiagent-job-worker:1", autoComplete = false, fetchAllVariables = true)
  public void agent89(JobClient client, ActivatedJob job) {
    handle(client, job);
  }

  void handle(JobClient client, ActivatedJob job) {
    Map<String, Object> vars = job.getVariablesAsMap();
    long turnKey = job.getElementInstanceKey();
    List<Map<String, Object>> calls = turns.computeIfAbsent(turnKey, k -> new ArrayList<>());
    String prompt = userPrompt(vars);

    List<ToolResult> results = new ArrayList<>();
    List<Map<String, Object>> collected = SimulatedAgent.asList(vars.get("toolCallResults"));
    for (Map<String, Object> call : calls) {
      Object content = collected.stream()
          .filter(r -> call.get("id").equals(r.get("id")))
          .map(r -> r.get("content"))
          .findFirst()
          .orElse(null);
      results.add(new ToolResult((String) call.get("name"), content));
    }

    Step step = agent.next(prompt, results);
    LOG.info("Simulated agent, turn {}: {}", turnKey, step);
    switch (step) {
      case CallTool tool -> {
        String id = "sim_" + turnKey + "_" + (calls.size() + 1);
        calls.add(Map.of("id", id, "name", tool.elementId(), "arguments", tool.arguments()));
        Map<String, Object> toolCall = new HashMap<>(tool.arguments());
        toolCall.put("_meta", Map.of("id", id, "name", tool.elementId()));
        client
            .newCompleteCommand(job)
            .withResult(r -> r.forAdHocSubProcess().activateElement(tool.elementId()).variables(Map.of("toolCall", toolCall)))
            .send()
            .join();
      }
      case Reply reply -> {
        Map<String, Object> agentVariable =
            Map.of("responseText", reply.text(), "context", context(vars, prompt, calls, results, reply.text()));
        client
            .newCompleteCommand(job)
            .variables(Map.of("agent", agentVariable))
            .withResult(r -> r.forAdHocSubProcess().completionConditionFulfilled(true))
            .send()
            .join();
        turns.remove(turnKey);
      }
      case OffRail off -> {
        // What the template's error expression produces when the model call limit is reached.
        client
            .newThrowErrorCommand(job)
            .errorCode("AGENT_OFF_RAIL")
            .errorMessage("Simulated: maximum number of model calls reached")
            .variables(Map.of("agentErrorCode", off.errorCode()))
            .send()
            .join();
        turns.remove(turnKey);
      }
    }
  }

  @SuppressWarnings("unchecked")
  private static String userPrompt(Map<String, Object> vars) {
    Map<String, Object> data = SimulatedAgent.asMap(vars.get("data"));
    return String.valueOf(SimulatedAgent.asMap(data.get("userPrompt")).get("prompt"));
  }

  /** Same shape as the connector's in-process memory, so the "conversation finished?" gateway works. */
  private static Map<String, Object> context(
      Map<String, Object> vars, String prompt, List<Map<String, Object>> calls, List<ToolResult> results, String reply) {
    Map<String, Object> previous = SimulatedAgent.asMap(vars.get("agentContext"));
    List<Map<String, Object>> messages =
        new ArrayList<>(SimulatedAgent.asList(SimulatedAgent.asMap(previous.get("conversation")).get("messages")));
    messages.add(Map.of("role", "user", "content", prompt));
    for (int i = 0; i < calls.size(); i++) {
      Map<String, Object> call = calls.get(i);
      messages.add(Map.of("role", "assistant", "toolCalls", List.of(call)));
      Object content = i < results.size() && results.get(i).content() != null ? results.get(i).content() : "";
      messages.add(Map.of("role", "tool_call_result", "id", call.get("id"), "content", content));
    }
    messages.add(Map.of("role", "assistant", "content", reply));
    int previousCalls = ((Number) SimulatedAgent.asMap(previous.get("metrics")).getOrDefault("modelCalls", 0)).intValue();
    Map<String, Object> context = new LinkedHashMap<>();
    context.put("conversation", Map.of("type", "in-process", "messages", messages));
    context.put("metrics", Map.of("modelCalls", previousCalls + calls.size() + 1, "simulated", true));
    return context;
  }
}
