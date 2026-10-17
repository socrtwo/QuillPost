// Several AIs on one request (ENSEMBLE.md §6): master and Quaker-consensus modes with mock
// providers, abort, timeouts, and the client's dispatch (named providers bypass the ensemble).
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const { load, memoryStorage, sseResponse } = require("./helpers.js");

const E = load("ensemble.js");
const P = load("providers.js");
const S = load("store.js");
const C = load("client.js");

const msgs = [{ role: "system", content: "SYS: answer briefly" }, { role: "user", content: "What is 2+2?" }];
const last = (req) => req.messages[req.messages.length - 1].content;
const kindOf = (req) => {
  const t = last(req);
  if (t.startsWith("You are now reviewing")) return "stance";
  if (t.includes("You are the clerk. Your current minute")) return "revise";
  if (t.startsWith(E.MASTER_PROMPT)) return t.includes(E.DRAFT_NOTE) ? "draft" : "master";
  return "answer";
};
const stance = (s, reason = "", change = "") => JSON.stringify({ stance: s, reason, suggested_change: change });

/**
 * A mock call(provider, model, req). answers: provider → text (or Error, or a function of the
 * request kind and round). Records every call as {provider, model, kind, req}.
 */
function mockCall(answers, { delay = 0 } = {}) {
  const calls = [];
  const round = {};
  const call = async (provider, model, req) => {
    const kind = kindOf(req);
    if (kind === "stance") round[provider] = (round[provider] || 0) + 1;
    calls.push({ provider, model, kind, req });
    if (delay) await new Promise((r) => setTimeout(r, delay));
    let a = answers[provider];
    if (typeof a === "function") a = a(kind, round[provider] || 0, req);
    if (a instanceof Error) throw a;
    if (req.onToken) req.onToken(String(a), String(a));
    return { text: String(a), model, provider, providerName: provider.toUpperCase(), usage: { in: 10, out: 5 } };
  };
  return { call, calls };
}
const members = (...ids) => ids.map((id) => ({ provider: id, model: id + "-model", name: id.toUpperCase() }));

// ------------------------------------------------------------------ master mode
test("master mode asks every member, then the master once with shuffled, labeled responses", async () => {
  const { call, calls } = mockCall({
    alpha: (k) => (k === "master" ? "FINAL: 4" : "alpha says 4"),
    beta: "beta says four",
    gamma: "gamma says 4.0",
  });
  const tokens = [];
  const statuses = [];
  // A fixed random source that reverses the order: shuffling is real, not identity.
  const r = await E.run({ messages: msgs, temperature: 0.2, onToken: (d, full) => tokens.push(full), onStatus: (t) => statuses.push(t) },
    { mode: "master", members: members("alpha", "beta", "gamma"), master: { provider: "alpha", model: "alpha-model", name: "ALPHA" }, call, random: () => 0 });

  const answers = calls.filter((c) => c.kind === "answer");
  assert.deepEqual(answers.map((c) => c.provider).sort(), ["alpha", "beta", "gamma"]);
  for (const c of answers) { assert.deepEqual(c.req.messages, msgs); assert.equal(c.req.temperature, 0.2); }
  const master = calls.filter((c) => c.kind === "master");
  assert.equal(master.length, 1);
  assert.equal(master[0].provider, "alpha");
  assert.equal(calls.length, 4);
  // The original system prompt and messages, then the final user message with the labeled answers.
  const m = master[0].req.messages;
  assert.deepEqual(m.slice(0, 2), msgs);
  assert.equal(m[2].role, "user");
  assert.ok(m[2].content.startsWith(E.MASTER_PROMPT));
  const order = ["Response A:", "Response B:", "Response C:"].map((l) => m[2].content.indexOf(l));
  assert.ok(order.every((i, k) => i > 0 && (k === 0 || i > order[k - 1])), "labeled A, B, C in order");
  const sections = m[2].content.split(/Response [ABC]:/).slice(1).map((x) => x.trim());
  assert.ok(sections[0].includes("beta") && sections[1].includes("gamma") && sections[2].includes("alpha"), "shuffled, not in member order: " + sections.join(" | "));
  assert.ok(!/ALPHA|BETA|GAMMA/.test(m[2].content), "no provider names in the master's prompt");
  assert.equal(master[0].req.temperature, 0.2);

  assert.equal(r.text, "FINAL: 4");
  assert.equal(r.provider, "alpha");
  assert.deepEqual(tokens, ["FINAL: 4"], "only the final answer is streamed");
  assert.deepEqual(r.usage, { in: 40, out: 20 }, "usage adds up every call");
  assert.equal(r.ensemble.mode, "master");
  assert.equal(r.ensemble.outcome, "synthesized");
  assert.equal(r.ensemble.members.length, 3);
  assert.equal(r.ensemble.members.find((x) => x.provider === "beta").text, "beta says four");
  assert.ok(r.ensemble.members.every((x) => typeof x.ms === "number" && x.model));
  assert.equal(statuses[0], "Asking 3 AIs…");
  assert.equal(E.outcomeText(r.ensemble, (id) => id.toUpperCase()), "Synthesized by ALPHA");
});

