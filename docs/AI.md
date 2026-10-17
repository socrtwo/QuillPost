# AI in Quillbox

The **AI** tab (Mail ribbon, next to *View*) is optional. Nothing is sent anywhere until you run
an AI command or ask something in the AI pane, and Quillbox has no AI service of its own: requests
go to the provider you choose, with your own key, or to a model that runs on your device.

The same commands are available from:

- the **AI** ribbon tab;
- the **AI** button in the reading pane's toolbar (also on phones, where the ribbon is hidden);
- the compose window's **AI** tab (draft commands, plus *Original message* for the message being
  answered);
- the **AI assistant** pane (chat), opened from the ribbon or the button in the top bar.

## Setting up

**AI ▸ Set up AI** opens a wizard that lists providers in four groups:

| Group | Providers |
|---|---|
| Free, no account | Quillbox Local AI (on-device model), the browser's built-in model (desktop Chrome) |
| Free with a free account | OpenRouter (free models), Google Gemini, Groq, Mistral, Cerebras, SambaNova, Z.ai, NVIDIA NIM, Cloudflare Workers AI, Cohere (trial) |
| On your own computer | Ollama (`http://localhost:11434/v1`), LM Studio (`http://localhost:1234/v1`) |
| Paid services | OpenAI, Anthropic, DeepSeek, xAI, Perplexity, Together, Fireworks, Hugging Face Inference Providers, Moonshot, Alibaba Qwen, Azure OpenAI / AI Foundry, Amazon Bedrock, any OpenAI-compatible service |

For a key-based provider the wizard links to the provider's sign-up, key and billing pages,
accepts the pasted key (*Paste* reads the clipboard once, on request, and recognises the key by
its format), and **Test & save** lists the provider's models with that key before anything is
stored. You then choose a model for writing and one for quick tasks. Free-tier terms are set by
each provider and change; the wizard shows a short note and links to the provider's own page.

