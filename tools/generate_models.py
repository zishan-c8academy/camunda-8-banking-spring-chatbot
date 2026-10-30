"""Generate the chatbot process models from one source.

Writes one folder per variant to src/main/resources/bpmn/. Each folder holds everything one deployment needs
(the BPMN, the hours and fees DMN, the advisor form), so you can upload a folder to Camunda Hub and deploy it
with "All resources":
  8.10-bedrock/   AI Agent Sub-process v2 template (Camunda 8.10), AWS Bedrock
  8.10-openai/    AI Agent Sub-process v2 template (Camunda 8.10), OpenAI-compatible endpoint
  8.9-bedrock/    AI Agent Sub-process template v8 (Camunda 8.9), AWS Bedrock
  8.9-openai/     AI Agent Sub-process template v8 (Camunda 8.9), OpenAI-compatible endpoint
  8.10-gateway/   8.10, OpenAI-compatible gateway configured as a cluster variable, with ids, job types and
                  message names prefixed for a shared cluster (see GATEWAY)

The four generic variants share the process id chatbot-conversation, so deploy one folder per cluster.
The tools, the flow and the prompts are identical in all five BPMN files. Only the agent element
(template id, version, job type, provider inputs) changes.

The models read the LLM settings (model, region, endpoint) from the cluster variable LLM_VARIABLE and the
credentials from secrets, so the Spring app passes no LLM settings.

Edit this file, not the XML. Run from the repository root:  python3 tools/generate_models.py
"""
import json
import os
import sys
from xml.sax.saxutils import escape

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from bpmnkit import Model, dmn, x  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "src", "main", "resources", "bpmn")
ICON = open(os.path.join(ROOT, "tools", "agent-icon.txt")).read().strip()

PROCESS_ID = "chatbot-conversation"
MESSAGE_NAME = "chatbot.customer-message"
FORM_ID = "advisor-reply"
DECISION_ID = "branch-hours-and-fees"

# Official element templates, read from github.com/camunda/connectors (stable/8.10 and stable/8.9).
# retry_backoff is set for a chat (a person is waiting): 5 seconds instead of the template defaults.
TARGETS = {
    "8.10": dict(
        template_id="io.camunda.connectors.agenticai.ai-agent-subprocess.v2", template_version="1",
        job_type="io.camunda.agenticai:aiagent:subprocess:2", retry_backoff="PT5S", platform="8.10.0",
        output_element="={\n  id: toolCall._meta.id,\n  name: toolCall._meta.name,\n  content: toolCallResult,\n  completedAt: now()\n}",
        agent_definition=True),
    "8.9": dict(
        template_id="io.camunda.connectors.agenticai.aiagent.jobworker.v1", template_version="8",
        job_type="io.camunda.agenticai:aiagent-job-worker:1", retry_backoff="PT5S", platform="8.9.0",
        output_element="={\n  id: toolCall._meta.id,\n  name: toolCall._meta.name,\n  content: toolCallResult\n}",
        agent_definition=False),
}
PROVIDERS = ("bedrock", "openai")

# Cluster variable (Admin > Cluster variables, since 8.9) with the LLM settings of the generic variants:
# {"model": "...", "region": "..."} for Bedrock, {"model": "...", "endpoint": "..."} for OpenAI-compatible.
LLM_VARIABLE = "CHATBOT_LLM"

# Gateway variant: for a cluster that already has an OpenAI-compatible LLM gateway configured as the cluster
# variable GATEWAY_VARIABLE (endpoint, model) and the connector secret GATEWAY_SECRET. The cluster is shared, so
# every id, job type and message name gets a prefix. Only for Camunda 8.10.
GATEWAY_VARIABLE = "GENAI_GATEWAY"
GATEWAY_SECRET = "GENAI_GATEWAY_KEY"
GATEWAY = dict(
    process_id="BankingChatbot_Conversation",
    process_name="Spring Boot AI chatbot",
    job_type_prefix="banking-chatbot.",
    message_name="banking-chatbot.customer-message",
    decision_id="BankingChatbot_BranchFees",
    form_id="BankingChatbot_AdvisorReply",
)