test("the shuffle is a real permutation", () => {
  let seed = 7;
  const rnd = () => ((seed = (seed * 16807) % 2147483647) / 2147483647);
  const out = E.shuffle([1, 2, 3, 4, 5, 6], rnd);
  assert.deepEqual([...out].sort(), [1, 2, 3, 4, 5, 6]);
  assert.notDeepEqual(out, [1, 2, 3, 4, 5, 6]);
});

test("a failing member is skipped and reported; one answer is returned as it is", async () => {
  const { call, calls } = mockCall({ alpha: (k) => (k === "master" ? "combined" : "alpha answer"), beta: new Error("quota reached (429)"), gamma: "gamma answer" });
  const r = await E.run({ messages: msgs }, { mode: "master", members: members("alpha", "beta", "gamma"), master: members("alpha")[0], call, random: () => 0.5 });
  assert.equal(r.text, "combined");
  const beta = r.ensemble.members.find((x) => x.provider === "beta");
  assert.match(beta.error, /quota reached/);
  assert.equal(beta.text, undefined);
  const master = calls.find((c) => c.kind === "master");
  assert.ok(!master.req.messages[2].content.includes("Response C:"), "only the two answers are sent to the master");

  const only = mockCall({ alpha: new Error("down"), beta: "the only answer" });
  const tokens = [];
  const r2 = await E.run({ messages: msgs, onToken: (d, f) => tokens.push(f) }, { mode: "master", members: members("alpha", "beta"), master: members("alpha")[0], call: only.call });
  assert.equal(r2.text, "the only answer");
  assert.equal(only.calls.filter((c) => c.kind === "master").length, 0, "no master call for a single answer");
  assert.match(r2.ensemble.note, /Only BETA answered/);
  assert.deepEqual(tokens, ["the only answer"]);

  const none = mockCall({ alpha: new Error("down"), beta: new Error("bad key") });
  await assert.rejects(E.run({ messages: msgs }, { mode: "master", members: members("alpha", "beta"), call: none.call }), (e) => /None of the AIs answered/.test(e.message) && /bad key/.test(e.message) && e.ensemble.members.length === 2);
});

test("a member that does not answer in time is left out and reported", async () => {
  const slow = (provider, model, req) => new Promise((resolve, reject) => {
    if (provider === "slow" && kindOf(req) === "answer") { req.signal.addEventListener("abort", () => reject(new Error("aborted"))); return; }
    resolve({ text: kindOf(req) === "master" ? "done" : provider + " ok", model, provider });
  });
  const t0 = Date.now();
  const r = await E.run({ messages: msgs }, { mode: "master", members: members("fast", "slow", "quick"), master: members("fast")[0], call: slow, memberTimeoutMs: 60 });
  assert.ok(Date.now() - t0 < 2000);
  assert.equal(r.text, "done");
  assert.match(r.ensemble.members.find((x) => x.provider === "slow").error, /no answer within/);
});

test("the master falling over still returns an answer, with a note", async () => {
  const { call } = mockCall({ alpha: (k) => (k === "master" ? new Error("overloaded") : "alpha answer"), beta: "beta answer" });
  const r = await E.run({ messages: msgs }, { mode: "master", members: members("alpha", "beta"), master: members("alpha")[0], call });
  assert.ok(["alpha answer", "beta answer"].includes(r.text));
  assert.match(r.ensemble.note, /could not combine the answers \(overloaded\)/);
});

