# File formats

QuillPost recognises files by their **content**, not their extension: a `.dat`, a file with no
extension or a wrongly named file opens the same way as a correctly named one.

## Messages, contacts and calendars

**File ▸ Open File…** (`mail.openFile`), dropping a file on the window, or *Open with QuillPost*
from the system (Android "Open with" / "Share", desktop double-click via the installers' file
associations) opens one item:

* a **message** in a reader window like Outlook's message window: sender, recipients, date,
  importance, the body (HTML is sanitised exactly like mail in the Inbox; remote images and
  tracking pixels stay blocked until *Show images*; inline `cid:` images come from the file itself),
  attachments (click to open — attached messages, vCards, calendars and documents open in turn —
  or download), notes for signed/encrypted mail, and *Reply*, *Reply all*, *Forward* (the original
  attachments and inline images travel with it), *Save to folder* (any IMAP folder; Outlook items
  are converted to MIME first), *Save as .eml* and *Headers*. Outlook templates (`.oft`) open
  directly as a new message;
* **contacts** in a list with *Add to People*;
* **events** in a list with *Add to calendar* (choice of calendar);
* **documents, spreadsheets and presentations** — see the next section.

*Send File as Attachment…* (`mail.sendFileAsAttachment`) starts a new message with the opened file
(or files you choose) attached.

Code: `web/src/main/kotlin/.../fileformats/` (readers; `MessageFormats.detect / parse / toRfc822`
is the API the import pipeline uses for single messages), `FileRoutes.kt` (`/api/file/*`),
`web/src/main/resources/web/fileopen.js` (UI), `app/.../FileHandoff.kt` (Android),
`desktop/.../FileLaunch.kt` (desktop).

### Fidelity tiers (single items)

| Tier | Meaning |
|---|---|
| **1 — full** | Shown as the original program showed it: all text, formatting, inline images, attachments and header fields. |
| **2 — high** | Everything readable and nearly all formatting; small visual differences (for example RTF drawn as HTML). |
| **3 — content** | Text, attachments and the main fields; formatting or extras are simplified. |
| **hand-off** | Recognised and passed to the suite program or the system's program (see below). |
| **— recognised** | Identified and explained, but not read. |

### Formats

