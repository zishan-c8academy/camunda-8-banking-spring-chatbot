package com.example.chatbot.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.client.CamundaClient;
import io.camunda.client.api.search.enums.UserTaskState;
import io.camunda.client.api.search.response.UserTask;
import io.camunda.client.api.search.response.Variable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A minimal advisor desk for demos: lists the open advisor tasks and completes one with an answer, the
 * same thing an advisor does in Tasklist. Uses the Camunda user task API.
 *
 * <p>Camunda 7 comparison: {@code taskService.createTaskQuery().taskCandidateGroup("advisors")} and
 * {@code taskService.complete(id, vars)}. In Camunda 8 the query reads secondary storage, so a new task
 * shows up after a short delay.
 */
@RestController
@RequestMapping("/api/advisor/tasks")
public class AdvisorDeskController {

  private final ObjectProvider<CamundaClient> client;
  private final ObjectMapper json;

  public AdvisorDeskController(ObjectProvider<CamundaClient> client, ObjectMapper json) {
    this.client = client;
    this.json = json;
  }

  public record AdvisorTask(String key, String name, String conversationId, String summary, String question) {}

  public record Answer(@NotBlank String answer) {}

  @GetMapping
  public ResponseEntity<List<AdvisorTask>> openTasks() {
    CamundaClient c = client.getIfAvailable();
    if (c == null) {
      return ResponseEntity.ok(List.of());
    }
    List<AdvisorTask> tasks = new ArrayList<>();
    for (UserTask task :
        c.newUserTaskSearchRequest()
            .filter(f -> f.state(UserTaskState.CREATED).candidateGroup("advisors"))
            .send()
            .join()
            .items()) {
      Map<String, String> vars = new LinkedHashMap<>();
      try {
        for (Variable v : c.newUserTaskVariableSearchRequest(task.getUserTaskKey()).send().join().items()) {
          vars.put(v.getName(), text(v.getValue()));
        }
      } catch (RuntimeException notIndexedYet) {
        // The task search can return a task a moment before its variables are searchable (secondary storage
        // lags the engine by a second or two). Skip it this round; the desk polls again.
        continue;
      }
      tasks.add(
          new AdvisorTask(
              Long.toString(task.getUserTaskKey()),
              task.getName(),
              vars.get("conversationId"),
              vars.get("summaryShown"),
              vars.get("questionShown")));
    }
    return ResponseEntity.ok(tasks);
  }

  @PostMapping("/{key}/complete")
  public ResponseEntity<Void> complete(@PathVariable long key, @Valid @RequestBody Answer answer) {
    CamundaClient c = client.getIfAvailable();
    if (c == null) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }
    c.newCompleteUserTaskCommand(key).variables(Map.of("advisorAnswer", answer.answer())).send().join();
    return ResponseEntity.noContent().build();
  }

  /** Variable values come back as JSON; show strings without quotes. */
  private String text(String value) {
    try {
      Object parsed = json.readValue(value, Object.class);
      return parsed instanceof String s ? s : value;
    } catch (JsonProcessingException | IllegalArgumentException e) {
      return value;
    }
  }
}
