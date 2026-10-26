// AI settings and key storage: keys never reach settings, localStorage or the settings export.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const { load, memoryStorage } = require("./helpers.js");

const P = load("providers.js");
const S = load("store.js");

const KEY = "sk-proj-THISISASECRETKEYVALUE0123456789abcdefghijklmnopqrstu";

test("API keys go to the key store, never into settings, localStorage or the export", async () => {
  const storage = memoryStorage();
  S.setStorage(storage);
  const keys = S.memoryBackend();
  S.setKeyBackend(keys);
  S.saveSettings((s) => { s.providers.openai = { id: "openai", model: "m", fastModel: "f" }; s.active = "openai"; s.prompts.push({ id: "p1", name: "Mine", prompt: "Do it", input: "message", output: "pane" }); });
  await S.setApiKey("openai", KEY);

  assert.equal(await S.getApiKey("openai"), KEY);
  assert.equal(keys._map.get("ai.key.openai"), KEY);
  assert.ok(!storage.dump().includes(KEY), "localStorage holds no key");
  const exported = JSON.stringify(S.exportSettings());
  assert.ok(!exported.includes(KEY), "export holds no key");
  assert.ok(!/sk-proj-/.test(exported));
  const ex = S.exportSettings();
  assert.equal(ex.format, "quillpost-ai-settings");
  assert.equal(ex.settings.providers.openai.model, "m");
  assert.equal(ex.settings.prompts[0].name, "Mine");
  assert.equal(ex.settings.usage, undefined);

  // The resolved provider carries the key for the request only.
  const rp = await S.resolveProvider();
  assert.equal(rp.key, KEY);
  assert.equal(rp.baseUrl, "https://api.openai.com/v1");
});

test("key fields smuggled into settings or an import are dropped", () => {
  const storage = memoryStorage();
  storage.setItem(S.SETTINGS_KEY, JSON.stringify({ active: "groq", providers: { groq: { id: "groq", model: "m", key: "gsk_leak", apiKey: "gsk_leak2" } } }));
  S.setStorage(storage);
  assert.equal(S.settings().providers.groq.key, undefined);
  assert.equal(S.settings().providers.groq.apiKey, undefined);
  S.importSettings({ format: "quillpost-ai-settings", settings: { providers: { mistral: { model: "x", key: "leak3" }, bogus: { model: "y" } }, prompts: [{ name: "P", prompt: "Q" }] } });
  assert.ok(!storage.dump().includes("leak"), "nothing key-like persisted");
  assert.equal(S.settings().providers.mistral.model, "x");
  assert.equal(S.settings().providers.bogus, undefined, "unknown providers ignored");
  assert.throws(() => S.importSettings({ hello: 1 }), /not an Anne\'s Post AI settings file/);
});

test("Android bridge backend: encrypted store is used, never web storage", async () => {
  const stored = new Map();
  const bridge = { secureGet: (n) => stored.get(n) || "", secureSet: (n, v) => { stored.set(n, v); return true; }, secureRemove: (n) => stored.delete(n) };
  const b = S.androidBackend(bridge);
  assert.equal(b.kind, "android-keystore");
  assert.equal(b.encrypted, true);
  S.setKeyBackend(b);
  await S.setApiKey("groq", "gsk_x");
  assert.equal(stored.get("ai.key.groq"), "gsk_x");
  assert.equal(await S.getApiKey("groq"), "gsk_x");
  S.setKeyBackend(b); // clears the cache
  assert.equal(await S.getApiKey("mistral"), "");
  await S.deleteApiKey("groq");
  assert.equal(stored.has("ai.key.groq"), false);
  const refusing = S.androidBackend({ ...bridge, secureSet: () => false });
  S.setKeyBackend(refusing);
  await assert.rejects(S.setApiKey("groq", "k"), /refused/);
});

test("an Android app without the secure bridge refuses to store keys instead of falling back", async () => {
  // Load a fresh copy of the module with a fake window that has the old bridge only.
  const path = require.resolve("../../main/resources/web/ai/store.js");
  delete require.cache[path];
  const g = globalThis;
  g.QuillPostAndroid = { platform: () => "android" };
  g.indexedDB = { open() { throw new Error("must not be used"); } };
  try {
    const S2 = require(path);
    S2.setStorage(memoryStorage());
    assert.equal(S2.keyBackend().kind, "unavailable");
    await assert.rejects(S2.setApiKey("openai", KEY), /cannot store API keys securely/);
  } finally {
    delete g.QuillPostAndroid; delete g.indexedDB;
    delete require.cache[path];
  }
});

test("desktop backend talks to the local key endpoint with the session token", async () => {
  const calls = [];
  const fetchFn = async (url, init) => { calls.push({ url, init }); return url.endsWith("missing") ? new Response("", { status: 404 }) : new Response(JSON.stringify({ value: "k1" }), { status: 200 }); };
  const b = S.desktopBackend(fetchFn, () => "tok");
  assert.equal(await b.get("ai.key.groq"), "k1");
  assert.equal(calls[0].url, "/api/ai/keys/ai.key.groq");
  assert.equal(calls[0].init.headers.Authorization, "Bearer tok");
  assert.equal(calls[0].init.headers["X-QuillPost-AI"], "1");
  await b.set("ai.key.groq", "v2");
  assert.equal(calls[1].init.method, "PUT");
  assert.deepEqual(JSON.parse(calls[1].init.body), { value: "v2" });
  assert.equal(await b.get("missing"), null);
});

test("maskKey and detectKey", () => {
  assert.equal(S.maskKey("short"), "•••••");
  assert.equal(S.maskKey(KEY), "sk-pro…rstu");
  assert.deepEqual(P.detectKey("my key: gsk_" + "a".repeat(52)), { provider: "groq", key: "gsk_" + "a".repeat(52) });
  assert.equal(P.detectKey("a".repeat(32)), null, "generic tokens only for the provider being set up");
  assert.equal(P.detectKey("a".repeat(32), "mistral").provider, "mistral");
  assert.equal(P.detectKey("sk-ant-api03-" + "x".repeat(90), "mistral").provider, "anthropic");
});

test("every provider is in a wizard section and has what the wizard needs", () => {
  const inSections = new Set(P.SECTIONS.flatMap((s) => s.ids));
  for (const p of P.PROVIDERS) {
    assert.ok(inSections.has(p.id), p.id + " is listed");
    assert.ok(p.links && p.links.docs, p.id + " has docs");
    assert.ok(P.FREE_LABEL[p.free], p.id + " free label");
    assert.ok(["openai", "anthropic", "gemini", "local", "builtin"].includes(p.kind));
  }
  assert.equal(inSections.size, P.PROVIDERS.length);
});
