package info.socrtwo.quillpost.web.fileformats

import jakarta.activation.DataHandler
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.util.ByteArrayDataSource
import java.util.Date
import java.util.Properties

/** File-name extension ↔ MIME type, for attachments that arrive without a usable type. */
object MimeTypes {
    private val BY_EXT = mapOf(
        "txt" to "text/plain", "htm" to "text/html", "html" to "text/html", "css" to "text/css", "csv" to "text/csv", "xml" to "application/xml",
        "eml" to "message/rfc822", "nws" to "message/rfc822", "mht" to "multipart/related", "mhtml" to "multipart/related", "msg" to "application/vnd.ms-outlook",
        "oft" to "application/vnd.ms-outlook", "vcf" to "text/vcard", "ics" to "text/calendar", "vcs" to "text/x-vcalendar", "dat" to "application/octet-stream",
        "pdf" to "application/pdf", "rtf" to "application/rtf", "zip" to "application/zip", "gz" to "application/gzip", "7z" to "application/x-7z-compressed",
        "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif", "bmp" to "image/bmp", "webp" to "image/webp",
        "svg" to "image/svg+xml", "tif" to "image/tiff", "tiff" to "image/tiff", "ico" to "image/x-icon", "heic" to "image/heic",
        "mp3" to "audio/mpeg", "wav" to "audio/wav", "mp4" to "video/mp4", "mov" to "video/quicktime",
        "doc" to "application/msword", "dot" to "application/msword", "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "docm" to "application/vnd.ms-word.document.macroEnabled.12", "dotx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.template",
        "xls" to "application/vnd.ms-excel", "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsm" to "application/vnd.ms-excel.sheet.macroEnabled.12",
        "xlsb" to "application/vnd.ms-excel.sheet.binary.macroEnabled.12", "ppt" to "application/vnd.ms-powerpoint", "pps" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation", "ppsx" to "application/vnd.openxmlformats-officedocument.presentationml.slideshow",
        "odt" to "application/vnd.oasis.opendocument.text", "ods" to "application/vnd.oasis.opendocument.spreadsheet", "odp" to "application/vnd.oasis.opendocument.presentation",
        "pages" to "application/vnd.apple.pages", "numbers" to "application/vnd.apple.numbers", "key" to "application/vnd.apple.keynote",
        "wpd" to "application/vnd.wordperfect", "wps" to "application/vnd.ms-works", "wk1" to "application/vnd.lotus-1-2-3", "wks" to "application/vnd.lotus-1-2-3",
        "123" to "application/vnd.lotus-1-2-3", "p7m" to "application/pkcs7-mime", "p7s" to "application/pkcs7-signature", "asc" to "application/pgp-encrypted",
        "hqx" to "application/mac-binhex40", "uue" to "text/x-uuencode", "json" to "application/json", "js" to "text/javascript"
    )

    fun byName(name: String?): String = BY_EXT[name?.substringAfterLast('.', "")?.lowercase()] ?: "application/octet-stream"

    fun extensionFor(mime: String): String {
        val m = mime.lowercase().substringBefore(';').trim()
        BY_EXT.entries.firstOrNull { it.value == m }?.let { return "." + it.key }
        return when { m.startsWith("text/") -> ".txt"; m.startsWith("image/") -> "." + m.substringAfter('/').take(4); else -> ".bin" }
    }
}

/** Writes a [ParsedMessage] as an RFC 822 message (for "Save to folder", forwarding and the import pipeline). */
object MimeWriter {