def apply_naming(xml, naming):
    """Renames the generic ids and names in a generated BPMN to the given naming."""
    return (xml
            .replace('name="' + MESSAGE_NAME + '"', 'name="' + naming["message_name"] + '"')
            .replace('type="chatbot.', 'type="' + naming["job_type_prefix"])
            .replace('decisionId="' + DECISION_ID + '"', 'decisionId="' + naming["decision_id"] + '"')
            .replace('formId="' + FORM_ID + '"', 'formId="' + naming["form_id"] + '"')
            .replace('name="Chatbot conversation"', 'name="' + naming["process_name"] + '"')
            .replace(PROCESS_ID, naming["process_id"]))

ZEEBE_NS = 'xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" xmlns:modeler="http://camunda.org/schema/modeler/1.0"'


def inp(source, target):
    if source is None:
        return f'<zeebe:input target="{target}" />'
    return f'<zeebe:input source="{x(source)}" target="{target}" />'


def out(source, target):
    return f'<zeebe:output source="{x(source)}" target="{target}" />'


def io(*parts):
    return "<zeebe:ioMapping>" + "".join(parts) + "</zeebe:ioMapping>"


def ext(*parts):
    return "<bpmn:extensionElements>" + "".join(p for p in parts if p) + "</bpmn:extensionElements>"


def doc(text):
    return f"<bpmn:documentation>{escape(text)}</bpmn:documentation>"


def feel_string(text):
    """A FEEL string literal. The text may hold newlines but no double quotes or backslashes."""
    assert '"' not in text and "\\" not in text, "FEEL text must not contain double quotes or backslashes"
    return '="' + text + '"'


def task(job_type, retries=3):
    return f'<zeebe:taskDefinition type="{job_type}" retries="{retries}" />'


# Every tool announces itself to the browser ("Checking your accounts...") through a start execution
# listener. The Spring worker for chatbot.progress maps the element id to the text.
PROGRESS = ('<zeebe:executionListeners><zeebe:executionListener eventType="start" type="chatbot.progress" '
            'retries="3" /></zeebe:executionListeners>')


def reply(text_expr, kind):
    """The 'send a message to the browser' service task (job type chatbot.reply)."""
    return ext(task("chatbot.reply"), io(inp(text_expr, "replyText"), inp(kind, "replyKind")))


# --------------------------------------------------------------------------- prompts
# FEEL strings: no double quotes inside the text.
SYSTEM_PROMPT = """You are the digital banking assistant of Banque Exemple Boréale, a fictional Canadian retail bank used for a demo. You chat with one signed-in customer about their own accounts and cards, branch hours and fees, and service requests. Investment, tax and legal advice, credit decisions, and anything unrelated to this customer's banking are out of scope.

GOAL AND SUCCESS CRITERIA
Resolve the customer's request in as few turns as possible, in plain conversational language. A reply is complete only when every balance, fee, card status or reference number in it comes from a tool result in this conversation, never from memory or assumption. A change to the customer's products (locking a card, opening a service request) is complete only when the customer said yes to that exact action in this conversation and the tool result confirms it.

TOOLS (you decide which to call, in what order, and how often)
- GetAccountSummary: the signed-in customer's accounts, balances and cards.
- LookUpBranchHoursAndFees: the bank's published branch hours and fees, by topic.
- LockCard: locks one card. Changes the customer's product.
- CreateServiceRequest: opens a service request and returns its reference number. Changes something.
- AskCustomer: asks the customer one question in the chat and waits for the answer.
- HandOffToAdvisor: sends the question to a human advisor and waits for the advisor's answer.
- CloseConversation: marks the conversation as finished.

APPROACH
Work toward the goal, not a fixed script. If you need account or card data, get it with a tool rather than asking the customer for it. Before any action that changes something, name the exact action (for example: lock the Visa card ending 4417) and get an explicit yes, with AskCustomer if you are in the middle of a request. If the customer asks for a person, reports fraud, is upset, or asks something your tools cannot answer, hand off to an advisor and pass on the advisor's answer. When the customer says goodbye or needs nothing else, call CloseConversation and then say a short goodbye.

RESTRICTIONS AND FAILURE BEHAVIOUR
Never invent balances, transactions, fees, card states or reference numbers. You only see the signed-in customer; never discuss anyone else's data. If a tool fails or returns nothing useful, say so plainly and offer a service request or an advisor. Do not give investment, tax or legal advice.

OUTPUT
Plain conversational text for a chat window, in the customer's language (English or French). At most four short sentences, or a short list. No JSON, no markdown headings, no code blocks, and no tool names or internal ids. Write like a careful bank employee: no emoji, no exclamation marks, and no dashes between clauses; use a comma or a new sentence."""

