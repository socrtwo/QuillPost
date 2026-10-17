# Commands, Quick Access Toolbar and keyboard

Every action in QuillPost Mail is a **command** in one registry
(`web/src/main/resources/web/commands.js`): `{ id, label, icon, tab, group, tip, keys, kind
(button / toggle / menu), scope (mail / compose / global), run, menu, isEnabled, isActive,
origin }`. The ribbon, the Quick Access Toolbar (QAT), the right-click menus, the keyboard and
command search all run the same commands by id, so any command can be put anywhere.

| File | What it holds |
|---|---|
| `commands.js` | Registry, key names and keymaps, the QAT model (standard choices, *Add All*, sanitising) |
| `mailtools.js` | Pure helpers: quoted-text folding, thread digest, mutt patterns, pipe filters, MoodWatch, tracker detection, free time slots, pre-send checks… |
| `mailcommands.js` | The desktop mail client's own commands (File, Home, Send / Receive, Folder, View, Help, and the message window's Message, Insert, Options, Format Text, Review and Draw tabs), Undo/Redo, ribbon bindings |
| `mailextra.js` | Commands from other mail programs, commands original to QuillPost, and their list / reading-pane behaviour |
| `qat.js`, `commands.css` | The QAT, its drop-down, *More Commands…*, command search, keyboard customisation, command reference, File backstage, the extra ribbon and compose tabs, and the keyboard dispatcher |
| `CommandFeatures.kt`, `CommandRoutes.kt` | Server side: `/api/x/…` endpoints (below) |

The original handlers in `app.js` / `mailplus.js` still exist; the ribbon, reading-pane and
toolbar buttons are re-pointed at the commands when `qat.js` starts, so if the command files fail
to load the client keeps working as before.

## Quick Access Toolbar

* Sits **above the ribbon** (in the title bar), **below the ribbon**, or **at the bottom near the
  keyboard** (phones); can show labels, can be hidden (View → *Show Quick Access Toolbar*, or
  right-click any ribbon button to bring it back).
* Default: *Send/Receive All Folders* and *Undo*.
* The ▾ drop-down is a checklist that starts with the desktop client's standard Mail choices —
  Send/Receive All Folders, Reply, Reply All, Forward, Delete, Undo, Redo, Print, Touch/Mouse
  Mode — then Save, New Email, Archive, Mark as Read, Flag, Move To, Report Junk, Mark All as
  Read, Update Folder, Send All, Reading Pane and Search Commands, followed by *Add All Choices
  (Save, Print first, then A–Z)*, *More Commands…*, the position choices, *Show Command Labels*,
  *Hide Quick Access Toolbar*, *Customize Keyboard Shortcuts…* and *Collapse the Ribbon*.
* **More Commands…** opens the chooser: *Popular Commands*, *Commands Not in the Ribbon*, *All
  Commands*, one list per tab and *Macros (Quick Steps)*; filter box; Add, Remove, Up, Down,
  Separator, Add All A–Z, Reset; Import/Export (a JSON file that also carries your key
  bindings); position, labels, touch mode and hide options.
* Right-click any ribbon, compose or QAT button: add/remove it from the QAT, assign a shortcut,
  read what it does and where it comes from.
* Stored per device in `localStorage` (`qb.qat`, `qb.keymap`, `qb.keyScheme`).

## Keyboard and command search

* **Alt+Q** (or Ctrl+K outside the message editor) opens command search: type what you want to
  do, Enter runs it, the pin adds it to the QAT. Empty search shows recent and popular commands.
* **Help → Customize Keyboard Shortcuts…** assigns or removes keys for any command. Single keys
  work in the message list; Ctrl/Alt combinations and F-keys work everywhere. Several commands may
  share a key in different places (Ctrl+K inserts a link while writing and opens command search
  elsewhere).
