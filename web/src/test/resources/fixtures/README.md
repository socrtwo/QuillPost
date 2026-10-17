# Test fixtures

## `samples/` (committed)

Files for the browser test `src/test/e2e/openfile.mjs` and for trying **Open File…** by hand:
`Welcome.eml`, `Quarterly figures.msg`, `Weekly status.oft`, `winmail.dat`, `Ada Lovelace.vcf`,
`Design review.ics`, `Old phone.vcs`, `Report.docx` and `no-extension-message` (an Outlook item
without an extension).

They are written by `SampleFilesTest` (`src/test/kotlin/.../fileformats/SampleFilesTest.kt`) from
the published specifications using the builders in `FixtureBuilders.kt` — no third-party content.
All names, addresses and texts are invented (`example.com`, `example.org`, `quillpost.test`).
They are part of this project and under its licence. Regenerate with
`REGENERATE_SAMPLES=1 ./gradlew test --tests '*SampleFilesTest*'`.

The unit tests build all their other fixtures in code (`FixtureBuilders.kt`: MS-CFB compound
files, MS-OXMSG Outlook items, MS-OXTNEF streams, MS-OXRTFCP compressed RTF; MIME, vCard and
iCalendar text inline in the tests).

## Real-world corpora (local verification only, not committed)

Used with `SAMPLES_DIR=<folder> ./gradlew test --tests '*ExternalSamplesTest*'`, which opens
every file in the folder and prints what was found. These files are not in the repository.

| Corpus | Source | Licence | Files used | Result |
|---|---|---|---|---|
| Apache POI test data — Outlook items | `https://raw.githubusercontent.com/apache/poi/trunk/test-data/hsmf/` | Apache License 2.0 | `quick.msg`, `simple_test_msg.msg`, `attachment_test_msg.msg`, `outlook_30_msg.msg`, `example_received_unicode.msg`, `example_received_regular.msg`, `example_sent_unicode.msg`, `attachment_msg_pdf.msg`, `attachment_msg_inlineImg.msg`, `cyrillic_message.msg`, `chinese-traditional.msg` | All open: subjects (Latin-1, Cyrillic, Chinese), senders, recipient tables, HTML / RTF / plain bodies, attachments, inline images by Content-ID and an attached `.msg`. |
| Apache POI test data — TNEF | `https://raw.githubusercontent.com/apache/poi/trunk/test-data/hmef/` | Apache License 2.0 | `quick-winmail.dat`, `winmail-sample1.dat`, `bug52400-winmail-simple.dat`, `bug52400-winmail-with-attachments.dat` | All open: RTF bodies (de-encapsulated HTML), long attachment names, PDF/PNG/DOC attachments. |
| CPython email test messages | `https://raw.githubusercontent.com/python/cpython/main/Lib/test/test_email/data/msg_01.txt` … `msg_47.txt` | Python Software Foundation License 2.0 | all 47 | All open; the run led to the handling of subtype-less `Content-Type: text`, mismatched boundary parameters, delivery reports, inline text parts and `Content-Description` names. |
| LibreOffice 24.2 conversions | made locally with `soffice --headless --convert-to …` from a short text, a CSV and a flat ODF presentation | own content | `.doc`, `.docx`, `.odt`, `.fodt`, `.rtf`, Word 2003 XML, `.xls`, `.xlsx`, `.ods`, `.fods`, Excel 2003 XML, `.slk`, `.dif`, `.ppt`, `.pptx`, `.odp`, `.fodp`, and copies of each without an extension | All recognised as the right format and kind, with or without the extension. |

## Mailboxes and mail stores (MailStoreTest, FormatsTest, MailImportTest, e2e/mailimport.mjs)

No mailbox fixtures are committed: every one is built by code in
`src/test/kotlin/info/socrtwo/quillpost/web/mailstore/Fixtures.kt` when the tests run —
mbox (mboxrd, mboxcl2, Thunderbird, Gmail Takeout), Eudora `.mbx` + `.toc` with an attachment
folder, Maildir/Maildir++, MH, Apple Mail bundles with `.emlx`/`.partial.emlx`, Outlook Express
`.dbx` stores with `Folders.dbx`, OE4 `.mbx`, Pegasus `.pmm`, The Bat! `.tbb`, zip/tar.gz
archives, minimal OLE2 compound files, compressed RTF, and Outlook `.pst` files in ANSI and
Unicode form, plain and with the compressible encoding (`PstWriter.kt`, written from the
published [MS-PST] structure definitions).

`FixtureExport` writes a set of them to a folder for the browser test:
`QUILLPOST_FIXTURE_OUT=/tmp/qp-fixtures ./gradlew test --tests '*FixtureExport*'`.

### Public samples used for local verification only (not committed)

`SampleFilesTest` reads every file in `$QUILLPOST_SAMPLES` when that variable is set. The PST/OST
reader was checked against these public files, which are **not** redistributed here:

| File | Source | License | Result |
|---|---|---|---|
| `dist-list.pst` (Unicode PST, compressible encoding; a contact, a contact group and a weekly appointment) | https://github.com/rjohnsondev/java-libpst — `src/test/resources/dist-list.pst` | Apache License 2.0 (repository license) | Folder tree, vCard, contact group members and `RRULE:FREQ=WEEKLY;BYDAY=TU` read correctly |
| `example-2013.ost` (Outlook 2013 OST, 4 KB pages, zlib-compressed blocks) | same repository — `src/test/resources/example-2013.ost` | Apache License 2.0 (repository license) | `Root - Mailbox/IPM_SUBTREE` tree, original Internet headers, HTML bodies, read/flag status and delivery times read correctly |