USER_PROMPT = "=if is defined(followUp) and followUp != null then followUp else firstMessage"

OFF_RAIL_CODES = ["MAXIMUM_NUMBER_OF_MODEL_CALLS_REACHED", "FAILED_TO_PARSE_RESPONSE_CONTENT",
                  "MODEL_RESPONSE_CONTENT_FILTERED", "MODEL_RESPONSE_GUARDRAIL_INTERVENED",
                  "MODEL_CONTEXT_WINDOW_EXCEEDED"]
ERROR_EXPRESSION = ('=if error != null and list contains([' + ", ".join('"' + c + '"' for c in OFF_RAIL_CODES) + '], error.code) '
                    'then bpmnError("AGENT_OFF_RAIL", error.message, {agentErrorCode: error.code}) else null')

# The total model call count lives in the agent context and grows across turns, so each turn gets
# a budget of 8 more calls on top of what the conversation has used so far.
MAX_MODEL_CALLS = "=get or else(chatAgent.context.metrics.modelCalls, 0) + 8"

FALLBACK_TEXT = ("I'm sorry, I can't finish this on my own. I've asked an advisor to pick up our conversation; "
                 "their answer will appear here.")
TIMEOUT_TEXT = "This chat closed after 15 minutes without a message. Start a new chat any time."

CONVERSATION_CLOSED = ('=some m in get or else(chatAgent.context.conversation.messages, []) satisfies '
                       '(some t in get or else(m.toolCalls, []) satisfies t.name = "CloseConversation")')


def sec(name, target):
    """Secret reference. 8.10 resolves camunda.secrets.NAME in the cluster before the job runs (the value never
    reaches job variables or logs); 8.9 only has the connector runtime's legacy {{secrets.NAME}} form."""
    return "=camunda.secrets." + name if target == "8.10" else "{{secrets." + name + "}}"


def provider_inputs(target, provider):
    """Provider fields. Endpoint, region and model come from a cluster variable, credentials from secrets, so
    the process carries its own LLM settings and the Spring app never sees them."""
    if provider == "gateway":
        # Same wiring as the agents that already run on the shared cluster.
        v = "camunda.vars.env." + GATEWAY_VARIABLE
        return [
            inp("openai", "provider.type"),
            inp("completions", "provider.openai.api.type"),
            inp("custom", "provider.openai.backend.type"),
            inp("=" + v + ".endpoint", "provider.openai.backend.custom.endpoint"),
            inp("apiKey", "provider.openai.backend.custom.authentication.type"),
            inp(sec(GATEWAY_SECRET, target), "provider.openai.backend.custom.authentication.apiKey"),
            inp("=" + v + ".model", "provider.openai.model.model"),
            inp("modelDefault", "provider.openai.api.completions.effort"),
            inp("=2048", "provider.openai.api.completions.maxCompletionTokens"),
            inp("PT30S", "provider.openai.timeouts.timeout"),
        ]
    llm = "=camunda.vars.env." + LLM_VARIABLE
    if provider == "bedrock":
        return [
            inp("bedrock", "provider.type"),
            inp(llm + ".region", "provider.bedrock.region"),
            inp("credentials", "provider.bedrock.authentication.type"),
            inp(sec("CHATBOT_AWS_ACCESS_KEY", target), "provider.bedrock.authentication.accessKey"),
            inp(sec("CHATBOT_AWS_SECRET_KEY", target), "provider.bedrock.authentication.secretKey"),
            inp("PT30S", "provider.bedrock.timeouts.timeout"),
            inp(llm + ".model", "provider.bedrock.model.model"),
            inp("=2048", "provider.bedrock.model.parameters.maxTokens"),
        ]
    if target == "8.10":
        return [
            inp("openai", "provider.type"),
            inp("completions", "provider.openai.api.type"),
            inp("custom", "provider.openai.backend.type"),
            inp(llm + ".endpoint", "provider.openai.backend.custom.endpoint"),
            inp("apiKey", "provider.openai.backend.custom.authentication.type"),
            inp(sec("CHATBOT_LLM_API_KEY", target), "provider.openai.backend.custom.authentication.apiKey"),
            inp("PT30S", "provider.openai.timeouts.timeout"),
            inp(llm + ".model", "provider.openai.model.model"),
            inp("modelDefault", "provider.openai.api.completions.effort"),
            inp("=2048", "provider.openai.api.completions.maxCompletionTokens"),
        ]
    return [
        inp("openaiCompatible", "provider.type"),
        inp(llm + ".endpoint", "provider.openaiCompatible.endpoint"),
        inp(sec("CHATBOT_LLM_API_KEY", target), "provider.openaiCompatible.authentication.apiKey"),
        inp("PT30S", "provider.openaiCompatible.timeouts.timeout"),
        inp(llm + ".model", "provider.openaiCompatible.model.model"),
        inp("=2048", "provider.openaiCompatible.model.parameters.maxCompletionTokens"),
    ]


