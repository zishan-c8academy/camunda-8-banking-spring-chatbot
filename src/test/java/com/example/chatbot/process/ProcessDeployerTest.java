package com.example.chatbot.process;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;

class ProcessDeployerTest {

  private static final String[] NONE = {};

  @Test
  void warnsOnlyForALocalClusterWithoutProfile() {
    assertThat(ProcessDeployer.localWithoutProfile(URI.create("http://localhost:8080"), NONE)).isTrue();
    assertThat(ProcessDeployer.localWithoutProfile(URI.create("http://127.0.0.1:8080"), NONE)).isTrue();
    // The profile was set: a local cluster is what the user asked for (simulate mode, for example).
    assertThat(ProcessDeployer.localWithoutProfile(URI.create("http://localhost:8080"), new String[] {"simulate"}))
        .isFalse();
    // A remote cluster without a profile: the user set the addresses on purpose.
    assertThat(ProcessDeployer.localWithoutProfile(URI.create("https://bru-2.api.camunda.io/abc"), NONE)).isFalse();
  }
}
