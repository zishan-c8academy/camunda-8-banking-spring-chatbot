package com.example.chatbot.process;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

/** The saas profile resolves its addresses and the Operate link from the environment variables. */
@SpringBootTest(
    properties = {
      "camunda.client.enabled=false",
      "CAMUNDA_CLUSTER_ID=abc-123",
      "CAMUNDA_CLUSTER_REGION=bru-2",
      "CAMUNDA_CLIENT_ID=id",
      "CAMUNDA_CLIENT_SECRET=secret"
    })
@ActiveProfiles("saas")
class SaasProfileTest {

  @Autowired private Environment environment;
  @Autowired private ChatbotProperties properties;

  @Test
  void addressesAndOperateLinkFollowTheClusterSettings() {
    assertThat(environment.getProperty("camunda.client.rest-address")).isEqualTo("https://bru-2.api.camunda.io/abc-123");
    assertThat(environment.getProperty("camunda.client.grpc-address")).isEqualTo("https://abc-123.bru-2.zeebe.camunda.io:443");
    assertThat(properties.operateInstanceUrl()).isEqualTo("https://bru-2.operate.camunda.io/abc-123/processes/{key}");
  }
}
