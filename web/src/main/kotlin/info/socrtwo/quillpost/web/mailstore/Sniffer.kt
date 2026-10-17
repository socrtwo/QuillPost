package info.socrtwo.quillpost.web.mailstore

import info.socrtwo.quillpost.web.fileformats.DocumentFormats
import info.socrtwo.quillpost.web.fileformats.MessageFormats
import java.io.File
import java.util.zip.ZipFile

/**
 * Identifies files by their bytes, not their names. Mail stores (mailboxes, mail folders, data
 * files) are recognised here; single messages, contacts and calendars by the shared message
 * readers and office files by the document detector (package fileformats), so every part of the
 * app agrees on what a file is. The extension is consulted only where the bytes cannot decide.
 */
object Sniffer {
    private fun d(category: String, format: String, label: String, program: String = "", supported: Boolean = true, note: String = "", mime: String = "", variant: String = "") =
        Detection(category, format, label, program, variant, supported, note, mime)

    private const val NSF_NOTE = "Lotus Notes databases (.nsf) use IBM's proprietary, undocumented on-disk format; there is no public specification to build a clean-room reader from. In Notes or HCL Domino, export the mail to .eml/.mbox (or copy it to an IMAP account) and open that instead."
    private const val TBB_NOTE = "The Bat! message bases are undocumented; messages are recovered by scanning for their headers. Read status, flags and the folder tree are not recovered — for full fidelity export to .mbox or .eml from The Bat! first."

    fun sniffFile(file: File, name: String = file.name): Detection = sniff(head(file, 65536), name, file.length(), file)

