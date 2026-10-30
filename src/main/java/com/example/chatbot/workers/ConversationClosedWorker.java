package com.example.chatbot.workers;

import com.example.chatbot.conversation.ChatEvent;
import com.example.chatbot.conversation.ChatEventHub;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.annotation.Variable;
import org.springframework.stereotype.Component;

/**
 * Tells the browser the conversation ended (job type {@code chatbot.closed}). It runs from the end
 * execution listener on the process, so every end event (goodbye, timeout, advisor) is covered once.
 */
@Component
public class ConversationClosedWorker {

  private final ChatEventHub hub;

  public ConversationClosedWorker(ChatEventHub hub) {
    this.hub = hub;
  }

  @JobWorker(type = "chatbot.closed")
  public void closed(@Variable("conversationId") String conversationId) {
    hub.publish(conversationId, ChatEvent.closed());
  }
}
