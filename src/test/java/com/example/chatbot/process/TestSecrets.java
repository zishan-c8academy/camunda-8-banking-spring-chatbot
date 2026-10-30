package com.example.chatbot.process;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Frame;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.testcontainers.DockerClientFactory;

/**
 * Gives the Camunda test containers a secret store, as Camunda 8.10 needs one to resolve
 * {@code =camunda.secrets.NAME} in the 8.10 models.
 *
 * <p>Camunda 8.10 reads a file store: one file per secret, the file name is the secret name. The
 * Camunda Process Test runtime sets only environment variables on its container, so
 * {@code src/test/resources/config/application.yaml} points the store at {@link #DIRECTORY}, and this
 * class writes dummy files there in every Camunda container of this test run. The store reads the
 * directory when a secret is first resolved, so writing the files before a test starts a process is
 * enough. The values are dummies: the scripted agent never calls an LLM.
 */
public final class TestSecrets {

  public static final String DIRECTORY = "/tmp/camunda-test-secrets";
  public static final List<String> NAMES =
      List.of("GENAI_GATEWAY_KEY", "CHATBOT_LLM_API_KEY", "CHATBOT_AWS_ACCESS_KEY", "CHATBOT_AWS_SECRET_KEY");

  private static final Set<String> PREPARED = ConcurrentHashMap.newKeySet();

  private TestSecrets() {}

  /** Writes the secret files into each running Camunda container of this test run, once per container. */
  public static void writeIntoCamundaContainers() {
    DockerClient docker = DockerClientFactory.instance().client();
    List<Container> containers =
        docker
            .listContainersCmd()
            .withLabelFilter(Map.of(DockerClientFactory.TESTCONTAINERS_SESSION_ID_LABEL, DockerClientFactory.SESSION_ID))
            .exec();
    String script =
        "mkdir -p " + DIRECTORY + " && "
            + NAMES.stream().map(n -> "printf test-value > " + DIRECTORY + "/" + n).collect(Collectors.joining(" && "));
    for (Container container : containers) {
      if (!container.getImage().startsWith("camunda/camunda") || !PREPARED.add(container.getId())) {
        continue;
      }
      ExecCreateCmdResponse exec =
          docker.execCreateCmd(container.getId()).withCmd("sh", "-c", script).withAttachStderr(true).exec();
      try {
        docker.execStartCmd(exec.getId()).exec(new ResultCallback.Adapter<Frame>()).awaitCompletion(30, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
      Long exitCode = docker.inspectExecCmd(exec.getId()).exec().getExitCodeLong();
      if (exitCode == null || exitCode != 0) {
        PREPARED.remove(container.getId());
        throw new IllegalStateException("Could not write test secrets into " + container.getImage() + ", exit " + exitCode);
      }
    }
  }

  /** Camunda engine version the tests run on: 8.9 by default, 8.10 with the camunda-8.10 Maven profiles. */
  public static String engine() {
    return System.getProperty("chatbot.test.engine", "8.9");
  }
}