def agent_body(target, provider):
    t = TARGETS[target]
    inputs = provider_inputs(target, provider) + [
        inp(feel_string(SYSTEM_PROMPT), "data.systemPrompt.prompt"),
        inp(USER_PROMPT, "data.userPrompt.prompt"),
        inp("=chatAgent.context", "agentContext"),
        inp("in-process", "data.memory.storage.type"),
        inp("=30", "data.memory.contextWindowSize"),
        inp(MAX_MODEL_CALLS, "data.limits.maxModelCalls"),
        inp("WAIT_FOR_TOOL_CALL_RESULTS", "data.events.behavior"),
        inp("text", "data.response.format.type"),
        inp("=false", "data.response.includeAssistantMessage"),
        inp("=true", "data.response.includeAgentContext"),
        inp(None, "agent"),
    ]
    headers = ('<zeebe:taskHeaders>'
               f'<zeebe:header key="elementTemplateVersion" value="{t["template_version"]}" />'
               f'<zeebe:header key="elementTemplateId" value="{t["template_id"]}" />'
               f'<zeebe:header key="retryBackoff" value="{t["retry_backoff"]}" />'
               f'<zeebe:header key="errorExpression" value="{x(ERROR_EXPRESSION)}" />'
               '</zeebe:taskHeaders>')
    parts = [
        f'<zeebe:adHoc outputCollection="toolCallResults" outputElement="{x(t["output_element"])}" />',
        task(t["job_type"]),
        io(*inputs, out("=agent", "chatAgent")),
        headers,
    ]
    if t["agent_definition"]:
        parts.append('<zeebe:properties><zeebe:property name="io.camunda.agenticai.toolContainer" value="true" /></zeebe:properties>')
        parts.append('<zeebe:agentDefinition agentType="aiAgentSubProcess" />')
    return doc("Answers the customer and decides which tools to use. It can read accounts, look up fees, lock a card "
               "or open a service request after the customer confirms, ask the customer a question, and hand off to an "
               "advisor.") + ext(*parts)


