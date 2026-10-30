package com.example.chatbot.process;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.command.DeployResourceCommandStep1;
import io.camunda.client.api.response.DeploymentEvent;
import io.camunda.client.spring.event.CamundaClientCreatedSpringEvent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Deploys the variant folder that matches {@code camunda.chatbot.target} and {@code llm-provider}: the
 * BPMN with the decision table and the form it uses, in one deployment.
 *
 * <p>The starter's {@code @Deployment} annotation takes a fixed list of files. This app picks one of five
 * folders from configuration, so it deploys from code once the client is connected. Deploying an
 * unchanged file is a no-op in Camunda 8: no new version is created.
 *
 * <p>Camunda 7 comparison: replaces the processes.xml / auto-deployment of the embedded engine.
 */
@Component
public class ProcessDeployer {

  private static final Logger LOG = LoggerFactory.getLogger(ProcessDeployer.class);

  private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "::1", "[::1]");

  private final ChatbotProperties properties;
  private final Environment environment;

  public ProcessDeployer(ChatbotProperties properties, Environment environment) {
    this.properties = properties;
    this.environment = environment;
  }

  @EventListener
  public void onClientCreated(CamundaClientCreatedSpringEvent event) {
    URI restAddress = event.getClient().getConfiguration().getRestAddress();
    LOG.info("Camunda REST address {}", restAddress);
    if (localWithoutProfile(restAddress, environment.getActiveProfiles())) {
      // The usual reason a chat lands on a laptop cluster instead of SaaS: the profile did not reach the JVM.
      LOG.warn(
          "No Spring profile is active, so the app connects to a local Camunda at {} and deploys there. For Camunda SaaS, start it with the saas profile: SPRING_PROFILES_ACTIVE=saas, or ./mvnw spring-boot:run \"-Dspring-boot.run.profiles=saas\".",
          restAddress);
    }
    if (!properties.deployOnStart()) {
      LOG.info("Deploy on start is off; not deploying {}", properties.resourceFolder());
      return;
    }
    try {
      deploy(event.getClient(), properties.resourceFolder());
    } catch (RuntimeException e) {
      // Keep the app up so the UI and health endpoint still answer; the health endpoint shows the cause.
      LOG.error("Could not deploy {}: {}", properties.resourceFolder(), e.getMessage());
    }
  }

  /** True when the app talks to a Camunda on this machine because no profile was set. */
  static boolean localWithoutProfile(URI restAddress, String[] activeProfiles) {
    return activeProfiles.length == 0 && restAddress != null && LOCAL_HOSTS.contains(restAddress.getHost());
  }

  /** Deploys every file in one classpath folder, for example {@code bpmn/8.10-openai}, as one deployment. */
  public static DeploymentEvent deploy(CamundaClient client, String folder) {
    Resource[] files;
    try {
      files = new PathMatchingResourcePatternResolver().getResources("classpath:" + folder + "/*");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    if (files.length == 0) {
      throw new IllegalStateException("No process files in " + folder);
    }
    DeployResourceCommandStep1 command = client.newDeployResourceCommand();
    DeployResourceCommandStep1.DeployResourceCommandStep2 ready = null;
    for (Resource file : files) {
      try {
        ready = (ready == null ? command : ready).addResourceBytes(file.getContentAsByteArray(), file.getFilename());
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    DeploymentEvent result = ready.send().join();
    result
        .getProcesses()
        .forEach(p -> LOG.info("Deployed {} version {} from {}", p.getBpmnProcessId(), p.getVersion(), folder));
    return result;
  }
}
