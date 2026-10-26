/* Anne's Post AI — settings and API-key storage.
 *
 * Settings (which provider is active, models, options, saved prompts, usage) live in this
 * browser's localStorage under "qb.ai.v1". API keys never do. They go to a separate key store:
 *   - Android app: the QuillPostAndroid bridge, which encrypts them with AES-GCM under a key held
 *     in the Android Keystore and keeps only the ciphertext in private SharedPreferences;
 *   - desktop app: the Anne's Post backend on this computer, which writes them to its private data
 *     folder (owner-only file permissions; not encrypted — the desktop launcher has no OS
 *     keychain bridge);
 *   - a plain browser (self-hosted web, iOS app): IndexedDB for this site, not encrypted at rest.
 * Nothing in exportSettings(), drafts or the account settings stored on the server ever holds a key.
 */
(function (root, factory) {
  const mod = factory(root);
  if (typeof module === "object" && module.exports) module.exports = mod;
  else root.QbAiStore = mod;
})(typeof globalThis !== "undefined" ? globalThis : this, function (root) {
  "use strict";
  const P = root.QbAiProviders || (typeof require === "function" ? require("./providers.js") : null);

  const SETTINGS_KEY = "qb.ai.v1";
  const KEY_PREFIX = "ai.key.";

  function defaultSettings() {
    return {
      enabled: true, active: null, providers: {},
      /** Language for generated text ("auto" follows the input). */
      language: "auto",
      /** Show the exact text and ask before every request to a cloud service. */
      confirmEachSend: false,
      /** Ask about this mailbox: include message previews (first lines), not only headers. */
      mailboxPreviews: false,
      /** Ask about this mailbox: at most this many messages of the folder are described. */
      mailboxLimit: 100,
      /** Network route for cloud requests: auto (direct, else through the Anne's Post backend), direct, proxy. */
      network: "auto",
      prompts: [],
      usage: { requests: 0, tokensIn: 0, tokensOut: 0, since: Date.now() },
      /** Providers whose "data goes to this service" notice the user has accepted. */
      privacyAck: [],
      /** Several AIs on one request (AI settings ▸ Use several AIs; see ensemble.js). Never holds keys. */
      ensemble: defaultEnsemble(),
    };
  }

  function defaultEnsemble() {
    return { mode: "single", members: [], master: null, maxRounds: 3, showDeliberation: true, memberTimeoutMs: 90000 };
  }

  /** A valid ensemble setting from whatever was stored or imported (provider ids and models only). */
  function normalizeEnsemble(e) {
    const d = defaultEnsemble();
    if (!e || typeof e !== "object") return d;
    const members = [];
    for (const m of Array.isArray(e.members) ? e.members : []) {
      if (!m || typeof m.provider !== "string" || !P.providerById(m.provider) || members.some((x) => x.provider === m.provider)) continue;
      members.push(typeof m.model === "string" && m.model ? { provider: m.provider, model: m.model } : { provider: m.provider });
    }
    const rounds = Math.round(Number(e.maxRounds));
    const timeout = Math.round(Number(e.memberTimeoutMs));
    return {
      mode: ["single", "master", "consensus"].includes(e.mode) ? e.mode : d.mode,
      members: members.slice(0, 6),
      master: typeof e.master === "string" && P.providerById(e.master) ? e.master : null,
      maxRounds: rounds >= 1 && rounds <= 5 ? rounds : d.maxRounds,
      showDeliberation: e.showDeliberation !== false,
      memberTimeoutMs: timeout >= 5000 && timeout <= 600000 ? timeout : d.memberTimeoutMs,
    };
  }

  // ------------------------------------------------------------------ settings
  let storage = (() => { try { return root.localStorage || null; } catch { return null; } })();
  let cache = null;
  const listeners = new Set();

  /** Tests and embedders can swap the storage (anything with getItem/setItem). */
  function setStorage(s) { storage = s; cache = null; }

  function settings() {
    if (cache) return cache;
    let s = defaultSettings();
    try { const raw = storage && storage.getItem(SETTINGS_KEY); if (raw) s = { ...s, ...JSON.parse(raw) }; } catch { /* corrupt or unavailable: defaults */ }
    if (!s.providers || typeof s.providers !== "object") s.providers = {};
    if (!Array.isArray(s.prompts)) s.prompts = [];
    s.prompts = s.prompts.filter((p) => p && typeof p.name === "string" && typeof p.prompt === "string");
    if (!Array.isArray(s.privacyAck)) s.privacyAck = [];
    if (!s.usage || typeof s.usage !== "object") s.usage = defaultSettings().usage;
    s.ensemble = normalizeEnsemble(s.ensemble);
    // Never keep anything key-shaped in settings, even if an older version or an import put it there.
    for (const cfg of Object.values(s.providers)) if (cfg && typeof cfg === "object") { delete cfg.key; delete cfg.apiKey; }
    cache = s;
    return s;
  }

  function saveSettings(mut) {
    const s = settings();
    if (mut) mut(s);
    s.ensemble = normalizeEnsemble(s.ensemble);
    for (const cfg of Object.values(s.providers)) if (cfg && typeof cfg === "object") { delete cfg.key; delete cfg.apiKey; }
    try { storage && storage.setItem(SETTINGS_KEY, JSON.stringify(s)); } catch { /* quota */ }
    listeners.forEach((f) => { try { f(); } catch (e) { console.error(e); } });
  }

  function onChange(f) { listeners.add(f); return () => listeners.delete(f); }
  function reload() { cache = null; listeners.forEach((f) => { try { f(); } catch {} }); }

  /** Settings as a portable JSON object (for "Export AI settings"): providers, models, options, prompts — never keys. */
  function exportSettings() {
    const s = JSON.parse(JSON.stringify(settings()));
    const providers = {};
    for (const [id, cfg] of Object.entries(s.providers)) {
      const { key, apiKey, models, ...rest } = cfg || {};
      providers[id] = rest;
    }
    return { format: "quillpost-ai-settings", version: 1, exported: new Date().toISOString(), settings: { ...s, providers, usage: undefined, privacyAck: [] } };
  }

  function importSettings(obj) {
    const src = obj && obj.format === "quillpost-ai-settings" ? obj.settings : null;
    if (!src || typeof src !== "object") throw new Error("This is not an Anne\'s Post AI settings file.");
    saveSettings((s) => {
      for (const k of ["enabled", "language", "confirmEachSend", "mailboxPreviews", "mailboxLimit", "network"]) if (k in src) s[k] = src[k];
      if (src.providers && typeof src.providers === "object") for (const [id, cfg] of Object.entries(src.providers)) if (P.providerById(id) && cfg && typeof cfg === "object") s.providers[id] = { ...cfg, id };
      if (Array.isArray(src.prompts)) for (const p of src.prompts) if (p && typeof p.name === "string" && typeof p.prompt === "string" && !s.prompts.some((q) => q.name === p.name)) s.prompts.push({ ...p, id: p.id || "p" + Math.random().toString(36).slice(2, 9) });
      if (src.active && s.providers[src.active]) s.active = src.active;
      if (src.ensemble && typeof src.ensemble === "object") s.ensemble = normalizeEnsemble(src.ensemble);
    });
  }

  // ------------------------------------------------------------------ key store backends
  /** In-memory store (tests). */
  function memoryBackend() {
    const m = new Map();
    return { kind: "memory", label: "memory only (not saved)", encrypted: false,
      get: async (n) => (m.has(n) ? m.get(n) : null), set: async (n, v) => { m.set(n, v); }, remove: async (n) => { m.delete(n); }, _map: m };
  }

  /** Android: QuillPostAndroid.secureGet/secureSet/secureRemove (Keystore-encrypted). */
  function androidBackend(bridge) {
    return { kind: "android-keystore", label: "this phone's app storage, encrypted with a key held in the Android Keystore", encrypted: true,
      get: async (n) => { const v = bridge.secureGet(n); return v == null || v === "" ? null : String(v); },
      set: async (n, v) => { if (bridge.secureSet(n, v) === false) throw new Error("The Android key store refused to save the key."); },
      remove: async (n) => { bridge.secureRemove(n); } };
  }

  /** Desktop app: the local Anne's Post backend keeps keys in its private data folder. */
  function desktopBackend(fetchFn, tokenFn) {
    const call = async (method, n, value) => {
      const headers = { Accept: "application/json", "X-QuillPost-AI": "1" };
      const tk = tokenFn && tokenFn();
      if (tk) headers.Authorization = "Bearer " + tk;
      if (value !== undefined) headers["Content-Type"] = "application/json";
      const res = await fetchFn("/api/ai/keys/" + encodeURIComponent(n), { method, headers, body: value !== undefined ? JSON.stringify({ value }) : undefined });
      if (res.status === 404 && method === "GET") return null;
      if (!res.ok) throw new Error("The Anne\'s Post key store answered " + res.status);
      return method === "GET" ? ((await res.json()).value || null) : null;
    };
    return { kind: "desktop-file", label: "the Anne\'s Post data folder on this computer (readable only by your user account; not encrypted)", encrypted: false,
      get: (n) => call("GET", n), set: (n, v) => call("PUT", n, v), remove: (n) => call("DELETE", n) };
  }

  /** Plain browser: IndexedDB for this site (not encrypted at rest). */
  function indexedDbBackend(idb) {
    let dbp = null;
    const db = () => dbp || (dbp = new Promise((resolve, reject) => {
      const r = idb.open("quillpost-ai", 1);
      r.onupgradeneeded = () => r.result.createObjectStore("keys");
      r.onsuccess = () => resolve(r.result);
      r.onerror = () => reject(r.error);
    }));
    const tx = async (mode, fn) => { const d = await db(); return new Promise((resolve, reject) => { const t = d.transaction("keys", mode); const req = fn(t.objectStore("keys")); t.oncomplete = () => resolve(req && req.result); t.onerror = () => reject(t.error); }); };
    return { kind: "indexeddb", label: "this browser's storage for this site (IndexedDB; not encrypted)", encrypted: false,
      get: async (n) => (await tx("readonly", (s) => s.get(n))) ?? null,
      set: (n, v) => tx("readwrite", (s) => s.put(v, n)),
      remove: (n) => tx("readwrite", (s) => s.delete(n)) };
  }

  function unavailableBackend(why) {
    return { kind: "unavailable", label: "unavailable", encrypted: false, get: async () => null, set: async () => { throw new Error(why); }, remove: async () => {} };
  }

  let backend = null;
  const keyCache = new Map();

  /** Picks the key store for this platform. The Android app never falls back to web storage. */
  function detectBackend() {
    const bridge = root.QuillPostAndroid;
    if (bridge) {
      if (typeof bridge.secureGet === "function" && typeof bridge.secureSet === "function") return androidBackend(bridge);
      return unavailableBackend("This version of the Android app cannot store API keys securely. Update Anne\'s Post, or use the on-device model or a local server that needs no key.");
    }
    try { if (root.indexedDB) return indexedDbBackend(root.indexedDB); } catch { /* blocked */ }
    return unavailableBackend("This browser offers no storage for API keys.");
  }

  function keyBackend() { return backend || (backend = detectBackend()); }
  /** Selects a backend explicitly (desktop app after /api/ai/info; tests). */
  function setKeyBackend(b) { backend = b; keyCache.clear(); }

  async function getApiKey(providerId) {
    if (keyCache.has(providerId)) return keyCache.get(providerId);
    let k = "";
    try { k = (await keyBackend().get(KEY_PREFIX + providerId)) || ""; } catch { k = ""; }
    keyCache.set(providerId, k);
    return k;
  }
  async function setApiKey(providerId, key) {
    await keyBackend().set(KEY_PREFIX + providerId, key);
    keyCache.set(providerId, key);
  }
  async function deleteApiKey(providerId) {
    keyCache.delete(providerId);
    await keyBackend().remove(KEY_PREFIX + providerId);
  }
  function maskKey(k) {
    if (!k) return "";
    return k.length <= 12 ? "•".repeat(k.length) : `${k.slice(0, 6)}…${k.slice(-4)}`;
  }

  // ------------------------------------------------------------------ resolved configuration
  function resolveBaseUrl(def, cfg) {
    let url = String((cfg && cfg.baseUrl) || def.baseUrl || "").trim();
    url = url.replace("{accountId}", encodeURIComponent((cfg && cfg.accountId) || ""))
      .replace("{resource}", encodeURIComponent((cfg && cfg.resource) || ""))
      .replace("{region}", encodeURIComponent((cfg && cfg.region) || "us-east-1"));
    return url.replace(/\/+$/, "");
  }

  async function resolveProvider(id) {
    const s = settings();
    const pid = id || s.active;
    if (!pid) return null;
    const def = P.providerById(pid);
    const cfg = s.providers[pid];
    if (!def || !cfg) return null;
    const key = def.needsKey || def.optionalKey ? await getApiKey(pid) : "";
    return { def, cfg, key, baseUrl: resolveBaseUrl(def, cfg) };
  }

  function activeProviderDef() { const s = settings(); return s.active ? P.providerById(s.active) : null; }
  function isCloud() { const p = activeProviderDef(); return !!p && !P.isOnDevice(p); }

  function recordUsage(tokensIn, tokensOut) {
    saveSettings((s) => { s.usage.requests++; s.usage.tokensIn += tokensIn || 0; s.usage.tokensOut += tokensOut || 0; });
  }

  return {
    SETTINGS_KEY, KEY_PREFIX, defaultSettings, defaultEnsemble, normalizeEnsemble, setStorage, settings, saveSettings, onChange, reload, exportSettings, importSettings,
    memoryBackend, androidBackend, desktopBackend, indexedDbBackend, keyBackend, setKeyBackend,
    getApiKey, setApiKey, deleteApiKey, maskKey, resolveBaseUrl, resolveProvider, activeProviderDef, isCloud, recordUsage,
  };
});