// ------------------------------------------------------------------ consensus mode
test("consensus reaches unity in round 1 when all unite", async () => {
  const { call, calls } = mockCall({
    alpha: (k) => (k === "draft" ? "MINUTE: 4" : k === "stance" ? stance("unite", "correct") : "alpha: 4"),
    beta: (k) => (k === "stance" ? "```json\n" + stance("unite") + "\n```" : "beta: 4"),
    gamma: (k) => (k === "stance" ? stance("unite", "fine") : "gamma: 4"),
  });
  const tokens = [], statuses = [];
  const r = await E.run({ messages: msgs, onToken: (d, f) => tokens.push(f), onStatus: (t) => statuses.push(t) },
    { mode: "consensus", members: members("alpha", "beta", "gamma"), master: members("alpha")[0], call, maxRounds: 3 });
  assert.equal(r.text, "MINUTE: 4");
  assert.deepEqual(tokens, ["MINUTE: 4"], "the adopted minute is sent as one chunk");
  assert.equal(r.ensemble.mode, "consensus");
  assert.equal(r.ensemble.outcome, "unity");
  assert.equal(r.ensemble.rounds.length, 1);
  assert.equal(r.ensemble.rounds[0].minute, "MINUTE: 4");
  assert.deepEqual(r.ensemble.rounds[0].responses.map((x) => x.stance), ["unite", "unite", "unite"]);
  assert.deepEqual(calls.map((c) => c.kind).sort(), ["answer", "answer", "answer", "draft", "stance", "stance", "stance"]);
  // The draft minute: the master prompt plus the draft note; the stance request carries the minute and the rules.
  const draft = calls.find((c) => c.kind === "draft");
  assert.ok(last(draft.req).startsWith(E.MASTER_PROMPT + " " + E.DRAFT_NOTE));
  const st = calls.find((c) => c.kind === "stance");
  assert.deepEqual(st.req.messages.slice(0, 2), msgs, "members receive the original request");
  assert.ok(last(st.req).includes("<minute>\nMINUTE: 4\n</minute>") && last(st.req).includes(E.STANCE_RULES) && last(st.req).includes('"stance":"unite"|"stand-aside"|"block"'));
  assert.ok(last(st.req).includes("Response A:") && last(st.req).includes("Response C:"), "with the anonymized round-0 answers");
  assert.equal(E.outcomeText(r.ensemble), "United (3 of 3)");
  assert.ok(statuses.includes("Round 1 of 3: all united"), statuses.join(" / "));
  assert.equal(r.usage.in, 70);
});

test("a block triggers a revision and a second round; stand-asides are recorded", async () => {
  const { call, calls } = mockCall({
    alpha: (k) => (k === "draft" ? "MINUTE v1" : k === "revise" ? "MINUTE v2" : k === "stance" ? stance("unite") : "a"),
    beta: (k, round) => (k === "stance" ? (round === 1 ? stance("block", "v1 gets the date wrong", "use the 22nd") : stance("stand-aside", "I would be shorter")) : "b"),
    gamma: (k) => (k === "stance" ? stance("unite") : "c"),
  });
  const statuses = [];
  const r = await E.run({ messages: msgs, onStatus: (t) => statuses.push(t) }, { mode: "consensus", members: members("alpha", "beta", "gamma"), master: members("alpha")[0], call, maxRounds: 3 });
  assert.equal(r.text, "MINUTE v2");
  assert.equal(r.ensemble.rounds.length, 2);
  assert.equal(r.ensemble.rounds[0].minute, "MINUTE v1");
  assert.equal(r.ensemble.rounds[1].minute, "MINUTE v2");
  assert.equal(r.ensemble.rounds[0].responses.find((x) => x.provider === "beta").stance, "block");
  assert.equal(r.ensemble.outcome, "unity-with-stand-asides");
  assert.deepEqual(r.ensemble.standAsides, [{ provider: "beta", reason: "I would be shorter" }]);
  const rev = calls.filter((c) => c.kind === "revise");
  assert.equal(rev.length, 1);
  assert.equal(rev[0].provider, "alpha", "the clerk revises");
  assert.ok(last(rev[0].req).includes("Block: v1 gets the date wrong") && last(rev[0].req).includes("Suggested change: use the 22nd"));
  assert.ok(last(rev[0].req).includes("Revise the minute to meet these objections where they are right; keep the original format requirements"));
  assert.ok(!/beta|BETA/.test(last(rev[0].req)), "objections are anonymous");
  assert.ok(statuses.includes("Round 1 of 3: 1 objection"), statuses.join(" / "));
  assert.equal(E.outcomeText(r.ensemble), "United; 1 stood aside");
});