    /**
     * Mail stores are recognised here; single messages, contacts and calendars are left to the
     * shared message readers (fileformats/MessageFormats.kt) and word-processor, spreadsheet and
     * presentation files to fileformats/DocumentFormats.kt.
     */
    fun sniff(h: ByteArray, name: String, size: Long, file: File? = null): Detection {
        val ext = name.substringAfterLast('.', "").lowercase()
        val ascii = String(h, 0, minOf(h.size, 4096), Charsets.ISO_8859_1)
        val zip = h.startsWithBytes(0x50, 0x4B, 0x03, 0x04) || h.startsWithBytes(0x50, 0x4B, 0x05, 0x06)
        val cfb = h.startsWithBytes(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1)
        when {
            h.startsWithBytes(0x21, 0x42, 0x44, 0x4E) -> {
                val ver = if (h.size > 12) h.u16(10) else 0
                val variant = if (ver >= 36) "Unicode, 4 KB pages" else if (ver >= 23) "Unicode" else "ANSI"
                return when (h.ascii(8, 2)) {
                    "SO" -> d("mailbox", "ost", "Outlook offline folders (.ost, $variant)", "Microsoft Outlook", variant = variant)
                    "AB" -> d("mailbox", "pab", "Outlook personal address book (.pab)", "Microsoft Outlook", variant = variant)
                    else -> d("mailbox", "pst", "Outlook data file (.pst, $variant)", "Microsoft Outlook", variant = variant)
                }
            }
            Dbx.isFolders(h) -> return d("mailbox", "dbx-folders", "Outlook Express folder list (Folders.dbx)", "Outlook Express 5/6", note = "Open the whole Outlook Express store folder (or a .zip of it) to import every folder with its hierarchy.")
            Dbx.isMessages(h) -> return d("mailbox", "dbx", "Outlook Express mail folder (.dbx)", "Outlook Express 5/6")
            Dbx.isDbx(h) -> return d("unknown", "dbx-other", "Outlook Express database (not a mail folder)", "Outlook Express 5/6", supported = false, note = "Offline.dbx and Pop3uidl.dbx hold no messages.")
            Dbx.isOe4(h) -> return d("mailbox", "oe4", "Outlook Express 4 mailbox (.mbx)", "Outlook Express 4")
            h.size >= 4 && h.ascii(0, 4) == "JMF9" -> return d("unknown", "oe4-idx", "Outlook Express 4 index (.idx)", "Outlook Express 4", supported = false, note = "Open the .mbx file with the same name; the index is not needed.")
            h.startsWithBytes(0x1F, 0x8B) -> return d("archive", "gzip", "gzip-compressed file", note = "Unpacked automatically (e.g. .mbox.gz, Google Takeout .tgz).")
            h.size > 262 && h.ascii(257, 5) == "ustar" -> return d("archive", "tar", "tar archive", note = "Unpacked automatically.")
            h.startsWithBytes(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) -> return d("archive", "7z", "7-Zip archive", supported = false, note = "7-Zip archives cannot be opened here; extract it first, or re-pack it as .zip.")
            h.size >= 4 && h.ascii(0, 4) == "Rar!" -> return d("archive", "rar", "RAR archive", supported = false, note = "RAR archives cannot be opened here; extract it first, or re-pack it as .zip.")
            h.size >= 3 && h.ascii(0, 3) == "BZh" -> return d("archive", "bzip2", "bzip2-compressed file", supported = false, note = "bzip2 files cannot be opened here; decompress it first, or re-pack it as .zip or .gz.")
            h.size >= 6 && h[0] == 0x1A.toByte() && h[1] == 0.toByte() && h[2] == 0.toByte() && h.u8(3) in 2..6 && h[4] == 0.toByte() && h[5] == 0.toByte() ->
                return d("mailbox", "nsf", "Lotus Notes / HCL Domino database (.nsf)", "Lotus Notes", supported = false, note = NSF_NOTE)
            (ext == "nsf" || ext == "ntf") -> return d("mailbox", "nsf", "Lotus Notes / HCL Domino database (.nsf)", "Lotus Notes", supported = false, note = NSF_NOTE)
            ascii.contains("<mdb:mork") -> return d("unknown", "msf", "Thunderbird / Netscape summary file (.msf)", "Thunderbird", supported = false, note = "This is only an index. Open the mailbox file next to it — the file with the same name and no extension.")
        }
        if (Mbox.looksLike(h) && !singleMessageMbox(h, size)) return d("mailbox", "mbox", "mbox mailbox", if (ascii.startsWith("From ???@???")) "Eudora" else "")
        if (ext == "pmm" && ScanStores.looksLikePmm(h)) return d("mailbox", "pegasus", "Pegasus Mail folder (.pmm)", "Pegasus Mail")
        if (ext == "pmi" || ext == "pmx") return d("unknown", "pegasus-index", "Pegasus Mail index", "Pegasus Mail", supported = false, note = "Open the .pmm folder file with the same name instead.")
        if (ext == "toc" && h.isNotEmpty() && ascii.take(200).any { it.code < 9 }) return d("unknown", "eudora-toc", "Eudora table of contents (.toc)", "Eudora", supported = false, note = "Open the .mbx file with the same name (or the whole Eudora folder) instead.")
        if (ext == "tbb" || ext == "tbn") return d("mailbox", "thebat", "The Bat! message base (.tbb)", "The Bat!", note = TBB_NOTE)

        // Not a mail store: ask the message and document readers. Zip and OLE2 files need their directory.
        val bytes = if ((zip || cfb) && file != null && size <= 100L * 1024 * 1024) file.readBytes() else h
        MessageFormats.detect(bytes, name)?.let { (f, _) ->
            val category = when (f) {
                in MessageFormats.MESSAGE_FORMATS -> "message"
                in MessageFormats.CONTACT_FORMATS -> "contacts"
                in MessageFormats.CALENDAR_FORMATS -> "calendar"
                else -> "file"
            }
            return d(category, f, MessageFormats.label(f), if (f == "msg" || f == "oft" || f == "tnef") "Microsoft Outlook" else "",
                note = if (category == "file") "Opened with File ▸ Open File…" else "")
        }
        DocumentFormats.detect(bytes, name)?.let { doc ->
            return d(doc.kind.name.lowercase(), doc.format, doc.label, doc.kind.appLabel)
        }
        if (ascii.startsWith("%PDF")) return d("document", "pdf", "PDF document", mime = "application/pdf")
        if (zip) {
            val entries = file?.let { f -> runCatching { ZipFile(f).use { z -> z.entries().asSequence().map { it.name }.take(20000).toList() } }.getOrNull() } ?: emptyList()
            return d("archive", "zip", "ZIP archive", note = if (entries.any { mailEntry(it) }) "Contains mail; it is unpacked and read as a folder." else "Unpacked and read as a folder.")
        }
        return when (ext) {
            "mbx" -> d("mailbox", "mbox", "Mailbox (.mbx)", "Eudora")
            "mbox", "mbs", "mbs1" -> d("mailbox", "mbox", "mbox mailbox")
            else -> d("unknown", ext.ifEmpty { "bin" }, "Unrecognised file", supported = false, note = "QuillPost could not tell what this file is from its contents.")
        }
    }

