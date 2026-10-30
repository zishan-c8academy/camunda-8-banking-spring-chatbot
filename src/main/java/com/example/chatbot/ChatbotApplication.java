package com.example.chatbot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Banking chatbot sample. The conversation is a Camunda 8 process; this app starts it, answers its jobs
 * and streams the replies to the browser.
 *
 * <p>Camunda 7 comparison: there is no embedded engine and no process engine configuration here. The
 * engine runs as a separate cluster (SaaS, Camunda 8 Run or Docker) and this app talks to it through the
 * Camunda Spring Boot starter.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ChatbotApplication {

  public static void main(String[] args) {
    SpringApplication.run(ChatbotApplication.class, args);
  }
}