    fun safeFileName(s: String): String = s.replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]+"), "_").trim().trim('.').take(100).ifBlank { "message" }

    private class KeepId(session: Session, private val id: String) : MimeMessage(session) {
        override fun updateMessageID() { if (id.isNotBlank()) setHeader("Message-ID", id) else super.updateMessageID() }
    }

    fun toRfc822(m: ParsedMessage): ByteArray {
        m.rfc822?.let { return it }
        val session = Session.getInstance(Properties())
        val msg = KeepId(session, m.messageId)
        fun addr(a: MailAddress): InternetAddress? = runCatching {
            if (a.address.isBlank()) InternetAddress("undisclosed-recipients:;") else InternetAddress(a.address, a.name.ifBlank { null }, "UTF-8")
        }.getOrNull()
        m.from?.let { addr(it) }?.let { msg.setFrom(it) }
        m.sender?.let { addr(it) }?.let { msg.sender = it }
        fun set(type: Message.RecipientType, list: List<MailAddress>) { val a = list.filter { it.address.isNotBlank() }.mapNotNull { addr(it) }; if (a.isNotEmpty()) msg.setRecipients(type, a.toTypedArray()) }
        set(Message.RecipientType.TO, m.to); set(Message.RecipientType.CC, m.cc); set(Message.RecipientType.BCC, m.bcc)
        m.replyTo.mapNotNull { addr(it) }.takeIf { it.isNotEmpty() }?.let { msg.replyTo = it.toTypedArray() }
        msg.setSubject(m.subject, "UTF-8")
        m.date?.let { msg.sentDate = Date(it) }
        if (m.inReplyTo.isNotBlank()) msg.setHeader("In-Reply-To", m.inReplyTo)
        if (m.references.isNotBlank()) msg.setHeader("References", m.references)
        when (m.importance) { "high" -> { msg.setHeader("Importance", "High"); msg.setHeader("X-Priority", "1") }; "low" -> { msg.setHeader("Importance", "Low"); msg.setHeader("X-Priority", "5") } }
        if (m.newsgroups.isNotBlank()) msg.setHeader("Newsgroups", m.newsgroups)

        val textPart = MimeBodyPart().apply { setText(m.bodyText.ifEmpty { m.bodyHtml?.let { Rtf.htmlToText(it) } ?: "" }, "UTF-8") }
        val inline = m.attachments.filter { it.inline && it.contentId != null }
        val files = m.attachments.filter { !(it.inline && it.contentId != null) }
        var body: MimeBodyPart = textPart
        if (m.bodyHtml != null) {
            val htmlPart = MimeBodyPart().apply { setText(m.bodyHtml, "UTF-8", "html") }
            var htmlSide: MimeBodyPart = htmlPart
            if (inline.isNotEmpty()) {
                val rel = MimeMultipart("related"); rel.addBodyPart(htmlPart)
                for (a in inline) rel.addBodyPart(part(a).apply { setHeader("Content-ID", "<${a.contentId}>"); disposition = "inline" })
                htmlSide = MimeBodyPart().apply { setContent(rel) }
            }
            val alt = MimeMultipart("alternative"); alt.addBodyPart(textPart); alt.addBodyPart(htmlSide)
            body = MimeBodyPart().apply { setContent(alt) }
        }
        val calendar = m.calendar
        if (files.isEmpty() && calendar == null) {
            val c = body.content
            if (c is MimeMultipart) msg.setContent(c) else msg.setText(textPart.content as String, "UTF-8")
        } else {
            val mixed = MimeMultipart("mixed"); mixed.addBodyPart(body)
            if (calendar != null) mixed.addBodyPart(MimeBodyPart().apply { setText(calendar, "UTF-8", "calendar"); fileName = "invite.ics" })
            for (a in files) mixed.addBodyPart(part(a).apply { disposition = "attachment"; fileName = a.fileName; a.contentId?.let { setHeader("Content-ID", "<$it>") } })
            msg.setContent(mixed)
        }
        msg.saveChanges()
        if (m.date == null) msg.removeHeader("Date")
        return java.io.ByteArrayOutputStream().also { msg.writeTo(it) }.toByteArray()
    }

    private fun part(a: ParsedAttachment): MimeBodyPart = MimeBodyPart().apply {
        val type = a.mimeType.ifBlank { "application/octet-stream" }
        dataHandler = DataHandler(ByteArrayDataSource(a.bytes, type))
        fileName = a.fileName
        setHeader("Content-Type", if (type == "message/rfc822") type else "$type; name=\"${a.fileName.replace("\"", "")}\"")
        if (!type.startsWith("text/") && type != "message/rfc822") setHeader("Content-Transfer-Encoding", "base64")
    }
}

/** MHTML web archives (RFC 2557): resources referenced by Content-Location become cid: references. */
object Mhtml {
    fun resolve(html0: String?, atts: List<ParsedAttachment>, css: List<Pair<String?, String>>, base: String?): Pair<String?, List<ParsedAttachment>> {
        var html = html0 ?: return null to atts
        val baseDir = base?.substringBeforeLast('/', "")?.let { if (it.isEmpty()) "" else "$it/" } ?: ""
        val out = ArrayList<ParsedAttachment>()
        var n = 0
        val replacements = ArrayList<Pair<String, String>>()
        for (a in atts) {
            val loc = a.contentLocation
            if (loc == null || a.embedded != null) { out += a; continue }
            val cid = a.contentId ?: "mhtml-${++n}@quillpost.local"
            for (cand in candidates(loc, baseDir)) replacements += cand to "cid:$cid"
            out += ParsedAttachment(a.fileName, a.mimeType, a.bytes, cid, true, loc, null)
        }
        // Longest first, so "img/a.png" is not replaced inside "img/a.png?x".
        replacements.sortByDescending { it.first.length }
        fun rewrite(s: String): String {
            var r = s
            for ((from, to) in replacements) {
                val q = Regex.escape(from)
                r = r.replace(Regex("(=\\s*\")$q(\")|(=\\s*')$q(')|(\\(\\s*[\"']?)$q([\"']?\\s*\\))|(=)$q(?=[\\s>])")) { m ->
                    val g = m.groupValues
                    when { g[1].isNotEmpty() -> g[1] + to + g[2]; g[3].isNotEmpty() -> g[3] + to + g[4]; g[5].isNotEmpty() -> g[5] + to + g[6]; else -> g[7] + to }
                }
            }
            return r
        }
        html = rewrite(html)
        if (css.isNotEmpty()) {
            val styles = css.joinToString("\n") { "<style>" + rewrite(it.second).replace("</style", "<\\/style") + "</style>" }
            val head = Regex("(?i)</head>").find(html)
            html = if (head != null) html.substring(0, head.range.first) + styles + html.substring(head.range.first) else styles + html
        }
        return html to out
    }

    private fun candidates(loc: String, baseDir: String): List<String> {
        val out = linkedSetOf(loc, loc.replace("&", "&amp;"))
        if (baseDir.isNotEmpty() && loc.startsWith(baseDir)) { val rel = loc.removePrefix(baseDir); out += rel; out += "./$rel"; out += rel.replace("&", "&amp;") }
        Regex("^https?://[^/]+(/.*)$").find(loc)?.let { out += it.groupValues[1] }
        return out.filter { it.isNotEmpty() }
    }
}
