#!/usr/bin/env node
// Drives the five demo conversations against a running app over its REST API and SSE stream, prints
// every turn, and checks the outcomes against the demo data. Works with a real LLM or in simulate mode.
//
//   node tools/live_test.mjs                          # app on http://localhost:8090
//   node tools/live_test.mjs --base http://host:8090 --timeout 120
//   node tools/live_test.mjs --only atm               # one scenario: balance, card, atm, address, advisor
//
// It resets the demo data first (cards active, no service requests). Exit code 1 if any check fails.
// Node 18 or later, no dependencies.

const args = process.argv.slice(2);
const opt = (name, fallback) => {
  const i = args.indexOf("--" + name);
  return i >= 0 ? args[i + 1] : fallback;
};
const BASE = opt("base", "http://localhost:8090").replace(/\/$/, "");
const TIMEOUT = Number(opt("timeout", "90")) * 1000;
const ONLY = opt("only", null);

const results = [];
const check = (scenario, name, ok, detail = "") => {
  results.push({ scenario, name, ok, detail });
  console.log(`   ${ok ? "PASS" : "FAIL"}  ${name}${detail ? "  (" + detail + ")" : ""}`);
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function api(method, path, body) {
  const res = await fetch(BASE + path, {
    method,
    headers: { "Content-Type": "application/json" },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`${method} ${path} -> ${res.status} ${text}`);
  return text ? JSON.parse(text) : null;
}

// One conversation: start it, follow its SSE stream, send messages, wait for replies.
class Chat {
  constructor(customerId) {
    this.customerId = customerId;
    this.events = [];
    this.seen = 0;
    this.abort = new AbortController();
  }

  async start(message) {
    console.log(`\n   customer ${this.customerId}`);
    console.log(`   > ${message}`);
    const r = await api("POST", "/api/conversations", { customerId: this.customerId, message });
    this.id = r.conversationId;
    console.log(`     conversation ${r.conversationId}, process instance ${r.processInstanceKey}`);
    this.listen();
    return this;
  }

  async listen() {
    try {
      const res = await fetch(`${BASE}/api/conversations/${this.id}/events`, { signal: this.abort.signal });
      const decoder = new TextDecoder();
      let buffer = "";
      for await (const chunk of res.body) {
        buffer += decoder.decode(chunk, { stream: true });
        let cut;
        while ((cut = buffer.indexOf("\n\n")) >= 0) {
          const block = buffer.slice(0, cut);
          buffer = buffer.slice(cut + 2);
          const data = block.split("\n").filter((l) => l.startsWith("data:")).map((l) => l.slice(5)).join("\n");
          if (data) this.onEvent(JSON.parse(data));
        }
      }
    } catch (e) {
      if (e.name !== "AbortError") console.log("     stream error: " + e.message);
    }
  }

  onEvent(e) {
    this.events.push(e);
    if (e.type === "progress") console.log(`     ... ${e.text}`);
    else if (e.type === "reply") console.log(`   < [${e.kind}] ${e.text.replace(/\n/g, "\n     ")}`);
    else if (e.type === "closed") console.log("     (conversation ended)");
  }

  /** Waits for the next reply after the ones already consumed. */
  async nextReply() {
    const deadline = Date.now() + TIMEOUT;
    while (Date.now() < deadline) {
      const replies = this.events.filter((e) => e.type === "reply");
      if (replies.length > this.seen) return replies[this.seen++];
      await sleep(250);
    }
    return null;
  }

  async say(message) {
    console.log(`   > ${message}`);
    await api("POST", `/api/conversations/${this.id}/messages`, { message });
  }

  async waitClosed(ms = 20000) {
    const deadline = Date.now() + ms;
    while (Date.now() < deadline) {
      if (this.events.some((e) => e.type === "closed")) return true;
      await sleep(250);
    }
    return false;
  }

  stop() {
    this.abort.abort();
  }
}

const card = async (customerId, last4) =>
  (await api("GET", `/api/demo/customers/${customerId}`)).cards.find((c) => c.last4 === last4);
const asks = (reply) => reply && (reply.kind === "question" || reply.text.includes("?"));
const says = (reply, re) => !!reply && re.test(reply.text);

const scenarios = {
  async balance() {
    const chat = await new Chat("C-1001").start("What is my chequing balance?");
    const r = await chat.nextReply();
    check("balance", "answer contains the seeded balance 2,418.55", says(r, /2[ ,]?418[.,]55/), r ? "" : "no reply");
    await chat.say("Thanks, that's all.");
    await chat.nextReply();
    console.log(`     closed by the agent: ${await chat.waitClosed()}`);
    chat.stop();
  },

  async card() {
    const chat = await new Chat("C-1001").start("I lost my credit card.");
    let r = await chat.nextReply();
    check("card", "agent asks before acting", asks(r), r ? "" : "no reply");
    check("card", "card still active before the customer says yes", (await card("C-1001", "4417")).status === "ACTIVE");
    await chat.say("Yes, please lock it.");
    r = await chat.nextReply();
    // a model may ask one more confirmation (for example which card); answer it once
    if ((await card("C-1001", "4417")).status !== "LOCKED" && asks(r)) {
      await chat.say("Yes, the Visa credit card ending 4417. Please lock it now.");
      r = await chat.nextReply();
    }
    check("card", "Visa ending 4417 locked after yes", (await card("C-1001", "4417")).status === "LOCKED");
    chat.stop();
  },

  async atm() {
    const chat = await new Chat("C-1002").start("How much does it cost to use another bank's ATM in Canada?");
    const r = await chat.nextReply();
    check("atm", "answer quotes the DMN fee of 2.00 dollars", says(r, /(2\.00|\$\s?2\b|2 dollars)/), r ? "" : "no reply");
    chat.stop();
  },

  async address() {
    const chat = await new Chat("C-1003").start("I moved. Please update my address to 12 rue Exemple, Montréal, H2X 1Y4.");
    let r = await chat.nextReply();
    for (let i = 0; i < 2 && r && !/SR-\d{4}-\d+/.test(r.text) && asks(r); i++) {
      await chat.say("Yes, go ahead and open the request.");
      r = await chat.nextReply();
    }
    const ref = r && r.text.match(/SR-\d{4}-\d+/);
    check("address", "service request reference returned", !!ref, ref ? ref[0] : r ? "no reference in reply" : "no reply");
    chat.stop();
  },

  async advisor() {
    const chat = await new Chat("C-1004").start("Can I talk to a person about a mortgage?");
    const findTask = async (ms) => {
      const deadline = Date.now() + ms;
      while (Date.now() < deadline) {
        const tasks = await api("GET", "/api/advisor/tasks");
        const t = tasks.find((x) => x.conversationId === chat.id);
        if (t) return t;
        await sleep(1000);
      }
      return null;
    };
    let task = await findTask(TIMEOUT / 2);
    if (!task) {
      // the agent may have replied with a question first
      const r = await chat.nextReply();
      if (asks(r)) await chat.say("Yes, please connect me with an advisor.");
      task = await findTask(TIMEOUT / 2);
    }
    check("advisor", "handoff created an advisor task", !!task, task ? task.name : "no task for this conversation");
    if (task) {
      console.log(`     advisor task: ${task.question}`);
      await api("POST", `/api/advisor/tasks/${task.key}/complete`, { answer: "Our 5-year fixed rate today is 4.79 percent." });
      let r = await chat.nextReply();
      while (r && !r.text.includes("4.79")) r = await chat.nextReply();
      check("advisor", "advisor's answer reaches the chat", says(r, /4\.79/));
    }
    chat.stop();
  },
};

const health = await api("GET", "/api/health");
const c = health.camunda;
console.log(`App ${BASE}: Camunda ${c.target}, provider ${c.llmProvider}, process ${c.processId}, connection ${c.connection}` +
  (c.simulatedAgent ? ", SIMULATED AGENT" : ""));
if (c.connection !== "up") {
  console.log("Camunda is not reachable from the app; stopping.");
  process.exit(1);
}
await api("POST", "/api/demo/reset");

for (const [name, run] of Object.entries(scenarios)) {
  if (ONLY && ONLY !== name) continue;
  console.log(`\n== ${name}`);
  try {
    await run();
  } catch (e) {
    check(name, "scenario ran", false, e.message);
  }
}

const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} checks passed`);
failed.forEach((f) => console.log(`  FAIL ${f.scenario}: ${f.name} ${f.detail}`));
process.exit(failed.length ? 1 : 0);
