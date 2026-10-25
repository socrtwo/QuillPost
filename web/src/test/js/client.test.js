// Provider request building, SSE parsing and the streaming chat flow (mocked network).
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const { load, memoryStorage, sseResponse } = require("./helpers.js");

const P = load("providers.js");
const S = load("store.js");
const C = load("client.js");

const rp = (id, extra = {}) => {
  const def = P.providerById(id);
  const cfg = { id, model: def.models.quality, fastModel: def.models.fast, ...extra };
  return { def, cfg, key: "sk-test-123", baseUrl: S.resolveBaseUrl(def, cfg) };
};
const msgs = [{ role: "system", content: "SYS" }, { role: "user", content: "Hello" }];

// ------------------------------------------------------------------ SSE splitter
test("takeSseBlocks splits on every blank-line form servers use", () => {
  for (const sep of ["\n\n", "\r\n\r\n", "\r\n\n", "\n\r\n"]) {
    const [blocks, rest] = C.takeSseBlocks(`data: one${sep}data: two${sep}data: thr`);
    assert.deepEqual(blocks, ["data: one", "data: two"], JSON.stringify(sep));
    assert.equal(rest, "data: thr");
  }
});

test("takeSseBlocks keeps an incomplete separator for the next chunk", () => {
  let [blocks, rest] = C.takeSseBlocks("data: a\r\n");
  assert.deepEqual(blocks, []);
  [blocks, rest] = C.takeSseBlocks(rest + "\r\ndata: b\n\n");
  assert.deepEqual(blocks, ["data: a", "data: b"]);
  assert.equal(rest, "");
});

test("parseSseBlock joins multi-line data, reads event names and skips comments", () => {
  assert.deepEqual(C.parseSseBlock("event: message_start\ndata: {\"a\":1}"), { event: "message_start", data: "{\"a\":1}" });
  assert.deepEqual(C.parseSseBlock("data: line1\r\ndata: line2"), { event: "message", data: "line1\nline2" });
  assert.equal(C.parseSseBlock(": keep-alive"), null);
  assert.deepEqual(C.parseSseBlock("data:no-space"), { event: "message", data: "no-space" });
});

test("streamDelta understands each wire format", () => {
  const acc = {};
  assert.equal(C.streamDelta("openai", JSON.stringify({ choices: [{ delta: { content: "Hi" } }] }), acc), "Hi");
  assert.equal(C.streamDelta("openai", "[DONE]", acc), "");
  C.streamDelta("openai", JSON.stringify({ choices: [{ delta: {}, finish_reason: "length" }], usage: { prompt_tokens: 5, completion_tokens: 7 } }), acc);
  assert.equal(acc.finish, "length");
  assert.deepEqual(acc.usage, { in: 5, out: 7 });

  const a2 = {};
  C.streamDelta("anthropic", JSON.stringify({ type: "message_start", message: { usage: { input_tokens: 11 } } }), a2);
  assert.equal(C.streamDelta("anthropic", JSON.stringify({ type: "content_block_delta", delta: { type: "text_delta", text: "Yo" } }), a2), "Yo");
  C.streamDelta("anthropic", JSON.stringify({ type: "message_delta", delta: { stop_reason: "end_turn" }, usage: { output_tokens: 3 } }), a2);
  assert.deepEqual(a2.usage, { in: 11, out: 3 });

  const g = {};
  assert.equal(C.streamDelta("gemini", JSON.stringify({ candidates: [{ content: { parts: [{ text: "thinking", thought: true }, { text: "Answer" }] }, finishReason: "STOP" }] }), g), "Answer");
  assert.equal(g.finish, "STOP");
});

test("errors reported inside a 200 stream become AiError", () => {
  assert.throws(() => C.streamDelta("openai", JSON.stringify({ error: { message: "upstream died" } }), {}, "OpenRouter"), /OpenRouter: upstream died/);
  assert.throws(() => C.streamDelta("anthropic", JSON.stringify({ type: "error", error: { message: "overloaded" } }), {}, "X"), /overloaded/);
  assert.throws(() => C.streamDelta("gemini", JSON.stringify({ promptFeedback: { blockReason: "SAFETY" } }), {}, "G"), /blocked/);
});