# --------------------------------------------------------------------------- the process
def build(target, provider):
    t = TARGETS[target]
    m = Model(PROCESS_ID, "Chatbot conversation")
    # Every way the conversation ends tells the browser through one process-level end listener.
    m.process_ext = ('<bpmn:extensionElements><zeebe:executionListeners>'
                     '<zeebe:executionListener eventType="end" type="chatbot.closed" retries="3" />'
                     '</zeebe:executionListeners></bpmn:extensionElements>')
    Y = 330
    m.node("Start", "startEvent", "Customer starts a chat", 100, Y - 18)
    m.node("MergeTurn", "exclusiveGateway", "", 180, Y - 25)

    AX, AY, AW, AH = 280, 80, 720, 500
    m.node("Agent", "adHocSubProcess", "Banking assistant", AX, AY, AW, AH, expanded=True,
           attrs=(f' zeebe:modelerTemplate="{t["template_id"]}" zeebe:modelerTemplateVersion="{t["template_version"]}"'
                  f' zeebe:modelerTemplateIcon="{ICON}"'),
           body=agent_body(target, provider))

    # ---- tools: activities with no incoming flow inside the ad-hoc sub-process
    m.node("GetAccountSummary", "serviceTask", "Get the customer's accounts and cards", AX + 40, AY + 50, parent="Agent", body=doc(
        "Returns the signed-in customer's accounts (type, last four digits, balance in CAD) and cards (network, last four "
        "digits, status). Use it whenever the customer asks about a balance, an account or a card, and before locking a "
        "card so you can name the right one. It only sees the signed-in customer. No input needed.") + ext(
        task("chatbot.accounts"), PROGRESS, io(inp("=customerId", "customerId"), out("=accountSummary", "toolCallResult"))))

    m.node("LookUpBranchHoursAndFees", "businessRuleTask", "Look up branch hours and fees", AX + 200, AY + 50, parent="Agent", body=doc(
        "Returns the bank's published answer for one topic from the hours and fees table. Use it for any question about "
        "opening hours or what something costs. Quote the answer and do not add fees that are not in it. If the topic is "
        "not covered, the answer says so.") + ext(
        f'<zeebe:calledDecision decisionId="{DECISION_ID}" bindingType="deployment" resultVariable="faqAnswer" />', PROGRESS,
        io(inp('=fromAi(toolCall.topic, "The topic to look up", "string", {enum: ["branch-hours", "monthly-account-fees", '
               '"interac-e-transfer", "atm-fees", "card-replacement", "international-wire", "other"]})', "topic"),
           out("={topic: topic, answer: faqAnswer}", "toolCallResult"))))

    m.node("CreateServiceRequest", "serviceTask", "Open a service request", AX + 360, AY + 50, parent="Agent", body=doc(
        "Opens a service request with the back office and returns its reference number. Use it when the customer wants "
        "something the bank does later: replace a card, dispute a transaction, change an address, order cheques. Confirm "
        "the request with the customer before you open it, and give the customer the reference number from the result.") + ext(
        task("chatbot.service-request"), PROGRESS,
        io(inp("=customerId", "customerId"),
           inp('=fromAi(toolCall.category, "Kind of request", "string", {enum: ["card-replacement", "transaction-dispute", '
               '"address-change", "cheque-order", "other"]})', "category"),
           inp('=fromAi(toolCall.details, "One or two sentences on what the customer wants, in their words", "string")', "details"),
           out("=serviceRequest", "toolCallResult"))))

    m.node("CloseConversation", "scriptTask", "Close the conversation", AX + 520, AY + 50, parent="Agent", body=doc(
        "Marks the conversation as finished. Use it only when the customer says goodbye or confirms they need nothing "
        "else. After calling it, reply with a short goodbye.") + ext(
        '<zeebe:script expression="={closed: true, note: &quot;Say a short goodbye.&quot;}" resultVariable="toolCallResult" />'))

    m.node("LockCard", "serviceTask", "Lock a card", AX + 40, AY + 190, parent="Agent", body=doc(
        "Locks one of the customer's cards so new purchases and withdrawals are refused. Use it only after the customer "
        "said yes to locking that specific card in this conversation. Pass the last four digits from the account summary. "
        "Returns the new card status.") + ext(
        task("chatbot.lock-card"), PROGRESS,
        io(inp("=customerId", "customerId"),
           inp('=fromAi(toolCall.cardLast4, "Last four digits of the card to lock, from the account summary", "string")', "cardLast4"),
           inp('=fromAi(toolCall.customerConfirmed, "true only if the customer explicitly said yes to locking this card", "boolean")',
               "customerConfirmed"),
           out("=cardLock", "toolCallResult"))))
    m.node("CardNotFound", "boundaryEvent", "Card not found", AX + 77, AY + 252, parent="Agent", label=(AX + 45, AY + 314),
           attrs=' attachedToRef="LockCard"', body='<bpmn:errorEventDefinition id="ErrDef_CardNotFound" errorRef="Error_CardNotFound" />')
    m.node("ExplainCardNotFound", "scriptTask", "Explain that the card was not found", AX + 200, AY + 190, parent="Agent", body=ext(
        '<zeebe:script expression="={locked: false, reason: &quot;No card with those last four digits belongs to this '
        'customer. Check the digits with the customer.&quot;}" resultVariable="toolCallResult" />'))

    m.node("HandOffToAdvisor", "userTask", "Hand off to an advisor", AX + 360, AY + 190, parent="Agent", body=doc(
        "Sends the conversation to a human advisor in Tasklist and waits for the advisor's answer, then returns it. Use it "
        "when the customer asks for a person, reports fraud, is upset, or asks something the other tools cannot answer. "
        "Pass a short summary and the customer's question. Relay the advisor's answer without changing any fact in it.") + ext(
        "<zeebe:userTask />", f'<zeebe:formDefinition formId="{FORM_ID}" bindingType="deployment" />',
        '<zeebe:assignmentDefinition candidateGroups="advisors" />', PROGRESS,
        io(inp('=fromAi(toolCall.summary, "Two or three sentences on the conversation so far, for the advisor", "string")', "summaryShown"),
           inp('=fromAi(toolCall.question, "The question the advisor should answer", "string")', "questionShown"),
           out('={advisorAnswer: advisorAnswer, answeredBy: "advisor"}', "toolCallResult"))))

    # Ask the customer: a small flow inside the tool. Send the question, wait for the next chat message.
    SX, SY = AX + 40, AY + 340
    m.node("AskCustomer", "subProcess", "Ask the customer and wait for the answer", SX, SY, 460, 130, parent="Agent", expanded=True, body=doc(
        "Sends one question to the customer in the chat, waits for the reply, and returns it. Use it to confirm an action "
        "that changes something (locking a card, opening a service request) or to get a detail you need to finish the "
        "current request. Ask one clear question.") + ext(
        PROGRESS,
        io(inp('=fromAi(toolCall.question, "One clear question for the customer", "string")', "question"),
           inp("=null", "customerAnswer"),
           out("={question: question, answer: customerAnswer}", "toolCallResult"))))
    m.node("AskStart", "startEvent", "Question needed", SX + 30, SY + 47, parent="AskCustomer")
    m.node("SendQuestion", "serviceTask", "Send the question", SX + 100, SY + 25, parent="AskCustomer",
           body=reply("=question", "question"))
    m.node("WaitForAnswer", "intermediateCatchEvent", "Customer answers", SX + 270, SY + 47, parent="AskCustomer",
           body=ext(io(out("=message", "customerAnswer"))) +
           '<bpmn:messageEventDefinition id="MsgDef_Answer" messageRef="Msg_CustomerMessage" />')
    m.node("AskEnd", "endEvent", "Answer received", SX + 380, SY + 47, parent="AskCustomer")
    m.flow("f_ask1", "AskStart", "SendQuestion")
    m.flow("f_ask2", "SendQuestion", "WaitForAnswer")
    m.flow("f_ask3", "WaitForAnswer", "AskEnd")
    m.flow("f_nf", "CardNotFound", "ExplainCardNotFound", out="b", inn="b", via=[(AX + 95, AY + 310), (AX + 255, AY + 310)])

    # ---- off the rails
    m.node("OffRail", "boundaryEvent", "Assistant off the rails", AX + 300, AY + AH - 18, attrs=' attachedToRef="Agent"', label=(AX + 330, AY + AH + 20),
           body=ext(io(out("=agentErrorCode", "agentErrorCode"))) +
           '<bpmn:errorEventDefinition id="ErrDef_OffRail" errorRef="Error_AgentOffRail" />')
    m.node("SendFallback", "serviceTask", "Send a polite fallback reply", AX + 380, 640, body=reply(feel_string(FALLBACK_TEXT), "fallback"))
    m.node("AdvisorTakeover", "userTask", "Answer the customer as an advisor", AX + 540, 640, body=doc(
        "The assistant stopped. An advisor reads the conversation details and writes the answer that goes to the chat.") + ext(
        "<zeebe:userTask />", f'<zeebe:formDefinition formId="{FORM_ID}" bindingType="deployment" />',
        '<zeebe:assignmentDefinition candidateGroups="advisors" />',
        io(inp('="The assistant stopped (" + get or else(agentErrorCode, "unknown reason") + ") for customer " + customerId + "."', "summaryShown"),
           inp(USER_PROMPT, "questionShown"),
           out("=advisorAnswer", "advisorAnswer"))))
    m.node("SendAdvisorReply", "serviceTask", "Send the advisor's reply", AX + 700, 640, body=reply("=advisorAnswer", "advisor"))
    m.node("EndAdvisor", "endEvent", "Handed to an advisor", AX + 862, 662)

    # ---- after each turn
    m.node("SendReply", "serviceTask", "Send reply", 1060, Y - 40, body=doc(
        "Pushes the assistant's answer to the customer's browser.") +
        reply('=get or else(chatAgent.responseText, "Sorry, I could not prepare an answer. Please try again.")', "answer"))
    m.node("GwFinished", "exclusiveGateway", "Conversation finished?", 1220, Y - 25, attrs=' default="f_more"')
    m.node("EndClosed", "endEvent", "Conversation closed", 1312, 182, label=(1352, 186))
    m.node("WaitCustomer", "eventBasedGateway", "", 1320, Y - 25)
    m.node("CustomerMessage", "intermediateCatchEvent", "Customer message", 1422, Y - 18,
           body=ext(io(out("=message", "followUp"))) +
           '<bpmn:messageEventDefinition id="MsgDef_FollowUp" messageRef="Msg_CustomerMessage" />')
    m.node("Silence", "intermediateCatchEvent", "15 minutes of silence", 1422, 432,
           body='<bpmn:timerEventDefinition id="TimerDef_Silence"><bpmn:timeDuration xsi:type="bpmn:tFormalExpression">PT15M'
                '</bpmn:timeDuration></bpmn:timerEventDefinition>')
    m.node("SendClosingNote", "serviceTask", "Tell the customer the chat closed", 1500, 410, body=reply(feel_string(TIMEOUT_TEXT), "closed"))
    m.node("EndTimeout", "endEvent", "Conversation closed", 1662, 432)

    m.flow("f_start", "Start", "MergeTurn")
    m.flow("f_turn", "MergeTurn", "Agent")
    m.flow("f_reply", "Agent", "SendReply")
    m.flow("f_check", "SendReply", "GwFinished")
    m.flow("f_done", "GwFinished", "EndClosed", "Yes", cond=CONVERSATION_CLOSED, out="t", inn="l", label=(1252, 250))
    m.flow("f_more", "GwFinished", "WaitCustomer", "No")
    m.flow("f_msg", "WaitCustomer", "CustomerMessage")
    m.flow("f_quiet", "WaitCustomer", "Silence", out="b", inn="l")
    m.flow("f_loop", "CustomerMessage", "MergeTurn", "Next turn", out="t", inn="t", via=[(1440, 40), (205, 40)], label=(1000, 22))
    m.flow("f_timeout", "Silence", "SendClosingNote")
    m.flow("f_timeout2", "SendClosingNote", "EndTimeout")
    m.flow("f_off", "OffRail", "SendFallback", out="b", inn="l", via=[(AX + 318, 680)])
    m.flow("f_off2", "SendFallback", "AdvisorTakeover")
    m.flow("f_off3", "AdvisorTakeover", "SendAdvisorReply")
    m.flow("f_off4", "SendAdvisorReply", "EndAdvisor")

    head = ('  <bpmn:message id="Msg_CustomerMessage" name="' + MESSAGE_NAME + '"><bpmn:extensionElements>'
            '<zeebe:subscription correlationKey="=conversationId" /></bpmn:extensionElements></bpmn:message>\n'
            '  <bpmn:error id="Error_AgentOffRail" name="Assistant off the rails" errorCode="AGENT_OFF_RAIL" />\n'
            '  <bpmn:error id="Error_CardNotFound" name="Card not found" errorCode="CARD_NOT_FOUND" />\n')
    defs = (f'exporter="tools/generate_models.py" exporterVersion="1" modeler:executionPlatform="Camunda Cloud" '
            f'modeler:executionPlatformVersion="{t["platform"]}"')
    return m.xml(ZEEBE_NS, defs, head)