| Format | Programs and versions | Extensions | Detection signature | Tier | Limitations |
|---|---|---|---|---|---|
| **Internet message** (RFC 822 / 2822 / 5322 with MIME, RFC 2045–2049) | every mail program since the 1980s: Outlook Express, Windows (Live) Mail, Thunderbird, Apple Mail "Save As", Gmail "Download message", Eudora, Pegasus, Pine… | `.eml`, `.mai`, `.txt`, none | a header block at the start with at least two well-known fields (an mbox `From ` line or BOM in front is allowed; a second `From ` separator means a mailbox, left to Import) | 1 | Scripts, forms and external stylesheets are removed (as for all mail). |
| — encodings inside messages | — | — | — | 1 | RFC 2047 words in any charset (also split inside a multi-byte character, glued to text, or in file names), RFC 2231 parameters with continuations, raw 8-bit headers (UTF-8, else windows-1252), unknown or misspelt charsets (aliases, `ks_c_5601-1987`, `x-mac-*`…), UTF-7, broken base64 and quoted-printable, missing or wrong boundaries, missing closing boundaries, subtype-less `Content-Type: text`, format=flowed (RFC 3676), text/enriched and text/richtext, delivery and read reports, digests, `message/rfc822` attachments (openable). |
| — files inside message text | uuencode (Unix, 1980s–), begin-base64, yEnc (Usenet), BinHex 4.0 (classic Mac) | — | `begin 644 name`, `=ybegin`, `(This file must be converted with BinHex` | 1 | Decoded into attachments and removed from the text. |
| — Mac attachments | AppleSingle, AppleDouble (`multipart/appledouble`), `application/applefile` | — | `00 05 16 00` / `00 05 16 07` | 1 | The data fork with its real name; resource forks are dropped (no other system can use them). |
| **Gmail / Google Takeout** single messages | all | `.eml` | RFC 5322 + `X-GM-THRID` / `X-Gmail-Labels` | 1 | Labels are shown as a note (Takeout `.mbox` files go to Import). |
| **Apple Mail message** | Mail 2 (Mac OS X 10.4) – current | `.emlx`, `.partial.emlx` | first line a byte count, then the message, then an XML property list | 1 | `.partial.emlx` keeps large attachments in separate files, which are not included (a note says so). |
| **News article** | Outlook Express 4–6, Windows Mail | `.nws` | RFC 5322 with `Newsgroups:` | 1 | Newsgroups shown; replying writes an e-mail, not a news post. |
| **Web archive (MHTML, RFC 2557)** | Internet Explorer 5–11, Word "Single File Web Page", Outlook "Save as MHT", Chrome/Edge "Save page as MHTML" | `.mht`, `.mhtml` | MIME `multipart/related` with `Snapshot-Content-Location`, "Saved by …" or no mail fields | 2 | `Content-Location` references become local parts, stylesheets are inlined; scripts and remote resources are not loaded. |
| **Outlook item** (MS-OXMSG in MS-CFB) | Outlook 97 – Microsoft 365, ANSI and Unicode items | `.msg` | compound file `D0 CF 11 E0 A1 B1 1A E1` with `__properties_version1.0` and `__substg1.0_*` streams | 1 (HTML), 2 (RTF) | Messages: transport headers, sender (SMTP or Exchange name), To/Cc/Bcc from the recipient table, HTML body, HTML recovered from RTF (MS-OXRTFEX), RTF-only bodies drawn as HTML (fonts, sizes, colours, bold/italic/underline/strike, alignment, indents, tables, links, bullets, PNG/JPEG pictures), compressed RTF (MS-OXRTFCP), attachments, inline images by Content-ID, attached Outlook items (opened as messages), named properties, code pages. S/MIME items (`IPM.Note.SMIME*`) are unpacked from `smime.p7m`. Not shown: embedded OLE objects; WMF/EMF pictures in RTF (a placeholder is drawn). Exchange-only senders show their name without an address. |
| — Outlook contact, appointment, task, note | Outlook 97 – 365 | `.msg` | as above, `IPM.Contact`, `IPM.Appointment`, `IPM.Schedule.Meeting.*`, `IPM.Task`, `IPM.StickyNote` | 2 / 3 | Contacts (names, e-mail 1–3, phones, addresses, company, birthday, web page, notes) → *Add to People*. Appointments and meeting requests (start/end, all-day, location, busy status, reminder, attendees) → *Add to calendar*; the recurrence pattern (`PidLidAppointmentRecur`) is not expanded — only the first occurrence is added, with a note. Tasks and notes open as messages with a note. |
| **Outlook template** | Outlook 97 – 365 | `.oft` | as `.msg` (the extension marks it as a template) | 1 | Opens directly as a new message with the template's body, recipients and attachments. |
| **TNEF / winmail.dat** (MS-OXTNEF) | Outlook 97 – 365 and Exchange sending "Rich Text" mail | `winmail.dat`, `.tnef`, `application/ms-tnef` parts | `78 9F 3E 22` | 2 | Attachments with their long names, attached messages (TNEF inside TNEF), subject, sender, recipients, dates and the RTF/HTML body. Inside a message, the winmail.dat part is replaced by its contents. Embedded OLE objects are skipped. |
| **S/MIME** (RFC 8551, CMS) | Outlook, Apple Mail, Thunderbird… | `.p7m`, `smime.p7m`, `multipart/signed` | DER `30 …` + OID 1.2.840.113549.1.7.2 (signed) / .3 (enveloped) | 1 (signed), — (encrypted) | Signed content is shown; the signature is **not verified** (a note says so). Encrypted messages cannot be read without the recipient's private key; the note says to open them in a program holding the certificate. |
| **OpenPGP** (RFC 4880, RFC 3156) | PGP, GnuPG, Enigmail, Mailvelope… | `.asc`, `.pgp`, inline, `multipart/signed` / `multipart/encrypted` | `-----BEGIN PGP` | 1 (signed), — (encrypted) | Clear-signed text is shown without its armour (dash-escaping undone); not verified. Encrypted messages are not decrypted. |
| **vCard** (RFC 2426, RFC 6350) | Outlook 97 – 365, Outlook Express, Windows Contacts export, Apple Contacts, Google Contacts, Android, Nokia/Palm | `.vcf`, `.vcard` | `BEGIN:VCARD` (UTF-8, UTF-16 with or without BOM, or windows-1252) | 2 | 2.1 bare parameters (`TEL;WORK;VOICE`), quoted-printable with or without `CHARSET`, Apple `item1.` groups, 4.0 `tel:` URIs. Photos, custom labels and IM fields are not imported. Several cards per file. |
| **Windows Contacts** | Windows Vista – 11 | `.contact` | XML in `http://schemas.microsoft.com/Contact` | 2 | Names, e-mail, phones, addresses, company, title, birthday, web page, notes. |
| **Windows Address Book** | Windows 95 – XP, Outlook Express 4–6 | `.wab` | extension only | — recognised | Undocumented binary format with no public specification: the reader explains how to export vCards or CSV from Windows Contacts / Outlook Express instead. |
| **iCalendar** (RFC 5545) | Outlook 2007 – 365, Google Calendar, Apple Calendar, Thunderbird… | `.ics`, `.ical`, `.ifb` | `BEGIN:VCALENDAR` with `VERSION:2.0` | 1 | Events with time zones, recurrence, exceptions, alarms and attendees (the existing calendar reader). To-dos and journal entries are ignored. |
| **vCalendar 1.0** | Outlook 97 – 2003, Palm Desktop, Lotus Organizer, Nokia / Sony Ericsson phones | `.vcs` | `BEGIN:VCALENDAR` with `VERSION:1.0` | 2 | Converted to iCalendar: quoted-printable values, `TZ:` offsets and the 1.0 recurrence grammar (`D1`, `W2 MO WE #10`, `MD1 15`, `MP1 1+ MO`, `YM1 6`, `UNTIL` dates). Alarms (`AALARM`/`DALARM`) are replaced by the default reminder; `DAYLIGHT` rules are not applied. |
| Encoded single files | uuencode, yEnc, BinHex 4.0, AppleSingle, AppleDouble | `.uue`, `.hqx`, `.as`, none | as above | — | Decoded; the file inside is then recognised and opened. |

