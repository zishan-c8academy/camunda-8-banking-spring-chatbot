package com.example.chatbot.process;

import static org.assertj.core.api.Assertions.assertThat;

import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import io.camunda.zeebe.model.bpmn.validation.zeebe.ZeebeDesignTimeValidators;
import java.io.InputStream;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.camunda.bpm.model.xml.validation.ValidationResults;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Static checks on the generated models: they parse with the Zeebe model API, pass Zeebe's design time
 * validation, keep the BPMN schema element order, differ only in the agent element, and each variant
 * folder deploys on its own.
 */
class BpmnModelsTest {

  static final String BPMN = "http://www.omg.org/spec/BPMN/20100524/MODEL";
  static final String ZEEBE = "http://camunda.org/schema/zeebe/1.0";
  static final List<String> FILES =
      List.of("chatbot-8.10-bedrock.bpmn", "chatbot-8.10-openai.bpmn", "chatbot-8.9-bedrock.bpmn", "chatbot-8.9-openai.bpmn",
          "chatbot-8.10-gateway.bpmn");
  /** The gateway variant's prefixed names and their generic counterparts. */
  static final Map<String, String> GATEWAY_NAMES =
      Map.of(
          "BankingChatbot_Conversation", "chatbot-conversation",
          "Spring Boot AI chatbot", "Chatbot conversation",
          "banking-chatbot.", "chatbot.",
          "BankingChatbot_BranchFees", "branch-hours-and-fees",
          "BankingChatbot_AdvisorReply", "advisor-reply");
  static final List<String> TOOLS =
      List.of("GetAccountSummary", "LookUpBranchHoursAndFees", "CreateServiceRequest", "CloseConversation",
          "LockCard", "HandOffToAdvisor", "AskCustomer");

  /** The variant folder a generated file lives in: chatbot-8.10-openai.bpmn is in bpmn/8.10-openai. */
  static String folder(String file) {
    if (file.startsWith("BankingChatbot_")) {
      return "bpmn/8.10-gateway";
    }
    return "bpmn/" + file.replace("chatbot-", "").replace(".bpmn", "");
  }

  private static InputStream open(String file) {
    return BpmnModelsTest.class.getClassLoader().getResourceAsStream(folder(file) + "/" + file);
  }

