package info.socrtwo.quillpost.web.fileformats

/**
 * Single-message and related formats, recognised by content:
 *
 * | id          | what                                                     |
 * |-------------|----------------------------------------------------------|
 * | eml         | RFC 822/5322 message (.eml; also Gmail / Takeout single messages, a one-message mbox) |
 * | emlx        | Apple Mail message (.emlx, .partial.emlx)                |
 * | nws         | Outlook Express / Windows Mail news article (.nws)       |
 * | mhtml       | MHTML web archive (.mht, .mhtml)                         |
 * | msg / oft   | Outlook item / Outlook template (MS-OXMSG in MS-CFB)     |
 * | tnef        | winmail.dat / application/ms-tnef (MS-OXTNEF)            |
 * | pgp         | OpenPGP armoured message (.asc, .pgp)                    |
 * | p7m         | S/MIME CMS message (.p7m)                                |
 * | vcf         | vCard 2.1 / 3.0 / 4.0                                    |
 * | contact     | Windows Contacts (.contact)                              |
 * | ics / vcs   | iCalendar / vCalendar 1.0                                |
 * | uue, yenc, binhex, applesingle, appledouble | encoded single files (decoded, then re-detected) |
 * | wab         | Windows Address Book (recognised by extension only; not readable) |
 *
 * [detect] is the sniffer rule set for the import pipeline; [parse] reads a message format and
 * [toRfc822] converts any message format to MIME so it can be appended to an IMAP folder.
 */
object MessageFormats {

    val MESSAGE_FORMATS = setOf("eml", "emlx", "nws", "mhtml", "msg", "oft", "tnef", "pgp", "p7m")
    val CONTACT_FORMATS = setOf("vcf", "contact")
    val CALENDAR_FORMATS = setOf("ics", "vcs")
    val WRAPPER_FORMATS = setOf("uue", "yenc", "binhex", "applesingle", "appledouble")

    val LABELS = mapOf(
        "eml" to "Internet message (.eml)", "emlx" to "Apple Mail message (.emlx)", "nws" to "News article (.nws)", "mhtml" to "Web archive (.mht)",
        "msg" to "Outlook item (.msg)", "oft" to "Outlook template (.oft)", "tnef" to "Outlook rich-text attachment (winmail.dat)",
        "pgp" to "OpenPGP message", "p7m" to "S/MIME message (.p7m)", "vcf" to "vCard contacts (.vcf)", "contact" to "Windows contact (.contact)",
        "ics" to "iCalendar (.ics)", "vcs" to "vCalendar 1.0 (.vcs)", "uue" to "uuencoded file", "yenc" to "yEnc-encoded file", "binhex" to "BinHex 4.0 file (.hqx)",
        "applesingle" to "AppleSingle file", "appledouble" to "AppleDouble file", "wab" to "Windows Address Book (.wab)"
    )

    fun label(format: String): String = LABELS[format] ?: format

    private val KEY_HEADERS = setOf("from", "to", "subject", "date", "message-id", "received", "return-path", "mime-version", "delivered-to", "cc",
        "reply-to", "x-mailer", "content-type", "newsgroups", "path", "x-gm-thrid", "x-gmail-labels", "user-agent", "in-reply-to", "references", "sender", "x-unsent")