test("maxRounds with a persistent block yields no-unity and still returns the minute", async () => {
  let revisions = 0;
  const { call, calls } = mockCall({
    alpha: (k) => (k === "draft" ? "MINUTE 0" : k === "revise" ? `MINUTE ${++revisions}` : k === "stance" ? stance("unite") : "a"),
    beta: (k) => (k === "stance" ? stance("block", "still wrong") : "b"),
  });
  const tokens = [];
  const r = await E.run({ messages: msgs, onToken: (d, f) => tokens.push(f) }, { mode: "consensus", members: members("alpha", "beta"), master: members("alpha")[0], call, maxRounds: 2 });
  assert.equal(r.ensemble.outcome, "no-unity");
  assert.equal(r.ensemble.rounds.length, 2);
  assert.equal(r.text, "MINUTE 1", "the clerk's last minute");
  assert.deepEqual(tokens, ["MINUTE 1"]);
  assert.deepEqual(r.ensemble.blocks, [{ provider: "beta", reason: "still wrong" }]);
  assert.equal(calls.filter((c) => c.kind === "revise").length, 1, "rounds - 1 revisions");
  assert.equal(calls.length, 2 + 1 + 2 * 2 + 1, "within N×(rounds+1)+rounds");
  assert.ok(calls.length <= E.requestCounts(2, 2).consensus);
  assert.equal(E.outcomeText(r.ensemble), "No unity after 2 rounds: 1 objection");
});

test("an unreadable stance counts as uniting, with a note; a failed member is left out of the round", async () => {
  const { call } = mockCall({
    alpha: (k) => (k === "draft" ? "M" : k === "stance" ? "Sure, looks good to me!" : "a"),
    beta: (k) => (k === "stance" ? new Error("timeout") : "b"),
    gamma: (k) => (k === "stance" ? stance("Stand Aside", "prefer bullets") : "c"),
  });
  const r = await E.run({ messages: msgs }, { mode: "consensus", members: members("alpha", "beta", "gamma"), master: members("alpha")[0], call });
  const round = r.ensemble.rounds[0];
  const a = round.responses.find((x) => x.provider === "alpha");
  assert.equal(a.stance, "unite");
  assert.match(a.note, /could not be read/);
  assert.equal(round.responses.find((x) => x.provider === "gamma").stance, "stand-aside");
  assert.ok(!round.responses.some((x) => x.provider === "beta"));
  assert.equal(round.missing[0].provider, "beta");
  assert.equal(r.ensemble.outcome, "unity-with-stand-asides");
});

test("parseStance reads fenced, loose and differently spelled stances", () => {
  assert.deepEqual(E.parseStance('Here: {"stance":"block","reason":"wrong","suggested_change":"fix it"}'), { stance: "block", reason: "wrong", suggestedChange: "fix it" });
  assert.equal(E.parseStance('{"stance":"stand_aside"}').stance, "stand-aside");
  assert.equal(E.parseStance("not json"), null);
  assert.equal(E.parseStance('{"stance":"maybe"}'), null);
});

// ------------------------------------------------------------------ abort, serial on-device members
test("abort cancels all calls", async () => {
  const seen = [];
  const call = (provider, model, req) => new Promise((resolve, reject) => {
    seen.push({ provider, signal: req.signal });
    req.signal.addEventListener("abort", () => reject(Object.assign(new Error("Stopped"), { code: "aborted" })));
  });
  const ctl = new AbortController();
  const p = E.run({ messages: msgs, signal: ctl.signal }, { mode: "consensus", members: members("a", "b", "c"), call });
  await new Promise((r) => setTimeout(r, 20));
  assert.equal(seen.length, 3, "members run in parallel");
  ctl.abort();
  await assert.rejects(p, (e) => e.code === "aborted");
  assert.ok(seen.every((x) => x.signal.aborted), "every outstanding member call was aborted");

  // A call that ignores its signal does not hold up Stop.
  const ctl2 = new AbortController();
  const stubborn = () => new Promise(() => {});
  const p2 = E.run({ messages: msgs, signal: ctl2.signal }, { mode: "master", members: members("a", "b"), call: stubborn });
  setTimeout(() => ctl2.abort(), 10);
  await assert.rejects(p2, (e) => e.code === "aborted");
});