* **Keyboard scheme**: desktop mail (default), **Gmail-style** (j/k, e, #, x, s, !, m, l, v, +)
  or **Mutt/Pine-style** (j/k, d/u, `|` pipe, b bounce, t tag, T tag pattern, `;` apply, l
  limit, L list reply, `$`/x expunge, c change folder, s save, h headers, Tab next unread).
* Help → *Keyboard Shortcuts* (`?`) is generated from the registry, so it always shows the keys
  in force.

## Ribbon additions

* **File** opens the backstage (Info, Open & Export, Save, Print, Options).
* New **Send / Receive**, **Folder** and **Tools** tabs; the compose window gains **Review** and
  **Draw** tabs and more buttons on Message, Insert, Format Text and Options.
* Context menus gain *Tag*, *More actions* and *Insights* submenus.

## Commands from other mail programs

| Program | Commands (real behaviour, not stubs) |
|---|---|
| **Eudora** | *Redirect* (resent unchanged with Resent-* headers, author shown "by way of" you), *Send Again* (extracts the undelivered original from a bounce and reopens it), *Filter Messages* (runs your rules on the selection only), *Make Nickname* / *Nicknames*, *MoodWatch* (0–3 chili peppers for heated text; outgoing check and incoming badge), *Change Status* (unread / read / replied / forwarded / redirected via IMAP flags), *Personalities* (send-as identity plus its signature), *Priority* (X-Priority 1–5), *Queue for Delivery* and *Send Queued Messages*, *Paste As Quotation* |
| **Pine / Alpine, mutt** | *Tag/Untag Messages Matching Pattern* and *Limit View (Zoom)* with mutt patterns (`~f ~t ~c ~s ~b ~d ~z ~N ~F ~Q ~P ~p ~X ~y ~l …`, `!`, `|`, parentheses), *Apply Command to Tagged*, *Pipe Message* (built-in grep, sort, uniq, wc, sed, cut, tr, head, tail, rot13, urls, emails… — nothing runs on the server shell), *Bounce*, *Take Address*, *Mark for Deletion* / *Undelete* (IMAP \Deleted, removed by *Compact*), *Jump to Message Number*, *Full Headers*, *Next/Previous Unread*, *Reply to List*, *Mark/Delete Conversation*, *Toggle New*, *Sort by Thread* |
| **Pegasus Mail** | *Glossary* (type an abbreviation, press F3), *Mail Merge* (personalised copies to every member of a contact group), *Copy to Self* |
| **Lotus Notes** | *Follow Up* with priority and reminder, *Copy Into New Calendar Entry*, *Copy Into New To Do*, *Mood Stamps*, *Who Is In This Conversation* |
| **Netscape Messenger / Thunderbird / Outlook Express** | *Tags* ($label1–$label5 and your own, stored as IMAP keywords; keys 1–5, 0), *Quick Filter Bar*, *Watch Conversation* (notification on replies), *Archive by Year / Month*, *Delete Attachments (Keep Message)*, *Message Body As* (original / simple HTML / plain text), *Delete Mail Marked as Junk*, *Go Back / Forward*, *Mark as Read by Date*, *Compact Folder*, *Edit As New*, *Run Rules (Filters) Now*, *Next Flagged / Unread Conversation* |
| **Gmail** | *Mute Conversation* (archived, later replies skip the Inbox — enforced on the server), *Label As*, *Mark as Important* ($Important), *Filter Messages Like These*, *Print All*, *Forward All*, *Create Event*, *Undo Send* (takes the waiting message back into Drafts and reopens it), *Questions Waiting for You* (nudges) |
| **Apple Mail** | *VIPs*, *VIP Mail*, *Notify Only for VIPs*, *Remind Me* (message comes back unread and pinned), *Protect Mail Activity* (tracking pixels blocked even when pictures are shown), *Unsubscribe* |
| **Hey, Superhuman, Spark** | *The Screener* (first-time senders wait until you say yes; "no" files them in Screened Out with a rule), *Paper Trail*, *Set Aside*, *Reply Later* + *Focus & Reply*, *Bundle Newsletters*, *Split Inbox* (VIP / People / Newsletters / Notifications / Receipts), *Snippets*, *Instant Intro*, *Get Me to Zero*, *Remind Me If No Reply*, *Send Later* |
| **Claris Emailer, Opera M2** | *Unified Inbox* (every signed-in account), *Mailing Lists* (grouped by List-Id), *Attachments by Type*, *Low Bandwidth Mode* |
| **Outlook** (desktop) | Everything that makes sense for IMAP/POP3/SMTP: see the File, Home, Send / Receive, Folder, View, Help, Message, Insert, Options, Format Text, Review and Draw tables below — e.g. Send/Receive All, Update Folder, Send All, Work Offline, Clean Up Conversation/Folder, Ignore, Never Block Sender/Domain/List, Reply with Meeting, Always Move Messages in This Conversation, Follow Up (Today…Custom, Add Reminder), Search Folders, Copy Folder, Recover Deleted Items, Folder Properties, Mailbox Cleanup, Save As (.eml/.txt/.html), Edit Message, Business Card, Attach Item, Calendar, Sensitivity, Follow Up for recipients, Delay Delivery, Format Painter, Change Case, Accessibility checker, Word Count, Draw, Screenshot, Undo/Redo of mail actions |

## Commands original to QuillPost (origin "QuillPost")

*Reply with Time Slots* / *Insert Free Time Slots* (free times from the built-in Calendar),
*Thread Digest* (per-person new text, open questions, requests, dates and links — no AI, quoted
history folded away), *Attachment Shelf*, *Waiting for Reply* (sent mail nobody answered, with a
Nudge button), *Unsubscribe Everywhere* (RFC 8058 one-click where offered, else an e-mail, else
the web page), *Sender Timeline* (all mail to/from a person, first contact, your reply time),
*Big-Attachment Finder* (with strip and delete), *Check Quote Integrity* (does a reply quote you
faithfully?), *Bulk Act on Sender or Domain*, *Sender Rules* (VIP, Focused, safe/blocked,
Screener, pictures, routing in one panel), *Preview as Plain Text / Safe HTML*, *Header Inspector*
(SPF/DKIM/DMARC with alignment, DKIM signers, Received hops and delays), *Clean Tracking Links*
(utm_*, fbclid… removed from links you open and send), *Check Message Before Sending* (and
*Always Check*), *Triage Mode*, *Export Conversation as Markdown*.

## Server endpoints added (`/api/x/…`)

`resend` (bounce / redirect with Resent-* headers, envelope sender = you, body and DKIM signature
untouched), `flags` (IMAP keywords and system flags), `keywords`, `compact`, `markread-range`,
`archive` (by year/month, or everything older than N days), `folder-stats`, `folder-copy`,
`large`, `deleted`, `shelf`, `rewrite` (strip attachments / change subject, keeping Message-ID and
flags), `lists`, `unsubscribe` (RFC 8058 one-click POST with private-network guard, or mailto via
SMTP), `inspect`, `original` (bounced message), `awaiting`, `outbox/flush`, `routes` (mute /
always-move conversations, applied by a per-account background job), and `state/{name}` (small
per-account JSON documents: tags, VIPs, screener, watched threads, search folders, snippets).
Message summaries now carry their IMAP keywords (`keywords`, including `\Deleted` while a message
is marked for deletion).

## Not included, and why

* **Exchange / Microsoft 365-only**: Recall, voting buttons, delegate and shared mailboxes,
  IRM/Permission, Teams/IM, Groups, OneNote, Loop components, Viva Insights, Copilot, add-ins,
  server-side free/busy and room finder, "Save Sent Item To" a different folder (the copy is
  filed by the server path that sends it), meeting polls.
* **Cloud services**: Smart Compose / smart replies and Superhuman Auto Summarize (AI); Dictate,
  Editor grammar, Smart Lookup / Researcher, Thesaurus and Online Pictures (Microsoft/Bing
  services); Fastmail Masked Email, Apple Hide My Email and Mail Drop, Gmail Confidential Mode,
  Spark shared links (provider accounts). The browser's own spell checker is used for *Spelling*.
* **Read tracking** (Superhuman/Spark "read statuses"): deliberately not offered — QuillPost blocks
  tracking pixels instead.
* **S/MIME and PGP** signing/encryption, Pegasus noticeboards, Outlook Download Headers / Mark to
  Download (POP3 offline-store features; QuillPost reads mail online), *Cancel All* (requests are
  not batched), *Ignore Subthread*.

## Tests

* `node --test web/src/test/js/*.test.js` — registry, keys and schemes, QAT ordering and
  sanitising, reference integrity (every id used by the ribbon, menus, QAT and schemes exists, every
  icon exists), and the pure mail helpers.
* `cd web && ./gradlew test` — includes `CommandFeaturesTest.kt`: pure helpers and the mail
  operations against an in-memory IMAP/SMTP server (bounce, redirect, tags, mark-for-deletion and
  compact, mark read by date, statistics, mailing lists, strip attachments, copy folder, Send
  Again).
* `node scripts/gen-commands-doc.js` regenerates the table below.

<!-- generated -->
## Command table

367 commands. Origin "Outlook" marks the desktop mail client's own commands; other origins name the mail program a command comes from, and "QuillPost" marks commands original to this client. Every one can be placed on the Quick Access Toolbar (Customize → More Commands…), found with command search (Alt+Q) and given a shortcut (Help → Customize Keyboard Shortcuts).

### File (21)

| Command | Id | Group | Keys | Origin |
|---|---|---|---|---|
| Account Information | `file.info` | Info |  | Outlook |
| Add Account | `file.addAccount` | Info |  | Outlook |
| Account Settings | `file.accountSettings` | Info |  | Outlook |
| Switch Account | `file.switchAccount` | Info |  | Outlook |
| Automatic Replies (Out of Office) | `file.autoReplies` | Info |  | Outlook |
| Mailbox Cleanup… | `file.mailboxCleanup` | Info |  | Outlook |
| Manage Rules & Alerts | `file.manageRules` | Info |  | Outlook |
| Import/Export | `file.importExport` | Open & Export |  | Outlook |
| Import Messages (.eml / .mbox)… | `file.importMessages` | Open & Export |  | Outlook |
| Export Folder (.mbox) | `file.exportFolder` | Open & Export |  | Outlook |
| Open Calendar File (.ics)… | `file.openCalendar` | Open & Export |  | Outlook |
| Save | `file.save` | Save | Ctrl+S | Outlook |
| Save As (.eml) | `file.saveAs` | Save | F12 | Outlook |
| Save As Text (.txt) | `file.saveAsText` | Save |  | Outlook |
| Save As Web Page (.html) | `file.saveAsHtml` | Save |  | Outlook |
| Save All Attachments | `file.saveAttachments` | Save |  | Outlook |
| Print | `file.print` | Print | Ctrl+P | Outlook |
| Print Preview | `file.printPreview` | Print |  | Outlook |
| Quick Print (Selected Messages) | `file.quickPrint` | Print |  | Outlook |
| Options | `file.options` | Options |  | Outlook |
| Exit (Sign Out) | `file.signOut` | Options |  | Outlook |

### Home (85)

| Command | Id | Group | Keys | Origin |
|---|---|---|---|---|
| New Email | `mail.newMail` | New | N, Ctrl+N | Outlook |
| New Items | `mail.newItems` | New |  | Outlook |
| New Email from Template | `mail.newFromTemplate` | New |  | Outlook |
| New Appointment | `mail.newEvent` | New | Ctrl+Shift+A | Outlook |
| New Meeting | `mail.newMeeting` | New | Ctrl+Shift+Q | Outlook |
| New Contact | `mail.newContact` | New | Ctrl+Shift+C | Outlook |
| New Contact Group | `mail.newContactGroup` | New | Ctrl+Shift+L | Outlook |
| New Task | `mail.newTask` | New | Ctrl+Shift+K | Outlook |
| Delete | `mail.delete` | Delete | Delete, Backspace | Outlook |
| Delete Permanently | `mail.deletePermanent` | Delete | Shift+Delete | Outlook |
| Ignore Conversation | `mail.ignore` | Delete |  | Outlook |
| Clean Up Conversation | `mail.cleanUpConversation` | Delete |  | Outlook |
| Archive | `mail.archive` | Delete | E | Outlook |
| Report Junk | `mail.junk` | Delete | J | Outlook |
| Not Junk | `mail.notJunk` | Delete | Shift+J | Outlook |
| Junk Options | `mail.reportMenu` | Delete |  | Outlook |
| Report Phishing | `mail.phish` | Delete |  | Outlook |
| Block Sender | `mail.blockSender` | Delete |  | Outlook |
| Never Block Sender | `mail.neverBlockSender` | Delete |  | Outlook |
| Never Block Sender's Domain | `mail.neverBlockDomain` | Delete |  | Outlook |
| Never Block This Group or Mailing List | `mail.neverBlockList` | Delete |  | Outlook |
| Junk Email Options | `mail.junkOptions` | Delete |  | Outlook |
| Sweep | `mail.sweep` | Delete |  | Outlook |
| Reply | `mail.reply` | Respond | R, Ctrl+R | Outlook |
| Reply All | `mail.replyAll` | Respond | A, Ctrl+Shift+R | Outlook |
| Forward | `mail.forward` | Respond | F, Ctrl+Shift+F | Outlook |
| Forward as Attachment | `mail.forwardAttach` | Respond | Ctrl+Alt+F | Outlook |
| Reply with Meeting | `mail.replyMeeting` | Respond | Ctrl+Alt+R | Outlook |
| Reply to Sender Only | `mail.replySender` | Respond |  | Outlook |
| Edit As New Message | `mail.editAsNew` | Respond | Ctrl+Alt+E | Thunderbird |
| More Respond Actions | `mail.respondMore` | Respond |  | Outlook |
| Quick Steps | `mail.quickSteps` | Quick Steps |  | Outlook |
| New Quick Step… | `mail.newQuickStep` | Quick Steps |  | Outlook |
| Manage Quick Steps | `mail.manageQuickSteps` | Quick Steps |  | Outlook |
| Move To | `mail.moveTo` | Move | Ctrl+Shift+V | Outlook |
| Move to Other Folder… | `mail.moveOther` | Move |  | Outlook |
| Copy to Folder… | `mail.copyTo` | Move |  | Outlook |
| Always Move Messages in This Conversation… | `mail.alwaysMoveConversation` | Move |  | Outlook |
| Rules | `mail.rulesMenu` | Move |  | Outlook |
| Create Rule… | `mail.createRule` | Move |  | Outlook |
| Always Move Messages From Sender… | `mail.alwaysMoveFromSender` | Move |  | Outlook |
| View in Browser | `mail.viewInBrowser` | Move |  | Outlook |
| Open in New Window | `mail.openInWindow` | Move | Ctrl+O | Outlook |
| Unread/Read | `mail.toggleRead` | Tags |  | Outlook |
| Mark as Read | `mail.markRead` | Tags | Ctrl+Q | Outlook |
| Mark as Unread | `mail.markUnread` | Tags | Ctrl+U, U | Outlook |
| Categorize | `mail.categorize` | Tags |  | Outlook |
| Clear All Categories | `mail.clearCategories` | Tags |  | Outlook |
| Manage Categories | `mail.manageCategories` | Tags |  | Outlook |
| Flag / Unflag | `mail.flag` | Tags | Insert, S | Outlook |
| Follow Up | `mail.followUp` | Tags |  | Outlook |
| Follow Up: Today | `mail.flagToday` | Tags |  | Outlook |
| Follow Up: Tomorrow | `mail.flagTomorrow` | Tags |  | Outlook |
| Follow Up: This Week | `mail.flagThisWeek` | Tags |  | Outlook |
| Follow Up: Next Week | `mail.flagNextWeek` | Tags |  | Outlook |
| Follow Up: No Date | `mail.flagNoDate` | Tags |  | Outlook |
| Follow Up: Custom… | `mail.flagCustom` | Tags |  | Outlook |
| Add Reminder… | `mail.addReminder` | Tags |  | Outlook |
| Mark Complete | `mail.markComplete` | Tags |  | Outlook |
| Clear Flag | `mail.clearFlag` | Tags |  | Outlook |
| Pin / Unpin | `mail.pin` | Tags |  | Outlook |
| Snooze | `mail.snooze` | Tags |  | Outlook |
| Search Mail | `mail.focusSearch` | Find | /, Ctrl+E, F3 | Outlook |
| Advanced Find… | `mail.advancedSearch` | Find | Ctrl+Alt+A | Outlook |
| Search People | `mail.searchPeople` | Find |  | Outlook |
| Address Book | `mail.addressBook` | Find | Ctrl+Shift+B | Outlook |
| Filter Email | `mail.filterEmail` | Find |  | Outlook |
| Clear Filter | `mail.clearFilter` | Find |  | Outlook |
| Find Related: Messages in This Conversation | `mail.findRelatedConversation` | Find |  | Outlook |
| Find Related: Messages from Sender | `mail.findRelatedSender` | Find |  | Outlook |
| Analyse & Make Rule | `mail.analyze` | Find |  | Outlook |
| Re-scan Folder for Junk | `mail.rescan` | Find |  | Outlook |
| Read Aloud | `mail.readAloud` | Speech | Ctrl+Alt+Space | Outlook |
| Translate Message | `mail.translate` | Speech |  | Outlook |
| Select All | `mail.selectAll` | Select | Ctrl+A | Outlook |
| Select / Deselect Message | `mail.toggleSelect` | Select | X | Outlook |
| Next Item | `mail.nextItem` | Select | ArrowDown, Ctrl+. | Outlook |
| Previous Item | `mail.prevItem` | Select | ArrowUp, Ctrl+, | Outlook |
| Copy Message Text | `mail.copyText` | Select |  | Outlook |
| Copy Sender Address | `mail.copySender` | Select |  | Outlook |
| Add Sender to Contacts | `mail.addSenderToContacts` | Select |  | Outlook |
| View Message Source | `mail.viewSource` | Select |  | Outlook |
| Edit Message (Subject)… | `mail.editMessage` | Select |  | Outlook |
| Undo | `edit.undo` | Undo | Ctrl+Z | Outlook |
| Redo | `edit.redo` | Undo | Ctrl+Shift+Z | Outlook |

### Send / Receive (7)

| Command | Id | Group | Keys | Origin |
|---|---|---|---|---|
| Send/Receive All Folders | `mail.sendReceiveAll` | Send & Receive | F9 | Outlook |
| Update Folder | `mail.updateFolder` | Send & Receive | Shift+F9 | Outlook |
| Send All | `mail.sendAll` | Send & Receive |  | Outlook |
| Show Progress | `mail.showProgress` | Send & Receive |  | Outlook |
| Send/Receive Settings | `mail.sendReceiveSettings` | Send & Receive |  | Outlook |
| Work Offline | `mail.workOffline` | Send & Receive |  | Outlook |
| Outbox (Scheduled) | `mail.viewOutbox` | Send & Receive |  | Outlook |

### Folder (20)

| Command | Id | Group | Keys | Origin |
|---|---|---|---|---|
| New Folder | `folder.new` | New | Ctrl+Shift+E | Outlook |
| New Subfolder | `folder.newSub` | New |  | Outlook |
| Rename Folder | `folder.rename` | Actions |  | Outlook |
| Copy Folder… | `folder.copy` | Actions |  | Outlook |
| Move Folder… | `folder.move` | Actions |  | Outlook |
| Delete Folder | `folder.delete` | Actions |  | Outlook |
| Go to Folder… | `folder.goTo` | Actions | Ctrl+Y | Outlook |
| Show in Favorites | `folder.favorite` | Actions |  | Outlook |
| Folder Properties | `folder.properties` | Actions |  | Outlook |
| Mark All as Read | `folder.markAllRead` | Clean Up | Ctrl+Alt+Q | Outlook |
| Mark as Read by Date… | `folder.markReadByDate` | Clean Up |  | Thunderbird |
| Run Rules Now | `folder.runRules` | Clean Up |  | Outlook |
| Clean Up Folder | `folder.cleanUp` | Clean Up |  | Outlook |
| Delete All | `folder.deleteAll` | Clean Up |  | Outlook |
| Empty Deleted Items Folder | `folder.emptyTrash` | Clean Up |  | Outlook |
| Recover Deleted Items | `folder.recoverDeleted` | Clean Up |  | Outlook |
| Compact Folder | `folder.compact` | Clean Up |  | Thunderbird |
| Archive Old Items… | `folder.archiveOld` | Clean Up |  | Outlook |
| Show All Folders A to Z | `folder.showAllAZ` | View |  | Outlook |
| New Search Folder… | `folder.newSearchFolder` | New |  | Outlook |

### View (29)

| Command | Id | Group | Keys | Origin |
|---|---|---|---|---|
| View Settings | `view.settings` | Current View |  | Outlook |
| Change View | `view.changeView` | Current View |  | Outlook |
| Reset View | `view.reset` | Current View |  | Outlook |
| Show as Conversations | `view.conversations` | Messages |  | Outlook |
| Conversation Settings | `view.conversationSettings` | Messages |  | Outlook |
| Expand All Conversations | `view.expandAll` | Messages |  | Outlook |
| Collapse All Conversations | `view.collapseAll` | Messages |  | Outlook |
| Message Preview | `view.preview` | Messages |  | Outlook |
| Arrange By | `view.arrangeBy` | Messages |  | Outlook |
| Reverse Sort | `view.reverseSort` | Messages |  | Outlook |
| Focused Inbox | `view.focused` | Messages |  | Outlook |
| Folder Pane | `view.folderPane` | Layout | Alt+F1 | Outlook |
| Reading Pane | `view.readingPane` | Layout |  | Outlook |
| Density | `view.density` | Layout |  | Outlook |
| To-Do Bar (My Day) | `view.todoBar` | Layout |  | Outlook |
| Reminders Window | `view.reminders` | Layout |  | Outlook |
| Close All Items | `view.closeAll` | Layout |  | Outlook |
| Collapse the Ribbon | `view.ribbon` | Layout | Ctrl+F1 | Outlook |
| Full Screen | `view.fullScreen` | Layout |  | Outlook |
| Zoom In (Reading Pane) | `view.zoomIn` | Zoom |  | Outlook |
| Zoom Out (Reading Pane) | `view.zoomOut` | Zoom |  | Outlook |
| Zoom 100% | `view.zoomReset` | Zoom |  | Outlook |
| Immersive Reader | `view.immersive` | Zoom |  | Outlook |
| Dark Mode | `view.dark` | Theme |  | Outlook |
| Colour Theme | `view.theme` | Theme |  | Outlook |
| Touch/Mouse Mode | `view.touchMode` | Quick Access Toolbar |  | Outlook |
| Show Quick Access Toolbar | `view.showQat` | Quick Access Toolbar |  | Outlook |
| Quick Access Toolbar Position | `view.qatPosition` | Quick Access Toolbar |  | Outlook |
| Show Command Labels | `view.qatLabels` | Quick Access Toolbar |  | Outlook |

### Help (10)

| Command | Id | Group | Keys | Origin |
|---|---|---|---|---|
| Help | `help.help` | Help | F1 | Outlook |
| Keyboard Shortcuts | `help.shortcuts` | Help | ? | Outlook |
| Search Commands (Tell Me) | `help.searchCommands` | Help | Alt+Q, Ctrl+K | Outlook |
| Command Reference | `help.commandReference` | Help |  | Outlook |
| Customize Quick Access Toolbar… | `help.customizeQat` | Help |  | Outlook |
| Customize Keyboard Shortcuts… | `help.customizeKeyboard` | Help |  | Outlook |
| Keyboard Scheme | `help.keySchemes` | Help |  | Outlook |
| What's New | `help.whatsNew` | Help |  | Outlook |
| Report a Problem | `help.feedback` | Help |  | Outlook |
| About QuillPost | `help.about` | Help |  | Outlook |

### Message (35)

| Command | Id | Group | Keys | Origin |
|---|---|---|---|---|
| Send | `compose.send` | Send | Ctrl+Enter, Alt+S | Outlook |
| Save Draft | `compose.saveDraft` | Send |  | Outlook |
| Discard | `compose.discard` | Send |  | Outlook |
| Pop Out / Dock | `compose.popOut` | Send |  | Outlook |
| Cut | `compose.cut` | Clipboard |  | Outlook |
| Copy | `compose.copy` | Clipboard |  | Outlook |
| Paste | `compose.paste` | Clipboard |  | Outlook |
| Paste as Plain Text | `compose.pastePlain` | Clipboard |  | Outlook |
| Format Painter | `compose.formatPainter` | Clipboard |  | Outlook |
| Bold | `compose.bold` | Basic Text |  | Outlook |
| Italic | `compose.italic` | Basic Text |  | Outlook |
| Underline | `compose.underline` | Basic Text |  | Outlook |
| Strikethrough | `compose.strike` | Basic Text |  | Outlook |
| Grow Font | `compose.growFont` | Basic Text | Ctrl+Shift+>, Ctrl+] | Outlook |
| Shrink Font | `compose.shrinkFont` | Basic Text | Ctrl+Shift+<, Ctrl+[ | Outlook |
| Text Highlight Colour | `compose.highlight` | Basic Text |  | Outlook |
| Font Colour | `compose.fontColor` | Basic Text |  | Outlook |
| Bullets | `compose.bullets` | Basic Text |  | Outlook |
| Numbering | `compose.numbering` | Basic Text |  | Outlook |
| Increase Indent | `compose.indent` | Basic Text |  | Outlook |
| Decrease Indent | `compose.outdent` | Basic Text |  | Outlook |
| Clear All Formatting | `compose.clearFormatting` | Basic Text | Ctrl+Space | Outlook |
| Attach File | `compose.attachFile` | Include |  | Outlook |
| Attach Item (Message) | `compose.attachItem` | Include |  | Outlook |
| Business Card (vCard) | `compose.businessCard` | Include |  | Outlook |
| Calendar (Free Times) | `compose.insertCalendar` | Include |  | Outlook |
| Signature | `compose.signature` | Include |  | Outlook |
| Link | `compose.link` | Include | Ctrl+K | Outlook |
| Address Book | `compose.addressBook` | Include |  | Outlook |
| Check Names | `compose.checkNames` | Include | Alt+K | Outlook |
| High Importance | `compose.importanceHigh` | Tags |  | Outlook |
| Low Importance | `compose.importanceLow` | Tags |  | Outlook |
| Sensitivity | `compose.sensitivity` | Tags |  | Outlook |
| Follow Up for Recipients… | `compose.followUpRecipients` | Tags |  | Outlook |
| My Templates | `compose.templates` | Tags |  | Outlook |

### Insert (12)

| Command | Id | Group | Keys | Origin |
|---|---|---|---|---|
| Table | `compose.table` | Insert |  | Outlook |
| Pictures | `compose.picture` | Insert |  | Outlook |
| Screenshot | `compose.screenshot` | Insert |  | Outlook |
| Drawing Canvas | `compose.draw` | Insert |  | Outlook |
| Horizontal Line | `compose.hr` | Insert |  | Outlook |
| Emoji | `compose.emoji` | Insert |  | Outlook |
| Symbol | `compose.symbol` | Insert |  | Outlook |
| Date & Time | `compose.dateTime` | Insert |  | Outlook |
| Text Box | `compose.textBox` | Insert |  | Outlook |
| Bookmark… | `compose.bookmark` | Insert |  | Outlook |
| Drop Cap | `compose.dropCap` | Insert |  | Outlook |
| Meeting | `compose.meeting` | Insert |  | Outlook |

### Draw (2)

| Command | Id | Group | Keys | Origin |
|---|---|---|---|---|
| Draw with Pen | `compose.drawPen` | Tools |  | Outlook |
| Highlighter (Sketch) | `compose.drawHighlighter` | Tools |  | Outlook |

### Options (10)

| Command | Id | Group | Keys | Origin |
|---|---|---|---|---|
| Bcc | `compose.showBcc` | Show Fields |  | Outlook |
| Cc | `compose.showCc` | Show Fields |  | Outlook |
| From | `compose.showFrom` | Show Fields |  | Outlook |
| Request a Read Receipt | `compose.readReceipt` | Tracking |  | Outlook |
| Request a Delivery Receipt | `compose.deliveryReceipt` | Tracking |  | Outlook |
| Delay Delivery… | `compose.delayDelivery` | More Options |  | Outlook |
| Send Later | `compose.sendLater` | More Options |  | Spark / Gmail |
| Direct Replies To… | `compose.directRepliesTo` | More Options |  | Outlook |
| Format: Plain Text | `compose.plainText` | More Options |  | Outlook |
| Page Colour | `compose.pageColor` | More Options |  | Outlook |

### Format Text (20)

| Command | Id | Group | Keys | Origin |
|---|---|---|---|---|
| Align Left | `compose.alignLeft` | Paragraph |  | Outlook |
| Center | `compose.alignCenter` | Paragraph |  | Outlook |
| Align Right | `compose.alignRight` | Paragraph |  | Outlook |
| Justify | `compose.justify` | Paragraph |  | Outlook |
| Line Spacing | `compose.lineSpacing` | Paragraph |  | Outlook |
| Shading | `compose.shading` | Paragraph |  | Outlook |
| Borders | `compose.borders` | Paragraph |  | Outlook |
| Sort Lines | `compose.sortLines` | Paragraph |  | Outlook |
| Show/Hide ¶ | `compose.showMarks` | Paragraph |  | Outlook |
| Style: Normal | `compose.styleNormal` | Styles |  | Outlook |
| Style: Heading 1 | `compose.styleH1` | Styles |  | Outlook |
| Style: Heading 2 | `compose.styleH2` | Styles |  | Outlook |
| Style: Heading 3 | `compose.styleH3` | Styles |  | Outlook |
| Style: Quote | `compose.styleQuote` | Styles |  | Outlook |
| Style: Code | `compose.styleCode` | Styles |  | Outlook |
| Superscript | `compose.superscript` | Font | Ctrl+Shift++ | Outlook |
| Subscript | `compose.subscript` | Font | Ctrl+= | Outlook |
| Change Case | `compose.changeCase` | Font |  | Outlook |
| Font | `compose.font` | Font |  | Outlook |
| Zoom (Message Body) | `compose.zoom` | Font |  | Outlook |

### Review (5)

| Command | Id | Group | Keys | Origin |
|---|---|---|---|---|
| Spelling & Grammar | `compose.spelling` | Proofing | F7 | Outlook |
| Word Count | `compose.wordCount` | Proofing | Ctrl+Shift+G | Outlook |
| Check Accessibility | `compose.accessibility` | Proofing |  | Outlook |
| Proofing Language | `compose.language` | Proofing |  | Outlook |
| Read Aloud (Draft) | `compose.readAloud` | Proofing |  | Outlook |

### Tools (111)

| Command | Id | Group | Keys | Origin |
|---|---|---|---|---|
| Redirect… | `eudora.redirect` | Eudora |  | Eudora |
| Send Again | `eudora.sendAgain` | Eudora |  | Eudora |
| Filter Messages | `eudora.filterMessages` | Eudora |  | Eudora |
| Make Nickname… | `eudora.makeNickname` | Eudora |  | Eudora |
| Nicknames | `eudora.nicknames` | Eudora |  | Eudora |
| MoodWatch | `eudora.moodWatch` | Eudora |  | Eudora |
| MoodWatch: Rate Incoming Mail | `eudora.moodWatchIncoming` | Eudora |  | Eudora |
| Change Status | `eudora.changeStatus` | Eudora |  | Eudora |
| Personalities | `eudora.personalities` | Eudora |  | Eudora |
| Priority | `eudora.priority` | Eudora |  | Eudora |
| Queue for Delivery | `eudora.queue` | Eudora |  | Eudora |
| Send Queued Messages | `eudora.sendQueued` | Eudora |  | Eudora |
| Paste As Quotation | `eudora.pasteQuotation` | Eudora |  | Eudora |
| Tag Messages Matching Pattern… | `pine.tagPattern` | Pine & mutt |  | Pine / mutt |
| Untag Messages Matching Pattern… | `pine.untagPattern` | Pine & mutt |  | Pine / mutt |
| Apply Command to Tagged… | `pine.applyToTagged` | Pine & mutt |  | Pine / mutt |
| Limit View to Pattern… (Zoom) | `pine.limit` | Pine & mutt |  | Pine / mutt |
| Show All Messages (Unzoom) | `pine.unlimit` | Pine & mutt |  | Pine / mutt |
| Pipe Message… | `pine.pipe` | Pine & mutt |  | Pine / mutt |
| Bounce… | `pine.bounce` | Pine & mutt |  | Pine / mutt |
| Take Address… | `pine.takeAddr` | Pine & mutt |  | Pine / mutt |
| Mark for Deletion | `pine.markDeleted` | Pine & mutt |  | Pine / mutt |
| Undelete | `pine.undelete` | Pine & mutt |  | Pine / mutt |
| Jump to Message Number… | `pine.jump` | Pine & mutt |  | Pine / mutt |
| Full Headers | `pine.headerMode` | Pine & mutt |  | Pine / mutt |
| Next Unread Message | `mutt.nextUnread` | Pine & mutt |  | Pine / mutt |
| Previous Unread Message | `mutt.prevUnread` | Pine & mutt |  | Pine / mutt |
| Next Flagged Message | `mutt.nextFlagged` | Pine & mutt |  | Thunderbird |
| Next Unread Conversation | `mutt.nextUnreadThread` | Pine & mutt |  | Thunderbird |
| Reply to List | `mutt.listReply` | Pine & mutt |  | Pine / mutt |
| Mark Conversation as Read | `mutt.readThread` | Pine & mutt |  | mutt / Thunderbird |
| Delete Conversation | `mutt.deleteThread` | Pine & mutt |  | Pine / mutt |
| Toggle New (Read/Unread) | `mutt.toggleNew` | Pine & mutt |  | Pine / mutt |
| Sort by Thread | `mutt.sortThreads` | Pine & mutt |  | Pine / mutt |
| Expand Glossary Entry | `pegasus.glossary` | Pegasus Mail | F3 | Pegasus Mail |
| Mail Merge… | `pegasus.mailMerge` | Pegasus Mail |  | Pegasus Mail |
| Copy to Self | `pegasus.copyToSelf` | Pegasus Mail |  | Pegasus Mail |
| Follow Up (with Priority)… | `notes.followUp` | Lotus Notes |  | Lotus Notes |
| Copy Into New Calendar Entry | `notes.copyToCalendar` | Lotus Notes |  | Lotus Notes |
| Copy Into New To Do | `notes.copyToTask` | Lotus Notes |  | Lotus Notes |
| Mood Stamp | `notes.moodStamp` | Lotus Notes |  | Lotus Notes |
| Who Is In This Conversation | `notes.whoIsIn` | Lotus Notes |  | Lotus Notes |
| Tag | `tb.tag` | Thunderbird |  | Netscape Messenger / Thunderbird |
| Tag: Important | `tb.tag1` | Thunderbird | 1 | Netscape Messenger / Thunderbird |
| Tag: Work | `tb.tag2` | Thunderbird | 2 | Netscape Messenger / Thunderbird |
| Tag: Personal | `tb.tag3` | Thunderbird | 3 | Netscape Messenger / Thunderbird |
| Tag: To Do | `tb.tag4` | Thunderbird | 4 | Netscape Messenger / Thunderbird |
| Tag: Later | `tb.tag5` | Thunderbird | 5 | Netscape Messenger / Thunderbird |
| Remove All Tags | `tb.removeTags` | Thunderbird | 0 | Netscape Messenger / Thunderbird |
| Quick Filter Bar | `tb.quickFilter` | Thunderbird | Ctrl+Alt+K | Netscape Messenger / Thunderbird |
| Watch Conversation | `tb.watchThread` | Thunderbird | W | Outlook Express / Thunderbird |
| Archive by Year | `tb.archiveYear` | Thunderbird |  | Netscape Messenger / Thunderbird |
| Archive by Month | `tb.archiveMonth` | Thunderbird |  | Netscape Messenger / Thunderbird |
| Delete Attachments (Keep Message) | `tb.stripAttachments` | Thunderbird |  | Netscape Messenger / Thunderbird |
| Message Body As | `tb.bodyAs` | Thunderbird |  | Netscape Messenger / Thunderbird |
| Delete Mail Marked as Junk in Folder | `tb.deleteJunkInFolder` | Thunderbird |  | Netscape Messenger / Thunderbird |
| Go Back | `tb.goBack` | Thunderbird | Alt+ArrowLeft | Netscape Messenger / Thunderbird |
| Go Forward | `tb.goForward` | Thunderbird | Alt+ArrowRight | Netscape Messenger / Thunderbird |
| Mute Conversation | `gmail.mute` | Gmail |  | Gmail |
| Muted and Routed Conversations | `gmail.unmuteAll` | Gmail |  | Gmail |
| Label As | `gmail.labels` | Gmail |  | Gmail |
| Mark as Important | `gmail.important` | Gmail |  | Gmail |
| Filter Messages Like These | `gmail.filterLike` | Gmail |  | Gmail |
| Print All (Conversation) | `gmail.printAll` | Gmail |  | Gmail |
| Forward All (Conversation) | `gmail.forwardAll` | Gmail |  | Gmail |
| Create Event | `gmail.createEvent` | Gmail |  | Gmail |
| Undo Send | `gmail.undoSend` | Gmail |  | Gmail |
| Add Sender to VIPs | `apple.vip` | Apple Mail |  | Apple Mail |
| VIP Mail | `apple.vipMail` | Apple Mail |  | Apple Mail |
| Notify Only for VIPs | `apple.vipNotify` | Apple Mail |  | Apple Mail |
| Remind Me | `apple.remindMe` | Apple Mail |  | Apple Mail |
| Protect Mail Activity (Block Trackers) | `apple.blockTrackers` | Apple Mail |  | Apple Mail |
| Unsubscribe | `mail.unsubscribe` | Apple Mail |  | Apple Mail / Gmail / Outlook |
| The Screener | `hey.screener` | Spark, Superhuman & Hey |  | Hey |
| Screen First-Time Senders | `hey.screenerToggle` | Spark, Superhuman & Hey |  | Hey |
| Paper Trail (Receipts) | `hey.paperTrail` | Spark, Superhuman & Hey |  | Hey |
| Move to Paper Trail | `hey.moveToPaperTrail` | Spark, Superhuman & Hey |  | Hey |
| Set Aside | `hey.setAside` | Spark, Superhuman & Hey |  | Hey |
| Show Set Aside | `hey.showSetAside` | Spark, Superhuman & Hey |  | Hey |
| Reply Later | `hey.replyLater` | Spark, Superhuman & Hey |  | Hey |
| Focus & Reply | `hey.focusReply` | Spark, Superhuman & Hey |  | Hey |
| Bundle Newsletters | `hey.bundle` | Spark, Superhuman & Hey |  | Hey / Inbox by Gmail |
| Split Inbox | `split.toggle` | Spark, Superhuman & Hey |  | Superhuman / Spark |
| Insert Snippet (Quick Parts) | `snip.insert` | Spark, Superhuman & Hey |  | Outlook Quick Parts / Superhuman Snippets |
| Manage Snippets | `snip.manage` | Spark, Superhuman & Hey |  | Superhuman / Spark / Hey |
| Instant Intro | `sh.instantIntro` | Spark, Superhuman & Hey |  | Superhuman |
| Get Me to Zero… | `sh.getMeToZero` | Spark, Superhuman & Hey |  | Superhuman |
| Remind Me If No Reply | `sh.remindNoReply` | Spark, Superhuman & Hey |  | Superhuman / Spark |
| Unified Inbox | `emailer.unifiedInbox` | Claris Emailer & Opera |  | Claris Emailer / Apple Mail |
| Mailing Lists | `opera.mailingLists` | Claris Emailer & Opera |  | Opera M2 |
| Attachments by Type | `opera.attachments` | Claris Emailer & Opera |  | Opera M2 |
| Low Bandwidth Mode | `opera.lowBandwidth` | Claris Emailer & Opera |  | Opera M2 |
| Reply with Time Slots | `new.replyTimeSlots` | Insights |  | QuillPost |
| Insert Free Time Slots | `new.insertTimeSlots` | Insights |  | QuillPost |
| Thread Digest | `new.threadDigest` | Insights |  | QuillPost |
| Attachment Shelf | `new.attachmentShelf` | Insights |  | QuillPost |
| Waiting for Reply | `new.waiting` | Insights |  | QuillPost |
| Questions Waiting for You | `new.nudges` | Insights |  | Gmail (Nudges) |
| Unsubscribe Everywhere… | `new.unsubscribeEverywhere` | Insights |  | QuillPost |
| Sender Timeline | `new.senderTimeline` | Insights |  | QuillPost (Opera M2 contact view) |
| Big-Attachment Finder | `new.bigAttachments` | Insights |  | QuillPost |
| Check Quote Integrity | `new.quoteCheck` | Insights |  | QuillPost |
| Bulk Act on Sender or Domain… | `new.bulkSender` | Insights |  | QuillPost |
| Sender Rules | `new.senderRules` | Insights |  | QuillPost |
| Preview as Plain Text / Safe HTML | `new.plainPreview` | Insights |  | QuillPost |
| Header Inspector (SPF/DKIM/DMARC) | `new.headerInspector` | Insights |  | QuillPost |
| Clean Tracking Links | `new.cleanLinksToggle` | Insights |  | QuillPost |
| Check Message Before Sending | `new.presend` | Insights |  | QuillPost |
| Always Check Before Sending | `new.presendAuto` | Insights |  | QuillPost |
| Triage Mode | `new.triage` | Insights |  | QuillPost |
| Export Conversation as Markdown | `new.exportMarkdown` | Insights |  | QuillPost |

