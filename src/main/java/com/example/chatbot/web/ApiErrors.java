package com.example.chatbot.web;

import com.example.chatbot.conversation.ConversationService.CamundaDisabledException;
import io.camunda.client.api.command.ClientException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiErrors {

  private static final Logger LOG = LoggerFactory.getLogger(ApiErrors.class);

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
    return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
  }

  @ExceptionHandler(CamundaDisabledException.class)
  public ResponseEntity<Map<String, String>> disabled(CamundaDisabledException e) {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", e.getMessage()));
  }

  @ExceptionHandler(ClientException.class)
  public ResponseEntity<Map<String, String>> camunda(ClientException e) {
    LOG.warn("Camunda call failed: {}", e.getMessage());
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
        .body(Map.of("error", "Camunda did not accept the request. Check the app log and /api/health."));
  }
}
