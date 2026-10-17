/* Quillbox AI — setup wizard (providers grouped by cost, key test, live model list), the
 * Providers & models manager, AI settings and the AI & Privacy page.
 *
 * The wizard opens the provider's sign-up or key page in the browser; the key comes back by
 * pasting it (Paste reads the clipboard once, on request, and recognises the key by its
 * format). Test & save lists the provider's models with the key before anything is stored.
 */
(function () {
  "use strict";
  const C = window.QbAiClient, S = window.QbAiStore, P = window.QbAiProviders, PR = window.QbAiPrompts;
  const qbAi = (window.qbAi = window.qbAi || {});

  /** Lets the Quillbox backend relay reach a custom or local server address (it refuses unknown hosts). */
  async function registerEndpoint(def, cfg) {
    const base = S.resolveBaseUrl(def, cfg);
    if (!base || base === def.baseUrl) return;
    try { await api("/api/ai/endpoints", { method: "POST", body: { baseUrl: base }, }); } catch (e) { console.warn("AI relay endpoint not registered:", e.message); }
  }

  // ------------------------------------------------------------------ setup wizard
  /** Opens the wizard. Resolves true when a provider is set up. */
  async function setup({ provider = null, reason = "" } = {}) {
    let configured = false;
    const root = el("div", { class: "ai-wizard" });
    let cleanup = [];
    const reset = () => { cleanup.forEach((f) => { try { f(); } catch {} }); cleanup = []; };

    const badge = (p) => el("span", { class: `ai-badge ai-${p.free}`, text: P.FREE_LABEL[p.free] });

    const showChooser = () => {
      reset(); root.innerHTML = "";
      if (reason) root.appendChild(el("p", { class: "ai-reason", text: reason }));
      root.appendChild(el("p", { class: "small muted", text: "Choose where Quillbox's AI features run. You can add several and switch at any time (AI ▸ Active AI). Nothing is sent anywhere until you run an AI command." }));
      const s = S.settings();
      for (const sec of P.SECTIONS) {
        root.append(el("h4", { text: sec.title }), el("p", { class: "small muted", text: sec.help }));
        const grid = el("div", { class: "ai-cards" });
        for (const id of sec.ids) {
          const p = P.providerById(id);
          const have = !!s.providers[id];
          grid.appendChild(el("button", { class: "ai-card" + (have ? " on" : ""), "data-provider": id, onclick: () => showProvider(p) },
            el("div", { class: "ai-card-head" }, el("b", { text: p.name }), badge(p)),
            el("div", { class: "small muted", text: p.freeNote }),
            have ? el("div", { class: "ai-set" }, icon("check-circle", 14), s.active === id ? " Active" : " Set up") : null));
        }
        root.appendChild(grid);
      }
    };

    const showProvider = (p) => {
      reset(); root.innerHTML = "";
      root.appendChild(el("button", { class: "link", onclick: showChooser }, "‹ All providers"));
      root.append(el("h4", {}, p.name, " ", badge(p)), el("p", { class: "small muted", text: p.freeNote }));
      if (p.notes) root.appendChild(el("p", { class: "ai-warn", text: p.notes }));
      if (p.kind === "local") return showDevice(p);
      if (p.kind === "builtin") return showBuiltin(p);
      showKeyed(p);
    };

    // ---------------------------------------------------------------- key-based providers
    const showKeyed = (p) => {
      const existing = S.settings().providers[p.id];
      const status = el("div", { class: "ai-status", role: "status" });
      const say = (msg, kind = "info") => { status.className = `ai-status ai-${kind}`; status.textContent = msg; };
      const fieldEls = {};
      const actions = el("div", { class: "btn-row ai-actions" });
      const open = (url, msg) => { if (!url) return; openExternal(url); say(msg); };
      if (p.links.download) actions.appendChild(el("button", { class: "btn", onclick: () => open(p.links.download, `Install ${p.name}, start it, then come back and press Test & save.`) }, icon("download"), "Download"));
      if (p.needsKey && p.links.keys) {
        if (p.free === "free" || p.free === "trial") actions.appendChild(el("button", { class: "btn", onclick: () => open(p.links.keys || p.links.signup, "Create a free account if asked, create an API key and copy it. Then come back and paste it below.") }, icon("ai-key"), "Create a free key"));
        actions.appendChild(el("button", { class: "btn", onclick: () => open(p.links.billing || p.links.keys, "Add a payment method or credits, then create an API key, copy it and paste it below.") }, icon("external"), p.free === "paid" ? "Create a paid key" : "Upgrade to paid"));
        actions.appendChild(el("button", { class: "btn", onclick: () => open(p.links.keys, p.keyViewable ? "Copy your existing key from the list, then paste it below." : `${p.name} shows each key only once, when it is created. If you no longer have it, create a new key, copy it and paste it below.`) }, icon("search"), "Find my key"));
      }
      actions.appendChild(el("button", { class: "link", onclick: () => openExternal(p.links.docs) }, "Documentation"));
      root.appendChild(actions);

      for (const f of p.fields || []) {
        const input = el("input", { type: "text", placeholder: f.placeholder, value: (existing && existing[f.key]) || (f.key === "baseUrl" ? p.baseUrl : ""), "data-field": f.key });
        fieldEls[f.key] = input;
        root.appendChild(el("div", { class: "field" }, el("label", { text: f.label }), input, f.help ? el("span", { class: "hint", text: f.help }) : null));
      }

      const keyInput = el("input", { type: "password", autocomplete: "off", spellcheck: "false", placeholder: p.needsKey ? "Paste the API key here" : "API key (optional)", "data-field": "key" });
      if (p.needsKey || p.optionalKey) {
        root.appendChild(el("div", { class: "field" }, el("label", { text: p.keyLabel || "API key" }),
          el("div", { class: "ai-row" }, keyInput,
            el("button", { class: "icon-btn", title: "Show or hide", onclick: () => { keyInput.type = keyInput.type === "password" ? "text" : "password"; } }, icon("eye")),
            el("button", { class: "btn", onclick: () => fromClipboard() }, icon("copy"), "Paste")),
          el("span", { class: "hint", text: `Stored in ${S.keyBackend().label}. Never in settings, drafts or exports.` })));
        S.getApiKey(p.id).then((k) => { if (k) keyInput.placeholder = `Saved key ${S.maskKey(k)} (leave empty to keep it)`; });
      }
      const makeActive = el("input", { type: "checkbox" });
      makeActive.checked = !S.settings().active || S.settings().active === p.id;
      root.appendChild(el("label", { class: "check" }, makeActive, el("span", { text: "Use this provider for AI commands" })));
      root.append(el("div", { class: "btn-row" }, el("button", { class: "btn primary", "data-action": "test-save", onclick: () => testAndSave() }, icon("check-circle"), "Test & save")), status);

      const fromClipboard = async () => {
        let text = null;
        try { text = await navigator.clipboard.readText(); } catch { /* not allowed */ }
        if (text == null) { say("The clipboard cannot be read here. Long-press (or right-click) the key field and choose Paste.", "err"); return; }
        const hit = P.detectKey(text, p.id);
        if (hit && hit.provider === p.id) { keyInput.value = hit.key; say(`Found a ${p.name} key (${S.maskKey(hit.key)}). Testing…`); await testAndSave(); }
        else if (hit) { const other = P.providerById(hit.provider); say(`The clipboard holds what looks like a ${other.name} key, not a ${p.name} key.`, "err"); status.append(" ", el("button", { class: "link", onclick: () => showProvider(other) }, `Set up ${other.name} instead`)); }
        else if (text.trim()) { keyInput.value = text.trim(); say("Pasted. Press Test & save."); }
        else say("The clipboard is empty.", "err");
      };

      const cfgFromFields = () => {
        const cfg = { ...(existing || { id: p.id, model: p.models.quality, fastModel: p.models.fast }) };
        for (const f of p.fields || []) cfg[f.key] = fieldEls[f.key].value.trim() || undefined;
        return cfg;
      };

      const testAndSave = async () => {
        const cfg = cfgFromFields();
        const key = keyInput.value.trim() || (await S.getApiKey(p.id));
        if (p.needsKey && !key) { say("Enter or paste the API key first.", "err"); return; }
        if (p.id === "custom" && !cfg.baseUrl) { say("Enter the base URL of the service.", "err"); return; }
        const base = S.resolveBaseUrl(p, cfg);
        if (!/^https?:\/\//i.test(base)) { say("The address must start with https:// (or http:// for a server on this computer).", "err"); return; }
        say("Contacting " + qbAi.hostOf(base) + "…");
        await registerEndpoint(p, cfg);
        try {
          const models = await C.listModels({ def: p, cfg, key, baseUrl: base });
          const picked = C.pickModels(p, models);
          cfg.models = models.slice(0, 500);
          if (!existing || !models.includes(cfg.model)) cfg.model = picked.quality || cfg.model;
          if (!existing || !cfg.fastModel || !models.includes(cfg.fastModel)) cfg.fastModel = picked.fast || cfg.model;
          cfg.verified = Date.now();
          if (key) await S.setApiKey(p.id, key);
          S.saveSettings((s) => { s.providers[p.id] = cfg; if (makeActive.checked || !s.active) s.active = p.id; });
          configured = true;
          showDone(p);
        } catch (e) {
          say(String((e && e.message) || e), "err");
        }
      };
      if (provider === p.id) setTimeout(() => (p.fields && p.fields.length ? Object.values(fieldEls)[0] : keyInput).focus(), 60);
    };

    // ---------------------------------------------------------------- on-device model
    const showDevice = (p) => {
      const L = window.QbAiLocal;
      const cur = (S.settings().providers.device && S.settings().providers.device.model) || L.LOCAL_MODELS[0].id;
      const gpuLine = el("p", { class: "small muted", text: "WebGPU: checking…" });
      (async () => {
        let gpu = "not available — the model will run on the CPU (slower)";
        try { const a = navigator.gpu && (await navigator.gpu.requestAdapter()); if (a) gpu = "available (faster)"; } catch {}
        gpuLine.textContent = "WebGPU: " + gpu;
      })();
      const list = el("div", { class: "ai-models" });
      for (const m of L.LOCAL_MODELS) {
        const r = el("input", { type: "radio", name: "aiLocalModel", value: m.id });
        r.checked = m.id === cur;
        list.appendChild(el("label", { class: "check" }, r, el("span", {}, el("b", { text: m.label }), ` · ${m.size}`, el("span", { class: "desc", text: " " + m.note }))));
      }
      const bar = el("progress", { max: "100", class: "ai-progress hidden" });
      const status = el("div", { class: "ai-status", role: "status" });
      const btn = el("button", { class: "btn primary" }, icon("download"), "Download and start");
      root.append(gpuLine, list, el("p", { class: "small muted", text: "The first start downloads the AI library from cdn.jsdelivr.net and the model from huggingface.co (use Wi-Fi: the files are large). After that it works offline, and what you ask never leaves this device." }), btn, bar, status);
      btn.onclick = async () => {
        const model = (list.querySelector("input:checked") || {}).value || L.LOCAL_MODELS[0].id;
        btn.disabled = true; bar.classList.remove("hidden"); bar.removeAttribute("value");
        const files = new Map();
        const off = L.onLocalProgress((pr) => {
          if (pr.file && pr.total) files.set(pr.file, { loaded: pr.loaded || 0, total: pr.total });
          let l = 0, t = 0; files.forEach((f) => { l += f.loaded; t += f.total; });
          if (t) { bar.value = Math.round((l / t) * 100); status.textContent = `Downloading ${(l / 1048576).toFixed(0)} of ${(t / 1048576).toFixed(0)} MB…`; }
          if (pr.status === "ready") status.textContent = "Starting the model…";
        });
        cleanup.push(off);
        try {
          const info = await L.loadLocalModel(model);
          S.saveSettings((s) => { s.providers.device = { id: "device", model, fastModel: model, verified: Date.now() }; s.active = "device"; });
          configured = true;
          status.textContent = `Ready (running with ${info.device === "webgpu" ? "WebGPU" : "WebAssembly on the CPU"}, ${info.dtype}).`;
          showDone(p);
        } catch (e) {
          status.className = "ai-status ai-err"; status.textContent = String((e && e.message) || e); btn.disabled = false;
        } finally { off(); }
      };
    };

    // ---------------------------------------------------------------- browser built-in model
    const showBuiltin = (p) => {
      const status = el("div", { class: "ai-status", role: "status" });
      const btn = el("button", { class: "btn primary" }, icon("ai-chip"), "Check and enable");
      root.append(btn, status);
      btn.onclick = async () => {
        const LM = window.LanguageModel;
        if (!LM) { status.textContent = "Not available in this browser. It needs desktop Chrome 148 or later (Windows, macOS, Linux, Chromebook Plus) with about 22 GB free disk and 16 GB of memory. Choose another option."; return; }
        try {
          const a = await LM.availability();
          if (a === "unavailable") { status.textContent = "This device does not meet the requirements for the built-in model."; return; }
          if (a !== "available") {
            status.textContent = "Downloading the browser's model…";
            const s = await LM.create({ monitor: (m) => m.addEventListener("downloadprogress", (e) => { status.textContent = `Downloading the browser's model… ${Math.round((e.loaded || 0) * 100)}%`; }) });
            s.destroy && s.destroy();
          }
          S.saveSettings((s) => { s.providers.builtin = { id: "builtin", model: "default", verified: Date.now() }; s.active = "builtin"; });
          configured = true;
          showDone(p);
        } catch (e) { status.textContent = String((e && e.message) || e); }
      };
    };

    // ---------------------------------------------------------------- finished
    const showDone = (p) => {
      reset(); root.innerHTML = "";
      const cfg = S.settings().providers[p.id];
      root.appendChild(el("h4", {}, icon("check-circle", 18), ` ${p.name} is ready`));
      if (cfg && cfg.models && cfg.models.length && !P.isOnDevice(p)) {
        const sel = (val, onch) => {
          const s = el("select", { onchange: (e) => onch(e.target.value) });
          if (!cfg.models.includes(val)) s.appendChild(el("option", { value: val, text: val }));
          for (const m of cfg.models) s.appendChild(el("option", { value: m, text: m }));
          s.value = val;
          return s;
        };
        root.append(
          el("div", { class: "field" }, el("label", { text: "Model for writing and analysis" }), sel(cfg.model, (v) => S.saveSettings((s) => { s.providers[p.id].model = v; }))),
          el("div", { class: "field" }, el("label", { text: "Model for quick tasks" }), sel(cfg.fastModel || cfg.model, (v) => S.saveSettings((s) => { s.providers[p.id].fastModel = v; }))));
      }
      const out = el("div", { class: "ai-try" });
      root.append(
        el("p", { class: "small muted", text: P.isOnDevice(p) ? "Text you give AI commands is processed on this device." : `When you run an AI command, Quillbox sends the text it needs to ${qbAi.hostOf(S.resolveBaseUrl(p, cfg || {}))} — straight from this page, or relayed by your Quillbox backend when the service does not accept calls from web pages. ${p.name}'s terms and privacy policy apply${p.free === "free" ? "; free tiers may use what you send to improve their models" : ""}.` }),
        el("div", { class: "btn-row" },
          el("button", { class: "btn", "data-action": "try", onclick: async () => {
            out.textContent = "…";
            try { await C.chat({ provider: p.id, tier: "fast", messages: [{ role: "user", content: "Reply with one short friendly sentence confirming you are working." }], maxTokens: 60, onToken: (_d, full) => { out.textContent = full; } }); }
            catch (e) { out.textContent = String((e && e.message) || e); }
          } }, icon("ai-chat"), "Try it"),
          el("button", { class: "btn", onclick: showChooser }, "Add another provider")),
        out);
    };

    if (provider && P.providerById(provider)) showProvider(P.providerById(provider)); else showChooser();
    await qbAi.dialog({ title: "Set up AI", body: root, buttons: [{ label: "Done", value: true, primary: true }], cls: "ai-setup-dialog" });
    reset();
    qbAi.refreshRibbon && qbAi.refreshRibbon();
    return configured || !!S.settings().active;
  }

  // ------------------------------------------------------------------ providers & models
  async function providers() {
    const root = el("div", { class: "ai-wizard" });
    const draw = async () => {
      root.innerHTML = "";
      const s = S.settings();
      const ids = Object.keys(s.providers);
      if (!ids.length) root.appendChild(el("p", { text: "No AI provider is set up yet." }));
      for (const id of ids) {
        const p = P.providerById(id);
        if (!p) continue;
        const cfg = s.providers[id];
        const key = p.needsKey || p.optionalKey ? await S.getApiKey(id) : "";
        const status = el("span", { class: "small muted" });
        const radio = el("input", { type: "radio", name: "aiActive" });
        radio.checked = s.active === id;
        radio.onchange = () => { S.saveSettings((x) => { x.active = id; }); draw(); };
        const modelSel = (val, key2) => {
          const list = cfg.models && cfg.models.length ? cfg.models : [val];
          const sel = el("select", { class: "ai-model-select", onchange: (e) => S.saveSettings((x) => { x.providers[id][key2] = e.target.value; }) });
          if (!list.includes(val)) sel.appendChild(el("option", { value: val, text: val }));
          for (const m of list) sel.appendChild(el("option", { value: m, text: m }));
          sel.value = val;
          return sel;
        };
        root.appendChild(el("div", { class: "ai-prov" + (s.active === id ? " on" : "") },
          el("label", { class: "check" }, radio, el("b", { text: p.name })),
          P.isOnDevice(p) ? el("div", { class: "small muted", text: `Model: ${cfg.model}` }) :
            el("div", { class: "ai-prov-models" }, el("label", { class: "small", text: "Writing:" }), modelSel(cfg.model, "model"), el("label", { class: "small", text: "Quick tasks:" }), modelSel(cfg.fastModel || cfg.model, "fastModel")),
          key ? el("div", { class: "small muted", text: `Key ${S.maskKey(key)} · stored in ${S.keyBackend().label}` }) : null,
          el("div", { class: "btn-row" },
            el("button", { class: "btn sm", onclick: async () => { status.textContent = "Testing…"; try { const r = await C.chat({ provider: id, tier: "fast", messages: [{ role: "user", content: "Reply with OK." }], maxTokens: 10 }); status.textContent = `Working (${r.model}): ${r.text.slice(0, 40)}`; } catch (e) { status.textContent = String(e.message || e); } } }, "Test"),
            el("button", { class: "btn sm", onclick: async () => { await setup({ provider: id }); draw(); } }, "Change"),
            el("button", { class: "btn sm", onclick: async () => {
              if (!(await qbAi.confirmDialog("Remove provider", `Remove ${p.name} and delete its saved key from this device?`, "Remove"))) return;
              await S.deleteApiKey(id).catch(() => {});
              S.saveSettings((x) => { delete x.providers[id]; if (x.active === id) x.active = Object.keys(x.providers)[0] || null; });
              draw();
            } }, "Remove"), status)));
      }
      root.appendChild(el("div", { class: "btn-row" }, el("button", { class: "btn primary", onclick: async () => { await setup(); draw(); } }, icon("plus"), "Add a provider")));
    };
    await draw();
    await qbAi.dialog({ title: "AI providers & models", body: root });
    qbAi.refreshRibbon && qbAi.refreshRibbon();
  }

  // ------------------------------------------------------------------ AI settings
  async function settingsDialog() {
    const s = S.settings();
    const root = el("div", { class: "ai-settings" });
    const check = (key, label, hint) => { const cb = el("input", { type: "checkbox" }); cb.checked = !!s[key]; cb.onchange = () => S.saveSettings((x) => { x[key] = cb.checked; }); return el("label", { class: "check" }, cb, el("span", {}, label, hint ? el("span", { class: "desc", text: " " + hint }) : null)); };
    const lang = select([["auto", "Same as the email"], ...PR.LANGUAGES.map((l) => [l, l])], s.language || "auto");
    lang.onchange = () => S.saveSettings((x) => { x.language = lang.value; });
    const limit = select([["25", "25"], ["50", "50"], ["100", "100"], ["200", "200"]], String(s.mailboxLimit || 100));
    limit.onchange = () => S.saveSettings((x) => { x.mailboxLimit = Number(limit.value); });
    const route = select([["auto", "Direct when the service allows it, else through the Quillbox backend"], ["direct", "Always direct from this page"], ["proxy", "Always through the Quillbox backend"]], s.network || "auto");
    route.onchange = () => S.saveSettings((x) => { x.network = route.value; });
    const usage = el("p", { class: "small muted", text: `Requests sent from this browser: ${s.usage.requests.toLocaleString()} · tokens in/out (where the service reports them): ${s.usage.tokensIn.toLocaleString()} / ${s.usage.tokensOut.toLocaleString()} · since ${new Date(s.usage.since).toLocaleDateString()}` });
    root.append(
      check("enabled", "AI features are on", "When off, AI commands do nothing and nothing is sent."),
      check("confirmEachSend", "Show what will be sent and ask before every request to a cloud service"),
      el("div", { class: "field" }, el("label", { text: "Language for generated text" }), lang),
      el("h4", { text: "Ask about this mailbox" }),
      check("mailboxPreviews", "Include the first words of each message, not only date, sender, subject and flags"),
      el("div", { class: "field" }, el("label", { text: "Describe at most this many messages of the folder" }), limit),
      el("h4", { text: "Network" }),
      el("div", { class: "field" }, el("label", { text: "Route for cloud requests" }), route, el("span", { class: "hint", text: "The Quillbox backend relays only to the AI services in its list and to addresses you set up here; it never logs keys or text." })),
      el("h4", { text: "Usage" }), usage,
      el("div", { class: "btn-row" },
        el("button", { class: "btn sm", onclick: () => { S.saveSettings((x) => { x.usage = { requests: 0, tokensIn: 0, tokensOut: 0, since: Date.now() }; }); usage.textContent = "Usage counters reset."; } }, "Reset counters")),
      el("h4", { text: "Backup" }),
      el("p", { class: "small muted", text: "Exports providers, models, options and your saved prompts. API keys are never included." }),
      el("div", { class: "btn-row" },
        el("button", { class: "btn sm", onclick: () => {
          const blob = new Blob([JSON.stringify(S.exportSettings(), null, 2)], { type: "application/json" });
          const a = el("a", { href: URL.createObjectURL(blob), download: "quillbox-ai-settings.json" }); document.body.appendChild(a); a.click(); a.remove();
        } }, icon("download"), "Export AI settings"),
        el("button", { class: "btn sm", onclick: async () => {
          const [f] = await pickFiles({ accept: ".json,application/json" });
          if (!f) return;
          try { S.importSettings(JSON.parse(await readFileText(f))); toast("AI settings imported. Add the API keys again in Providers & models."); } catch (e) { toast(e.message, { danger: true }); }
        } }, icon("upload"), "Import")));
    await qbAi.dialog({ title: "AI settings", body: root, wide: false });
    qbAi.refreshRibbon && qbAi.refreshRibbon();
  }

  // ------------------------------------------------------------------ AI & Privacy
  const SCOPE_TEXT = {
    message: "the open message: sender, recipients, date, subject, attachment names and body text",
    thread: "every message of the conversation (up to 10), the same fields each",
    draft: "your draft's text (not the quoted original or your signature) and its subject",
    selection: "the selected text of your draft, or your part of the draft when nothing is selected",
    mailbox: "the folder's message list: date, sender, subject and flags (first words only if you turn that on), at most the number set in AI settings",
    none: "only what you type",
  };
  async function privacy() {
    const s = S.settings();
    const p = s.active ? P.providerById(s.active) : null;
    const rows = PR.COMMANDS.map((c) => el("tr", {}, el("td", { text: c.label }), el("td", { text: SCOPE_TEXT[c.scope] + (c.id === "phishing" ? ", selected headers (authentication results, Return-Path, List-Unsubscribe), the links and the built-in junk engine's findings" : "") })));
    const backend = S.keyBackend();
    const body = el("div", { class: "ai-privacy" },
      el("p", {}, el("b", { text: "Quillbox has no AI service of its own. " }), "AI requests go from your device to the provider you choose, with your own key, or to a model on this device. Nothing is sent until you run an AI command or ask in the AI pane, and every answer has a \"What was sent\" section showing the exact text."),
      el("p", {}, el("b", { text: "Active provider: " }), p ? p.name : "none",
        p ? (P.isOnDevice(p) ? " — runs on this device; your mail does not leave it." : ` — what a command needs is sent to ${qbAi.hostOf(S.resolveBaseUrl(p, s.providers[p.id] || {}))}; its terms and privacy policy apply.`) : ""),
      el("h4", { text: "What each command sends" }),
      el("table", { class: "ai-table" }, el("tbody", {}, ...rows)),
      el("p", { class: "small muted", text: "Long messages are cut to a size limit (smaller for the on-device model). Message text is sent as plain text: remote images, attachments' contents and your password are never sent." }),
      el("h4", { text: "Relay" }),
      el("p", { text: "Some services do not accept requests from web pages. For those (and for local servers the page cannot reach) the request goes through the Quillbox backend that serves this page — on the desktop and Android apps that is on this device; for a self-hosted Quillbox it is your own server. The relay only forwards to the AI services Quillbox knows and the addresses you set up, and never logs keys or text." }),
      el("h4", { text: "API keys" }),
      el("p", { text: `Keys are stored in ${backend.label}. They are never put in settings, drafts, exported settings or anything sent to your mail server.` + (backend.encrypted ? "" : " This storage is not encrypted by Quillbox; anyone with access to this device account could read it. Use the on-device model or a local server if that matters.") }),
      el("h4", { text: "Changes are previewed" }),
      el("p", { text: "AI text is shown first and goes into your draft only when you press Insert or Replace, as one step you can undo (Ctrl+Z, or Undo on the notice). Tasks, events, contacts and categories are added only for the items you tick." }),
      el("h4", { text: "Free tiers" }),
      el("p", { text: "Some free tiers may use what you send to improve their models. For confidential mail use the on-device model, the browser's built-in model, or Ollama / LM Studio on your own computer." }),
      el("div", { class: "btn-row" },
        el("button", { class: "btn", onclick: () => qbAi.clearModels && qbAi.clearModels() }, icon("trash"), "Delete downloaded models"),
        el("button", { class: "btn danger", onclick: async () => {
          if (!(await qbAi.confirmDialog("Delete saved keys", "Delete every saved AI API key from this device? Provider settings stay; you will need to paste the keys again.", "Delete keys"))) return;
          for (const id of P.PROVIDERS.map((x) => x.id)) await S.deleteApiKey(id).catch(() => {});
          toast("Saved AI keys deleted.");
        } }, icon("ai-key"), "Delete saved keys")));
    await qbAi.dialog({ title: "AI & privacy", body });
  }

  Object.assign(qbAi, { setup, providers, settings: settingsDialog, privacy, registerEndpoint });
})();
