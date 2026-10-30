package com.example.chatbot.conversation;

import java.time.Instant;

/**
 * One event on a conversation's browser stream.
 *
 * @param type {@code reply}, {@code progress} or {@code closed}
 * @param kind for replies: {@code answer}, {@code question}, {@code fallback}, {@code advisor} or
 *     {@code closed}
 * @param text what the browser shows
 */
public record ChatEvent(String type, String kind, String text, Instant at) {

  public static ChatEvent reply(String kind, String text) {
    return new ChatEvent("reply", kind, text, Instant.now());
  }

  public static ChatEvent progress(String text) {
    return new ChatEvent("progress", null, text, Instant.now());
  }

  public static ChatEvent closed() {
    return new ChatEvent("closed", null, "The conversation has ended.", Instant.now());
  }
}
