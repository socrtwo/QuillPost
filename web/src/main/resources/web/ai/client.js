/* Quillbox AI — provider-neutral chat client: OpenAI-compatible chat/completions, Anthropic
 * Messages, Gemini generateContent, the browser's built-in model and the on-device model.
 * Streams (server-sent events) with Stop (AbortSignal) everywhere.
 *
 * Network route: requests go straight from this page to the provider when the provider allows
 * cross-origin calls. Providers that do not (and local servers the page cannot reach directly)
 * are called through the Quillbox backend's narrow relay, POST /api/ai/proxy, which only
 * forwards to the provider addresses in its allow-list and the custom address you registered.
 *
 * The request builders and the SSE splitter are pure functions, unit-tested in web/src/test/js.
 */
(function (root, factory) {
  const mod = factory(root);
  if (typeof module === "object" && module.exports) module.exports = mod;
  else root.QbAiClient = mod;
})(typeof globalThis !== "undefined" ? globalThis : this, function (root) {
  "use strict";
  const P = root.QbAiProviders || (typeof require === "function" ? require("./providers.js") : null);
  const S = root.QbAiStore || (typeof require === "function" ? require("./store.js") : null);

  class AiError extends Error {
    /** code: no-provider · auth · quota · model · network · cors · aborted · unsupported · proxy · other */
    constructor(message, code = "other", status) { super(message); this.name = "AiError"; this.code = code; this.status = status; }
  }

  /** Finish reasons (OpenAI, Anthropic, Gemini) meaning the model did not end the answer itself. */
  const CUT = new Set(["length", "content_filter", "max_tokens", "refusal", "model_context_window_exceeded", "MAX_TOKENS", "SAFETY", "RECITATION", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "LANGUAGE"]);
  const cutShort = (reason) => typeof reason === "string" && CUT.has(reason);
  /** OpenAI-compatible services known to accept stream_options.include_usage (token counts while streaming). */
  const STREAM_USAGE = new Set(["openai", "openrouter", "groq", "deepseek", "together", "fireworks", "cerebras", "xai", "azure", "mistral"]);

  const textOf = (c) => (typeof c === "string" ? c : (c || []).filter((p) => p.type === "text").map((p) => p.text).join("\n"));

  // ------------------------------------------------------------------ request builders (pure)
  /**
   * The HTTP request for one chat call. [opts.stream] asks for server-sent events;
   * [opts.dropTemperature] and [opts.tokenParam] are set by the retry logic when a model
   * refuses a parameter.
   */
  function buildChatRequest(rp, model, req, opts = {}) {
    const { def, key, baseUrl } = rp;
    const stream = !!opts.stream;
    const system = req.messages.filter((m) => m.role === "system").map((m) => textOf(m.content)).join("\n\n");
    const turns = req.messages.filter((m) => m.role !== "system");
    if (def.kind === "anthropic") {
      const headers = { "Content-Type": "application/json", "x-api-key": key, "anthropic-version": "2023-06-01", "anthropic-dangerous-direct-browser-access": "true" };
      const body = { model, max_tokens: req.maxTokens || 4096, messages: turns.map((m) => ({ role: m.role, content: textOf(m.content) })) };
      if (system) body.system = system;
      if (req.temperature != null && !opts.dropTemperature) body.temperature = req.temperature;
      if (stream) body.stream = true;
      return { url: `${baseUrl}/messages`, method: "POST", headers, body };
    }
    if (def.kind === "gemini") {
      const headers = { "Content-Type": "application/json", "x-goog-api-key": key };
      const body = { contents: turns.map((m) => ({ role: m.role === "assistant" ? "model" : "user", parts: [{ text: textOf(m.content) }] })) };
      if (system) body.systemInstruction = { parts: [{ text: system }] };
      const gen = {};
      if (req.temperature != null && !opts.dropTemperature) gen.temperature = req.temperature;
      if (req.maxTokens) gen.maxOutputTokens = req.maxTokens;
      if (Object.keys(gen).length) body.generationConfig = gen;
      const m = encodeURIComponent(String(model).replace(/^models\//, ""));
      return { url: stream ? `${baseUrl}/models/${m}:streamGenerateContent?alt=sse` : `${baseUrl}/models/${m}:generateContent`, method: "POST", headers, body };
    }
    // OpenAI-compatible
    const headers = { "Content-Type": "application/json", ...(def.extraHeaders || {}) };
    if (key) headers.Authorization = `Bearer ${key}`;
    if (def.id === "azure" && key) headers["api-key"] = key;
    const body = { model, messages: req.messages.map((m) => ({ role: m.role, content: textOf(m.content) })) };
    if (req.temperature != null && !opts.dropTemperature) body.temperature = req.temperature;
    if (req.maxTokens) body[opts.tokenParam || (def.completionTokens ? "max_completion_tokens" : "max_tokens")] = req.maxTokens;
    body.stream = stream;
    if (stream && STREAM_USAGE.has(def.id) && !opts.noStreamUsage) body.stream_options = { include_usage: true };
    return { url: `${baseUrl}/chat/completions`, method: "POST", headers, body };
  }

  /** The request that lists a provider's models (and so validates the key). */
  function buildModelsRequest(rp) {
    const { def, key, baseUrl } = rp;
    const headers = { ...(def.extraHeaders || {}) };
    if (def.kind === "anthropic") return { url: `${baseUrl}/models?limit=100`, method: "GET", headers: { ...headers, "x-api-key": key, "anthropic-version": "2023-06-01", "anthropic-dangerous-direct-browser-access": "true" } };
    if (def.kind === "gemini") return { url: `${baseUrl}/models?pageSize=200`, method: "GET", headers: { ...headers, "x-goog-api-key": key } };
    if (key) headers.Authorization = `Bearer ${key}`;
    if (def.id === "azure" && key) headers["api-key"] = key;
    return { url: `${baseUrl}/models`, method: "GET", headers };
  }

  function parseModelList(def, j) {
    if (def.kind === "gemini") return (j.models || []).filter((m) => !m.supportedGenerationMethods || m.supportedGenerationMethods.includes("generateContent")).map((m) => String(m.name).replace(/^models\//, ""));
    const list = Array.isArray(j) ? j : j.data || j.models || [];
    const ids = list.map((m) => (typeof m === "string" ? m : m.id || m.name)).filter(Boolean);
    if (def.id === "openrouter") {
      const free = list.filter((m) => (m.id && m.id.endsWith(":free")) || (m.pricing && Number(m.pricing.prompt) === 0 && Number(m.pricing.completion) === 0)).map((m) => m.id);
      return [...new Set(["openrouter/free", ...free, "openrouter/auto", ...ids])];
    }
    return [...new Set(ids)];
  }

  /** Sensible fast/quality models from a provider's list when its defaults are not offered. */
  function pickModels(def, list) {
    const has = (m) => list.includes(m);
    if (!list.length) return { fast: def.models.fast, quality: def.models.quality };
    const chatty = list.filter((m) => !/embed|whisper|tts|audio|image|vision-preview|moderation|rerank|guard|transcrib|dall-e|imagen|veo|search-preview/i.test(m));
    const pool = chatty.length ? chatty : list;
    const fast = has(def.models.fast) ? def.models.fast : pool.find((m) => /flash-lite|mini|luna|haiku|small|8b|20b|flash|lite|fast|instant/i.test(m)) || pool[0];
    const quality = has(def.models.quality) ? def.models.quality : pool.find((m) => /sonnet|sol|pro|large|medium|120b|70b|max|plus|flash-latest/i.test(m)) || fast;
    return { fast, quality };
  }

  // ------------------------------------------------------------------ SSE (pure)
  /** Splits complete server-sent-event blocks off the buffer. Events end with a blank line; servers mix \n and \r\n, so the separator is 2 to 4 characters. */
  function takeSseBlocks(buf) {
    const blocks = [];
    let m;
    while ((m = /\r?\n\r?\n/.exec(buf))) {
      blocks.push(buf.slice(0, m.index));
      buf = buf.slice(m.index + m[0].length);
    }
    return [blocks, buf];
  }

  /** One SSE block → {event, data} (multi-line data joined with \n), or null for comments/keep-alives. */
  function parseSseBlock(block) {
    let event = "message";
    const data = [];
    for (const line of block.split(/\r?\n/)) {
      if (line.startsWith("event:")) event = line.slice(6).trim();
      else if (line.startsWith("data:")) data.push(line.slice(5).replace(/^ /, ""));
    }
    return data.length ? { event, data: data.join("\n") } : null;
  }

  /**
   * Interprets one event's data for a provider kind. Returns the text delta ("" for none) and
   * updates [acc] ({finish, usage}). Throws AiError when the stream reports a failure (gateways
   * such as OpenRouter report errors inside a 200 stream).
   */
  function streamDelta(kind, data, acc, providerName = "The AI service") {
    if (data === "[DONE]") return "";
    let j;
    try { j = JSON.parse(data); } catch { return ""; /* keep-alive or partial */ }
    if (kind === "anthropic") {
      if (j.type === "error") throw new AiError(`${providerName}: ${(j.error && j.error.message) || "stream error"}`);
      if (j.type === "message_start" && j.message && j.message.usage) acc.usage = { in: j.message.usage.input_tokens || 0, out: (acc.usage && acc.usage.out) || 0 };
      if (j.type === "message_delta" && j.usage) acc.usage = { in: (acc.usage && acc.usage.in) || 0, out: j.usage.output_tokens || 0 };
      if (j.type === "message_delta" && j.delta && j.delta.stop_reason) acc.finish = j.delta.stop_reason;
      if (j.type === "content_block_delta" && j.delta && j.delta.type === "text_delta") return j.delta.text || "";
      return "";
    }
    if (kind === "gemini") {
      if (j.error) throw new AiError(`${providerName}: ${j.error.message || "the answer failed"}`);
      if (j.promptFeedback && j.promptFeedback.blockReason) throw new AiError(`${providerName} blocked the request (${j.promptFeedback.blockReason}).`);
      const c = j.candidates && j.candidates[0];
      if (c && c.finishReason) acc.finish = c.finishReason;
      if (j.usageMetadata) acc.usage = { in: j.usageMetadata.promptTokenCount || 0, out: j.usageMetadata.candidatesTokenCount || 0 };
      return ((c && c.content && c.content.parts) || []).filter((p) => !p.thought).map((p) => p.text || "").join("");
    }
    if (j.error) throw new AiError(`${providerName}: ${j.error.message || j.error.code || "the answer failed"}`);
    const c = j.choices && j.choices[0];
    if (c && c.finish_reason) acc.finish = c.finish_reason;
    if (j.usage) acc.usage = { in: j.usage.prompt_tokens || 0, out: j.usage.completion_tokens || 0 };
    const d = c && c.delta && c.delta.content;
    return typeof d === "string" ? d : "";
  }

  /** A complete (non-streamed) answer → {text, finish, usage}. */
  function parseCompletion(kind, j, providerName = "The AI service") {
    if (kind === "anthropic") return { text: (j.content || []).filter((c) => c.type === "text").map((c) => c.text).join(""), finish: j.stop_reason, usage: { in: (j.usage && j.usage.input_tokens) || 0, out: (j.usage && j.usage.output_tokens) || 0 } };
    if (kind === "gemini") { const acc = {}; const text = streamDelta("gemini", JSON.stringify(j), acc, providerName); return { text, finish: acc.finish, usage: acc.usage }; }
    if (j.error && !j.choices) throw new AiError(`${providerName}: ${j.error.message || j.error.code || "the answer failed"}`);
    const msg = j.choices && j.choices[0] && j.choices[0].message;
    const text = msg ? (typeof msg.content === "string" ? msg.content : Array.isArray(msg.content) ? msg.content.map((p) => p.text || "").join("") : "") : "";
    return { text, finish: j.choices && j.choices[0] && j.choices[0].finish_reason, usage: j.usage ? { in: j.usage.prompt_tokens || 0, out: j.usage.completion_tokens || 0 } : undefined };
  }

  /** On a 400 naming a parameter the model refuses, the options for one retry (or null). */
  function adjustForRetry(opts, body) {
    if (/stream_options/i.test(body) && !opts.noStreamUsage) return { ...opts, noStreamUsage: true };
    if (/temperature/i.test(body) && !opts.dropTemperature) return { ...opts, dropTemperature: true };
    if (/max_completion_tokens/i.test(body) && opts.tokenParam !== "max_tokens") return { ...opts, tokenParam: "max_tokens" };
    if (/max_tokens/i.test(body) && opts.tokenParam !== "max_completion_tokens") return { ...opts, tokenParam: "max_completion_tokens" };
    return null;
  }

  function httpError(def, status, body, model) {
    let msg = body;
    try {
      const j = JSON.parse(body);
      msg = (j.error && j.error.message) || j.message || (j.error && j.error.code) || j.detail || (typeof j.error === "string" ? j.error : "") || body;
    } catch { /* not JSON */ }
    msg = String(msg).slice(0, 400);
    if (status === 401 || status === 403) return new AiError(`${def.name} rejected the API key (${status}). ${msg}`, "auth", status);
    if (status === 402 || status === 429) return new AiError(`${def.name}: quota or rate limit reached (${status}). ${msg}`, "quota", status);
    if (status === 404 || /model.*(not found|does not exist|unknown|invalid)/i.test(msg)) return new AiError(`${def.name} does not offer the model "${model}". Choose another in AI ▸ Providers & models. (${msg})`, "model", status);
    return new AiError(`${def.name} error ${status}: ${msg}`, "other", status);
  }

  // ------------------------------------------------------------------ transport
  const net = {
    fetch: (...a) => root.fetch(...a),
    /** Session token for the Quillbox backend relay (set by the UI). */
    token: () => null,
  };
  function configure(o) { Object.assign(net, o); }

  /** Origins a direct call failed for (CORS or mixed content) in this session: relay them straight away. */
  const relayOnly = new Set();
  const originOf = (url) => { try { return new URL(url).origin; } catch { return url; } };

  const isLoopback = (url) => /^https?:\/\/(localhost|127\.0\.0\.1|\[::1\])([:/]|$)/i.test(url);

  /** "direct", "proxy" or "direct-then-proxy" for a request to [url]. */
  function routeFor(def, url) {
    const pref = (S && S.settings().network) || "auto";
    if (pref === "direct") return "direct";
    if (pref === "proxy") return "proxy";
    if (def.cors === "no" || relayOnly.has(originOf(url))) return "proxy";
    // An http:// server from an https:// page is blocked as mixed content: relay it.
    if (/^http:/i.test(url) && root.location && root.location.protocol === "https:") return "proxy";
    return "direct-then-proxy";
  }

  async function proxyFetch(r, signal) {
    const headers = { "Content-Type": "application/json", Accept: "*/*", "X-Quillbox-AI": "1" };
    const tk = net.token();
    if (tk) headers.Authorization = "Bearer " + tk;
    const res = await net.fetch("/api/ai/proxy", { method: "POST", headers, signal, body: JSON.stringify({ url: r.url, method: r.method, headers: r.headers, body: r.body == null ? null : JSON.stringify(r.body) }) });
    const why = res.headers && res.headers.get && res.headers.get("X-Quillbox-Proxy-Error");
    if (why) throw new AiError(`The Quillbox relay refused the request: ${decodeURIComponent(why)}`, "proxy", res.status);
    return res;
  }

  /** Sends [r] by the right route. Resolves with a fetch Response and the route taken. */
  async function send(def, r, signal) {
    const route = routeFor(def, r.url);
    const direct = () => net.fetch(r.url, { method: r.method, headers: r.headers, body: r.body == null ? undefined : JSON.stringify(r.body), signal });
    try {
      if (route === "proxy") return { res: await proxyFetch(r, signal), route: "proxy" };
      try { return { res: await direct(), route: "direct" }; }
      catch (e) {
        if (e && e.name === "AbortError") throw e;
        if (route !== "direct-then-proxy") throw e;
        const res = await proxyFetch(r, signal);
        relayOnly.add(originOf(r.url));
        return { res, route: "proxy" };
      }
    } catch (e) {
      if (e && e.name === "AbortError") throw new AiError("Stopped", "aborted");
      if (e instanceof AiError) throw e;
      throw networkError(def, e, r.url);
    }
  }

  function networkError(def, e, url) {
    const why = String((e && e.message) || e);
    if (isLoopback(url) || /192\.168\.|\/\/10\./.test(url)) return new AiError(`Could not reach the local AI server at ${url}. Is it running? (${why})`, "network");
    return new AiError(`Could not reach ${def.name}: ${why}. Check the connection.`, "network");
  }

  /** Reads an SSE response, calling onData(data, event) per event. */
  async function readSse(res, onData) {
    const reader = res.body.getReader();
    const dec = new TextDecoder();
    let buf = "";
    const dispatch = (block) => { const ev = parseSseBlock(block); if (ev) onData(ev.data, ev.event); };
    try {
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        buf += dec.decode(value, { stream: true });
        const [blocks, rest] = takeSseBlocks(buf);
        buf = rest;
        blocks.forEach(dispatch);
      }
      buf += dec.decode();
      if (buf.trim()) dispatch(buf);
    } catch (e) {
      try { reader.cancel().catch(() => {}); } catch { /* already closed */ }
      if (e && e.name === "AbortError") throw new AiError("Stopped", "aborted");
      throw e;
    }
  }

  async function httpChat(rp, model, req) {
    const { def } = rp;
    const stream = !!req.onToken;
    let opts = { stream };
    for (let attempt = 0; attempt < 4; attempt++) {
      const r = buildChatRequest(rp, model, req, opts);
      const { res, route } = await send(def, r, req.signal);
      if (!res.ok) {
        const body = await res.text();
        const retry = res.status === 400 || res.status === 422 ? adjustForRetry(opts, body) : null;
        if (retry) { opts = retry; continue; }
        throw httpError(def, res.status, body, model);
      }
      const type = (res.headers.get("content-type") || "").toLowerCase();
      const meta = { model, provider: def.id, providerName: def.name, route, endpoint: r.url };
      if (stream && res.body && type.includes("event-stream")) {
        let full = "";
        const acc = {};
        await readSse(res, (data) => {
          const d = streamDelta(def.kind, data, acc, def.name);
          if (d) { full += d; req.onToken(d, full); }
        });
        return { ...meta, text: full, usage: acc.usage, truncated: cutShort(acc.finish) };
      }
      let j;
      const raw = await res.text();
      try { j = JSON.parse(raw); } catch { throw new AiError(`${def.name} returned an unexpected response.`); }
      const out = parseCompletion(def.kind, j, def.name);
      if (req.onToken) req.onToken(out.text, out.text);
      return { ...meta, text: out.text, usage: out.usage, truncated: cutShort(out.finish) };
    }
    throw new AiError(`${def.name} rejected the request parameters.`);
  }

  async function builtinChat(model, req) {
    const LM = root.LanguageModel;
    if (!LM) throw new AiError("This browser has no built-in AI model. It is available in desktop Chrome 148 or later on supported hardware; choose another provider.", "unsupported");
    const avail = LM.availability ? await LM.availability() : "available";
    if (avail === "unavailable") throw new AiError("The browser reports its built-in model is unavailable on this device.", "unsupported");
    const system = req.messages.filter((m) => m.role === "system").map((m) => textOf(m.content)).join("\n\n");
    const turns = req.messages.filter((m) => m.role !== "system");
    const last = turns.pop();
    const session = await LM.create({ initialPrompts: [...(system ? [{ role: "system", content: system }] : []), ...turns.map((t) => ({ role: t.role, content: textOf(t.content) }))], signal: req.signal });
    const meta = { model, provider: "builtin", providerName: "Built-in browser AI", route: "device" };
    try {
      const prompt = textOf(last ? last.content : "");
      if (req.onToken && session.promptStreaming) {
        let full = "";
        for await (const chunk of session.promptStreaming(prompt, { signal: req.signal })) {
          const c = String(chunk);
          // Older implementations stream the whole text so far; newer ones stream deltas.
          const delta = c.startsWith(full) ? c.slice(full.length) : c;
          full = c.startsWith(full) ? c : full + c;
          if (delta) req.onToken(delta, full);
        }
        return { ...meta, text: full };
      }
      const text = String(await session.prompt(prompt, { signal: req.signal }));
      if (req.onToken) req.onToken(text, text);
      return { ...meta, text };
    } catch (e) {
      if (e && e.name === "AbortError") throw new AiError("Stopped", "aborted");
      throw e;
    } finally { if (session.destroy) session.destroy(); }
  }

  // ------------------------------------------------------------------ public API
  /**
   * One chat request. req: {messages, tier ('fast'|'quality'), model, temperature, maxTokens,
   * signal, onToken(delta, full), provider}. Resolves {text, model, provider, providerName,
   * route, usage, truncated}.
   */
  async function chat(req) {
    const rp = await S.resolveProvider(req.provider);
    if (!rp) throw new AiError("No AI provider is set up yet.", "no-provider");
    const { def, cfg } = rp;
    const model = req.model || (req.tier === "fast" ? cfg.fastModel || cfg.model : cfg.model) || def.models.quality;
    if (def.needsKey && !rp.key) throw new AiError(`${def.name} has no API key. Open AI ▸ Set up AI to add one.`, "auth");
    if (req.signal && req.signal.aborted) throw new AiError("Stopped", "aborted");
    let res;
    if (def.kind === "builtin") res = await builtinChat(model, req);
    else if (def.kind === "local") {
      const L = root.QbAiLocal;
      if (!L) throw new AiError("The on-device model is not available here.", "unsupported");
      res = await L.localChat(model, req);
    } else res = await httpChat(rp, model, req);
    S.recordUsage((res.usage && res.usage.in) || 0, (res.usage && res.usage.out) || 0);
    // An answer that arrives after Stop is dropped.
    if (req.signal && req.signal.aborted) throw new AiError("Stopped", "aborted");
    return res;
  }

  /** Lists a provider's models (validating the key at the same time). */
  async function listModels(rp, signal) {
    const { def } = rp;
    if (def.kind === "local") return (root.QbAiLocal ? root.QbAiLocal.LOCAL_MODELS : []).map((m) => m.id);
    if (def.kind === "builtin") {
      const LM = root.LanguageModel;
      if (!LM) throw new AiError("This browser has no built-in AI model (desktop Chrome 148+ only).", "unsupported");
      if ((await LM.availability()) === "unavailable") throw new AiError("The built-in model is unavailable on this device.", "unsupported");
      return ["default"];
    }
    const r = buildModelsRequest(rp);
    const { res } = await send(def, r, signal);
    const text = await res.text();
    if (!res.ok) throw httpError(def, res.status, text, "");
    let j;
    try { j = JSON.parse(text); } catch { throw new AiError(`${def.name} returned an unexpected response.`); }
    return parseModelList(def, j);
  }

  return { AiError, buildChatRequest, buildModelsRequest, parseModelList, pickModels, takeSseBlocks, parseSseBlock, streamDelta, parseCompletion, adjustForRetry, httpError, routeFor, configure, chat, listModels };
});