    /** A file starting "From " that holds a single message is a saved message, not a mailbox. */
    private fun singleMessageMbox(h: ByteArray, size: Long): Boolean {
        if (size > h.size) return false
        val text = String(h, Charsets.ISO_8859_1)
        return !Regex("\\n\\r?\\nFrom \\S+\\s+.*\\d{1,2}:\\d{2}").containsMatchIn(text)
    }

    /** True when the text starts with RFC 5322 header fields that only mail carries. */
    fun looksLikeMessage(ascii: String): Boolean {
        val lines = ascii.trimStart('\r', '\n').lineSequence().take(60).toList()
        var headers = 0; var mailish = 0
        for (l in lines) {
            if (l.isBlank()) break
            if (l.startsWith(" ") || l.startsWith("\t")) continue
            val m = Regex("^([!-9;-~]+):").find(l) ?: return headers >= 2 && mailish >= 1
            headers++
            if (m.groupValues[1].lowercase() in setOf("from", "to", "subject", "date", "message-id", "received", "return-path", "mime-version", "delivered-to", "x-mailer", "reply-to", "cc", "sender", "x-originating-ip")) mailish++
        }
        return headers >= 2 && mailish >= 2
    }

    private fun mailEntry(n: String): Boolean {
        val l = n.lowercase()
        return l.contains("/cur/") || l.contains("/new/") || l.endsWith(".eml") || l.endsWith(".emlx") || l.endsWith(".mbox") || l.endsWith(".mbx") ||
            l.endsWith(".dbx") || l.endsWith(".pst") || l.endsWith(".ost") || l.endsWith(".pmm") || l.endsWith(".msf")
    }

    // ------------------------------------------------------------------------------ directories

    /** Describes a folder by what is inside it. */
    fun sniffDir(dir: File): Detection {
        val all = dir.walk().maxDepth(6).filter { it.isFile }.take(20000).toList()
        val names = all.map { it.name.lowercase() }
        fun any(p: (String) -> Boolean) = names.any(p)
        val dirs = dir.walk().maxDepth(6).filter { it.isDirectory }.map { it.name.lowercase() }.toSet()
        return when {
            "folders.dbx" in names || any { it.endsWith(".dbx") } -> d("mailbox", "oe-store", "Outlook Express store (.dbx folders)", "Outlook Express 5/6")
            any { it.endsWith(".pst") || it.endsWith(".ost") } -> d("mailbox", "pst-folder", "Folder with Outlook data files", "Microsoft Outlook")
            ("cur" in dirs || "new" in dirs) -> d("mailbox", "maildir", "Maildir", "Courier, Dovecot, qmail, mutt, KMail, Evolution…")
            any { it.endsWith(".emlx") } -> d("mailbox", "apple-mail", "Apple Mail mailboxes (.mbox bundles with .emlx)", "Apple Mail")
            any { it.endsWith(".toc") } && any { it.endsWith(".mbx") } -> d("mailbox", "eudora", "Eudora mail folder (.mbx + .toc)", "Eudora")
            any { it.endsWith(".pmm") } -> d("mailbox", "pegasus", "Pegasus Mail folder", "Pegasus Mail")
            any { it.endsWith(".tbb") } -> d("mailbox", "thebat", "The Bat! mail folder", "The Bat!", note = TBB_NOTE)
            any { it.endsWith(".msf") } || dirs.any { it.endsWith(".sbd") } -> d("mailbox", "thunderbird", "Thunderbird / Netscape / SeaMonkey mail folder", "Thunderbird, Netscape, SeaMonkey")
            ".mh_sequences" in names -> d("mailbox", "mh", "MH mail folders", "nmh, Sylpheed, Claws Mail")
            any { it.endsWith(".eml") } -> d("mailbox", "eml-folder", "Folder of .eml messages", "Windows Live Mail, Windows Mail, Outlook Express exports")
            any { it.endsWith(".nsf") } -> d("mailbox", "nsf", "Lotus Notes / HCL Domino databases", "Lotus Notes", supported = false, note = NSF_NOTE)
            else -> d("mailbox", "folder", "Folder of mail files")
        }
    }
}
