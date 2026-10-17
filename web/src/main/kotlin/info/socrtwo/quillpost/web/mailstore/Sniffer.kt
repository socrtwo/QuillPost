package info.socrtwo.quillpost.web.mailstore

import java.io.File
import java.util.zip.ZipFile

/**
 * Identifies files by their bytes, not their names: mail stores, single messages, and the word
 * processor, spreadsheet and presentation files of current and historical programs (so the UI can
 * hand those to QuillPen, QuillGrid or QuillStage). The extension is consulted only where the
 * bytes cannot decide (e.g. plain-text formats).
 */
object Sniffer {
    private fun d(category: String, format: String, label: String, program: String = "", supported: Boolean = true, note: String = "", mime: String = "", variant: String = "") =
        Detection(category, format, label, program, variant, supported, note, mime)

    private const val NSF_NOTE = "Lotus Notes databases (.nsf) use IBM's proprietary, undocumented on-disk format; there is no public specification to build a clean-room reader from. In Notes or HCL Domino, export the mail to .eml/.mbox (or copy it to an IMAP account) and open that instead."
    private const val TBB_NOTE = "The Bat! message bases are undocumented; messages are recovered by scanning for their headers. Read status, flags and the folder tree are not recovered — for full fidelity export to .mbox or .eml from The Bat! first."

    fun sniffFile(file: File, name: String = file.name): Detection = sniff(head(file, 65536), name, file.length(), file)