  private static Document dom(String file) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    DocumentBuilder builder = factory.newDocumentBuilder();
    try (InputStream in = open(file)) {
      return builder.parse(in);
    }
  }

  private static Element byId(Document doc, String id) {
    NodeList all = doc.getElementsByTagNameNS(BPMN, "*");
    for (int i = 0; i < all.getLength(); i++) {
      Element e = (Element) all.item(i);
      if (id.equals(e.getAttribute("id"))) {
        return e;
      }
    }
    throw new AssertionError("No element " + id);
  }

  private static List<Element> children(Element parent) {
    List<Element> list = new ArrayList<>();
    for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
      if (n instanceof Element e) {
        list.add(e);
      }
    }
    return list;
  }

  private static Map<String, String> agentInputs(Document doc) {
    Element agent = byId(doc, "Agent");
    NodeList inputs = agent.getElementsByTagNameNS(ZEEBE, "input");
    Map<String, String> map = new java.util.HashMap<>();
    for (int i = 0; i < inputs.getLength(); i++) {
      Element in = (Element) inputs.item(i);
      // only the agent's own inputs, not those of the tools inside it
      if (in.getParentNode().getParentNode().getParentNode() == agent) {
        map.put(in.getAttribute("target"), in.getAttribute("source"));
      }
    }
    return map;
  }

  @ParameterizedTest
  @ValueSource(strings = {"chatbot-8.10-bedrock.bpmn", "chatbot-8.10-openai.bpmn", "chatbot-8.9-bedrock.bpmn", "chatbot-8.9-openai.bpmn", "chatbot-8.10-gateway.bpmn"})
  void parsesAndPassesZeebeValidation(String file) {
    BpmnModelInstance model = Bpmn.readModelFromStream(open(file));
    Bpmn.validateModel(model);
    ValidationResults results = model.validate(ZeebeDesignTimeValidators.VALIDATORS);
    StringBuilder report = new StringBuilder();
    results.getResults().forEach((element, list) -> list.forEach(r -> report.append(r.getMessage()).append('\n')));
    assertThat(results.hasErrors()).as(report.toString()).isFalse();
  }

  @ParameterizedTest
  @ValueSource(strings = {"chatbot-8.10-bedrock.bpmn", "chatbot-8.10-openai.bpmn", "chatbot-8.9-bedrock.bpmn", "chatbot-8.9-openai.bpmn", "chatbot-8.10-gateway.bpmn"})
  void keepsBpmnSchemaOrderInsideEveryElement(String file) throws Exception {
    // documentation, extensionElements, incoming, outgoing, then event definitions / loop characteristics,
    // then child flow elements. Some tools reject other orders.
    List<String> order = List.of("documentation", "extensionElements", "incoming", "outgoing");
    NodeList all = dom(file).getElementsByTagNameNS(BPMN, "*");
    for (int i = 0; i < all.getLength(); i++) {
      Element element = (Element) all.item(i);
      int last = -1;
      for (Element child : children(element)) {
        if (!BPMN.equals(child.getNamespaceURI())) {
          continue;
        }
        int rank = order.indexOf(child.getLocalName());
        if (rank == -1) {
          rank = order.size();
        }
        assertThat(rank)
            .as("%s in %s#%s", child.getLocalName(), file, element.getAttribute("id"))
            .isGreaterThanOrEqualTo(last);
        last = rank;
      }
    }
  }

  @Test
  void agentElementMatchesTheTargetTemplate() throws Exception {
    for (String file : FILES) {
      Document doc = dom(file);
      Element agent = byId(doc, "Agent");
      String jobType = ((Element) agent.getElementsByTagNameNS(ZEEBE, "taskDefinition").item(0)).getAttribute("type");
      boolean hasAgentDefinition = agent.getElementsByTagNameNS(ZEEBE, "agentDefinition").getLength() > 0;
      Map<String, String> inputs = agentInputs(doc);
      if (file.contains("8.10")) {
        assertThat(agent.getAttributeNS(ZEEBE, "modelerTemplate"))
            .isEqualTo("io.camunda.connectors.agenticai.ai-agent-subprocess.v2");
        assertThat(jobType).isEqualTo("io.camunda.agenticai:aiagent:subprocess:2");
        assertThat(hasAgentDefinition).as(file).isTrue();
      } else {
        assertThat(agent.getAttributeNS(ZEEBE, "modelerTemplate"))
            .isEqualTo("io.camunda.connectors.agenticai.aiagent.jobworker.v1");
        assertThat(jobType).isEqualTo("io.camunda.agenticai:aiagent-job-worker:1");
        assertThat(hasAgentDefinition).as(file).isFalse();
      }
      String expectedProvider =
          file.contains("bedrock") ? "bedrock" : file.contains("8.10") ? "openai" : "openaiCompatible";
      assertThat(inputs.get("provider.type")).as(file).isEqualTo(expectedProvider);
      assertThat(inputs.get("agentContext")).isEqualTo("=chatAgent.context");
      if (file.contains("gateway")) {
        assertThat(inputs.get("provider.openai.backend.custom.endpoint")).isEqualTo("=camunda.vars.env.GENAI_GATEWAY.endpoint");
        assertThat(inputs.get("provider.openai.model.model")).isEqualTo("=camunda.vars.env.GENAI_GATEWAY.model");
        assertThat(inputs.get("provider.openai.backend.custom.authentication.apiKey")).isEqualTo("=camunda.secrets.GENAI_GATEWAY_KEY");
      } else {
        // The process carries its own LLM settings: the CHATBOT_LLM cluster variable, never an app variable.
        String llm = "=camunda.vars.env.CHATBOT_LLM.";
        assertThat(inputs.entrySet())
            .as(file)
            .filteredOn(e -> e.getKey().matches("provider\\..*\\.(model\\.model|region|endpoint)"))
            .hasSize(2)
            .allSatisfy(e -> assertThat(e.getValue()).startsWith(llm));
      }
      assertThat(inputs.values()).as(file).noneMatch(v -> v.contains("llm."));
      assertThat(inputs.values()).noneMatch(v -> v.contains("\u2014"));
      // Credentials are only ever secret references: on 8.10 the centralized =camunda.secrets.NAME, which
      // the cluster resolves before the job runs; on 8.9 the connectors runtime's {{secrets.NAME}}.
      String secretPrefix = file.contains("8.10") ? "=camunda.secrets." : "{{secrets.";
      List<String> credentials = inputs.entrySet().stream()
          .filter(e -> e.getKey().matches(".*(apiKey|accessKey|secretKey)$"))
          .map(Map.Entry::getValue)
          .toList();
      assertThat(credentials).as(file).isNotEmpty().allMatch(v -> v.startsWith(secretPrefix));
      // and no file mixes the two forms
      String xml = new String(open(file).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      if (file.contains("8.10")) {
        assertThat(xml).as(file).doesNotContain("{{secrets.");
      } else {
        assertThat(xml).as(file).doesNotContain("camunda.secrets.");
      }
    }
  }

  @Test
  void everythingExceptTheAgentElementIsIdentical() throws Exception {
    String reference = null;
    for (String file : FILES) {
      Document doc = dom(file);
      Element agent = byId(doc, "Agent");
      for (Element child : children(agent)) {
        if ("extensionElements".equals(child.getLocalName())) {
          agent.removeChild(child);
        }
      }
      agent.removeAttributeNS(ZEEBE, "modelerTemplate");
      agent.removeAttributeNS(ZEEBE, "modelerTemplateVersion");
      Element process = (Element) doc.getElementsByTagNameNS(BPMN, "process").item(0);
      StringWriter out = new StringWriter();
      TransformerFactory.newInstance().newTransformer().transform(new DOMSource(process), new StreamResult(out));
      String text = out.toString();
      if (file.contains("gateway")) {
        for (Map.Entry<String, String> name : GATEWAY_NAMES.entrySet()) {
          text = text.replace(name.getKey(), name.getValue());
        }
      }
      if (reference == null) {
        reference = text;
      } else {
        assertThat(text).as(file).isEqualTo(reference);
      }
    }
  }

  @Test
  void linkedDecisionAndFormsUseDeploymentBinding() throws Exception {
    // The app deploys BPMN, DMN and form together, so each process version uses the DMN and form of its own
    // deployment. From Hub, deploy with "All resources" for the same reason.
    for (String file : FILES) {
      Document doc = dom(file);
      for (String tag : List.of("calledDecision", "formDefinition")) {
        NodeList links = doc.getElementsByTagNameNS(ZEEBE, tag);
        assertThat(links.getLength()).as(file + " " + tag).isPositive();
        for (int i = 0; i < links.getLength(); i++) {
          assertThat(((Element) links.item(i)).getAttribute("bindingType")).as(file + " " + tag).isEqualTo("deployment");
        }
      }
    }
  }

  @Test
  void agentTimeoutsFitAChat() throws Exception {
    for (String file : FILES) {
      Document doc = dom(file);
      Element agent = byId(doc, "Agent");
      Map<String, String> inputs = agentInputs(doc);
      assertThat(inputs.entrySet())
          .as(file)
          .filteredOn(e -> e.getKey().endsWith("timeouts.timeout"))
          .singleElement()
          .satisfies(e -> assertThat(e.getValue()).isEqualTo("PT30S"));
      NodeList headers = agent.getElementsByTagNameNS(ZEEBE, "header");
      String backoff = null;
      for (int i = 0; i < headers.getLength(); i++) {
        Element h = (Element) headers.item(i);
        if ("retryBackoff".equals(h.getAttribute("key")) && h.getParentNode().getParentNode().getParentNode() == agent) {
          backoff = h.getAttribute("value");
        }
      }
      assertThat(backoff).as(file).isEqualTo("PT5S");
      Element task = (Element) agent.getElementsByTagNameNS(ZEEBE, "taskDefinition").item(0);
      assertThat(task.getAttribute("retries")).as(file).isEqualTo("3");
    }
  }

  @Test
  void everyFolderHoldsOneProcessAndTheDecisionAndFormItLinks() throws Exception {
    // Hub's "Deploy all resources" deploys a whole folder; two BPMN files with the same process id fail.
    for (String file : FILES) {
      java.io.File dir = new java.io.File(BpmnModelsTest.class.getClassLoader().getResource(folder(file)).toURI());
      List<String> names = java.util.Arrays.stream(dir.list()).sorted().toList();
      Document doc = dom(file);
      String decisionId = ((Element) doc.getElementsByTagNameNS(ZEEBE, "calledDecision").item(0)).getAttribute("decisionId");
      String formId = ((Element) doc.getElementsByTagNameNS(ZEEBE, "formDefinition").item(0)).getAttribute("formId");
      assertThat(names).as(folder(file)).containsExactlyInAnyOrder(file, decisionId + ".dmn", formId + ".form");
    }
  }

  @Test
  void gatewayVariantUsesOnlyPrefixedNames() throws Exception {
    String xml = new String(open("chatbot-8.10-gateway.bpmn").readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    assertThat(xml)
        .contains("id=\"BankingChatbot_Conversation\"", "name=\"banking-chatbot.customer-message\"",
            "decisionId=\"BankingChatbot_BranchFees\"", "formId=\"BankingChatbot_AdvisorReply\"")
        .doesNotContain("type=\"chatbot.", "chatbot-conversation", "\"advisor-reply\"", "\"branch-hours-and-fees\"");
    String dmn = new String(open("BankingChatbot_BranchFees.dmn").readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    String form = new String(open("BankingChatbot_AdvisorReply.form").readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    assertThat(dmn).contains("<decision id=\"BankingChatbot_BranchFees\"");
    assertThat(form).contains("\"id\": \"BankingChatbot_AdvisorReply\"");
  }

  @Test
  void everyToolDescribesItselfAndReportsProgress() throws Exception {
    Document doc = dom(FILES.get(0));
    for (String tool : TOOLS) {
      Element element = byId(doc, tool);
      assertThat(element.getAttribute("name")).as(tool).isNotBlank();
      assertThat(element.getElementsByTagNameNS(BPMN, "documentation").getLength()).as(tool).isPositive();
      assertThat(children(element)).as(tool + " must have no incoming flow")
          .noneMatch(c -> "incoming".equals(c.getLocalName()));
      if (!tool.equals("CloseConversation")) {
        assertThat(element.getElementsByTagNameNS(ZEEBE, "executionListener").getLength()).as(tool).isPositive();
      }
    }
  }
}
