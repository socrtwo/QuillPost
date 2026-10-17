# quillbox
Email client for Android, desktop, web and iOS: downloads IMAP/POP3 mail, composes with a
formatting toolbar, and keeps junk out of the Inbox with open DNS blocklists, an offline
brand-impersonation detector, a learning classifier and user rules. Besides Mail it has a
Calendar, People (contacts) and To Do, and its interface follows the familiar layout of
Microsoft 365 Outlook: app bar, ribbon, folder pane, Focused/Other inbox, conversations and a
reading pane.

Quillbox is an independent open-source project. It is not affiliated with, sponsored or endorsed
by Microsoft. Outlook and Microsoft 365 are trademarks of Microsoft Corporation and are named here
only to describe the layout Quillbox users will recognise. Quillbox uses its own name, logo,
icons, colours and open fonts, and contains no Microsoft code or artwork.

---

## Quillbox (Android app)

Quillbox for Android is the Outlook-style client packaged as an app. The APK contains the
same mail engine and the same interface as the web and desktop versions: at start-up the app
launches the backend on `127.0.0.1` inside its own process and shows the UI full-screen in a
WebView. Nothing leaves the phone except the connections to your own mail provider, the DNS
blocklist lookups and the provider autodiscovery, exactly as on the desktop.

- **Application ID:** `info.socrtwo.quillbox`
- **minSdk:** 26 (Android 8.0) · **compile/target SDK:** 35 (Android 15)
- **Also runs on ChromeOS** (install the APK) and on Android tablets, where the layout becomes
  folders + list with the reading pane sliding in, like Outlook on an iPad.

What the Android shell adds on top of the shared UI:

- the back button closes dialogs, the folder drawer and the open message before leaving the app;
- attachments are saved to *Downloads/Quillbox* and offered to a viewer app; compose attachments
  use the system file picker;
- links in messages open in the browser, `mailto:` links from other apps open the composer;
- *Print* uses Android's print service; the theme follows the system light/dark setting;
- the account (with its password, when *Keep me signed in* is ticked) is stored in the app's
  private WebView storage; rules, settings and the learned classifier live under the app's
  private files directory.

### Tech stack

| Concern            | Choice                                                        |
|--------------------|--------------------------------------------------------------|
| Language / build   | Kotlin, Gradle Kotlin DSL, Gradle wrapper 8.13               |
| UI                 | The web client (`web/src/main/resources/web`) in a WebView   |
| Backend            | Ktor 3 (CIO engine) compiled from `web/src/main/kotlin`      |
| Mail protocols     | Jakarta Mail — `org.eclipse.angus:angus-mail` (IMAP/POP3/SMTP) |
| Junk engine        | Shared with web/desktop (`web/src/main/kotlin/.../spam`)      |

### Project layout

```
app/src/main/java/info/socrtwo/quillbox/
├── MainActivity.kt     // WebView shell: back button, downloads, file picker, links, printing
└── LocalServer.kt      // starts the shared Ktor backend on 127.0.0.1 (stable port, app data dir)
web/src/main/kotlin/    // the backend and junk engine, compiled into the APK as-is
web/src/main/resources/web/   // index.html, styles.css, app.js — the UI, packaged into the APK
```

### Build the debug APK

Requires JDK 17+ and the Android SDK (platform 35, build-tools). The Gradle wrapper is
committed, so:

```bash
./gradlew assembleDebug
```

The sideloadable debug APK is written to:

```
app/build/outputs/apk/debug/app-debug.apk
```

Install it on a device with `adb install app/build/outputs/apk/debug/app-debug.apk`.

> **CI:** `.github/workflows/android.yml` builds `assembleDebug` on every push and uploads
> `app-debug.apk` as a build artifact (the `quillbox-debug-apk` artifact).

## Web client (Outlook-style, with built-in junk protection)