test("on-device members take turns; cloud members run in parallel", async () => {
  let running = 0, maxSerial = 0, serialRunning = 0;
  const call = async (provider, model, req) => {
    const serial = provider.startsWith("dev");
    running++; if (serial) { serialRunning++; maxSerial = Math.max(maxSerial, serialRunning); }
    await new Promise((r) => setTimeout(r, 15));
    running--; if (serial) serialRunning--;
    return { text: kindOf(req) === "master" ? "final" : provider, model, provider };
  };
  const ms = [{ provider: "dev1", model: "m", serial: true }, { provider: "dev2", model: "m", serial: true }, { provider: "cloud", model: "m" }];
  const r = await E.run({ messages: msgs }, { mode: "master", members: ms, master: ms[2], call });
  assert.equal(r.text, "final");
  assert.equal(maxSerial, 1);
});

// ------------------------------------------------------------------ the client's dispatch
const okJson = (text, usage = { prompt_tokens: 3, completion_tokens: 2 }) => new Response(JSON.stringify({ choices: [{ message: { content: text }, finish_reason: "stop" }], usage }), { status: 200, headers: { "content-type": "application/json" } });
async function setupEnsemble(mode = "master") {
  S.setStorage(memoryStorage());
  S.setKeyBackend(S.memoryBackend());
  S.saveSettings((s) => {
    s.providers.groq = { id: "groq", model: "g-big", fastModel: "g-small" };
    s.providers.mistral = { id: "mistral", model: "m-big" };
    s.providers.perplexity = { id: "perplexity", model: "p-big" };
    s.active = "groq";
    s.ensemble = { mode, members: [{ provider: "groq" }, { provider: "mistral", model: "m-chosen" }, { provider: "perplexity" }], master: "mistral", maxRounds: 2 };
  });
  await S.setApiKey("groq", "sk-groq-secret-1111");
  await S.setApiKey("mistral", "sk-mistral-secret-2222");
  await S.setApiKey("perplexity", "sk-pplx-secret-3333");
}

test("chat() uses the ensemble for normal requests; each member keeps its own route, including the relay", async () => {
  await setupEnsemble("master");
  const calls = [];
  C.configure({ token: () => "tok", fetch: async (url, init) => {
    const env = url === "/api/ai/proxy" ? JSON.parse(init.body) : null;
    const target = env ? env.url : url;
    const body = JSON.parse(env ? env.body : init.body);
    calls.push({ url, target, model: body.model, last: body.messages[body.messages.length - 1].content, stream: body.stream });
    if (/groq/.test(target) && url !== "/api/ai/proxy") throw new TypeError("Failed to fetch"); // a direct call blocked by the browser: relayed
    if (body.messages[body.messages.length - 1].content.startsWith(E.MASTER_PROMPT)) {
      return sseResponse([`data: {"choices":[{"delta":{"content":"Comb"}}]}\n\n`, `data: {"choices":[{"delta":{"content":"ined"}}]}\n\n`, "data: [DONE]\n\n"]);
    }
    return okJson(`answer from ${body.model}`);
  } });
  const tokens = [], statuses = [];
  const r = await C.chat({ messages: msgs, onToken: (d, full) => tokens.push(full), onStatus: (t) => statuses.push(t) });
  assert.equal(r.text, "Combined");
  assert.deepEqual(tokens, ["Comb", "Combined"], "only the master's answer streams");
  assert.equal(r.provider, "mistral");
  assert.equal(r.model, "m-chosen", "the master uses its member model");
  const members = calls.filter((c) => !c.last.startsWith(E.MASTER_PROMPT));
  assert.deepEqual(members.map((c) => c.model).sort(), ["g-big", "g-big", "m-chosen", "p-big"]);
  assert.ok(calls.some((c) => c.url === "/api/ai/proxy" && /groq/.test(c.target)), "groq fell back to the relay");
  assert.ok(calls.some((c) => c.url === "/api/ai/proxy" && /perplexity/.test(c.target)), "perplexity always goes through the relay");
  assert.equal(r.ensemble.members.length, 3);
  assert.ok(r.ensemble.members.every((m) => m.text && m.text.startsWith("answer from")));
  assert.equal(statuses[0], "Asking 3 AIs…");
  assert.equal(S.settings().usage.requests, 4, "usage counts every call");
});