    fun sniff(h: ByteArray, name: String, size: Long, file: File? = null): Detection {
        val ext = name.substringAfterLast('.', "").lowercase()
        val ascii = String(h, 0, minOf(h.size, 4096), Charsets.ISO_8859_1)
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
            h.startsWithBytes(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1) -> return ole(h, name, file)
            h.startsWithBytes(0x50, 0x4B, 0x03, 0x04) || h.startsWithBytes(0x50, 0x4B, 0x05, 0x06) -> return zip(name, file)
            h.startsWithBytes(0x1F, 0x8B) -> return d("archive", "gzip", "gzip-compressed file", note = "Unpacked automatically (e.g. .mbox.gz, Google Takeout .tgz).")
            h.size > 262 && h.ascii(257, 5) == "ustar" -> return d("archive", "tar", "tar archive", note = "Unpacked automatically.")
            h.startsWithBytes(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) -> return d("archive", "7z", "7-Zip archive", supported = false, note = "7-Zip archives cannot be opened here; extract it first, or re-pack it as .zip.")
            h.size >= 4 && h.ascii(0, 4) == "Rar!" -> return d("archive", "rar", "RAR archive", supported = false, note = "RAR archives cannot be opened here; extract it first, or re-pack it as .zip.")
            h.size >= 3 && h.ascii(0, 3) == "BZh" -> return d("archive", "bzip2", "bzip2-compressed file", supported = false, note = "bzip2 files cannot be opened here; decompress it first, or re-pack it as .zip or .gz.")
            h.startsWithBytes(0x78, 0x9F, 0x3E, 0x22) -> return d("message", "tnef", "Outlook rich-text attachment (winmail.dat / TNEF)", "Microsoft Outlook / Exchange", mime = "application/ms-tnef")
            h.size >= 6 && h[0] == 0x1A.toByte() && h[1] == 0.toByte() && h[2] == 0.toByte() && h.u8(3) in 2..6 && h[4] == 0.toByte() && h[5] == 0.toByte() ->
                return d("mailbox", "nsf", "Lotus Notes / HCL Domino database (.nsf)", "Lotus Notes", supported = false, note = NSF_NOTE)
            (ext == "nsf" || ext == "ntf") -> return d("mailbox", "nsf", "Lotus Notes / HCL Domino database (.nsf)", "Lotus Notes", supported = false, note = NSF_NOTE)
            ascii.startsWith("%PDF") -> return d("document", "pdf", "PDF document", mime = "application/pdf")
            ascii.startsWith("{\\rtf") -> return d("document", "rtf", "Rich Text Format document", "Word, WordPad and others", mime = "application/rtf")
            h.startsWithBytes(0xFF, 0x57, 0x50, 0x43) -> return d("document", "wpd", "WordPerfect document", "WordPerfect")
            h.startsWithBytes(0x31, 0xBE, 0x00, 0x00) || h.startsWithBytes(0x32, 0xBE, 0x00, 0x00) -> return d("document", "write", "Microsoft Write / Word for DOS document", "Microsoft Write, Word for DOS")
            h.startsWithBytes(0xDB, 0xA5, 0x2D, 0x00) || h.startsWithBytes(0xDC, 0xA5) -> return d("document", "doc", "Word for Windows 1.x/2.0 document", "Microsoft Word")
            h.startsWithBytes(0xFE, 0x37, 0x00, 0x1C) || h.startsWithBytes(0xFE, 0x37, 0x00, 0x23) -> return d("document", "doc", "Word for Macintosh document", "Microsoft Word for Mac")
            h.size >= 8 && h.ascii(4, 4) == "BOBO" -> return d("document", "cwk", "AppleWorks / ClarisWorks document", "AppleWorks / ClarisWorks")
            h.startsWithBytes(0x00, 0x00, 0x02, 0x00, 0x06, 0x04, 0x06, 0x00) -> return d("spreadsheet", "wk1", "Lotus 1-2-3 worksheet (WKS/WK1)", "Lotus 1-2-3")
            h.startsWithBytes(0x00, 0x00, 0x1A, 0x00) && h.size > 6 && h.u8(5) == 0x10 -> return d("spreadsheet", "wk3", "Lotus 1-2-3 worksheet (WK3/WK4)", "Lotus 1-2-3")
            h.startsWithBytes(0x00, 0x00, 0x02, 0x00, 0x04, 0x04) -> return d("spreadsheet", "wks", "Microsoft Works spreadsheet (WKS)", "Microsoft Works")
            (h.startsWithBytes(0x09, 0x00, 0x04, 0x00) || h.startsWithBytes(0x09, 0x02, 0x06, 0x00) || h.startsWithBytes(0x09, 0x04, 0x06, 0x00)) && ext in setOf("xls", "xlw", "xlt", "") ->
                return d("spreadsheet", "xls", "Excel 2.x–4.0 worksheet (BIFF)", "Microsoft Excel")
            ascii.startsWith("ID;P") -> return d("spreadsheet", "slk", "SYLK spreadsheet", "Multiplan / Excel")
            ascii.startsWith("TABLE\r\n0,1") || ascii.startsWith("TABLE\n0,1") -> return d("spreadsheet", "dif", "Data Interchange Format spreadsheet (DIF)", "VisiCalc / Lotus / Excel")
            ascii.startsWith("<MakerFile") || ascii.startsWith("<MIFFile") -> return d("document", "fm", "FrameMaker document", "Adobe FrameMaker")
            ascii.startsWith("[ver]") && ext == "sam" -> return d("document", "sam", "Ami Pro document", "Lotus Ami Pro")
            ascii.contains("<mdb:mork") -> return d("unknown", "msf", "Thunderbird / Netscape summary file (.msf)", "Thunderbird", supported = false, note = "This is only an index. Open the mailbox file next to it — the file with the same name and no extension.")
        }
        // Text-based formats.
        val trimmed = ascii.trimStart('﻿', ' ', '\r', '\n', '\t')
        val upper = trimmed.take(64).uppercase()
        when {
            upper.startsWith("BEGIN:VCARD") -> return d("contacts", "vcf", "vCard contacts", mime = "text/vcard")
            upper.startsWith("BEGIN:VCALENDAR") -> return d("calendar", "ics", "iCalendar file", mime = "text/calendar")
            trimmed.startsWith("<?xml") || trimmed.startsWith("<") -> xml(trimmed, ext)?.let { return it }
        }
        if (Mbox.looksLike(h)) return d("mailbox", "mbox", "mbox mailbox", if (ascii.startsWith("From ???@???")) "Eudora" else "", variant = "")
        if (Emlx.looksLike(h)) return d("message", "emlx", "Apple Mail message (.emlx)", "Apple Mail")
        if (ext == "pmm" && ScanStores.looksLikePmm(h)) return d("mailbox", "pegasus", "Pegasus Mail folder (.pmm)", "Pegasus Mail")
        if (ext == "pmi" || ext == "pmx") return d("unknown", "pegasus-index", "Pegasus Mail index", "Pegasus Mail", supported = false, note = "Open the .pmm folder file with the same name instead.")
        if (ext == "toc" && h.isNotEmpty() && ascii.take(200).any { it.code < 9 }) return d("unknown", "eudora-toc", "Eudora table of contents (.toc)", "Eudora", supported = false, note = "Open the .mbx file with the same name (or the whole Eudora folder) instead.")
        if (ext == "tbb" || ext == "tbn") return d("mailbox", "thebat", "The Bat! message base (.tbb)", "The Bat!", note = TBB_NOTE)
        if (looksLikeMessage(ascii)) {
            if (ascii.contains(Regex("(?im)^Content-Type:\\s*multipart/related")) && !ascii.contains(Regex("(?im)^(From|To):")) || ext == "mht" || ext == "mhtml")
                return d("document", "mht", "Web archive (MHTML)", "Word, Internet Explorer", mime = "multipart/related")
            return d("message", "eml", "Internet message (RFC 5322)", mime = "message/rfc822")
        }
        // Plain-text formats decided by name.
        return when (ext) {
            "csv", "tsv", "tab" -> d("spreadsheet", ext, "Delimited text (${ext.uppercase()})")
            "txt", "text", "asc" -> d("document", "txt", "Plain text")
            "md", "markdown" -> d("document", "md", "Markdown text")
            "htm", "html" -> d("document", "html", "Web page (HTML)", mime = "text/html")
            "mbx" -> d("mailbox", "mbox", "Mailbox (.mbx)", "Eudora")
            "mbox", "mbs", "mbs1" -> d("mailbox", "mbox", "mbox mailbox")
            else -> d("unknown", ext.ifEmpty { "bin" }, "Unrecognised file", supported = false, note = "QuillPost could not tell what this file is from its contents.")
        }
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