You can add several providers and switch with **Active AI**, or let several answer together
(see [Using several AIs](#using-several-ais)); **Providers & models** tests,
re-keys or removes them. **AI settings** has the on/off switch, the language for generated text,
what *Ask mailbox* sends, the network route, usage counters (requests and the token counts the
services report) and export/import of the AI settings (never including keys).

### On-device model

Quillbox Local AI runs a small open instruction model with Transformers.js in a Web Worker, on
WebGPU when available, otherwise WebAssembly on the CPU. The library (pinned version, from
cdn.jsdelivr.net) and the model (from huggingface.co) are downloaded once and cached by the
browser; after that it works offline and nothing you ask leaves the device.

| Model | Download | Notes |
|---|---|---|
| Qwen2.5 0.5B Instruct (recommended) | ≈ 480–510 MB | Best quality for its size; many languages |
| SmolLM2 360M Instruct | ≈ 270–365 MB | Fastest on older phones; English-centric |
| Gemma 3 270M IT | ≈ 270–545 MB | Simple rewrites and summaries |
| Llama 3.2 1B Instruct | ≈ 1.24 GB | Needs WebGPU |

Small models are fine for summaries, rewrites and simple extraction; structured commands (events,
contacts, phishing check) work noticeably better with a cloud or local-server model. Texts sent to
the on-device model are cut shorter (3,500 characters instead of 24,000).
**AI settings ▸ Delete downloaded models** (or the button on *AI & privacy*) frees the space.

The browser's built-in model needs desktop Chrome 148+ on supported hardware; it is not available
on Android or iOS.

## Commands

| Command | Works on | Result |
|---|---|---|
| Summarize | open message | answer in the AI pane |
| Summarize thread | the conversation (up to 10 messages) | AI pane |
| Translate | open message | AI pane, with *Show under the message* |
| Explain jargon | open message | AI pane |
| Phishing check | open message, selected headers, its links (shown text → real target) and the built-in junk engine's findings | risk, signals with evidence, advice; *Report junk* |
| Classify | open message and your category names | suggested category, kind, priority; *Apply category* (with Undo) |
| Unsubscribe helper | the List-Unsubscribe / List-Unsubscribe-Post headers (read locally, no AI) | the methods offered, *Unsubscribe*, and *Explain with AI* on request |
| Draft reply | the message being answered (+ what you already wrote) | options: Reply / Accept / Decline / Ask for details / Thank, tone, length, instructions; streaming preview, then *Insert into reply* |
| Rewrite | selected draft text (else your part of the draft) | Improve, More formal, More friendly, Shorter, Longer, Fix grammar; preview, then *Replace* |
| Translate draft | selection or your draft | preview, then *Replace* |
| Proofread | selection or your draft | preview, then *Replace* |
| Subject line | your draft | three suggestions to pick and edit |
| Generate template | your description | name, subject and text with [placeholders]; *Save as template*, *Insert into draft* or *New message* |
| Action items | open message | checklist → **To Do** (with Undo) |
| Events | open message | checklist → **Calendar** (with Undo) |
| Contacts | open message | checklist → **People**, skipping addresses already there (with Undo) |
| Follow-up | open message | suggested reminder → To Do task, or flag the message with a due date |
| Ask mailbox | the current folder's message list | chat answers that cite messages as [#n] |
| AI pane | your question, plus the open message, its conversation, the folder list, or nothing (you choose) | chat |
| My prompts / Prompt library | your own instructions, run on the message, thread, draft, selection, folder list or nothing | AI pane, or preview then Replace/Insert |

"Your part of the draft" is everything above the quoted original and your signature.

**Stop** (ribbon, pane, the progress notice, and every preview) cancels the request in progress;
a streamed answer stops where it is.

Every command is also listed in `window.QB_AI_COMMANDS` (`{id, label, icon, group, tip, run}`)
for a command registry or Quick Access Bar.

### How results are applied

- Text for a draft is shown in a preview while it streams; you can edit it, try again, or copy
  it. It reaches the draft only when you press **Insert** or **Replace**, as one editing step:
  the browser's Undo (Ctrl+Z) reverts it, and so does **Undo** on the notice that follows.
- Tasks, events, contacts and categories are added only for the items you tick in the preview,
  and the notice offers **Undo**.
- Model output shown in the pane is rendered from plain text with a small Markdown subset; HTML
  in an answer (or in the email it read) is never interpreted.

## Using several AIs

You can send each request to several AI services at once and let Quillbox combine their answers.
Set it up in **AI settings ▸ Use several AIs** (or **AI ▸ Choose AIs…**): tick at least two of the
providers you have set up (each with its own model; up to six), then choose what happens when
several AIs answer:

- **One AI combines the answers (master).** Every chosen AI answers the request on its own, at the
  same time. Then the AI you picked as *Master* reads all the answers — shuffled and labelled
  only *Response A*, *Response B*, … so it cannot favour a service by name — and writes the single
  best answer: it keeps what is correct, fixes mistakes, settles disagreements on the merits and
  keeps the format the command asked for. If only one AI answers, its answer is used as it is.
- **The AIs deliberate until they agree (Quaker consensus).** This follows the way a Quaker
  business meeting decides: there is no vote; the group looks for *unity*. A **clerk** (the AI
  you picked as *Master / clerk*) listens to everyone and writes down the "sense of the meeting"
  as a **minute** — here, a proposed answer drawn from every AI's own first answer. Each AI then
  reads the minute and says whether it can **unite** with it (it is right and complete),
  **stand aside** (it would have preferred something different, but can live with it; its concern
  is recorded), or **block** (a principled objection: the minute is wrong or harmful, and it says
  what must change). If nobody blocks, the minute is adopted. If someone blocks, the clerk
  revises the minute to meet the objections that are right, and the AIs consider it again — for
  at most the number of *Rounds of discussion* you chose (1–5, 3 by default). If a block remains
  after the last round, Quillbox still gives you the clerk's last minute and says plainly that
  **the AIs did not reach unity**, with each objection.

**Single AI** turns this off: only the active AI answers. The AI tab's **Several AIs** menu
switches between *Single AI*, *Master Combines Answers* and *Quaker Consensus*; **Choose AIs…**
opens the settings; **Show AI Discussion** turns the discussion display on or off. All three are
also in `window.QB_AI_COMMANDS` (group "Providers": `ai.ensembleMode`, `ai.ensembleMembers`,
`ai.showDeliberation`).

While the AIs work, the AI pane (and the preview of a draft) shows progress such as "Asking 3
AIs…" or "Round 2 of 3: 1 objection". Below the answer, **How the AIs answered** (when *Show the
discussion* is on) lists each AI's own answer with how long it took — or why it was left out —
and, for consensus, every round's minute with each AI's stance and reason, and the outcome:
"Synthesized by …", "United (3 of 3)", "United; 1 stood aside" or "No unity after 3 rounds: 1
objection". Only the final answer is ever used: *Insert*, *Replace*, tasks, events and the other
results never contain the discussion.

An AI that fails or does not answer within 90 seconds is left out, and that is reported. The
on-device model and the browser's built-in model take their requests one at a time; cloud
services and local servers are asked in parallel. **Stop** cancels every outstanding request. Key
tests, model lists and the wizard's *Try it* always talk to one provider.

**Cost.** Each request goes to every chosen service: about N+1 requests in master mode (N answers
and the master) and up to N×(rounds+1)+rounds in consensus mode (the answers, every AI in each
round, the clerk's draft and revisions). With three AIs and three rounds that is 4 or up to 15
requests. Paid services charge for each, and free tiers use up their limits faster. The usage
counters in AI settings count every request.

**Privacy.** The text a command needs goes to *every* chosen service, and the master or the AIs
in the discussion also see the other AIs' answers. The settings section and the notice before
the first request list every service that will receive the text; *What was sent* lists them for
each answer. For confidential mail, choose only the on-device model and local servers.

## Privacy

- **Nothing is sent until a command runs.** The first time you use a cloud provider, Quillbox
  says where the text goes and asks; *AI settings ▸ Show what will be sent and ask before every
  request* shows the exact text before each request.
- **What was sent.** Every answer has a *What was sent* section with the exact messages, the
  provider, model and host, and whether the request was relayed. Keys are never shown there.
- **Only what a command needs.** Message commands send the sender, recipients, date, subject,
  attachment names and body text (as plain text, cut to a size limit). Remote images, attachment
  contents and your mail password are never sent. *Phishing check* also sends a few headers
  (Authentication-Results, Return-Path, List-Unsubscribe…), the link targets and the local junk
  engine's findings.
- **Ask mailbox** sends one line per message — date, sender, subject and flags — for at most the
  number of messages set in AI settings (100 by default). Message bodies are not sent; the first
  words of each message are added only if you turn that on. Before a folder's list is first sent
  in a session, Quillbox shows exactly what will go.
- **Email is treated as data.** Message text is fenced in tags and the system prompt tells the
  model to ignore instructions inside it, so a message cannot steer the assistant. This lowers,
  but cannot rule out, prompt injection; results are always previewed.
- Free tiers of some services may use what you send to improve their models. For confidential
  mail use the on-device model, the browser's built-in model, or Ollama / LM Studio.

**AI & privacy** (AI tab) shows all of this for the active provider, where keys are stored, and
has *Delete downloaded models* and *Delete saved keys*.

## The relay

Some services (Perplexity, NVIDIA NIM, Cloudflare Workers AI, and others) refuse requests from web
pages (no CORS). Quillbox then sends the request through the backend that serves the page:
`POST /api/ai/proxy`. In the desktop and Android apps that backend runs on the same device; for a
self-hosted Quillbox it is your server. A request first goes direct when the service allows it;
if the browser blocks it, it is relayed, and that service is relayed straight away for the rest
of the session. *AI settings ▸ Network* can force either route.

The relay is deliberately narrow:

- it requires a signed-in Quillbox session and an `X-Quillbox-AI: 1` header;
- it forwards only to the providers' base URLs (the list in `AiRoutes.kt`, kept in step with
  `web/ai/providers.js` by a unit test) and to custom or local server addresses that the signed-in
  account registered while setting up a provider (stored in the account's data folder,
  `ai-endpoints.json`); URLs with credentials, dot segments, other ports or other paths are
  refused;
- it never connects to link-local addresses (where cloud metadata services live), and connects
  to loopback or private addresses (a local Ollama, a server on your LAN) only when the browser
  asking is itself on loopback or a private network — so a Quillbox server reachable from the
  internet is not a way into its own network. `QUILLBOX_AI_RELAY_PRIVATE=on` or `off` overrides
  this; use `off` when Quillbox sits behind a reverse proxy on the same machine (every client then
  appears to come from loopback). Addresses are checked when the request is relayed; a host whose
  DNS answer changes between the check and the connection is not detected;
- it forwards only the provider headers (`Authorization`, `x-api-key`, `api-key`,
  `x-goog-api-key`, `anthropic-version`, `Content-Type`, `Accept`, attribution headers), does not
  follow redirects, streams the answer back unchanged, and never logs keys or text;
- a self-hosted server can turn it off with `QUILLBOX_AI_RELAY=off`.

## Where API keys are stored

Keys are never stored with the AI settings (localStorage `qb.ai.v1`), in drafts, in the account
settings kept on the Quillbox server, or in an AI settings export.

| Platform | Key storage | Encrypted |
|---|---|---|
| Android app | Encrypted with AES-256-GCM under a key generated in the **Android Keystore** (it never leaves the Keystore); the ciphertext is kept in the app's private SharedPreferences (`quillbox.ai.secure`). The page reaches it through the `QuillboxAndroid.secureGet/secureSet/secureRemove` bridge, which answers only while the WebView shows the local Quillbox page. The app never falls back to web storage for keys: an older app without the bridge refuses to save a key. | Yes |
| Desktop app (Windows, macOS, Linux) | The desktop launcher opens Quillbox in your web browser and has no bridge to the OS keychain. Its backend stores keys in its private data folder, `~/.quillbox/ai/keys.json` (or `$QUILLBOX_DATA_DIR/ai/keys.json`). On macOS and Linux the folder is `0700` and the file `0600` (only your user account can read them); on Windows the file is in your user profile, whose default permissions already exclude other standard users. This store is enabled only when the backend was started by the desktop launcher, and answers only to a signed-in session of the same page. | **No** — protected by file permissions only |
| Web (self-hosted Quillbox in a browser) and the iOS app | IndexedDB (`quillbox-ai`) for the Quillbox site in this browser (on iOS: the app's own WebView storage). | **No** — readable by anyone with access to this browser profile or device account |

If unencrypted storage is a concern on the desktop or web, prefer the on-device model or a local
server that needs no key, and use *AI & privacy ▸ Delete saved keys* when you are done.

## For developers

- `web/src/main/resources/web/ai/` — `providers.js` (provider table), `client.js` (request
  builders, SSE parsing, streaming chat, routing to the relay), `store.js` (settings, key stores),
  `local.js` (on-device model worker), `prompts.js` (commands and prompt construction),
  `apply.js` (output cleaning, JSON, Markdown-lite, List-Unsubscribe, validation of extracted
  items), `ensemble.js` (several AIs: master and Quaker-consensus orchestration; it depends only
  on an injected `call(provider, model, req)`, and `client.js` `chat()` uses it when an ensemble is
  set up and the request names no provider), `pane.js`, `wizard.js`, `commands.js` (UI); styles in
  `web/ai.css`.
- `web/src/main/kotlin/.../AiRoutes.kt` — `/ai/*` static files, `/api/ai/info`, the relay,
  `/api/ai/endpoints`, the desktop key store; `app/.../AiSecureStore.kt` — the Android Keystore
  store.
- Tests: `node --test web/src/test/js/*.test.js` (Node 18+, no npm packages) — request building,
  SSE splitting (`\n\n`, `\r\n\r\n`, `\r\n\n`, `\n\r\n`), streaming with Stop, relay fallback, key
  storage (keys never in settings or exports), prompt construction, and the ensemble with mock
  providers (`ensemble.test.js`: master and consensus flows, failures and timeouts, Stop, the
  client's dispatch and relay fallback per member). `./gradlew check` in `web/`
  runs them through the `jsTest` task (skipped with a warning when Node is not installed), along
  with `AiRoutesTest` (relay allow-list, desktop key file).