// ------------------------------------------------------------------ request builders
test("OpenAI-compatible request: bearer key, streaming usage, completion-token naming", () => {
  const r = C.buildChatRequest(rp("openai"), "m1", { messages: msgs, temperature: 0.2, maxTokens: 300 }, { stream: true });
  assert.equal(r.url, "https://api.openai.com/v1/chat/completions");
  assert.equal(r.headers.Authorization, "Bearer sk-test-123");
  assert.equal(r.body.model, "m1");
  assert.equal(r.body.stream, true);
  assert.deepEqual(r.body.stream_options, { include_usage: true });
  assert.equal(r.body.max_completion_tokens, 300);
  assert.equal(r.body.max_tokens, undefined);
  assert.deepEqual(r.body.messages, [{ role: "system", content: "SYS" }, { role: "user", content: "Hello" }]);
});

test("OpenRouter sends attribution headers; Groq uses max_tokens; retry options adjust parameters", () => {
  const r = C.buildChatRequest(rp("openrouter"), "openrouter/free", { messages: msgs, maxTokens: 50 }, { stream: false });
  assert.equal(r.headers["X-Title"], "Anne's Post");
  assert.equal(r.body.stream, false);
  assert.equal(r.body.stream_options, undefined);
  const g = C.buildChatRequest(rp("groq"), "x", { messages: msgs, maxTokens: 50, temperature: 0.5 }, {});
  assert.equal(g.body.max_tokens, 50);
  const o = C.adjustForRetry({}, '{"error":{"message":"Unsupported parameter: temperature"}}');
  assert.deepEqual(o, { dropTemperature: true });
  assert.equal(C.buildChatRequest(rp("groq"), "x", { messages: msgs, temperature: 0.5 }, o).body.temperature, undefined);
  assert.deepEqual(C.adjustForRetry({}, "Unsupported parameter: 'max_tokens'"), { tokenParam: "max_completion_tokens" });
  assert.equal(C.adjustForRetry({ dropTemperature: true }, "temperature"), null);
});

test("Azure adds api-key; Cloudflare and Bedrock substitute account and region", () => {
  const az = rp("azure", { resource: "my-res" });
  assert.equal(az.baseUrl, "https://my-res.openai.azure.com/openai/v1");
  assert.equal(C.buildChatRequest(az, "dep", { messages: msgs }).headers["api-key"], "sk-test-123");
  assert.equal(rp("cloudflare", { accountId: "abc123" }).baseUrl, "https://api.cloudflare.com/client/v4/accounts/abc123/ai/v1");
  assert.equal(rp("bedrock", { region: "eu-west-1" }).baseUrl, "https://bedrock-runtime.eu-west-1.amazonaws.com/openai/v1");
  assert.equal(rp("bedrock").baseUrl, "https://bedrock-runtime.us-east-1.amazonaws.com/openai/v1");
});

test("Anthropic request: system prompt separate, key header, default max_tokens", () => {
  const r = C.buildChatRequest(rp("anthropic"), "m", { messages: msgs }, { stream: true });
  assert.equal(r.url, "https://api.anthropic.com/v1/messages");
  assert.equal(r.headers["x-api-key"], "sk-test-123");
  assert.equal(r.headers["anthropic-version"], "2023-06-01");
  assert.equal(r.headers.Authorization, undefined);
  assert.equal(r.body.system, "SYS");
  assert.deepEqual(r.body.messages, [{ role: "user", content: "Hello" }]);
  assert.equal(r.body.max_tokens, 4096);
  assert.equal(r.body.stream, true);
});