`web/` is a self-hosted web mail client: a Ktor backend that talks IMAP/POP3/SMTP on your behalf,
plus a browser front end laid out like Microsoft 365 Outlook (folder pane, message list, reading
pane, command bar, keyboard shortcuts, dark mode, responsive down to phone width).

### Run it

```bash
cd web
./gradlew run            # http://localhost:8080
```

Environment variables: `PORT` (default 8080), `HOST` (default 0.0.0.0), `QUILLBOX_DATA_DIR`
(default `~/.quillbox`; holds per-account rules, settings, the learned classifier and the
analysis cache — **never passwords**).

To try it without a real mailbox:

```bash
cd web
./gradlew demo           # in-memory IMAP/SMTP seeded with genuine, spoofed and blacklisted mail
```

then sign in as `demo@quillbox.test` / `demo` and, under *Server settings*, use IMAP
`127.0.0.1:3143` (security *None*) and SMTP `127.0.0.1:3025` (*None*).

Browser tests (Playwright with Chromium, run against demo servers; each script's header has the
exact commands): `node src/test/e2e/embed.mjs` tests the QuillPen embedded editor against a fake
QuillPen page (`src/test/quillpen-fake/`), and `node src/test/e2e/layout.mjs` opens every
dialog, menu and overlay on emulated phones (412×915, 360×640, 915×412 landscape, with system-bar
insets and the keyboard) and checks that nothing sits under the bars or keyboard, overlaps or
scrolls sideways. `node scripts/gen-icons.mjs` regenerates the icons from `branding/QuillPost/`.

### Setting up an account

A three-step wizard, as in Outlook:

1. **Choose your provider.** Tiles for the hundred most used mailbox domains worldwide plus
   Proton Mail — Gmail, Yahoo, Outlook.com/Hotmail/Live/MSN, AOL, iCloud, Comcast/Xfinity,
   Verizon, AT&T/SBCGlobal/BellSouth, Cox, Spectrum, EarthLink, Erols/Astound, Orange/Wanadoo,
   Free, SFR, La Poste, GMX, WEB.DE, T-Online, Libero, Virgilio, TIM/Alice, Ziggo, KPN,
   Telenet, Proximus, Bluewin, BT, Sky, Virgin Media, TalkTalk, QQ, NetEase 163/126, Sina,
   Sohu, Alibaba, Yahoo! JAPAN, docomo, au, @nifty, BIGLOBE, OCN, Naver, Daum/Kakao, Nate,
   Rediffmail, Yandex, Mail.ru, Rambler, UOL, BOL, iG, Terra, Telstra/BigPond, Optus and more
   (`web/src/main/kotlin/.../Providers.kt`, 100+ providers, 350+ domains) — grouped by region
   with a search box, plus **Other** for any mailbox. Historic brands that were merged
   (Verizon → AOL, Erols → Astound, Voilà → Orange, Chello → Ziggo…) point at the successor's
   servers and say so.
2. **Name, address and password.** The provider's servers, caveats and *Create an app
   password* link are shown right away (Gmail, Yahoo, iCloud, AOL, Fastmail, Yandex, Mail.ru
   and QQ need an app password or authorisation code). With *Other* the settings are looked up
   from the address (built-in table → Mozilla ISPDB → the domain's autoconfig → MX record → port
   probe).
3. **Review the server settings** and *Test connection* before signing in. Tick *Keep me
   signed in* to store the account on the device.