    private fun xml(t: String, ext: String): Detection? {
        val s = t.take(4096)
        return when {
            s.contains("progid=\"Word.Document\"") || s.contains("schemas.microsoft.com/office/word/2003/wordml") -> d("document", "wordml", "Word 2003 XML document", "Microsoft Word")
            s.contains("progid=\"Excel.Sheet\"") || s.contains("urn:schemas-microsoft-com:office:spreadsheet") -> d("spreadsheet", "spreadsheetml", "Excel 2003 XML spreadsheet", "Microsoft Excel")
            s.contains("progid=\"PowerPoint.Show\"") -> d("presentation", "pptxml", "PowerPoint 2003 XML presentation", "Microsoft PowerPoint")
            s.contains("office:mimetype=\"application/vnd.oasis.opendocument.text\"") -> d("document", "fodt", "Flat OpenDocument text", "LibreOffice / OpenOffice")
            s.contains("office:mimetype=\"application/vnd.oasis.opendocument.spreadsheet\"") -> d("spreadsheet", "fods", "Flat OpenDocument spreadsheet", "LibreOffice / OpenOffice")
            s.contains("office:mimetype=\"application/vnd.oasis.opendocument.presentation\"") -> d("presentation", "fodp", "Flat OpenDocument presentation", "LibreOffice / OpenOffice")
            Regex("(?i)<(!doctype html|html)").containsMatchIn(s.take(1024)) -> d("document", "html", "Web page (HTML)", mime = "text/html")
            else -> null
        }
    }

    // ----------------------------------------------------------------------------- OLE2 (CFB)

    /** The stream and storage names of an OLE2 compound file (enough of the directory to classify it). */
    fun oleNames(file: File): Set<String> = FileBytes(file).use { fb ->
        val h = fb.readUpTo(0, 512)
        val shift = h.u16(0x1E).coerceIn(9, 12)
        val ss = 1 shl shift
        val fatSectors = (0 until 109).map { h.u32(0x4C + it * 4) }.filter { it < 0xFFFFFFFAL }
        fun sectorOff(n: Long) = (n + 1) * ss
        val fat = ArrayList<Long>()
        for (s in fatSectors.take(64)) { val b = fb.readUpTo(sectorOff(s), ss); for (i in 0 until b.size / 4) fat += b.u32(i * 4) }
        val names = LinkedHashSet<String>()
        var sec = h.u32(0x30)
        var guard = 0
        while (sec < 0xFFFFFFFAL && guard++ < 256) {
            val b = fb.readUpTo(sectorOff(sec), ss)
            for (o in 0 until b.size / 128) {
                val len = b.u16(o * 128 + 64)
                if (len in 2..64) names += String(b, o * 128, len - 2, Charsets.UTF_16LE)
            }
            sec = fat.getOrNull(sec.toInt()) ?: break
        }
        names
    }