# --------------------------------------------------------------------------- decision table and form
FAQ = [
    ("branch-hours", "Branches are open Monday to Wednesday and Friday 9:30 to 16:30, Thursday 9:30 to 19:00, and "
                     "Saturday 10:00 to 14:00 at selected branches. Closed Sundays and statutory holidays."),
    ("monthly-account-fees", "Everyday chequing: 4.95 dollars a month, waived with a 2,000 dollar minimum monthly balance. "
                             "Unlimited chequing: 15.95 dollars a month. Students and customers aged 60 or over pay no monthly fee."),
    ("interac-e-transfer", "Interac e-Transfer is free on Unlimited chequing. On Everyday chequing the first 5 a month are "
                           "free, then 1.00 dollar each."),
    ("atm-fees", "Free at Banque Exemple Boréale ATMs. 2.00 dollars at other ATMs in Canada and 5.00 dollars outside Canada, "
                 "plus any fee the other bank charges."),
    ("card-replacement", "The first replacement of a lost or stolen card in a year is free, then 10.00 dollars each. A new "
                         "card arrives in 5 to 7 business days."),
    ("international-wire", "Outgoing international wire: 30.00 dollars. Incoming international wire: 15.00 dollars."),
]
FAQ_DEFAULT = "No published answer for this topic. Offer to open a service request or to hand off to an advisor."