test("Gemini request: streaming URL, roles, system instruction, generation config", () => {
  const conv = [...msgs, { role: "assistant", content: "Hi!" }, { role: "user", content: "More" }];
  const r = C.buildChatRequest(rp("gemini"), "models/gemini-x", { messages: conv, temperature: 0, maxTokens: 64 }, { stream: true });
  assert.equal(r.url, "https://generativelanguage.googleapis.com/v1beta/models/gemini-x:streamGenerateContent?alt=sse");
  assert.equal(r.headers["x-goog-api-key"], "sk-test-123");
  assert.deepEqual(r.body.systemInstruction, { parts: [{ text: "SYS" }] });
  assert.deepEqual(r.body.contents.map((c) => c.role), ["user", "model", "user"]);
  assert.deepEqual(r.body.generationConfig, { temperature: 0, maxOutputTokens: 64 });
  assert.match(C.buildChatRequest(rp("gemini"), "g", { messages: msgs }).url, /:generateContent$/);
});

test("model list requests and parsing", () => {
  assert.equal(C.buildModelsRequest(rp("anthropic")).url, "https://api.anthropic.com/v1/models?limit=100");
  assert.equal(C.buildModelsRequest(rp("groq")).headers.Authorization, "Bearer sk-test-123");
  const or = C.parseModelList(P.providerById("openrouter"), { data: [{ id: "a/paid", pricing: { prompt: "0.1", completion: "0.2" } }, { id: "b/free:free" }] });
  assert.deepEqual(or.slice(0, 3), ["openrouter/free", "b/free:free", "openrouter/auto"]);
  assert.deepEqual(C.parseModelList(P.providerById("gemini"), { models: [{ name: "models/g1", supportedGenerationMethods: ["generateContent"] }, { name: "models/emb", supportedGenerationMethods: ["embedContent"] }] }), ["g1"]);
  const picked = C.pickModels(P.providerById("custom"), ["text-embed-1", "acme-mini", "acme-large"]);
  assert.deepEqual(picked, { fast: "acme-mini", quality: "acme-large" });
});

test("HTTP errors are classified for the user", () => {
  const def = P.providerById("groq");
  assert.equal(C.httpError(def, 401, '{"error":{"message":"bad key"}}', "m").code, "auth");
  assert.equal(C.httpError(def, 429, "slow down", "m").code, "quota");
  assert.equal(C.httpError(def, 404, "nope", "m").code, "model");
  assert.match(C.httpError(def, 500, "boom", "m").message, /Groq error 500: boom/);
});

// ------------------------------------------------------------------ chat flow with a mocked network
function setupProvider(id, cfg = {}) {
  S.setStorage(memoryStorage());
  S.setKeyBackend(S.memoryBackend());
  const def = P.providerById(id);
  S.saveSettings((s) => { s.providers[id] = { id, model: "big", fastModel: "small", ...cfg }; s.active = id; });
  return S.setApiKey(id, "sk-secret-value-123456");
}

