/* Anne's Post AI — the on-device model ("Anne's Post Local AI"): a small open instruction model run
 * with Transformers.js in a module Web Worker. WebGPU is used when the device offers it,
 * otherwise WebAssembly on the CPU.
 *
 * The library (pinned version) and the model weights are downloaded on first use from jsDelivr
 * and the Hugging Face Hub, then served from the browser cache. Nothing you read or write is
 * sent anywhere: prompts never leave the worker.
 */
(function (root) {
  "use strict";

  const LOCAL_MODELS = [
    { id: "onnx-community/Qwen2.5-0.5B-Instruct", label: "Qwen2.5 0.5B Instruct (recommended)", size: "≈ 480–510 MB", note: "Best quality for its size; handles many languages." },
    { id: "HuggingFaceTB/SmolLM2-360M-Instruct", label: "SmolLM2 360M Instruct (smallest download)", size: "≈ 270–365 MB", note: "Fastest on older phones; English-centric." },
    { id: "onnx-community/gemma-3-270m-it-ONNX", label: "Gemma 3 270M IT", size: "≈ 270–545 MB", note: "Very small; simple rewrites and summaries." },
    { id: "onnx-community/Llama-3.2-1B-Instruct-q4f16", label: "Llama 3.2 1B Instruct (needs WebGPU)", size: "≈ 1.24 GB", note: "Noticeably better writing; only for devices with WebGPU and plenty of storage." },
  ];

  const TRANSFORMERS_URL = "https://cdn.jsdelivr.net/npm/@huggingface/transformers@4.2.0";

  const WORKER_SRC = `
import { pipeline, TextStreamer, InterruptableStoppingCriteria, env } from '${TRANSFORMERS_URL}';
env.allowLocalModels = false;
let gen = null, loaded = '', info = null;
const stopper = new InterruptableStoppingCriteria();
async function load(model) {
  if (gen && loaded === model) return info;
  let device = 'wasm', dtype = 'q8';
  try {
    if (self.navigator && navigator.gpu) {
      const adapter = await navigator.gpu.requestAdapter();
      if (adapter) { device = 'webgpu'; dtype = adapter.features && adapter.features.has('shader-f16') ? 'q4f16' : 'q4'; }
    }
  } catch (e) {}
  if (/q4f16$/.test(model)) dtype = 'q4f16';
  const progress = (p) => self.postMessage({ type: 'progress', p: { status: p.status, file: p.file, loaded: p.loaded, total: p.total, progress: p.progress } });
  try {
    gen = await pipeline('text-generation', model, { device, dtype, progress_callback: progress });
  } catch (e) {
    if (device !== 'webgpu') throw e;
    device = 'wasm'; dtype = 'q8';
    gen = await pipeline('text-generation', model, { device, dtype, progress_callback: progress });
  }
  loaded = model;
  info = { device, dtype };
  return info;
}
self.onmessage = async (e) => {
  const m = e.data;
  try {
    if (m.type === 'load') { const i = await load(m.model); self.postMessage({ type: 'ready', id: m.id, info: i }); return; }
    if (m.type === 'stop') { stopper.interrupt(); return; }
    if (m.type === 'generate') {
      await load(m.model);
      stopper.reset();
      const streamer = new TextStreamer(gen.tokenizer, { skip_prompt: true, skip_special_tokens: true, callback_function: (t) => self.postMessage({ type: 'token', id: m.id, text: t }) });
      const out = await gen(m.messages, { max_new_tokens: m.maxTokens || 512, do_sample: m.temperature > 0, temperature: m.temperature || undefined, repetition_penalty: 1.1, streamer, stopping_criteria: stopper });
      const g = out[0].generated_text;
      const text = Array.isArray(g) ? (g[g.length - 1].content || '') : String(g);
      self.postMessage({ type: 'done', id: m.id, text });
    }
  } catch (err) {
    self.postMessage({ type: 'error', id: m.id, message: String((err && err.message) || err) });
  }
};
`;

  let worker = null;
  let seq = 0;
  const pending = new Map();
  const progressListeners = new Set();
  const AiError = () => (root.QbAiClient ? root.QbAiClient.AiError : Error);

  function localError(m) {
    if (/fetch|network|Failed to load|import/i.test(m)) return `The on-device model needs an internet connection the first time, to download the AI library and model files (${m}).`;
    if (/memory|allocation|OOM/i.test(m)) return `This device ran out of memory loading the model. Choose a smaller on-device model in AI ▸ Set up AI (${m}).`;
    return `On-device model: ${m}`;
  }

  function getWorker() {
    if (worker) return worker;
    const url = URL.createObjectURL(new Blob([WORKER_SRC], { type: "text/javascript" }));
    worker = new Worker(url, { type: "module" });
    worker.onmessage = (e) => {
      const m = e.data;
      if (m.type === "progress") { progressListeners.forEach((f) => f(m.p)); return; }
      const p = pending.get(m.id);
      if (!p) return;
      if (m.type === "token") p.onToken && p.onToken(m.text);
      else if (m.type === "ready") { pending.delete(m.id); p.resolve(m.info); }
      else if (m.type === "done") { pending.delete(m.id); p.resolve(m.text); }
      else if (m.type === "error") { pending.delete(m.id); const E = AiError(); p.reject(new E(localError(m.message), "other")); }
    };
    worker.onerror = (e) => {
      const E = AiError();
      const msg = localError(e.message || "The on-device model could not start");
      pending.forEach((p) => p.reject(new E(msg, "network")));
      pending.clear();
      if (worker) worker.terminate();
      worker = null;
    };
    return worker;
  }

  function onLocalProgress(f) { progressListeners.add(f); return () => progressListeners.delete(f); }

  /** Download (if needed) and load a model. Resolves with the backend used. */
  function loadLocalModel(model) {
    const id = ++seq;
    return new Promise((resolve, reject) => {
      pending.set(id, { resolve, reject });
      getWorker().postMessage({ type: "load", id, model });
    });
  }

  async function localChat(model, req) {
    const id = ++seq;
    const messages = req.messages.map((m) => ({ role: m.role, content: typeof m.content === "string" ? m.content : "" }));
    let full = "";
    const w = getWorker();
    const onAbort = () => w.postMessage({ type: "stop" });
    if (req.signal) req.signal.addEventListener("abort", onAbort);
    try {
      const text = await new Promise((resolve, reject) => {
        pending.set(id, { resolve, reject, onToken: (t) => { full += t; req.onToken && req.onToken(t, full); } });
        w.postMessage({ type: "generate", id, model, messages, maxTokens: Math.min(req.maxTokens || 768, 2048), temperature: req.temperature ?? 0 });
      });
      if (req.signal && req.signal.aborted) { const E = AiError(); throw new E("Stopped", "aborted"); }
      return { text: text || full, model, provider: "device", providerName: "On-device model", route: "device" };
    } finally {
      if (req.signal) req.signal.removeEventListener("abort", onAbort);
    }
  }

  /** Approximate storage used by this site (Cache Storage holds the model files), if the browser reports it. */
  async function localStorageEstimate() {
    try {
      const e = navigator.storage && navigator.storage.estimate ? await navigator.storage.estimate() : null;
      if (e && e.usage) return `${(e.usage / 1048576).toFixed(0)} MB used by Anne\'s Post in this browser's storage.`;
    } catch { /* unsupported */ }
    return "";
  }

  /** Removes downloaded model files from the browser cache. */
  async function clearLocalModels() {
    let n = 0;
    try { for (const k of await caches.keys()) if (/transformers/i.test(k)) { await caches.delete(k); n++; } } catch { /* unsupported */ }
    if (worker) worker.terminate();
    worker = null;
    return n;
  }

  root.QbAiLocal = { LOCAL_MODELS, loadLocalModel, localChat, onLocalProgress, localStorageEstimate, clearLocalModels };
})(typeof globalThis !== "undefined" ? globalThis : this);
