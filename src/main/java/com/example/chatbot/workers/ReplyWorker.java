package com.example.chatbot.workers;

import com.example.chatbot.conversation.ChatEvent;
import com.example.chatbot.conversation.ChatEventHub;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.annotation.Variable;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Sends a message to the customer's browser (job type {@code chatbot.reply}). Used by "Send reply",
 * "Send the question", the fallback and the advisor's reply.
 *
 * <p>Camunda 7: a JavaDelegate on a service task. Here the process does not wait for this app inside a
 * transaction: Camunda creates a job, this worker takes it, and completes it when done.
 */
@Component
public class ReplyWorker {

  private static final Pattern THINKING = Pattern.compile("(?s)<thinking>.*?</thinking>");

  private final ChatEventHub hub;

  public ReplyWorker(ChatEventHub hub) {
    this.hub = hub;
  }

  @JobWorker(type = "chatbot.reply")
  public void sendReply(
      @Variable("conversationId") String conversationId,
      @Variable("replyText") String replyText,
      @Variable("replyKind") String replyKind) {
    hub.publish(conversationId, ChatEvent.reply(replyKind == null ? "answer" : replyKind, clean(replyText)));
  }

  /** Some models add reasoning in tags even when asked not to; the customer never sees it. */
  static String clean(String text) {
    if (text == null || text.isBlank()) {
      return "Sorry, I could not prepare an answer. Please try again.";
    }
    return THINKING.matcher(text).replaceAll("").trim();
  }
}
