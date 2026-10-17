# File formats

QuillPost recognises files by their **content**, not their extension: a `.dat`, a file with no
extension or a wrongly named file opens the same way as a correctly named one. Detection is in
`web/src/main/kotlin/.../mailstore/Sniffer.kt`.

How to open a file:

* **File ▸ Open File…** (`mail.openMessageFile`) — any file. A single message opens in the reading
  pane; a mailbox offers to import it; contacts and calendars go to People and Calendar; documents,
  spreadsheets and presentations are handed to QuillPen, QuillGrid or QuillStage (or any other app).
* **File ▸ Import Mailbox…** (`mail.importMailbox`) — a mailbox file, a whole mail folder (folder
  upload), or a `.zip` / `.tar` / `.tar.gz` / `.tgz` / `.gz` of either.
* **Drop** files or folders on the window.
* **Open with QuillPost** from the file manager (Android "Open with"/"Share", desktop file
  associations, iOS "Open in").

Every action is a command, so it can be put on the Quick Access Toolbar, given a key or found with
command search (Alt+Q). **Local Archives** (`mail.localArchives`) shows the imported mail.

## Mailboxes and mail stores

### Where imported mail goes

The **Import Mailbox** dialog shows the detected format and program, the folder tree with message
counts (and contacts / calendar / task items), lets you tick folders, and offers two destinations:

* **A folder of the signed-in IMAP account** — the source tree is recreated under a folder you
  name (and, optionally, under an existing folder). Messages are uploaded with IMAP `APPEND`
  keeping their status (read, replied, flagged, forwarded as `$Forwarded`, draft), their original
  arrival time as the IMAP `INTERNALDATE`, and categories/labels/tags as IMAP keywords (when the
  server accepts keywords; otherwise the message is stored without them and the report says so).
* **Local Archives** — a new read-only mail store in the account's data folder
  (`accounts/<id>/archives/<archive>/`): one mbox file (mboxrd) plus a JSON index per folder. It
  appears in the folder pane under *Local Archives*, with the same list, reading pane, attachments,
  search (headers and bodies), mark read/unread, flag, *Export folder (.mbox)* and *Save As (.eml)*
  as server folders. Moving or deleting messages there is refused (copy or forward instead); the
  whole archive can be removed from its context menu. Nothing is uploaded to the mail server.

Contacts, calendar items and tasks found in the source (Outlook) go to **People**, **Calendar** and
**To Do** when *Also import contacts, calendar items and tasks* is ticked.

Imports run in the background with a progress bar and **Cancel** (everything copied before the
cancel is kept). The finished dialog lists everything skipped or changed — damaged messages,
attachments that could not be resolved, messages the source had marked deleted (skipped unless
*Include messages … marked as deleted* is ticked) — and can save the report as a text file.

A single message file opens in the reading pane from *Local Archives ▸ Opened files*, where it
stays until removed, so it can be replied to, forwarded or saved to a folder.

### Fidelity tiers

| Tier | Meaning |
|---|---|
| **1 — full** | Every message byte-for-byte (or rebuilt with all its parts), folder tree, status flags, arrival dates and tags. |
| **2 — content + tree** | Messages and folder tree are exact; status is partial or best-effort (the format keeps it in an undocumented index). |
| **3 — recovery** | Messages are found by scanning an undocumented store; status and folders are not recovered. |
| **— recognised** | Identified and explained, but not read (see *Not supported*). |

### Formats