A branded splash screen covers start-up on every platform (system launch splash on Android and
iOS, then the page's own splash until the mailbox is ready).

### Junk protection (on by default)

Every new message is analysed on the server and, when it is junk, moved to the Junk folder
automatically (POP3 accounts get a local Junk view instead, since POP3 has no folders). Signals,
all free and needing no API key:

| Signal | What it does |
|---|---|
| **DNS blocklists** | The sending IPs from the `Received` chain are checked against Spamhaus ZEN, SpamCop, Barracuda and PSBL; the sender, Reply-To and link domains against Spamhaus DBL, SURBL and URIBL. An IP listing decides on its own only when the sender is not authenticated: large senders share sending addresses (Amazon SES, SendGrid, Google) that get listed because of other tenants, so for DMARC-authenticated mail the listing is shown and counted as a strong signal but does not force junk. Lists can be toggled, re-weighted and extended with your own zones in *Settings → Junk email*. Spamhaus refuses queries via public resolvers (8.8.8.8, 1.1.1.1); the UI shows *refused* when that happens. |
| **Authentication results** | SPF / DKIM / DMARC verdicts recorded by your own mail provider. |
| **Impersonation detector** | An offline dictionary of ~2,500 organisations (Fortune/Global 500 companies, banks, insurers, retailers, carriers, government agencies, universities) and the domains they really send from; every domain was checked to resolve in DNS when the table was generated (`web/src/main/resources/brands/*.tsv`, regenerated with `scripts/gen-brands.py`). The sender's display name, address, subject and body signature are scanned for a claimed organisation and compared with the real sending domain; look-alike domains (`paypa1.com`, `arnazon.com`, `secure-paypal-login.net`, `paypal.com.verify.ru`) are caught with homoglyph normalisation and edit distance. A mismatch is shown as **"Claims to be PayPal — domain is not PayPal's"**. |
| **Learned classifier** | A naive-Bayes model that trains on your *Junk* / *Not junk* clicks (seeded with a small built-in corpus, and deliberately weak until you have taught it). |
| **Content heuristics** | Urgency and prize language, link-text/href mismatches, bare-IP links, shorteners, hidden text, dangerous attachment types, Reply-To on another domain, and so on. Link evidence is weighed against what is known about the sender: when DMARC (or SPF plus an aligned DKIM signature) proves the From domain, link/sender disagreement weighs little; links on another domain of the same organisation (facebookmail.com → facebook.com, e.paypal.com → paypal-communication.com) or on mail-service click trackers and link-protection gateways (Mailchimp, SendGrid, Salesforce Marketing Cloud, Safe Links, Proofpoint…) are not disagreement at all, and wrapped links are unwrapped to their real destination first. A shown brand hostname that opens somewhere else from an unauthenticated sender still scores fully. Links are judged by role: only the calls to action count, while footer plumbing (unsubscribe, preferences, privacy, view in browser), social icons and app-store buttons are ignored, and a link whose own text says *sign in* / *verify* is the one judged for off-site sign-in requests. Domains are reduced to their registrable form with the full Public Suffix List (`web/src/main/resources/psl/icann.dat`, ICANN section, refreshed with `scripts/update-psl.py`), so `mail.example.co.uk` and `shop.example.co.jp` compare correctly. |
| **Sender history** | The account's own experience with each sender, kept in `sender-history.json` next to the Bayes model: messages received, opened and replied to, *Junk* / *Not junk* marks, and the link domains the sender habitually uses. A sender you have replied to, marked not junk or opened three times (and never marked junk) is a *known correspondent*: the verdict gets a −10 credit and the link and sign-in checks are relaxed. Link domains a sender has used in three or more non-junk messages count as that sender's own. Free-mail and shared sending domains are tracked per address, everything else per organisation domain; one *Junk* click revokes the trust. |
| **Rules, safe and blocked senders** | Deterministic and always win. |

A message is only *suspicious* when there is one strong signal or two independent kinds of
evidence (authentication failure, blocklist, brand impersonation, the classifier once you have
trained it, sender history, content). A pile of weak bulk-mail traits from an authenticated sender stays clean.

Every verdict is explained: the reading pane shows the **sender's address in large bold type** with
the domain underlined and coloured by verdict, badges for the claimed organisation and the
authentication results, and a *Why?* list with the weight of each signal.

Per-message buttons (toolbar, hover actions, context menu and keyboard):

- **Junk** (`J`) moves the message to Junk and trains the classifier; **Not junk** (`Shift+J`)
  restores it, trains the classifier and guarantees it is never auto-filed again. Both have *Undo*.
- **Analyse & make rule** runs the full analysis and proposes a rule that would catch this message
  and others like it (sender domain or exact address, spoofed display name, distinctive subject
  phrase, scam phrase). Each condition explains why it was chosen. Edit it, *Preview matches*
  against the current folder, then *Save rule* or *Save & apply to current mail* to file the
  matching messages that are already in the folder.

Optionally, a locally running open-source language model served by [Ollama](https://ollama.com)
(*Settings → AI models*) adds a second opinion and extra rule conditions. Nothing is ever sent to
a cloud service; the built-in engine needs no model at all.

### Other features

**Mail.** Focused Inbox (Focused/Other tabs, learned from your behaviour, with "Always move to
Focused/Other" overrides), conversation view with the thread shown in the reading pane, pinned
messages, categories with colours, follow-up flags with due dates (Today, Tomorrow, This week,
Next week, custom), snooze (the message comes back to the Inbox at the chosen time), sweep,
ignore conversation, quick steps, rules created from a message (conditions on sender, To/Cc,
subject, body, attachments and importance; actions move, copy, delete, categorise, pin, flag,
forward, mark read), folder create/rename/move/delete/empty with drag-and-drop of messages,
favourites, advanced search across all folders, filters (unread, flagged, to me, has files,
mentions, pinned), sorting by date/sender/subject/size/importance/flag/category.

Compose: rich-text editor with fonts, sizes, colours, highlight, lists, tables, links, pictures,
emoji, @mentions, signatures (separate defaults for new mail and replies), templates, importance,
read and delivery receipts, Reply-To, aliases (send from another address), recipient
autocomplete from contacts, contact lists, recent recipients and people who have written to you,
undo send (0–30 seconds), schedule send, drafts with autosave, forward as attachment, edit as new.

Editing in QuillPen: when QuillPen, the suite's word processor, is installed, the message window
gets a **QuillPen** button that opens the message body in a complete embedded copy of QuillPen
(ribbon and all) inside the message window; *Done* brings the text back, *Cancel* keeps the old
body, and *Send* while QuillPen is open asks it for the latest text first. *Settings ▸ Compose and
reply ▸ Edit messages in QuillPen* chooses Off, Ask (offered in every new message) or Always. On
Android QuillPen is found automatically (it shares its editor through a content provider); the
desktop app looks in QuillPen's install folder (`resources/embed`) or a folder you choose; the web
version takes the address of a QuillPen web deployment. Not available on iOS. The protocol is the
suite's EMBED.md; QuillPost's side is `web/src/main/resources/web/embed.js`,
`web/src/main/kotlin/.../QuillPenEmbed.kt` and `app/src/main/java/.../QuillPenAndroid.kt`.

Reading: meeting invitations with Accept/Tentative/Decline (with or without a response),
read-receipt requests, importance banners, remote-image blocking, attachment preview, download
all as .zip, immersive reader with read-aloud, translation through a local Ollama model, print,
message source and headers, open in a new window, .eml download.

Mailbox: automatic replies (date range, contacts-only, once per sender per N days), mbox export
and .eml/.mbox import, storage quota, several accounts at once with unread counts.

**Calendar.** Day, 3-day, work week, week, month and agenda views; mini month; several
calendars with colours; recurring events (daily, weekdays, weekly, monthly by date or by "second
Tuesday", "last Friday", yearly, custom intervals, end by date or count) with single-occurrence
edits and deletions; time zones per event; reminders; free/busy/tentative/out-of-office "show
as"; categories; private events; meeting invitations sent as standard iCalendar (`.ics`) email
and replies tracked per attendee; `.ics` import and export; subscriptions to internet calendars
(`https://`/`webcal://`); holiday calendars (US, UK, Canada, Australia, India, Germany, France,
Christian, Jewish and Islamic holidays); a birthdays calendar from your contacts; drag to create,
move and resize events; print.

**People.** Contacts with names, several e-mail addresses, phones and addresses, company,
birthday, anniversary, notes, categories and favourites; contact lists (groups) that expand when
used as a recipient; vCard (`.vcf`, versions 2.1/3.0/4.0) and CSV (Outlook and Google column
names) import and export; recent messages from each contact.

**To Do.** My Day, Important, Planned, Tasks, Flagged email and your own lists; steps, due dates,
reminders, repeating tasks, notes and categories; a My Day pane next to the inbox.

**Everywhere.** Light/dark mode and nine accent colours, compact/comfortable density, three
reading-pane layouts, zoom, Outlook-style keyboard shortcuts (`Ctrl+N`, `Ctrl+R`, `Ctrl+Shift+R`,
`Ctrl+Shift+F`, `Ctrl+Q`, `Ctrl+U`, `Ctrl+Shift+V`, `Insert`, `Del`, `Ctrl+1`–`Ctrl+4` to switch
apps, `F9`, `/`, `?`), swipe actions on phones, desktop notifications
and reminders.
Menus, dialogs, settings pages and lists that have more below show it: a thin scrollbar that
widens when used, a shadow at the edge with more, and a *More* chip on menus and dialogs (the
suite's shared scroll cues, `scrollcue.js`). On phones every dialog, menu, drawer, toast and
floating button stays clear of the status bar, navigation bar, cut-outs and keyboard.

### What Quillbox cannot do

Quillbox talks to standard IMAP, POP3 and SMTP servers. Features that depend on Microsoft
Exchange or Microsoft 365 servers are not available, and Quillbox does not imitate them:

- shared mailboxes, delegate access, message recall, voting buttons, reactions, Copilot and
  Microsoft Teams integration;
- server-side free/busy look-up and room booking: invitations are sent as ordinary iCalendar
  e-mail, which Outlook, Google Calendar and Apple Calendar all understand;
- server-side automatic replies and rules: Quillbox applies them while it is running (always, for
  the server version; the desktop and phone apps must be open);
- calendar and contact sync with CalDAV/CardDAV or Exchange: calendars, contacts and tasks are
  stored by Quillbox per account and move between programs as `.ics`, `.vcf` and `.csv` files;
  internet calendars can be subscribed to read-only;
- Outlook `.pst`/`.ost` files: convert them first (for example with `readpst -M` from libpst) and
  import the resulting `.mbox` or `.eml` files;
- S/MIME signing and encryption.

**Where the data lives.** Each account's calendars, contacts, tasks, message categories and
flags, scheduled messages and settings are JSON files in the data directory (`~/.quillbox`, or
`$QUILLBOX_DATA_DIR`; inside the app's private storage on Android). Back that folder up to keep
them. Settings from 1.3 and earlier are upgraded automatically the first time 1.4 starts.

### API

The backend exposes a JSON API (`/api/session`, `/api/folders`, `/api/messages`, `/api/message`,
`/api/junk`, `/api/analyze`, `/api/rules/preview`, `/api/rules/apply`, `/api/settings`, …) with a
bearer token from `/api/session`. The original stateless endpoints `/api/inbox` and `/api/send`
are unchanged, so the iOS client keeps working.

## Other platforms

Quillbox started as an Android app; sibling clients live in their own folders. Each is built
by its own GitHub Actions workflow.

| Folder      | Target                          | Stack                                              | Build artifact |
|-------------|---------------------------------|----------------------------------------------------|----------------|
| `app/`      | Android (and **ChromeOS**)      | WebView shell around the embedded web client (Ktor CIO backend in-process, Jakarta Mail) | `.apk`         |
| `desktop/`  | Windows / macOS / Linux / Raspberry Pi OS | Native launcher (Compose Desktop) around the embedded web client | `.msi`/`.dmg`/`.deb` (x64 + arm64) + portable archives |
| `web/`      | Browser (self-hosted)           | Ktor backend (JVM, Jakarta Mail) + Outlook-style web UI, junk engine | server `.zip`  |
| `ios/`      | iPhone / iPad                   | SwiftUI + WebKit shell showing the web client served by your own Quillbox server | `.app` (simulator) |

Notes on the architecture choices:

- **One interface everywhere.** The Outlook-style UI and the backend live in `web/` and are
  compiled into every other target, so Android, iOS, Windows, macOS, Linux, ChromeOS and the
  browser all show the same three-pane client (stacked panes with a folder drawer on phones,
  folders + list with a sliding reading pane on tablets), the same junk verdicts, the same
  *Analyse & make rule* dialog and the same settings.
- **Android** runs the backend inside the app process and shows the UI in a WebView
  (`app/src/main/java/.../LocalServer.kt`, `MainActivity.kt`). No server needed.
- **ChromeOS** runs the Android APK directly — no separate build. The Linux `.deb` or the web
  zip also work in the ChromeOS Linux container.
- **Desktop** is the web client packaged as a native app: it embeds the same backend and
  Outlook-style UI (compiled straight from `web/`), starts it on `127.0.0.1` and opens it in
  your browser, with a tray icon to reopen or quit. Run locally with `cd desktop && ./gradlew run`;
  package installers with `./gradlew packageDistributionForCurrentOS` (.msi / .dmg / .deb, the
  arm64 .deb being the Raspberry Pi OS package).
- **Web**: browsers cannot open IMAP/SMTP sockets, so `web/` is a small backend that does the
  mail work and serves a browser UI. See the section above.
- **iOS**: Jakarta Mail is JVM-only and Apple does not allow a JVM on iOS, so the iPhone/iPad
  app cannot run the backend itself. It shows the same web client full-screen in WebKit, served
  by a Quillbox server on your own network — the `quillbox-web` zip on a PC, Mac, Raspberry Pi
  or NAS (it binds to all interfaces by default), or the desktop app started with
  `HOST=0.0.0.0`. Enter the address (for example `http://192.168.1.20:8080`) on the first
  screen; *Settings → Account → Change server* changes it later. Printing, attachment saving
  (share sheet), external links and `mailto:` are handled natively. The Xcode project is
  generated from `ios/project.yml` via [XcodeGen](https://github.com/yonaskolb/XcodeGen)
  (`cd ios && xcodegen generate && open Quillbox.xcodeproj`).

## Releases

Pushing a tag such as `v1.4.0` makes GitHub Actions build every target and attach the files
to one GitHub Release (`Releases → Quillbox v1.4.0`):

| Target | Release file |
|---|---|
| Android, ChromeOS | `quillbox-v1.4.0.apk` (signed when the signing secrets exist, else `-unsigned`) |
| Windows | `quillbox-desktop-v1.4.0-windows-x64.msi`, or the portable `.zip` |
| macOS | `quillbox-desktop-v1.4.0-macos-arm64.dmg`, or the portable `.tar.gz` |
| Linux x64 | `quillbox-desktop-v1.4.0-linux-x64.deb`, or the portable `.tar.gz` |
| Raspberry Pi OS (64-bit) / Linux arm64 | `quillbox-desktop-v1.4.0-linux-arm64.deb`, or the portable `.tar.gz` |
| Web / any OS with Java 17+ (servers, Termux, ChromeOS Linux) | `quillbox-web-v1.4.0-any-jvm.zip` → unzip, run `bin/quillbox-web` |
| iOS | `Quillbox-iOS-simulator.app.zip` (unsigned simulator build; a device build needs an Apple Developer account) |

### Making a release from Termux (Android)

Windows, macOS and iOS packages can only be built on those systems, so from a phone the job
is: build and test what the phone can (the web/JVM server), then push a tag and let GitHub
Actions build all eight targets. Everything below is typed into Termux.

One-time setup:

```bash
pkg update && pkg upgrade -y
pkg install -y git openjdk-17 gh unzip
termux-setup-storage                                  # optional: reach ~/storage/downloads
git config --global user.name  "Your Name"
git config --global user.email "you@example.com"
gh auth login                                         # GitHub CLI login (HTTPS + browser/token)
git clone https://github.com/socrtwo/quillbox.git
cd quillbox
mkdir -p ~/.gradle && printf 'org.gradle.jvmargs=-Xmx1536m\norg.gradle.daemon=false\n' >> ~/.gradle/gradle.properties
```

Build and test the web/JVM server on the phone (this is the same zip the release ships):

```bash
cd ~/quillbox/web
chmod +x gradlew
./gradlew build distZip --no-daemon                   # runs the junk-engine tests, writes build/distributions/quillbox-web-1.4.0.zip
cd build/distributions && unzip -o quillbox-web-1.4.0.zip && cd quillbox-web-1.4.0
PORT=8080 ./bin/quillbox-web                          # open http://localhost:8080 in the phone browser
```

Cut the release (all targets are built by GitHub Actions and attached to one GitHub Release):

```bash
cd ~/quillbox
git checkout main && git pull
# bump the version first if needed: app/build.gradle.kts (versionCode/versionName),
#   web/build.gradle.kts (version), desktop/build.gradle.kts (version, packageVersion)
git commit -am "Release v1.4.0"        # only if you changed something
git push
git tag -a v1.4.0 -m "Quillbox 1.4.0"
git push origin v1.4.0                 # <- this triggers the Android, Desktop, Web and iOS release builds

gh run list --limit 8                  # watch the four workflows
gh run watch                           # follow one interactively
gh release view v1.4.0                 # list the attached files when they are done
gh release download v1.4.0 -D ~/storage/downloads/quillbox-v1.4.0
```

If the repository has no signing secrets, the APK arrives unsigned; sign it on the phone:

```bash
pkg install -y apksigner
keytool -genkeypair -v -keystore ~/quillbox-release.jks -alias quillbox -keyalg RSA -keysize 2048 -validity 10000
apksigner sign --ks ~/quillbox-release.jks --ks-key-alias quillbox \
  --out quillbox-v1.4.0.apk ~/storage/downloads/quillbox-v1.4.0/quillbox-v1.4.0-unsigned.apk
```

To have CI sign it instead, add the secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`
and `KEY_PASSWORD` (see *Automated signed builds in CI* above):

```bash
base64 -w 0 ~/quillbox-release.jks | gh secret set KEYSTORE_BASE64
gh secret set KEYSTORE_PASSWORD
gh secret set KEY_ALIAS --body quillbox
gh secret set KEY_PASSWORD
```

[docs/RELEASING.md](docs/RELEASING.md) has the longer version, including the unofficial way
to build the APK inside Termux and what a phone cannot build (Windows/macOS installers,
a signed iOS app).

## Security note

Credentials and downloaded mail are stored locally only and are transmitted solely to the
user's own mail servers (on the web/iOS clients, via your own Quillbox backend). Use real
credentials only on your own device; the repository ships with placeholder values only.

The web backend keeps passwords in memory for the life of a session. On disk it writes rules,
settings, analysis results, and your calendars, contacts, tasks, message categories/flags and
scheduled messages (without any password) in its data directory. Scheduled messages are sent
from that queue while the backend runs. Put it behind HTTPS (a reverse proxy such as Caddy or
nginx) whenever it is reachable from outside the machine it runs on. The junk engine's only
outbound traffic is DNS: blocklist lookups for sending IPs and domains. Other outbound traffic
happens only when you ask for it: internet calendars you subscribe to, and remote images you
choose to load. Translation and the optional AI features use a local Ollama server only.