test("chat streams tokens, records usage and returns the full text", async () => {
  await setupProvider("groq");
  const calls = [];
  C.configure({ fetch: async (url, init) => { calls.push({ url, init }); return sseResponse(["data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}\r\n", "\r\ndata: {\"choices\":[{\"delta\":{\"content\":\"lo\"}}]}\n\n", "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":2}}\n\ndata: [DONE]\n\n"]); } });
  const seen = [];
  const r = await C.chat({ messages: msgs, tier: "fast", onToken: (d, full) => seen.push(full) });
  assert.equal(r.text, "Hello");
  assert.deepEqual(seen, ["Hel", "Hello"]);
  assert.equal(r.model, "small");
  assert.equal(r.route, "direct");
  assert.equal(calls[0].url, "https://api.groq.com/openai/v1/chat/completions");
  assert.equal(JSON.parse(calls[0].init.body).stream, true);
  assert.deepEqual(r.usage, { in: 9, out: 2 });
  assert.equal(S.settings().usage.requests, 1);
  assert.equal(S.settings().usage.tokensOut, 2);
});

test("Stop aborts a stream in progress", async () => {
  await setupProvider("groq");
  let served = 0;
  C.configure({ fetch: async (url, init) => sseResponse(Array.from({ length: 50 }, (_, i) => `data: {"choices":[{"delta":{"content":"t${i} "}}]}\n\n`), { delay: 5, signal: init.signal }) });
  const ctl = new AbortController();
  let got = "";
  await assert.rejects(C.chat({ messages: msgs, signal: ctl.signal, onToken: (d, full) => { got = full; served++; if (full.length > 10) ctl.abort(); } }), (e) => e.code === "aborted");
  assert.ok(got.length > 10 && got.length < 30, got);
  await new Promise((r) => setTimeout(r, 40));
  assert.ok(served < 8, "no tokens after Stop");
});

test("a 400 naming temperature is retried without it", async () => {
  await setupProvider("groq");
  const bodies = [];
  C.configure({ fetch: async (url, init) => {
    bodies.push(JSON.parse(init.body));
    if (bodies.length === 1) return new Response('{"error":{"message":"temperature is not supported"}}', { status: 400 });
    return new Response(JSON.stringify({ choices: [{ message: { content: "ok" }, finish_reason: "stop" }] }), { status: 200, headers: { "content-type": "application/json" } });
  } });
  const r = await C.chat({ messages: msgs, temperature: 0.3 });
  assert.equal(r.text, "ok");
  assert.equal(bodies[0].temperature, 0.3);
  assert.equal(bodies[1].temperature, undefined);
});

test("providers that refuse web pages go through the Anne's Post relay with the session token", async () => {
  await setupProvider("perplexity");
  const calls = [];
  C.configure({ token: () => "session-tok", fetch: async (url, init) => { calls.push({ url, init }); return new Response(JSON.stringify({ choices: [{ message: { content: "relayed" } }] }), { status: 200, headers: { "content-type": "application/json" } }); } });
  const r = await C.chat({ messages: msgs });
  assert.equal(r.text, "relayed");
  assert.equal(r.route, "proxy");
  assert.equal(calls[0].url, "/api/ai/proxy");
  assert.equal(calls[0].init.headers.Authorization, "Bearer session-tok");
  assert.equal(calls[0].init.headers["X-QuillPost-AI"], "1");
  const env = JSON.parse(calls[0].init.body);
  assert.equal(env.url, "https://api.perplexity.ai/chat/completions");
  assert.equal(env.headers.Authorization, "Bearer sk-secret-value-123456");
  assert.equal(JSON.parse(env.body).messages[1].content, "Hello");
});

test("a direct call blocked by the browser falls back to the relay", async () => {
  await setupProvider("sambanova");
  const urls = [];
  C.configure({ fetch: async (url) => { urls.push(url); if (url !== "/api/ai/proxy") throw new TypeError("Failed to fetch"); return new Response(JSON.stringify({ choices: [{ message: { content: "via relay" } }] }), { status: 200, headers: { "content-type": "application/json" } }); } });
  const r = await C.chat({ messages: msgs });
  assert.deepEqual(urls, ["https://api.sambanova.ai/v1/chat/completions", "/api/ai/proxy"]);
  assert.equal(r.text, "via relay");
});

test("a refusal by the relay itself is reported as such", async () => {
  await setupProvider("perplexity");
  C.configure({ fetch: async () => new Response('{"error":"x"}', { status: 403, headers: { "X-QuillPost-Proxy-Error": encodeURIComponent("host is not allowed") } }) });
  await assert.rejects(C.chat({ messages: msgs }), (e) => e.code === "proxy" && /host is not allowed/.test(e.message));
});

test("no provider and missing key give clear errors", async () => {
  S.setStorage(memoryStorage());
  S.setKeyBackend(S.memoryBackend());
  await assert.rejects(C.chat({ messages: msgs }), (e) => e.code === "no-provider");
  S.saveSettings((s) => { s.providers.groq = { id: "groq", model: "m" }; s.active = "groq"; });
  await assert.rejects(C.chat({ messages: msgs }), (e) => e.code === "auth");
});