| Program | Versions | Extensions | Detection signature | Tier | Notes / limitations |
|---|---|---|---|---|---|
| **Microsoft Outlook** — Personal Folders | 97–2002 (ANSI), 2003–365 (Unicode) | `.pst` | `!BDN` at 0, client magic `SM`, `wVer` 14/15 (ANSI) or ≥ 23 (Unicode) | 1 | Read clean-room from Microsoft's published *[MS-PST]*: node/block B-trees, data and subnode trees, heap-on-node, property and table contexts, named-property map, "compressible" encoding. Folders (IPM subtree), original Internet headers (PidTagTransportMessageHeaders) or headers rebuilt from sender and recipient table (To/Cc/Bcc), plain, HTML and RTF bodies — compressed RTF expanded (*[MS-OXRTFCP]*), HTML recovered from encapsulated RTF (*[MS-OXRTFEX]*), genuine RTF converted to HTML with fonts, sizes, colours, alignment, tables, links and pictures — attachments (by value, inline `cid:` images, attached Outlook items as `message/rfc822`, OLE objects as files, linked attachments as a note), read / replied / forwarded / flagged / complete status, categories, importance, sensitivity, delivery time. Contacts → vCard (People), contact groups → contact lists, appointments → iCalendar with weekly/daily/monthly/yearly recurrence, location, attendees and reminder (Calendar), tasks → To Do. Sticky notes and journal entries are imported as messages. **Not supported:** "high" (cyclic) encryption — re-export from Outlook with compressible encryption. A PST *password* is only a checksum stored in the file and is not needed to read it. Recurrence exceptions (moved/deleted single occurrences) are not carried over; timed recurring events are anchored in UTC. |
| Microsoft Outlook / Exchange — Offline Folders | 2003–365, including the 4 KB-page format of Outlook 2013+ | `.ost` | `!BDN`, client magic `SO`, `wVer` 23 or ≥ 36 (4 KB pages, zlib-compressed blocks) | 1 | As PST. Mail is taken from *Root – Mailbox ▸ IPM_SUBTREE*; public-folder and system folders are skipped. Verified with a public sample OST (see fixtures README). |
| Microsoft Outlook — Personal Address Book | 97–2002 | `.pab` | `!BDN`, client magic `AB` | 2 | Read with the PST reader; contacts become vCards. Untested against a real file. |
| **mbox** family (RFC 4155) | all | `.mbox`, `.mbx`, `.mbs`, none | a first line `From <sender> <asctime date>` | 1 | **mboxo / mboxrd** (`>From ` quoting undone one level), **mboxcl / mboxcl2** (Content-Length trusted when it lands on the next separator, so unquoted `From ` lines in bodies survive). Status from `Status` (R) / `X-Status` (A F D T), arrival time from the separator line. The dialect is reported in the dialog. |
| Mozilla Thunderbird, Netscape Messenger / Communicator, SeaMonkey, Mozilla Mail | all | profile folder: files without extension, `.sbd` sub-folders, `.msf` | mbox content + `X-Mozilla-Status` | 1 | Read, replied, flagged, deleted, forwarded from `X-Mozilla-Status`; tags from `X-Mozilla-Keys` and Netscape 4 labels from `X-Mozilla-Status2` as keywords (`$label1`…). `Inbox` + `Inbox.sbd/` become a folder with sub-folders. The `.msf` index (Mork) is ignored: everything in it is rebuilt from the messages. |
| Pine / Alpine, mutt, Evolution, KMail (mbox), Mutt/Mail on Unix, Zimbra (mbox / tgz export), Opera Mail (`.mbs` export) | all | `.mbox`, none, `.mbs`, `.tgz` | mbox | 1 | `X-Keywords` / `Keywords` become keywords. A Zimbra `.tgz` of `.eml` files is unpacked and read as a folder. |
| **Gmail (Google Takeout)** | all | `.mbox` (often inside `.zip` / `.tgz`) | mbox + `X-Gmail-Labels` | 1 | Split into folders by label: Inbox, Sent, Drafts, Spam, Trash, Chats, the first user label, else *All Mail*. Unread from `Unread`, flagged from `Starred`, *Important* → `$Important`, other labels → keywords. |
| **Maildir / Maildir++** — Courier, Dovecot, qmail, Postfix, KMail, Evolution, mutt, offlineimap, mbsync | all | folder with `cur/` `new/` `tmp/` (or a zip of it) | directory layout | 1 | Flags from `:2,` (or `!2,`) info: S R F T D P; Dovecot keywords (`a`–`z` via `dovecot-keywords`); `new/` = unread; arrival time = file time. Maildir++ `.Sent`, `.Archive.2020` (dot hierarchy, modified-UTF-7 names decoded) and KMail `.Name.directory` sub-folders. |
| **MH** — nmh, MH, Sylpheed, Claws Mail | all | folder of numbered files | numeric names holding messages, optional `.mh_sequences` | 1 | `unseen`, `flagged`, `replied`/`answered`, `forwarded` sequences. Claws Mail's binary `.claws_mark` is not read (status then defaults to read). |
| **Apple Mail** | Mail 2 (10.4) – current (V2…V10 layouts) | `.emlx`, `.partial.emlx`, `.mbox` bundles | `<byte count>\n` + message + XML plist | 1 | Flags from the plist bit field (read, deleted, answered, flagged, draft, forwarded, redirected, junk) with the flag colour as `$MailFlagBit0-2`; arrival date. Attachments stripped into `Attachments/<n>/<part>/` are put back into `.partial.emlx` messages. Nested `X.mbox` bundles become sub-folders. Mail 1.x bundles (an `mbox` file inside) are read as mbox. |
| **Windows Live Mail, Windows Mail (Vista), Outlook Express exports, any folder of `.eml`** | all | folders of `.eml` | RFC 5322 headers | 1 for content, status not available | These programs keep read status in an ESE database (`.edb`) that is not read; messages are imported as read. Sub-folders keep their names. |
| **Outlook Express 5 / 6** | 5.0–6.0 | `.dbx` + `Folders.dbx` | `CF AD 12 FE` + class id `C5 FD 74 6F` (folder) / `C6 FD 74 6F` (folder list) | 2 | Undocumented by Microsoft; read from the community format description. Raw messages are exact. With `Folders.dbx` (open the whole store folder or a zip of it) the folder tree is rebuilt. Read / replied / forwarded status and arrival time from the message index (best effort). A damaged index is recovered by scanning for message block chains (status then unknown). `Offline.dbx` / `Pop3uidl.dbx` hold no mail. |
| Outlook Express 4 | 4.x | `.mbx` (+ `.idx`) | `JMF6` | 2 | Messages located by their 16-byte record headers; status is not read (imported as read). |
| **Eudora** (Windows and Mac) | 1.x – 7.1, OSE | `.mbx` + `.toc`, `.fol` folders, `Attach` folder | mbox with `From ???@???` separators | 2 | The binary `.toc` was never published and changed between versions; QuillPost accepts it only when its records point exactly at message starts (Windows 104/218-byte and classic Mac 278/220-byte layouts), and then restores read / replied / forwarded / redirected / queued state and skips deleted-but-not-compacted messages. `Attachment Converted: "…"` lines: when the attachment folder is part of what was opened, the files are attached back to the message; otherwise they are listed in the report. |
| **Pegasus Mail** | 2.x – 4.x | `.pmm` (+ `.pmi`), `.cnm` | 128-byte header (folder name) + messages separated by Ctrl-Z | 2 | Folder name from the header; `.cnm` new-mail files are unread messages. The `.pmi` index and `hierarch.pm` are not read (status: read; hierarchy from the file system). |
| **The Bat!** | all | `.tbb` (+ `.tbn`) | file name (format undocumented) | 3 | Messages recovered by scanning for RFC 5322 header blocks; status, flags and folder tree are not recovered. Export to `.mbox`/`.eml` from The Bat! for full fidelity. |
| Archives | — | `.zip`, `.tar`, `.tgz`, `.tar.gz`, `.gz` | `PK`, `ustar`, `1F 8B` | — | Unpacked (zip-slip safe) and read as a folder. Office/OpenDocument/iWork zips are recognised as documents, not unpacked. |

