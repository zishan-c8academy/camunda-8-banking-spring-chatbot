# From Camunda 7 on Spring to Camunda 8: where it shows in this code

Camunda 7 ran inside your Spring app. Camunda 8 runs beside it: the app talks to a cluster over gRPC or REST and does its work in job workers. Most of what changes for a Spring team is in the table below.

| Camunda 7 (embedded, Spring) | Camunda 8 | Where in this sample |
|---|---|---|
| Embedded engine, `ProcessEngineConfiguration`, engine tables in your database | Remote cluster (SaaS or Self-Managed). The app holds only a client. | `pom.xml` (`camunda-spring-boot-3-starter`), `application.yaml` |
| `camunda-bpm-spring-boot-starter` | `io.camunda:camunda-spring-boot-3-starter` for Spring Boot 3, `camunda-spring-boot-starter` for Spring Boot 4 | `pom.xml` |
| `JavaDelegate` with `execution.getVariable` / `setVariable` | `@JobWorker` method with `@Variable` parameters; the returned map becomes variables | `workers/*Worker.java` |
| `throw new BpmnError("CODE")` | `throw CamundaError.bpmnError("CODE", message, variables)` | `workers/LockCardWorker.java` (card not found) |
| RuntimeException, transaction rollback, job executor retries | The job fails with fewer retries and a backoff; at 0 retries Operate shows an incident. `CamundaError.jobError(...)` sets both. | `workers/LockCardWorker.java` (core banking down) |
| `ExecutionListener` class run in the engine transaction | Execution listener with a job type; a worker handles it outside the engine | `ProgressWorker`, `ConversationClosedWorker`, listeners in `tools/generate_models.py` |
| `runtimeService.startProcessInstanceByKey(key, businessKey, vars)` | `newCreateInstanceCommand().bpmnProcessId(id).latestVersion().businessId(id).variables(vars)` | `conversation/ConversationService.java` |
| `runtimeService.correlateMessage(name, businessKey, vars)` | `newPublishMessageCommand().messageName(name).correlationKey(key).timeToLive(ttl)`. The message can wait until the process is ready. | `conversation/ConversationService.java` |
| `taskService.createTaskQuery().taskCandidateGroup(g)`, `taskService.complete(id, vars)` | `newUserTaskSearchRequest().filter(f -> f.candidateGroup(g))`, `newCompleteUserTaskCommand(key)`. Queries read secondary storage, so new data shows after a short delay. | `web/AdvisorDeskController.java` |
| `processes.xml` or auto-deployment of the classpath | `@Deployment` on a bean, or a deploy command from code. Deploying an unchanged file creates no new version. | `process/ProcessDeployer.java` |
| JUEL expressions `${...}` | FEEL expressions `=...` | every `zeebe:input` in the BPMN |
| Embedded forms, generated task forms | Camunda Forms (JSON), linked to the user task | `bpmn/advisor-reply.form` |
| Cockpit, Tasklist (webapps) | Operate, Tasklist | screenshots in `docs/img/` |
| camunda-bpm-assert, Zeebe Process Test | Camunda Process Test (CPT), with Testcontainers. Zeebe Process Test has no 8.10 release. | `src/test/java/.../process/ChatbotProcessTest.java` |
| Connectors run inside the engine | Connectors run in a separate connectors runtime and read connector secrets | `docker-compose.yaml` (profile `llm`) |
| Credentials in properties files or environment variables of the app | Camunda secrets. 8.10: `=camunda.secrets.NAME`, resolved by the cluster from its secret store; 8.9: `{{secrets.NAME}}`, resolved by the connectors runtime. The app never sees them. | `tools/generate_models.py` (`sec`), `./secrets` in `docker-compose.yaml` |

## What to look at first

1. `workers/LockCardWorker.java`: the three outcomes a worker has (result, BPMN error, job error with retries).
2. `conversation/ConversationService.java`: starting an instance and publishing a message.
3. `process/ChatbotProcessTest.java`: how a process test replaces the agent with a script and drives the engine.

## Things that behave differently

- **No shared transaction.** In Camunda 7 a delegate and the engine state commit together. In Camunda 8 the worker does its work, then completes the job. If the app crashes in between, the job times out and runs again, so workers must be safe to run twice.
- **Variables are JSON.** Return maps or records; there are no Java object variables.
- **Queries are eventually consistent.** Search endpoints (user tasks, instances) read secondary storage, which lags the engine by a moment. Commands (start, publish, complete) act immediately.
- **Message correlation is by key, not by business key.** This sample uses the conversation id for both the business id and the correlation key.
