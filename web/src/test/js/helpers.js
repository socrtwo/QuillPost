// Shared fixtures for the AI module tests (node --test web/src/test/js).
"use strict";
const path = require("node:path");
const dir = path.join(__dirname, "../../main/resources/web/ai");
const load = (name) => require(path.join(dir, name));

/** A localStorage stand-in that records everything written. */
function memoryStorage() {
  const m = new Map();
  return { getItem: (k) => (m.has(k) ? m.get(k) : null), setItem: (k, v) => m.set(k, String(v)), removeItem: (k) => m.delete(k), dump: () => [...m.values()].join("\n"), _m: m };
}

/** A fetch Response whose body streams [chunks] as server-sent events. */
function sseResponse(chunks, { status = 200, type = "text/event-stream", delay = 0, signal = null } = {}) {
  const enc = new TextEncoder();
  let i = 0;
  const body = new ReadableStream({
    start(ctl) {
      // Like fetch: aborting the request errors the body stream with an AbortError.
      if (signal) signal.addEventListener("abort", () => { try { ctl.error(new DOMException("The operation was aborted.", "AbortError")); } catch {} });
    },
    async pull(ctl) {
      if (delay) await new Promise((r) => setTimeout(r, delay));
      if (i < chunks.length) ctl.enqueue(enc.encode(chunks[i++]));
      else ctl.close();
    },
  });
  return new Response(body, { status, headers: { "content-type": type } });
}

module.exports = { load, memoryStorage, sseResponse };
