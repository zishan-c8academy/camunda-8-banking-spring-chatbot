package com.example.chatbot.conversation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Keeps the events of each conversation in memory and pushes them to the browsers that listen
 * (server-sent events). A browser that connects late gets the earlier events first.
 *
 * <p>Single-instance only: with several app instances, put a broker (Redis, Kafka) between the workers
 * and the browser connections.
 */
@Component
public class ChatEventHub {

  private static final Logger LOG = LoggerFactory.getLogger(ChatEventHub.class);
  private static final Duration EMITTER_TIMEOUT = Duration.ofMinutes(30);

  private final Map<String, Stream> streams = new ConcurrentHashMap<>();
  /**
   * Events go out as JSON text, serialized here. Relying on Spring's message converters for the record
   * broke when the Camunda 8.10 starter was on the classpath: the event name went out, the data did not.
   */
  private final ObjectMapper json =
      new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  private static final class Stream {
    final List<ChatEvent> history = new CopyOnWriteArrayList<>();
    final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    volatile Long processInstanceKey;
  }

  public void register(String conversationId, long processInstanceKey) {
    stream(conversationId).processInstanceKey = processInstanceKey;
  }

  public boolean exists(String conversationId) {
    return streams.containsKey(conversationId);
  }

  public Long processInstanceKey(String conversationId) {
    Stream s = streams.get(conversationId);
    return s == null ? null : s.processInstanceKey;
  }

  public void publish(String conversationId, ChatEvent event) {
    Stream s = stream(conversationId);
    s.history.add(event);
    for (SseEmitter emitter : s.emitters) {
      send(s, emitter, event);
    }
  }

  public List<ChatEvent> history(String conversationId) {
    Stream s = streams.get(conversationId);
    return s == null ? List.of() : new ArrayList<>(s.history);
  }

  public SseEmitter subscribe(String conversationId) {
    Stream s = stream(conversationId);
    SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT.toMillis());
    s.emitters.add(emitter);
    emitter.onCompletion(() -> s.emitters.remove(emitter));
    emitter.onTimeout(() -> s.emitters.remove(emitter));
    emitter.onError(e -> s.emitters.remove(emitter));
    for (ChatEvent event : s.history) {
      send(s, emitter, event);
    }
    return emitter;
  }

  public void clear() {
    streams.values().forEach(s -> s.emitters.forEach(SseEmitter::complete));
    streams.clear();
  }

  private Stream stream(String conversationId) {
    return streams.computeIfAbsent(conversationId, id -> new Stream());
  }

  private void send(Stream s, SseEmitter emitter, ChatEvent event) {
    String data;
    try {
      data = json.writeValueAsString(event);
    } catch (JsonProcessingException e) {
      LOG.warn("Could not serialize chat event {}: {}", event.type(), e.getMessage());
      return;
    }
    try {
      emitter.send(SseEmitter.event().name(event.type()).data(data, MediaType.APPLICATION_JSON));
    } catch (IOException | IllegalStateException e) {
      LOG.debug("Browser stream closed: {}", e.getMessage());
      s.emitters.remove(emitter);
    } catch (RuntimeException e) {
      LOG.warn("Could not send chat event {} to a browser: {}", event.type(), e.toString());
      s.emitters.remove(emitter);
    }
  }
}