### Not supported (recognised and explained)

| Format | Why |
|---|---|
| **Lotus Notes / HCL Domino** `.nsf` (signature `1A 00 00 0x 00 00`) | IBM's on-disk database format is proprietary and undocumented; there is no public specification to write a clean-room reader from (the only full readers use IBM's C API or reverse-engineered, non-permissively licensed code). Export from Notes/Domino to `.eml` / mbox, or copy the mail to an IMAP account, then import that. |
| **Claris Emailer** | No public description of its database format exists. Export from Emailer (or import into Apple Mail and export mbox) first. |
| Outlook PST/OST with "high" (cyclic) encryption | Needs key tables that are not part of QuillPost; re-export from Outlook. |
| `.msf`, `.toc`, `.idx`, `.pmi` index files on their own | They hold no messages; the dialog says which file to open instead. |
| 7-Zip, RAR, bzip2 | No decompressor in QuillPost; extract first or re-pack as `.zip`. |

### Other files recognised for hand-off

Detected by content and offered to the Quill suite app (or the system's handler) or as an
attachment, never imported as mail:

* **Word processors** → QuillPen: Word 97–2003 `.doc`, Word 2.0, Word for DOS/Mac, Write, Word 2003
  XML, `.docx` (OOXML), OpenDocument text (`.odt`, flat `.fodt`), OpenOffice 1 `.sxw`, StarWriter,
  RTF, WordPerfect, Works `.wps`, Publisher, AppleWorks/ClarisWorks, Hangul `.hwp`, Apple Pages,
  FrameMaker, Ami Pro, HTML, MHTML, plain text, Markdown, EPUB, PDF.
* **Spreadsheets** → QuillGrid: Excel 2–4 (BIFF) and 97–2003 `.xls`, `.xlsx`, SpreadsheetML 2003,
  OpenDocument `.ods`/`.fods`, `.sxc`, StarCalc, Lotus 1-2-3 (WKS/WK1/WK3/WK4), Works `.wks`,
  Quattro Pro, SYLK, DIF, Apple Numbers, CSV/TSV.
* **Presentations** → QuillStage: PowerPoint 97–2003 `.ppt`, `.pptx`, PowerPoint 2003 XML,
  OpenDocument `.odp`/`.fodp`, `.sxi`, StarImpress, Apple Keynote.

### Code map

| File | What it does |
|---|---|
| `mailstore/Sniffer.kt` | Detection of files and folders |
| `mailstore/MailStores.kt` | Opens any file or folder as a store; Maildir, MH, Apple bundles, Thunderbird `.sbd`, folders of `.eml` |
| `mailstore/Mbox.kt`, `Emlx.kt`, `Eudora.kt`, `Dbx.kt`, `ScanStores.kt` | The readers |
| `mailstore/Pst.kt`, `PstStore.kt`, `Rtf.kt` | PST/OST/PAB (NDB + LTP; messaging layer; RTF) |
| `MessageImport.kt` | Single message files → RFC 5322 (`MessageImport.parse`; extra parsers plug into `MessageImport.parsers`) |
| `MailImport.kt`, `MailImportRoutes.kt` | Uploads, analysis, background jobs, IMAP APPEND, `/api/mailbox/*`, `/api/archives` |
| `LocalArchives.kt` | The Local Archives store |
| `resources/web/mailimport.js` | Commands, dialogs, drag-and-drop, Local Archives in the folder pane |