    /**
     * The single-message format of [bytes] and a confidence between 0 and 1, or null when the
     * bytes are not one of these formats (mailbox files such as mbox are left to the mailbox sniffer).
     */
    fun detect(bytes: ByteArray, name: String = ""): Pair<String, Double>? {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (bytes.isEmpty()) return null
        if (Tnef.isTnef(bytes)) return "tnef" to 0.99
        if (LegacyEncodings.isAppleSingle(bytes)) return "applesingle" to 0.97
        if (LegacyEncodings.isAppleDouble(bytes)) return "appledouble" to 0.97
        Cfb.open(bytes)?.let { if (OutlookMsg.isMsg(it)) return (if (ext == "oft") "oft" else "msg") to 0.99 }
        if (Cfb.isCfb(bytes)) return null
        if (bytes[0] == 0x30.toByte() && Cms.isCms(bytes)) return "p7m" to 0.85
        val prefix = bytes.copyOf(minOf(bytes.size, 65536))
        val utf16 = TextCodec.bom(prefix)?.first?.name()?.startsWith("UTF-16") == true || (prefix.size > 8 && (prefix[1].toInt() == 0 && prefix[3].toInt() == 0 || prefix[0].toInt() == 0 && prefix[2].toInt() == 0))
        val head = (if (utf16) TextCodec.auto(prefix) else String(prefix, Charsets.ISO_8859_1)).trimStart('\uFEFF', '\u00EF', '\u00BB', '\u00BF', '\r', '\n', ' ', '\t')
        val upper = head.take(4096).uppercase()
        if (upper.startsWith("BEGIN:VCARD")) return "vcf" to 0.98
        if (upper.startsWith("BEGIN:VCALENDAR")) return (if (Regex("(?m)^VERSION:\\s*1\\.0").containsMatchIn(upper)) "vcs" else "ics") to 0.98
        if (head.startsWith("<?xml") || head.startsWith("<c:contact") || head.startsWith("<contact")) {
            if (head.contains("http://schemas.microsoft.com/Contact")) return "contact" to 0.95
            return null
        }
        if (head.startsWith("-----BEGIN PGP")) return "pgp" to 0.9
        // Apple Mail: a byte count on the first line, then the message, then a property list.
        Regex("^(\\d{1,10})[ \\t]*\\r?\\n").find(head)?.let { m ->
            val rest = head.substring(m.range.last + 1)
            if (headerScore(rest) >= 2) return "emlx" to 0.95
        }
        if (head.startsWith("From ")) {
            val nl = head.indexOf('\n')
            val rest = if (nl > 0) head.substring(nl + 1) else ""
            if (headerScore(rest) >= 2) {
                // Another separator line followed by headers means a mailbox, not a single message.
                val more = Regex("\\n\\r?\\nFrom [^\\r\\n]*\\r?\\n[A-Za-z-]+:").containsMatchIn(String(bytes, Charsets.ISO_8859_1))
                return if (more) null else "eml" to 0.8
            }
        }
        val score = headerScore(head)
        if (score >= 2) {
            val hdrs = MimeReader.parseHeaderBlock(head.substringBefore("\r\n\r\n").substringBefore("\n\n"))
            fun h(n: String) = hdrs.firstOrNull { it.first.equals(n, true) }?.second
            val ct = h("Content-Type")?.lowercase() ?: ""
            if (ext == "mht" || ext == "mhtml" || h("Snapshot-Content-Location") != null || (ct.startsWith("multipart/related") && (h("From")?.contains("Saved by", true) == true || h("To") == null && h("Received") == null)))
                return "mhtml" to 0.95
            if (h("Newsgroups") != null && (h("To") == null || ext == "nws")) return "nws" to 0.9
            return "eml" to (0.55 + 0.08 * score).coerceAtMost(0.97)
        }
        if (ext == "eml" || ext == "nws" || ext == "mht") return ext.replace("mht", "mhtml") to 0.3
        if (Regex("(?m)^begin(-base64)? [0-7]{3,4} \\S").containsMatchIn(head.take(8192))) return "uue" to 0.85
        if (Regex("(?m)^=ybegin ").containsMatchIn(head.take(8192))) return "yenc" to 0.85
        if (head.contains("(This file must be converted with BinHex")) return "binhex" to 0.9
        if (ext == "wab") return "wab" to 0.5
        return null
    }

    /** How many lines at the start look like well-known header fields (0 when the block is not a header block). */
    private fun headerScore(text: String): Int {
        var known = 0
        var lines = 0
        for (line in text.lineSequence()) {
            if (line.isEmpty() || line == "\r") break
            if (line[0] == ' ' || line[0] == '\t') continue
            val c = line.indexOf(':')
            if (c <= 0 || line.substring(0, c).any { it <= ' ' || it.code > 126 }) return if (lines >= 3 && known >= 2) known else 0
            if (line.substring(0, c).lowercase() in KEY_HEADERS) known++
            if (++lines > 200) break
        }
        return known
    }

    /** Reads a message format (see [MESSAGE_FORMATS]); [name] distinguishes templates (.oft). */
    fun parse(bytes: ByteArray, name: String = "", format: String? = null): ParsedMessage {
        val f = format ?: detect(bytes, name)?.first ?: "eml"
        return when (f) {
            "msg", "oft" -> OutlookMsg.parse(bytes, if (f == "oft") "x.oft" else name)
            "tnef" -> Tnef.parse(bytes)
            "emlx" -> emlx(bytes, name)
            "pgp" -> MimeReader.parse(("Content-Type: text/plain\r\n\r\n").toByteArray() + bytes, "pgp").copy(subject = name.substringBeforeLast('.').ifBlank { "OpenPGP message" }, rfc822 = null)
            "p7m" -> MimeReader.parse(("Content-Type: application/pkcs7-mime; name=smime.p7m\r\nContent-Transfer-Encoding: binary\r\n\r\n").toByteArray(Charsets.ISO_8859_1) + bytes, "p7m").copy(subject = name.substringBeforeLast('.'), rfc822 = null)
            else -> MimeReader.parse(bytes, f)
        }
    }