    private fun ole(h: ByteArray, name: String, file: File?): Detection {
        val names = file?.let { runCatching { oleNames(it) }.getOrNull() } ?: emptySet()
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            names.any { it.startsWith("__substg1.0_") } || "__properties_version1.0" in names ->
                if (ext == "oft") d("message", "oft", "Outlook message template (.oft)", "Microsoft Outlook", mime = "application/vnd.ms-outlook")
                else d("message", "msg", "Outlook message (.msg)", "Microsoft Outlook", mime = "application/vnd.ms-outlook")
            "WordDocument" in names -> d("document", "doc", "Word 97–2003 document", "Microsoft Word", mime = "application/msword")
            "Workbook" in names || "Book" in names -> d("spreadsheet", "xls", "Excel 97–2003 workbook", "Microsoft Excel", mime = "application/vnd.ms-excel")
            "PowerPoint Document" in names -> d("presentation", "ppt", "PowerPoint 97–2003 presentation", "Microsoft PowerPoint", mime = "application/vnd.ms-powerpoint")
            "MatOST" in names -> d("document", "wps", "Microsoft Works document", "Microsoft Works")
            "Quill" in names -> d("document", "pub", "Microsoft Publisher document", "Microsoft Publisher")
            "VisioDocument" in names -> d("document", "vsd", "Visio drawing", "Microsoft Visio", supported = false)
            "HwpSummaryInformation" in names || "BodyText" in names -> d("document", "hwp", "Hangul (HWP) document", "Hancom Office")
            "StarWriterDocument" in names -> d("document", "sdw", "StarWriter document", "StarOffice")
            "StarCalcDocument" in names -> d("spreadsheet", "sdc", "StarCalc spreadsheet", "StarOffice")
            "StarImpressDocument" in names -> d("presentation", "sdd", "StarImpress presentation", "StarOffice")
            "NativeContent_MAIN" in names -> d("spreadsheet", "qpw", "Quattro Pro notebook", "Corel Quattro Pro")
            "PerfectOffice_MAIN" in names -> d("document", "wpd", "WordPerfect document", "WordPerfect")
            ext == "msg" -> d("message", "msg", "Outlook message (.msg)", "Microsoft Outlook", mime = "application/vnd.ms-outlook")
            else -> d("unknown", "ole", "OLE2 compound document", supported = false, note = "An OLE2 compound file of a kind QuillPost does not recognise.")
        }
    }

    // --------------------------------------------------------------------------------------- ZIP

    private fun zip(name: String, file: File?): Detection {
        val ext = name.substringAfterLast('.', "").lowercase()
        val entries = file?.let { f -> runCatching { ZipFile(f).use { z -> z.entries().asSequence().map { it.name }.take(20000).toList() } }.getOrNull() } ?: emptyList()
        val set = entries.toSet()
        val mimetype = file?.takeIf { "mimetype" in set }?.let { f -> runCatching { ZipFile(f).use { z -> z.getInputStream(z.getEntry("mimetype")).readBytes().toString(Charsets.US_ASCII).trim() } }.getOrNull() }.orEmpty()
        fun has(prefix: String) = entries.any { it.startsWith(prefix) }
        return when {
            has("word/") && "[Content_Types].xml" in set -> d("document", "docx", "Word document (Office Open XML)", "Microsoft Word")
            has("xl/") && "[Content_Types].xml" in set -> d("spreadsheet", "xlsx", "Excel workbook (Office Open XML)", "Microsoft Excel")
            has("ppt/") && "[Content_Types].xml" in set -> d("presentation", "pptx", "PowerPoint presentation (Office Open XML)", "Microsoft PowerPoint")
            mimetype.endsWith("opendocument.text") || mimetype.endsWith("opendocument.text-template") -> d("document", "odt", "OpenDocument text", "LibreOffice / OpenOffice")
            mimetype.endsWith("opendocument.spreadsheet") -> d("spreadsheet", "ods", "OpenDocument spreadsheet", "LibreOffice / OpenOffice")
            mimetype.endsWith("opendocument.presentation") -> d("presentation", "odp", "OpenDocument presentation", "LibreOffice / OpenOffice")
            mimetype == "application/vnd.sun.xml.writer" -> d("document", "sxw", "OpenOffice.org 1 text", "OpenOffice.org")
            mimetype == "application/vnd.sun.xml.calc" -> d("spreadsheet", "sxc", "OpenOffice.org 1 spreadsheet", "OpenOffice.org")
            mimetype == "application/vnd.sun.xml.impress" -> d("presentation", "sxi", "OpenOffice.org 1 presentation", "OpenOffice.org")
            mimetype.startsWith("application/epub") -> d("document", "epub", "EPUB e-book")
            "Index/Document.iwa" in set || "index.apxl" in set || "index.xml" in set && ext in setOf("pages", "numbers", "key") -> when (ext) {
                "numbers" -> d("spreadsheet", "numbers", "Apple Numbers spreadsheet", "Apple Numbers")
                "key" -> d("presentation", "key", "Apple Keynote presentation", "Apple Keynote")
                else -> d("document", "pages", "Apple Pages document", "Apple Pages")
            }
            else -> d("archive", "zip", "ZIP archive", note = if (entries.any { mailEntry(it) }) "Contains mail; it is unpacked and read as a folder." else "Unpacked and read as a folder.")
        }
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