def build_dmn(decision_id=DECISION_ID):
    rules = [([f"\"{k}\""], [json.dumps(v, ensure_ascii=False)], f"Published answer for {k}") for k, v in FAQ]
    rules.append((["-"], [json.dumps(FAQ_DEFAULT, ensure_ascii=False)], "Anything else"))
    return dmn(decision_id, "Branch hours and fees", "FIRST", [("Topic", "topic", "string")],
               [("Answer", "answer", "string")], rules, "8.9.0")


def build_form(form_id=FORM_ID):
    comps = [
        {"type": "text", "text": "## A customer needs an advisor\nConversation {{conversationId}}, customer {{customerId}}. "
                                 "Your answer goes straight to the customer's chat window."},
        {"type": "textarea", "key": "summaryShown", "label": "Summary", "readonly": True},
        {"type": "textarea", "key": "questionShown", "label": "Customer's question", "readonly": True},
        {"type": "textarea", "key": "advisorAnswer", "label": "Your answer to the customer", "validate": {"required": True}},
    ]
    for i, c in enumerate(comps):
        c["id"] = f"Field_{i}"
    return json.dumps({"$schema": "https://unpkg.com/@camunda/schema@0.0.1/resources/form-schema.json",
                       "executionPlatform": "Camunda Cloud", "executionPlatformVersion": "8.9.0",
                       "exporter": {"name": "tools/generate_models.py", "version": "1"},
                       "schemaVersion": 18, "id": form_id, "type": "default", "components": comps},
                      indent=2, ensure_ascii=False) + "\n"


def write_variant(folder, bpmn_name, bpmn, decision_id, form_id):
    """One deployable folder: the BPMN with the DMN and form it links to."""
    path = os.path.join(OUT, folder)
    os.makedirs(path, exist_ok=True)
    for name in os.listdir(path):
        os.remove(os.path.join(path, name))
    files = {bpmn_name: bpmn, decision_id + ".dmn": build_dmn(decision_id), form_id + ".form": build_form(form_id)}
    for name, text in files.items():
        with open(os.path.join(path, name), "w") as f:
            f.write(text)
    return folder + "/"


def main():
    written = []
    for target in TARGETS:
        for provider in PROVIDERS:
            written.append(write_variant(f"{target}-{provider}", f"chatbot-{target}-{provider}.bpmn",
                                         build(target, provider), DECISION_ID, FORM_ID))
    written.append(write_variant("8.10-gateway", "chatbot-8.10-gateway.bpmn",
                                 apply_naming(build("8.10", "gateway"), GATEWAY),
                                 GATEWAY["decision_id"], GATEWAY["form_id"]))
    print("Wrote " + ", ".join(written) + " in " + os.path.relpath(OUT, ROOT))


if __name__ == "__main__":
    main()