## Documents, spreadsheets and presentations

Word-processor, spreadsheet and presentation files are recognised by content and offered to the
Quill suite program that opens them with the original program's layout: **QuillPen**
(documents), **QuillGrid** (spreadsheets) and **QuillStage** (presentations). *Open in …* starts
the suite app on Android (`io.github.socrtwo.quillpen` / `.quillgrid` / `.quillstage`, or any app
that can open the file when it is not installed), hands the file to the program the operating
system has registered for it in the desktop app, and downloads it in a web browser (which cannot
start other programs). *Send as attachment* starts a new message with the file. Rendering
fidelity is that of the receiving program (tier **hand-off**). Detection: `DocumentFormats.kt`.

| Kind | Format | Detection signature |
|---|---|---|
| Document | Word 97 – 2003 (`.doc`, `.dot`), Word 6 / 95 | compound file with a `WordDocument` stream |
| Document | Word 2007 – 365 (`.docx`, `.docm`, `.dotx`, `.dotm`) | ZIP with `word/document.xml` |
| Document | Word 2003 XML | `progid="Word.Document"` or `<w:wordDocument` |
| Document | Word for DOS, Windows Write (`.doc`, `.wri`); Word for Macintosh 4/5 | `31 BE 00 00`; `FE 37 00` |
| Document | Rich Text Format (`.rtf`) | `{\rtf` |
| Document | OpenDocument text (`.odt`, `.ott`, flat `.fodt`), OpenOffice.org 1 (`.sxw`), StarWriter (`.sdw`) | stored `mimetype` entry; `<office:document` with the text MIME type; compound file `StarWriterDocument` |
| Document | WordPerfect 5.x – X9 (`.wpd`, `.wp`, `.wp5`, `.wp6`) | `FF 57 50 43` (`\xFFWPC`), product 1 / type 10 |
| Document | Microsoft Works 4 – 9 (`.wps`), WPS Writer | compound file with `MatOST` / `CONTENTS` |
| Document | Apple Pages '09 and 2013+ (`.pages`) | ZIP with `index.xml`, or `Index/Document.iwa` without slides or tables |
| Document | AppleWorks / ClarisWorks (`.cwk`), Lotus Word Pro (`.lwp`), Ami Pro (`.sam`), AbiWord (`.abw`), Hangul (`.hwp`) | `BOBO` at offset 4; `WordPro`; `[ver]`; `<abiword`; `HWP Document File` or compound file with `FileHeader` + `BodyText` |
| Document | Text, Markdown, HTML | extension (no signature) |
| Spreadsheet | Excel 97 – 2003 (`.xls`, `.xlt`), Excel 5 / 95, Excel 2.x – 4.0 | compound file with `Workbook` / `Book`; BIFF2–4 BOF record `09 00/02/04` |
| Spreadsheet | Excel 2007 – 365 (`.xlsx`, `.xlsm`, `.xltx`), binary `.xlsb` | ZIP with `xl/workbook.xml` / `xl/workbook.bin` |
| Spreadsheet | Excel 2003 XML Spreadsheet | `progid="Excel.Sheet"` or the `urn:schemas-microsoft-com:office:spreadsheet` namespace |
| Spreadsheet | OpenDocument (`.ods`, `.ots`, flat `.fods`), OpenOffice.org 1 (`.sxc`), StarCalc (`.sdc`) | `mimetype`; `<office:document`; compound file `StarCalcDocument` |
| Spreadsheet | Apple Numbers '09 and 2013+ (`.numbers`) | ZIP with `Index/CalculationEngine.iwa` or `Index/Tables/` |
| Spreadsheet | Lotus 1-2-3 (`.wks`, `.wk1`, `.wk3`, `.wk4`, `.123`) | `00 00 02 00 04/06 04`; `00 00 1A 00 xx 10 04` |
| Spreadsheet | Quattro Pro (`.wq1`, `.wb1`–`.wb3`, `.qpw`) | `00 00 02 00 2x 51`; compound file `NativeContent_MAIN` |
| Spreadsheet | Works spreadsheet (`.xlr`), WPS Spreadsheets (`.et`) | compound file `WksSSWorkBook`; `Workbook` + extension |
| Spreadsheet | SYLK (`.slk`), DIF (`.dif`), CSV / TSV, Gnumeric | `ID;P`; `TABLE` + `0,1`; extension; `<gnm:Workbook` or gzip + extension |
| Presentation | PowerPoint 97 – 2003 (`.ppt`, `.pps`, `.pot`), WPS Presentation (`.dps`) | compound file with `PowerPoint Document` |
| Presentation | PowerPoint 2007 – 365 (`.pptx`, `.pptm`, `.ppsx`, `.potx`) | ZIP with `ppt/presentation.xml` |
| Presentation | OpenDocument (`.odp`, `.otp`, flat `.fodp`), OpenOffice.org 1 (`.sxi`), StarImpress (`.sdd`) | `mimetype`; `<office:document`; compound file `StarImpressDocument` |
| Presentation | Apple Keynote '09 and 2013+ (`.key`) | ZIP with `index.apxl`, or `Index/Slide*.iwa` |
| Presentation | Corel Presentations (`.shw`), Lotus Freelance (`.prz`, `.pre`) | `\xFFWPC` + extension; extension |

Password-protected Office files (`EncryptedPackage`) are recognised by their extension and handed
over as they are. Files wrapped in uuencode, BinHex, AppleSingle or AppleDouble are unwrapped first.

### Tests

* Kotlin (`./gradlew test`): `fileformats/*Test.kt` build every fixture byte by byte from the
  specifications (`FixtureBuilders.kt`: compound files, Outlook items, TNEF streams, compressed RTF)
  and check each reader and detection rule; `SampleFilesTest` keeps the sample files in
  `src/test/resources/fixtures/samples` current; `OpenedFilesTest` covers the file store.
  `SAMPLES_DIR=… ./gradlew test --tests '*ExternalSamplesTest*'` runs the readers over a folder of
  real files (see `src/test/resources/fixtures/README.md` for the corpora used).
* JavaScript (`node --test web/src/test/js/*.test.js`): `fileopen.test.js`.
* Browser: `node web/src/test/e2e/openfile.mjs` against the demo server opens an `.eml`, a `.msg`,
  an `.oft`, a `winmail.dat`, a `.vcf`, an `.ics`, a `.vcs`, a `.docx` and an extension-less Outlook
  item through the real UI.
