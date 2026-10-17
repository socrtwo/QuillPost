# Test fixtures

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
