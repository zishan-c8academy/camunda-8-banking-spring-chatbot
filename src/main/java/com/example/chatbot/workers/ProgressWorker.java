package com.example.chatbot.workers;

import com.example.chatbot.conversation.ChatEvent;
import com.example.chatbot.conversation.ChatEventHub;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.annotation.Variable;
import io.camunda.client.api.response.ActivatedJob;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Turns the start of each agent tool into a "thinking" step in the chat (job type {@code
 * chatbot.progress}). Every tool in the model has a start execution listener with this job type; the
 * job carries the id of the tool element.
 *
 * <p>Camunda 7: an ExecutionListener class on the activity, run inside the engine's transaction. In
 * Camunda 8 a listener is a job like any other, so this runs in your app, outside the engine.
 */
@Component
public class ProgressWorker {

  static final Map<String, String> STEPS =
      Map.of(
          "GetAccountSummary", "Checking your accounts",
          "LookUpBranchHoursAndFees", "Looking up branch hours and fees",
          "LockCard", "Locking the card",
          "CreateServiceRequest", "Opening a service request",
          "AskCustomer", "Preparing a question for you",
          "HandOffToAdvisor", "Asking an advisor. This can take a few minutes");

  private final ChatEventHub hub;

  public ProgressWorker(ChatEventHub hub) {
    this.hub = hub;
  }

  @JobWorker(type = "chatbot.progress")
  public void announce(ActivatedJob job, @Variable("conversationId") String conversationId) {
    hub.publish(conversationId, ChatEvent.progress(STEPS.getOrDefault(job.getElementId(), "Working on it")));
  }
}
