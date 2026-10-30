package com.example.chatbot.web;

import com.example.chatbot.bank.DemoBank;
import com.example.chatbot.conversation.ChatEventHub;
import com.example.chatbot.conversation.ConversationService;
import com.example.chatbot.conversation.ConversationService.StartedConversation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api")
public class ChatController {

  private final ConversationService conversations;
  private final ChatEventHub hub;
  private final DemoBank bank;

  public ChatController(ConversationService conversations, ChatEventHub hub, DemoBank bank) {
    this.conversations = conversations;
    this.hub = hub;
    this.bank = bank;
  }

  public record StartRequest(@NotBlank String customerId, @NotBlank @Size(max = 2000) String message) {}

  public record MessageRequest(@NotBlank @Size(max = 2000) String message) {}

  /** Process instance keys are 64-bit; they go to the browser as strings. */
  public record StartResponse(String conversationId, String processInstanceKey, String operateUrl) {}

  @PostMapping("/conversations")
  public ResponseEntity<StartResponse> start(@Valid @RequestBody StartRequest request) {
    StartedConversation started = conversations.start(request.customerId(), request.message());
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            new StartResponse(
                started.conversationId(),
                Long.toString(started.processInstanceKey()),
                conversations.operateLink(started.processInstanceKey())));
  }

  @PostMapping("/conversations/{id}/messages")
  public ResponseEntity<Void> message(
      @PathVariable String id, @Valid @RequestBody MessageRequest request) {
    if (!hub.exists(id)) {
      return ResponseEntity.notFound().build();
    }
    conversations.sendMessage(id, request.message());
    return ResponseEntity.accepted().build();
  }

  @GetMapping(path = "/conversations/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter events(@PathVariable String id) {
    return hub.subscribe(id);
  }

  @GetMapping("/customers")
  public List<DemoBank.Customer> customers() {
    return bank.customers();
  }

  /** Accounts and cards of a demo customer, for checking a card lock from a test script. */
  @GetMapping("/demo/customers/{customerId}")
  public Map<String, Object> customer(@PathVariable String customerId) {
    return bank.summary(customerId);
  }

  /** Puts the fictional bank data back to its starting state (cards unlocked) and forgets chats. */
  @PostMapping("/demo/reset")
  public Map<String, String> reset() {
    bank.reset();
    hub.clear();
    return Map.of("status", "reset");
  }
}