test("key tests, Try it and model lists name a provider and bypass the ensemble", async () => {
  await setupEnsemble("consensus");
  const urls = [];
  C.configure({ fetch: async (url, init) => { urls.push(url === "/api/ai/proxy" ? JSON.parse(init.body).url : url); return url.endsWith("/models") ? new Response(JSON.stringify({ data: [{ id: "x" }] }), { status: 200 }) : okJson("OK"); } });
  const r = await C.chat({ provider: "groq", tier: "fast", messages: [{ role: "user", content: "Reply with OK." }], maxTokens: 10 });
  assert.equal(r.text, "OK");
  assert.equal(r.ensemble, undefined);
  assert.deepEqual(urls, ["https://api.groq.com/openai/v1/chat/completions"]);
  const rp = await S.resolveProvider("mistral");
  const list = await C.listModels(rp);
  assert.deepEqual(list, ["x"]);
  assert.equal(urls.length, 2);
  assert.ok(C.ensemblePlan(), "the ensemble is still set up for normal commands");
});

test("the ensemble needs two usable members; otherwise chat() is the single active AI", async () => {
  await setupEnsemble("master");
  S.saveSettings((s) => { s.ensemble.members = [{ provider: "groq" }, { provider: "openai" }]; });
  assert.equal(C.ensemblePlan(), null, "openai is not set up");
  S.saveSettings((s) => { s.ensemble.members = [{ provider: "groq" }, { provider: "mistral" }]; s.ensemble.mode = "single"; });
  assert.equal(C.ensemblePlan(), null);
  S.saveSettings((s) => { s.ensemble.mode = "consensus"; s.ensemble.master = "deepseek"; });
  const plan = C.ensemblePlan();
  assert.equal(plan.master.provider, "groq", "an unusable master falls back to the first member");
  assert.equal(plan.members[1].model, "m-big", "a member's model defaults to the provider's writing model");
});

test("Stop during an ensemble aborts every member request", async () => {
  await setupEnsemble("master");
  const signals = [];
  C.configure({ fetch: (url, init) => new Promise((resolve, reject) => { signals.push(init.signal); init.signal.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError"))); }) });
  const ctl = new AbortController();
  const p = C.chat({ messages: msgs, signal: ctl.signal });
  await new Promise((r) => setTimeout(r, 20));
  ctl.abort();
  await assert.rejects(p, (e) => e.code === "aborted");
  assert.ok(signals.length >= 2 && signals.every((s) => s.aborted));
});

// ------------------------------------------------------------------ settings
test("ensemble settings are normalized, exported and imported, and the export never contains keys", async () => {
  await setupEnsemble("consensus");
  S.saveSettings((s) => { s.ensemble.maxRounds = 9; s.ensemble.members.push({ provider: "nope" }, { provider: "groq" }); s.ensemble.apiKey = "sk-should-vanish"; });
  const e = S.settings().ensemble;
  assert.equal(e.maxRounds, 3, "out-of-range rounds fall back to the default");
  assert.deepEqual(e.members.map((m) => m.provider), ["groq", "mistral", "perplexity"]);
  assert.equal(e.apiKey, undefined);
  assert.equal(e.showDeliberation, true);
  assert.equal(e.memberTimeoutMs, 90000);
  const exp = JSON.stringify(S.exportSettings());
  for (const k of ["sk-groq-secret-1111", "sk-mistral-secret-2222", "sk-pplx-secret-3333", "sk-should-vanish"]) assert.ok(!exp.includes(k), "export leaks " + k);
  assert.equal(JSON.parse(exp).settings.ensemble.mode, "consensus");
  S.setStorage(memoryStorage());
  S.importSettings(JSON.parse(exp));
  assert.equal(S.settings().ensemble.mode, "consensus");
  assert.equal(S.settings().ensemble.master, "mistral");
  assert.deepEqual(S.defaultSettings().ensemble, { mode: "single", members: [], master: null, maxRounds: 3, showDeliberation: true, memberTimeoutMs: 90000 });
});

test("the cost note's request counts", () => {
  assert.deepEqual(E.requestCounts(3, 3), { master: 4, consensus: 15 });
});
