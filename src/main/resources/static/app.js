// Chat page. Talks to the Spring app only: POST to start or continue a conversation, and one
// server-sent event stream per conversation for replies, progress steps and the end of the chat.
(() => {
  const $ = (id) => document.getElementById(id);
  const log = $("log"), form = $("composer"), input = $("message"), send = $("send");
  const customerSelect = $("customer"), errorBox = $("error");

  let conversationId = null;
  let stream = null;
  let rendered = 0;   // events shown for this conversation; the server replays history on reconnect
  let replayed = 0;
  let busy = false;
  let liveStep = null;

  const WHO = { answer: "Assistant", question: "Assistant asks", fallback: "Assistant", advisor: "Advisor", closed: "Assistant" };

  function add(className, text, who) {
    const li = document.createElement("li");
    li.className = className;
    if (who !== undefined) {
      const bubble = document.createElement("div");
      bubble.className = "bubble";
      const label = document.createElement("span");
      label.className = "who";
      label.textContent = who;
      bubble.append(label, document.createTextNode(text));
      li.append(bubble);
    } else {
      li.textContent = text;
    }
    log.append(li);
    log.scrollTop = log.scrollHeight;
    return li;
  }

  function setBusy(value) {
    busy = value;
    send.disabled = value;
    if (!value && liveStep) { liveStep.classList.remove("live"); liveStep = null; }
  }

  function showError(message) {
    errorBox.textContent = message;
    errorBox.hidden = !message;
  }

  function handle(event) {
    replayed += 1;
    if (replayed <= rendered) return; // already on screen before a reconnect
    rendered += 1;
    const data = JSON.parse(event.data);
    if (event.type === "progress") {
      if (liveStep) liveStep.classList.remove("live");
      liveStep = add("step live", data.text + "…");
    } else if (event.type === "reply") {
      setBusy(false);
      add("assistant " + (data.kind || "answer"), data.text, WHO[data.kind] || "Assistant");
      input.focus();
    } else if (event.type === "closed") {
      setBusy(false);
      add("notice", "Conversation ended. Your next message starts a new one.");
      endConversation();
    }
  }

  function openStream(id) {
    rendered = 0;
    stream = new EventSource(`/api/conversations/${encodeURIComponent(id)}/events`);
    stream.onopen = () => { replayed = 0; };
    ["reply", "progress", "closed"].forEach((type) => stream.addEventListener(type, handle));
  }

  function endConversation() {
    if (stream) stream.close();
    stream = null;
    conversationId = null;
  }

  async function post(url, body) {
    const response = await fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: body ? JSON.stringify(body) : undefined,
    });
    if (!response.ok) {
      let message = `Request failed (${response.status}).`;
      try { message = (await response.json()).error || message; } catch (e) { /* not JSON */ }
      throw new Error(message);
    }
    return response.status === 202 || response.status === 204 ? null : response.json();
  }

  async function sendMessage(text) {
    showError("");
    add("customer", text, "You");
    setBusy(true);
    try {
      if (!conversationId) {
        const started = await post("/api/conversations", { customerId: customerSelect.value, message: text });
        conversationId = started.conversationId;
        $("conv-id").textContent = conversationId;
        const operate = $("operate");
        operate.href = started.operateUrl;
        operate.hidden = false;
        openStream(conversationId);
      } else {
        await post(`/api/conversations/${encodeURIComponent(conversationId)}/messages`, { message: text });
      }
    } catch (e) {
      setBusy(false);
      showError(e.message);
    }
  }

  function newConversation() {
    endConversation();
    log.replaceChildren();
    $("conv-id").textContent = "Not started";
    $("operate").hidden = true;
    setBusy(false);
    showError("");
  }

  form.addEventListener("submit", (e) => {
    e.preventDefault();
    const text = input.value.trim();
    if (!text || busy) return;
    input.value = "";
    sendMessage(text);
  });

  input.addEventListener("keydown", (e) => {
    if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); form.requestSubmit(); }
  });

  $("examples").addEventListener("click", (e) => {
    if (e.target.tagName !== "BUTTON" || busy) return;
    sendMessage(e.target.textContent);
  });

  // Advisor desk: open advisor tasks, answered here or in Tasklist.
  const desk = $("desk");
  const drafts = {};
  async function refreshDesk() {
    if (desk.contains(document.activeElement)) return; // do not redraw while someone types
    let tasks = [];
    try { tasks = await (await fetch("/api/advisor/tasks")).json(); } catch (e) { return; }
    desk.replaceChildren();
    if (!tasks.length) {
      const p = document.createElement("p");
      p.className = "small";
      p.textContent = "No open tasks.";
      desk.append(p);
      return;
    }
    tasks.forEach((t) => {
      const box = document.createElement("form");
      box.className = "task";
      const lines = [["Task", t.name], ["Conversation", t.conversationId], ["Summary", t.summary], ["Question", t.question]];
      lines.forEach(([k, v]) => {
        if (!v) return;
        const p = document.createElement("p");
        const b = document.createElement("strong");
        b.textContent = k + ": ";
        p.append(b, document.createTextNode(v));
        box.append(p);
      });
      const label = document.createElement("label");
      label.className = "sr-only";
      label.htmlFor = "answer-" + t.key;
      label.textContent = "Answer for the customer";
      const area = document.createElement("textarea");
      area.id = "answer-" + t.key;
      area.rows = 2;
      area.required = true;
      area.placeholder = "Answer for the customer";
      area.value = drafts[t.key] || "";
      area.addEventListener("input", () => { drafts[t.key] = area.value; });
      const button = document.createElement("button");
      button.type = "submit";
      button.className = "secondary";
      button.textContent = "Send answer";
      box.append(label, area, button);
      box.addEventListener("submit", async (e) => {
        e.preventDefault();
        button.disabled = true;
        try {
          await post(`/api/advisor/tasks/${t.key}/complete`, { answer: area.value });
          delete drafts[t.key];
          box.remove();
        } catch (err) { showError(err.message); button.disabled = false; }
      });
      desk.append(box);
    });
  }
  setInterval(refreshDesk, 3000);

  customerSelect.addEventListener("change", newConversation);

  $("reset").addEventListener("click", async () => {
    try { await post("/api/demo/reset"); } catch (e) { showError(e.message); return; }
    newConversation();
    input.focus();
  });

  async function init() {
    try {
      const customers = await (await fetch("/api/customers")).json();
      customers.forEach((c) => customerSelect.append(new Option(`${c.name} (${c.id})`, c.id)));
    } catch (e) {
      showError("Could not load the demo customers.");
    }
    try {
      const health = await (await fetch("/api/health")).json();
      const c = health.camunda;
      $("model").textContent = c.processFolder.replace("bpmn/", "");
      const status = $("status");
      status.textContent = `Camunda ${c.target} · ${c.llmProvider} · ${c.connection}`;
      status.classList.add(c.connection === "up" ? "up" : "down");
      // The process reads its model settings from a cluster variable; say so before the first chat fails.
      if (c.llmClusterVariable && !c.llmClusterVariable.endsWith(": ok")) {
        showError(`Cluster variable ${c.llmClusterVariable}. See "The CHATBOT_LLM cluster variable" in the README.`);
      }
      if (c.simulatedAgent) {
        $("sim-badge").hidden = false;
        $("guardrail-example").hidden = false;
      }
    } catch (e) {
      $("status").textContent = "Health check failed";
    }
  }

  init();
})();