    private fun emlx(bytes: ByteArray, name: String): ParsedMessage {
        val head = String(bytes, 0, minOf(bytes.size, 32), Charsets.ISO_8859_1)
        val m = Regex("^\\s*(\\d+)[ \\t]*\\r?\\n").find(head) ?: return MimeReader.parse(bytes, "emlx")
        val start = m.range.last + 1
        val len = m.groupValues[1].toLong().coerceIn(0, (bytes.size - start).toLong()).toInt()
        val msg = bytes.copyOfRange(start, start + len)
        val tail = String(bytes, start + len, bytes.size - start - len, Charsets.UTF_8)
        val parsed = MimeReader.parse(msg, "emlx")
        val notes = ArrayList(parsed.notes)
        if (name.lowercase().endsWith(".partial.emlx") || tail.contains("<key>remote-id</key>") && parsed.attachments.isEmpty() && String(msg, Charsets.ISO_8859_1).contains("X-Apple-Content-Length"))
            notes += "Apple Mail keeps this message's attachments in separate files next to the .emlx; they are not included."
        // The property list carries the read / flagged state; the date received is in the message.
        return parsed.copy(notes = notes, rfc822 = msg)
    }

    /** The message as RFC 822 bytes, for appending to a mail folder or for the import pipeline. */
    fun toRfc822(bytes: ByteArray, name: String = ""): ByteArray {
        val f = detect(bytes, name)?.first ?: "eml"
        if (f == "eml" || f == "nws" || f == "mhtml") return MimeReader.unwrap(bytes)
        return MimeWriter.toRfc822(parse(bytes, name, f))
    }

    // ------------------------------------------------------------------ whole-file classification

    /** What an opened file is, for the "Open file" command. */
    data class Classified(
        /** message, contacts, calendar, document, spreadsheet, presentation, mailbox, other */
        val kind: String,
        val format: String,
        val label: String,
        val confidence: Double,
        /** For wrappers (BinHex, AppleSingle, uuencode, yEnc): the decoded file and its name. */
        val bytes: ByteArray,
        val name: String,
        val document: DocumentFormats.Detected? = null,
        val wrappedIn: String? = null
    )

    fun classify(bytes: ByteArray, name: String, depth: Int = 0): Classified {
        val det = detect(bytes, name)
        if (det != null) {
            val (f, c) = det
            if (f in WRAPPER_FORMATS && depth < 4) unwrapFile(bytes, f, name)?.let { (inner, innerName) ->
                return classify(inner, innerName, depth + 1).copy(wrappedIn = label(f))
            }
            val kind = when (f) { in MESSAGE_FORMATS -> "message"; in CONTACT_FORMATS -> "contacts"; in CALENDAR_FORMATS -> "calendar"; else -> "other" }
            if (kind != "other" || f == "wab") return Classified(kind, f, label(f), c, bytes, name)
        }
        DocumentFormats.detect(bytes, name)?.let { d -> return Classified(d.kind.name.lowercase(), d.format, d.label, d.confidence, bytes, name, d) }
        MailboxHint.detect(bytes, name)?.let { (f, l) -> return Classified("mailbox", f, l, 0.8, bytes, name) }
        return Classified("other", name.substringAfterLast('.', "").lowercase(), "File", 0.0, bytes, name)
    }

    private fun unwrapFile(bytes: ByteArray, f: String, name: String): Pair<ByteArray, String>? {
        val text by lazy { String(bytes, Charsets.ISO_8859_1) }
        val d: LegacyEncodings.Decoded? = when (f) {
            "applesingle", "appledouble" -> LegacyEncodings.decodeApple(bytes)?.takeIf { it.bytes.isNotEmpty() }
            "binhex" -> LegacyEncodings.findBinHex(text).firstOrNull()?.file
            "uue" -> LegacyEncodings.findUuencoded(text).firstOrNull()?.file
            "yenc" -> LegacyEncodings.findYenc(text).firstOrNull()?.file
            else -> null
        }
        return d?.let { it.bytes to it.fileName.ifBlank { name.substringBeforeLast('.') } }
    }
}

/**
 * Minimal recognition of mailbox stores so an opened mailbox can be routed to the import
 * pipeline; the full sniffer and the readers live with the import feature.
 */
object MailboxHint {
    fun detect(b: ByteArray, name: String): Pair<String, String>? {
        val ext = name.substringAfterLast('.', "").lowercase()
        val head = String(b, 0, minOf(b.size, 8), Charsets.ISO_8859_1)
        return when {
            head.startsWith("!BDN") -> (if (ext == "ost") "ost" else "pst") to "Outlook data file (.${if (ext == "ost") "ost" else "pst"})"
            b.size >= 4 && b[0] == 0xCF.toByte() && b[1] == 0xAD.toByte() && b[2] == 0x12.toByte() && b[3] == 0xFE.toByte() -> "dbx" to "Outlook Express folder (.dbx)"
            head.startsWith("From ") -> "mbox" to "Mailbox (mbox)"
            ext in setOf("mbox", "mbx", "mbs") -> "mbox" to "Mailbox (.$ext)"
            ext == "nsf" -> "nsf" to "Lotus Notes database (.nsf)"
            else -> null
        }
    }
}
