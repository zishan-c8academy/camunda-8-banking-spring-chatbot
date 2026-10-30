package com.example.chatbot.process;

import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import io.camunda.client.api.worker.JobHandler;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Stands in for the AI Agent connector in process tests: no model, no connector runtime. It answers the
 * agent's jobs the way the connector does, from a script:
 *
 * <ul>
 *   <li>{@link #callTool}: activate one tool element in the ad-hoc sub-process with a {@code toolCall}
 *       variable, as the connector does when the model asks for a tool.
 *   <li>{@link #reply}: complete the ad-hoc sub-process and set {@code agent} with {@code responseText}
 *       and a {@code context} that holds the conversation.
 *   <li>{@link #offRail}: throw the {@code AGENT_OFF_RAIL} error that the template's error expression
 *       raises when the model call limit is hit.
 * </ul>
 *
 * Every job's variables are recorded so tests can check what the agent received (tool results, the user
 * prompt, the context from the previous turn).
 */
public class ScriptedAgent implements JobHandler {

  private sealed interface Step permits CallTool, Reply, OffRail {}

  private record CallTool(String elementId, Map<String, Object> arguments) implements Step {}

  private record Reply(String text) implements Step {}

  private record OffRail(String errorCode) implements Step {}

  private final Queue<Step> steps = new ConcurrentLinkedQueue<>();
  private final List<Map<String, Object>> jobs = new CopyOnWriteArrayList<>();
  private final List<Map<String, Object>> messages = new ArrayList<>();
  private final List<Map<String, Object>> returnedContexts = new CopyOnWriteArrayList<>();
  private int modelCalls;
  private int callIds;

  public ScriptedAgent callTool(String elementId, Map<String, Object> arguments) {
    steps.add(new CallTool(elementId, arguments));
    return this;
  }

  public ScriptedAgent reply(String text) {
    steps.add(new Reply(text));
    return this;
  }

  public ScriptedAgent offRail(String errorCode) {
    steps.add(new OffRail(errorCode));
    return this;
  }

  /** Variables of every agent job, in order. */
  public List<Map<String, Object>> jobs() {
    return jobs;
  }

  /** The agent context returned at the end of each turn. */
  public List<Map<String, Object>> returnedContexts() {
    return returnedContexts;
  }

  @Override
  public synchronized void handle(JobClient client, ActivatedJob job) {
    jobs.add(job.getVariablesAsMap());
    Step step = steps.poll();
    if (step == null) {
      client.newFailCommand(job).retries(0).errorMessage("ScriptedAgent: script has no more steps").send().join();
      return;
    }
    modelCalls++;
    switch (step) {
      case CallTool call -> {
        String id = "call_" + (++callIds);
        messages.add(
            Map.of(
                "role", "assistant",
                "toolCalls", List.of(Map.of("id", id, "name", call.elementId(), "arguments", call.arguments()))));
        Map<String, Object> toolCall = new HashMap<>(call.arguments());
        toolCall.put("_meta", Map.of("id", id, "name", call.elementId()));
        client
            .newCompleteCommand(job)
            .withResult(
                r -> r.forAdHocSubProcess().activateElement(call.elementId()).variables(Map.of("toolCall", toolCall)))
            .send()
            .join();
      }
      case Reply reply -> {
        messages.add(Map.of("role", "assistant", "content", reply.text()));
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("conversation", Map.of("type", "in-process", "messages", new ArrayList<>(messages)));
        context.put("metrics", Map.of("modelCalls", modelCalls));
        returnedContexts.add(context);
        Map<String, Object> agent = Map.of("responseText", reply.text(), "context", context);
        client
            .newCompleteCommand(job)
            .variables(Map.of("agent", agent))
            .withResult(r -> r.forAdHocSubProcess().completionConditionFulfilled(true))
            .send()
            .join();
      }
      case OffRail off ->
          client
              .newThrowErrorCommand(job)
              .errorCode("AGENT_OFF_RAIL")
              .errorMessage("Maximum number of model calls reached")
              .variables(Map.of("agentErrorCode", off.errorCode()))
              .send()
              .join();
    }
  }
}
