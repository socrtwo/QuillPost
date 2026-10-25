package info.socrtwo.quillpost.web.fileformats

import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MailDateFormat
import java.util.Base64

/**
 * A forgiving RFC 822 / 2045-2049 / 5322 reader for single messages (.eml, .emlx, .nws, .mht,
 * Gmail and Takeout downloads) — written for what real mailers produced rather than what the
 * standards say: 8-bit headers in any charset, RFC 2047 words in the wrong places, RFC 2231
 * parameters, missing or wrong boundaries, damaged base64, unknown charsets (UTF-7 included),
 * format=flowed, text/enriched, inline uuencode / yEnc / BinHex, AppleDouble, TNEF, and
 * S/MIME or PGP signed and encrypted messages (shown as far as possible, never verified).
 */
object MimeReader {

    /** One MIME entity: raw header fields (unfolded, still encoded) and the raw body. */
    class Entity(val headers: List<Pair<String, String>>, val body: ByteArray) {
        fun header(n: String): String? = headers.firstOrNull { it.first.equals(n, true) }?.second
        val contentType: Pair<String, Map<String, String>> by lazy { HeaderParams.parse(header("Content-Type") ?: "") }
        /** The media type; a missing or subtype-less type is text/plain as RFC 2045 says ("text" → text/plain). */
        val type: String get() = contentType.first.let { if (it.isEmpty() || !it.contains('/')) (if (it.isEmpty() || it == "text") "text/plain" else "application/octet-stream") else it }
        fun param(n: String): String? = contentType.second[n.lowercase()]
        val disposition: Pair<String, Map<String, String>> by lazy { HeaderParams.parse(header("Content-Disposition") ?: "") }
        val fileName: String? get() = (disposition.second["filename"] ?: contentType.second["name"])?.let { TextCodec.decodeHeader(it).trim() }?.takeIf { it.isNotEmpty() }
        val transferEncoding: String get() = (header("Content-Transfer-Encoding") ?: "").trim().lowercase().substringBefore(';').trim()
        val contentId: String? get() = header("Content-ID")?.trim()?.trim('<', '>')?.trim()?.takeIf { it.isNotEmpty() }
        val contentLocation: String? get() = header("Content-Location")?.replace(Regex("\\s+"), "")?.trim('"')?.takeIf { it.isNotEmpty() }

        /** The body with its transfer encoding undone. */
        fun decoded(): ByteArray = when (transferEncoding) {
            "base64" -> decodeBase64(body)
            "quoted-printable" -> TextCodec.decodeQuotedPrintable(String(body, Charsets.ISO_8859_1))
            "x-uuencode", "uuencode", "x-uue", "uue" -> {
                val t = String(body, Charsets.ISO_8859_1)
                LegacyEncodings.findUuencoded(t).firstOrNull()?.file?.bytes ?: LegacyEncodings.uudecode(t)
            }
            else -> body
        }
    }

    // ------------------------------------------------------------------ entity parsing

    /** Splits header block and body at the first empty line (CRLF or LF). */
    fun parseEntity(b: ByteArray, from: Int = 0, to: Int = b.size): Entity {
        var i = from
        var headerEnd = -1
        var bodyStart = to
        // A body without headers (starts with an empty line) is allowed.
        while (i < to) {
            val nl = indexOf(b, '\n'.code.toByte(), i, to)
            val lineEnd = if (nl < 0) to else nl
            val len = lineEnd - i - (if (lineEnd > i && b[lineEnd - 1] == '\r'.code.toByte()) 1 else 0)
            if (len == 0) { headerEnd = i; bodyStart = if (nl < 0) to else nl + 1; break }
            // A line that cannot be a header (no colon, not a continuation) ends broken header blocks.
            if (i > from && b[i] != ' '.code.toByte() && b[i] != '\t'.code.toByte() && !looksLikeHeader(b, i, lineEnd)) { headerEnd = i; bodyStart = i; break }
            if (nl < 0) { headerEnd = to; bodyStart = to; break }
            i = nl + 1
        }
        if (headerEnd < 0) { headerEnd = to; bodyStart = to }
        val headerText = TextCodec.auto(b.copyOfRange(from, headerEnd))
        return Entity(parseHeaderBlock(headerText), b.copyOfRange(bodyStart, to))
    }

    private fun looksLikeHeader(b: ByteArray, s: Int, e: Int): Boolean {
        var k = s
        while (k < e) {
            val c = b[k].toInt() and 0xff
            if (c == ':'.code) return k > s
            if (c <= 32 || c >= 127) return false
            k++
        }
        return false
    }

    /** Unfolds and splits a header block ("Name: value" lines); values stay encoded. */
    fun parseHeaderBlock(text: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (line in text.replace("\r\n", "\n").split('\n')) {
            if (line.isEmpty()) continue
            if ((line[0] == ' ' || line[0] == '\t') && out.isNotEmpty()) {
                val (n, v) = out.last(); out[out.size - 1] = n to (v + " " + line.trim())
                continue
            }
            val c = line.indexOf(':')
            if (c <= 0) continue
            out += line.substring(0, c).trim() to line.substring(c + 1).trim()
        }
        return out
    }

    /** The parts of a multipart body, split on [boundary] (guessed when missing). */
    fun splitMultipart(body: ByteArray, boundary0: String?): List<Entity> {
        val declared = boundary0?.takeIf { it.isNotEmpty() }
        if (declared != null) {
            val parts = splitOn(body, declared)
            if (parts.isNotEmpty()) return parts
            // The boundary parameter does not match the delimiter lines: use the one the body uses.
            val guessed = guessBoundary(body)?.takeIf { it != declared } ?: return parts
            return splitOn(body, guessed)
        }
        return splitOn(body, guessBoundary(body) ?: return emptyList())
    }

    private fun splitOn(body: ByteArray, boundary: String): List<Entity> {
        val delim = "--$boundary".toByteArray(Charsets.ISO_8859_1)
        val parts = ArrayList<Entity>()
        var partStart = -1
        var i = 0
        while (i <= body.size) {
            val nl = indexOf(body, '\n'.code.toByte(), i, body.size)
            val lineEnd = if (nl < 0) body.size else nl
            if (startsWith(body, i, delim)) {
                // "--boundary" possibly followed by "--" and whitespace.
                var k = i + delim.size
                val closing = k + 1 < body.size && body[k] == '-'.code.toByte() && body[k + 1] == '-'.code.toByte()
                if (closing) k += 2
                var onlySpace = true
                for (x in k until lineEnd) { val c = body[x].toInt(); if (c != ' '.code && c != '\t'.code && c != '\r'.code) { onlySpace = false; break } }
                if (onlySpace) {
                    if (partStart >= 0) {
                        var end = i
                        if (end > partStart && body[end - 1] == '\n'.code.toByte()) end--
                        if (end > partStart && body[end - 1] == '\r'.code.toByte()) end--
                        parts += parseEntity(body, partStart, end.coerceAtLeast(partStart))
                    }
                    if (closing) return parts
                    partStart = if (nl < 0) body.size else nl + 1
                }
            }
            if (nl < 0) break
            i = nl + 1
        }
        if (partStart in 0 until body.size) parts += parseEntity(body, partStart, body.size)
        return parts
    }

    private fun guessBoundary(body: ByteArray): String? {
        val t = String(body, 0, minOf(body.size, 65536), Charsets.ISO_8859_1)
        return Regex("(?m)^--([^\\s-][^\\r\\n]{0,69}?)\\s*$").find(t)?.groupValues?.get(1)?.removeSuffix("--")
    }

    private fun indexOf(b: ByteArray, x: Byte, from: Int, to: Int): Int { for (k in from until to) if (b[k] == x) return k; return -1 }
    private fun startsWith(b: ByteArray, at: Int, p: ByteArray): Boolean { if (at + p.size > b.size) return false; for (k in p.indices) if (b[at + k] != p[k]) return false; return true }

    fun decodeBase64(b: ByteArray): ByteArray {
        val clean = java.io.ByteArrayOutputStream(b.size)
        for (x in b) { val c = x.toInt() and 0xff; if (c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code || c == '+'.code || c == '/'.code) clean.write(c) else if (c == '-'.code) clean.write('+'.code) else if (c == '_'.code) clean.write('/'.code) }
        var s = clean.toByteArray()
        if (s.size % 4 == 1) s = s.copyOf(s.size - 1)
        val padded = s + ByteArray((4 - s.size % 4) % 4) { '='.code.toByte() }
        return runCatching { Base64.getDecoder().decode(padded) }.getOrDefault(ByteArray(0))
    }

    // ------------------------------------------------------------------ headers to values

    fun addresses(raw: String?): List<MailAddress> {
        if (raw.isNullOrBlank()) return emptyList()
        val parsed = runCatching { InternetAddress.parseHeader(raw.replace(Regex("[\\r\\n]+"), " "), false).toList() }.getOrNull()
        if (parsed != null && parsed.isNotEmpty()) return parsed.flatMap { a ->
            if (a.isGroup) runCatching { a.getGroup(false)?.toList() }.getOrNull()?.map { MailAddress(TextCodec.decodeHeader(it.personal ?: ""), it.address ?: "") } ?: emptyList()
            else listOf(MailAddress(TextCodec.decodeHeader(a.personal ?: "").trim(), (a.address ?: "").trim()))
        }.filter { it.name.isNotEmpty() || it.address.isNotEmpty() }
        return splitOutsideQuotes(raw, ',').mapNotNull { s ->
            val t = s.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val m = Regex("^(.*?)<([^<>]*)>").find(t)
            if (m != null) MailAddress(TextCodec.decodeHeader(m.groupValues[1].trim().trim('"')), m.groupValues[2].trim())
            else Regex("[^\\s<>\"]+@[^\\s<>\"]+").find(t)?.let { MailAddress(TextCodec.decodeHeader(t.replace(it.value, "").trim().trim('(', ')', '"')), it.value) } ?: MailAddress(TextCodec.decodeHeader(t), "")
        }
    }

    private fun splitOutsideQuotes(s: String, sep: Char): List<String> {
        val out = ArrayList<String>(); val cur = StringBuilder(); var q = false; var angle = 0
        for (c in s) {
            when {
                c == '"' -> { q = !q; cur.append(c) }
                c == '<' && !q -> { angle++; cur.append(c) }
                c == '>' && !q -> { angle = (angle - 1).coerceAtLeast(0); cur.append(c) }
                c == sep && !q && angle == 0 -> { out += cur.toString(); cur.setLength(0) }
                else -> cur.append(c)
            }
        }
        out += cur.toString()
        return out
    }

    fun parseDate(raw: String?): Long? {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        runCatching { MailDateFormat().parse(s)?.time }.getOrNull()?.let { return it }
        // "Mon, 3 Jun 2002 10:11:12 GMT+0200", ISO 8601, and dates with stray text in parentheses.
        val cleaned = s.replace(Regex("\\(.*?\\)"), "").replace(Regex("GMT([+-]\\d{4})"), "$1").replace(Regex("\\s+"), " ").trim()
        runCatching { MailDateFormat().parse(cleaned)?.time }.getOrNull()?.let { return it }
        runCatching { java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()?.let { return it }
        return null
    }

    fun importanceOf(headers: List<Pair<String, String>>): String {
        fun h(n: String) = headers.firstOrNull { it.first.equals(n, true) }?.second?.trim()?.lowercase()
        val imp = h("Importance") ?: h("Priority") ?: h("X-MSMail-Priority")
        if (imp != null) {
            if (imp.startsWith("high") || imp.startsWith("urgent")) return "high"
            if (imp.startsWith("low") || imp.startsWith("non-urgent")) return "low"
        }
        val xp = h("X-Priority")?.take(1)?.toIntOrNull() ?: return "normal"
        return when { xp <= 2 -> "high"; xp >= 4 -> "low"; else -> "normal" }
    }

    // ------------------------------------------------------------------ message → ParsedMessage

    private class Ctx(val depth: Int) {
        val text = StringBuilder()
        var html: StringBuilder? = null
        val atts = ArrayList<ParsedAttachment>()
        val notes = LinkedHashSet<String>()
        var calendar: String? = null
        var security: String? = null
        val css = ArrayList<Pair<String?, String>>()
        fun appendHtml(h: String) { (html ?: StringBuilder().also { html = it }).append(h) }
    }

    private const val MAX_DEPTH = 12

    /** Strips wrappers that are not part of the message: BOM, an mbox "From " line, leading blank lines. */
    fun unwrap(b: ByteArray): ByteArray {
        var s = 0
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) s = 3
        while (s < b.size && (b[s] == '\r'.code.toByte() || b[s] == '\n'.code.toByte())) s++
        if (b.size - s > 5 && String(b, s, 5, Charsets.ISO_8859_1) == "From ") {
            val nl = indexOf(b, '\n'.code.toByte(), s, b.size)
            if (nl > 0) s = nl + 1
        }
        return if (s == 0) b else b.copyOfRange(s, b.size)
    }

    fun parse(bytes: ByteArray, format: String = "eml", depth: Int = 0): ParsedMessage {
        val raw = unwrap(bytes)
        val top = parseEntity(raw)
        val ctx = Ctx(depth)
        walk(top, ctx, depth, null)
        val hs = top.headers
        fun h(n: String) = hs.firstOrNull { it.first.equals(n, true) }?.second
        var html = ctx.html?.toString()?.takeIf { it.isNotBlank() }
        var atts: List<ParsedAttachment> = ctx.atts
        if (format == "mhtml" || h("Snapshot-Content-Location") != null) {
            val r = Mhtml.resolve(html, atts, ctx.css, top.contentLocation ?: h("Snapshot-Content-Location"))
            html = r.first; atts = r.second
        }
        // Inline resources: referenced from the HTML by cid: (or content location).
        atts = atts.map { a ->
            val ref = html != null && ((a.contentId != null && html.contains("cid:" + a.contentId, true)) || (a.contentLocation != null && html.contains(a.contentLocation)))
            if (ref != a.inline) ParsedAttachment(a.fileName, a.mimeType, a.bytes, a.contentId, ref, a.contentLocation, a.embedded) else a
        }
        val notes = ArrayList(ctx.notes)
        h("X-Gmail-Labels")?.let { notes += "Gmail labels: " + TextCodec.decodeHeader(it).split(',').joinToString(", ") { l -> l.trim().trim('"') } + "." }
        val decodedHeaders = hs.map { (n, v) -> n to TextCodec.decodeHeader(v) }
        val from = addresses(h("From")).firstOrNull()
        val sender = addresses(h("Sender")).firstOrNull()?.takeIf { from == null || !it.address.equals(from.address, true) }
        return ParsedMessage(
            format = format,
            subject = TextCodec.decodeHeader(h("Subject") ?: "").trim(),
            from = from,
            sender = sender,
            to = addresses(h("To")) + addresses(h("Apparently-To")),
            cc = addresses(h("Cc")),
            bcc = addresses(h("Bcc")),
            replyTo = addresses(h("Reply-To")),
            date = parseDate(h("Date")) ?: parseDate(h("Delivery-Date")) ?: parseDate(h("Received")?.substringAfterLast(';')),
            messageId = h("Message-ID")?.trim() ?: "",
            inReplyTo = h("In-Reply-To")?.trim() ?: "",
            references = h("References")?.trim() ?: "",
            headers = decodedHeaders,
            bodyText = ctx.text.toString().trim('\n', '\r'),
            bodyHtml = html,
            attachments = atts,
            importance = importanceOf(hs),
            notes = notes,
            security = ctx.security,
            calendar = ctx.calendar,
            newsgroups = h("Newsgroups")?.trim() ?: "",
            template = h("X-Unsent")?.trim() == "1",
            rfc822 = raw
        )
    }

    private fun walk(e: Entity, ctx: Ctx, depth: Int, parentType: String?) {
        if (depth > MAX_DEPTH) return
        val explicitType = e.header("Content-Type") != null && e.contentType.first.isNotEmpty()
        val type = if (!explicitType && parentType == "multipart/digest") "message/rfc822" else e.type
        val disp = e.disposition.first.lowercase()
        val name = e.fileName
        val isAttachment = disp == "attachment" || (name != null && !type.startsWith("multipart/") && !(disp == "inline" && type.startsWith("text/") && name.isBlank()))
        when {
            type.startsWith("multipart/") -> multipart(e, type, ctx, depth)
            type == "message/rfc822" || type == "message/global" || type == "message/news" -> {
                val inner = runCatching { parse(e.decoded(), "eml", depth + 1) }.getOrNull()
                if (inner != null) {
                    val n = name ?: (MimeWriter.safeFileName(inner.subject.ifBlank { "Attached message" }) + ".eml")
                    ctx.atts += ParsedAttachment(n, "message/rfc822", e.decoded(), e.contentId, false, e.contentLocation, inner)
                }
            }
            type == "application/ms-tnef" || type == "application/vnd.ms-tnef" || name?.equals("winmail.dat", true) == true -> tnef(e, ctx, depth)
            type == "application/pkcs7-mime" || type == "application/x-pkcs7-mime" || (name?.lowercase()?.endsWith(".p7m") == true && type == "application/octet-stream") -> smime(e, ctx, depth)
            type == "application/pkcs7-signature" || type == "application/x-pkcs7-signature" -> { ctx.security = ctx.security ?: "smime-signed"; ctx.notes += SMIME_SIGNED }
            type == "application/pgp-signature" -> { ctx.security = ctx.security ?: "pgp-signed"; ctx.notes += PGP_SIGNED }
            type == "application/pgp-encrypted" && !isAttachment -> {}
            type == "application/applefile" -> LegacyEncodings.decodeApple(e.decoded())?.takeIf { it.bytes.isNotEmpty() }?.let {
                ctx.atts += ParsedAttachment(it.fileName.ifBlank { name ?: "file" }, MimeTypes.byName(it.fileName), it.bytes)
            }
            type == "application/mac-binhex40" || type == "application/mac-binhex" -> {
                val d = LegacyEncodings.findBinHex(String(e.decoded(), Charsets.ISO_8859_1)).firstOrNull()?.file
                if (d != null) ctx.atts += ParsedAttachment(d.fileName.ifBlank { name ?: "file" }, MimeTypes.byName(d.fileName), d.bytes) else attach(e, type, ctx)
            }
            (type == "text/calendar" || type == "application/ics") -> {
                if (ctx.calendar == null) ctx.calendar = TextCodec.decode(e.decoded(), e.param("charset") ?: "utf-8")
                if (isAttachment) attach(e, type, ctx)
            }
            (type == "message/delivery-status" || type == "message/disposition-notification" || type == "text/rfc822-headers" || type == "message/global-delivery-status") && disp != "attachment" -> {
                // Delivery and read reports: shown under the explanation, as mail programs do.
                val t = TextCodec.decode(e.decoded(), e.param("charset") ?: "utf-8").trim()
                if (t.isNotEmpty()) { if (ctx.text.isNotEmpty()) ctx.text.append("\n\n"); ctx.text.append(t); if (ctx.html != null) ctx.appendHtml("<pre style=\"white-space:pre-wrap\">" + Rtf.escapeHtml(t) + "</pre>") }
            }
            isAttachment && disp == "inline" && type == "text/plain" -> { plain(e, ctx); attach(e, type, ctx) }
            isAttachment -> attach(e, type, ctx)
            type == "text/html" || type == "application/xhtml+xml" -> {
                val bytes = e.decoded()
                val cs = e.param("charset") ?: Regex("(?i)<meta[^>]+charset=[\"']?([A-Za-z0-9_.:-]+)").find(String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1))?.groupValues?.get(1)
                ctx.appendHtml(TextCodec.decode(bytes, cs))
            }
            type == "text/enriched" || type == "text/richtext" -> {
                val t = TextCodec.decode(e.decoded(), e.param("charset"))
                ctx.appendHtml(Enriched.toHtml(t, type == "text/richtext"))
                if (ctx.text.isEmpty()) ctx.text.append(Rtf.htmlToText(Enriched.toHtml(t, type == "text/richtext")))
            }
            type == "text/rtf" || type == "application/rtf" -> {
                val b = e.decoded()
                if (ctx.html == null && Rtf.isRtf(b)) { ctx.appendHtml(Rtf.toHtml(b)); if (ctx.text.isEmpty()) ctx.text.append(Rtf.toText(b)) } else attach(e, type, ctx)
            }
            type == "text/css" -> ctx.css += e.contentLocation to TextCodec.decode(e.decoded(), e.param("charset"))
            type.startsWith("text/") && (type == "text/plain" || !explicitType || type == "text/x-moz-deleted" || type.startsWith("text/x-")) && type != "text/x-vcard" -> plain(e, ctx)
            else -> attach(e, type, ctx)
        }
    }

    private fun multipart(e: Entity, type: String, ctx: Ctx, depth: Int) {
        val parts = splitMultipart(e.body, e.param("boundary"))
        if (parts.isEmpty()) { plain(Entity(listOf("Content-Type" to "text/plain"), e.body), ctx); return }
        when (type) {
            "multipart/alternative" -> {
                // Plain text for the text body, the richest renderable part for the HTML body.
                val sub = Ctx(depth)
                val richest = parts.lastOrNull { val t = it.type; t == "text/html" || t == "multipart/related" || t == "text/enriched" || t == "text/richtext" || t == "multipart/mixed" }
                for (p in parts) {
                    val t = p.type
                    if (t == "text/plain" && ctx.text.isEmpty()) plain(p, ctx)
                    else if (p === richest) walk(p, sub, depth + 1, type)
                    else if (t == "text/calendar" || t == "application/ics") walk(p, ctx, depth + 1, type)
                    else if (!t.startsWith("text/") && t != "multipart/related" && richest == null) walk(p, ctx, depth + 1, type)
                }
                sub.html?.let { ctx.appendHtml(it.toString()) }
                if (sub.text.isNotEmpty() && ctx.text.isEmpty()) ctx.text.append(sub.text)
                ctx.atts += sub.atts; ctx.notes += sub.notes; ctx.css += sub.css
                if (ctx.calendar == null) ctx.calendar = sub.calendar
                if (ctx.security == null) ctx.security = sub.security
            }
            "multipart/related" -> {
                val start = e.param("start")?.trim('<', '>')
                val root = parts.firstOrNull { start != null && it.contentId == start } ?: parts.first()
                walk(root, ctx, depth + 1, type)
                for (p in parts) if (p !== root) {
                    if (p.type == "text/css") walk(p, ctx, depth + 1, type)
                    else if (p.type.startsWith("multipart/") || p.type == "text/html" && ctx.html == null) walk(p, ctx, depth + 1, type)
                    else attach(p, p.type, ctx, related = true)
                }
            }
            "multipart/signed" -> {
                val proto = e.param("protocol")?.lowercase() ?: ""
                walk(parts.first(), ctx, depth + 1, type)
                if (proto.contains("pgp")) { ctx.security = ctx.security ?: "pgp-signed"; ctx.notes += PGP_SIGNED }
                else { ctx.security = ctx.security ?: "smime-signed"; ctx.notes += SMIME_SIGNED }
            }
            "multipart/encrypted" -> {
                ctx.security = "pgp-encrypted"; ctx.notes += PGP_ENCRYPTED
                parts.drop(1).forEach { attach(it, it.type, ctx, fallbackName = "encrypted.asc") }
            }
            "multipart/appledouble" -> {
                val header = parts.firstOrNull { it.type == "application/applefile" }?.let { LegacyEncodings.decodeApple(it.decoded()) }
                for (p in parts) if (p.type != "application/applefile") {
                    if (p.fileName == null && header != null && header.fileName.isNotBlank()) attach(p, p.type, ctx, fallbackName = header.fileName)
                    else walk(p, ctx, depth + 1, type)
                }
            }
            else -> for (p in parts) walk(p, ctx, depth + 1, type)
        }
    }

    private fun plain(e: Entity, ctx: Ctx) {
        var t = TextCodec.decode(e.decoded(), e.param("charset"))
        if (e.param("format")?.equals("flowed", true) == true) t = unflow(t, e.param("delsp")?.equals("yes", true) == true)
        // Files encoded inside the text (uuencode, begin-base64, yEnc, BinHex).
        val latin = String(e.decoded(), Charsets.ISO_8859_1)
        val found = (LegacyEncodings.findUuencoded(latin) + LegacyEncodings.findYenc(latin) + LegacyEncodings.findBinHex(latin)).sortedBy { it.range.first }
        if (found.isNotEmpty()) {
            for (f in found) ctx.atts += ParsedAttachment(f.file.fileName, MimeTypes.byName(f.file.fileName), f.file.bytes)
            // Remove the encoded blocks from the text shown.
            val sb = StringBuilder(); var last = 0
            for (f in found) { if (f.range.first >= last) { sb.append(latin, last, f.range.first); last = f.range.last + 1 } }
            sb.append(latin.substring(minOf(last, latin.length)))
            t = TextCodec.decode(sb.toString().toByteArray(Charsets.ISO_8859_1), e.param("charset"))
            if (e.param("format")?.equals("flowed", true) == true) t = unflow(t, e.param("delsp")?.equals("yes", true) == true)
        }
        t = pgpInline(t, ctx)
        if (ctx.text.isNotEmpty()) ctx.text.append("\n\n")
        ctx.text.append(t.replace("\r\n", "\n"))
        if (ctx.html != null) ctx.appendHtml("<pre style=\"white-space:pre-wrap;font-family:inherit\">" + Rtf.escapeHtml(t) + "</pre>")
    }

    /** Clear-signed PGP text is shown without its armour; encrypted PGP text is left as is. */
    private fun pgpInline(t: String, ctx: Ctx): String {
        if (t.contains("-----BEGIN PGP MESSAGE-----")) { ctx.security = "pgp-encrypted"; ctx.notes += PGP_ENCRYPTED; return t }
        val b = t.indexOf("-----BEGIN PGP SIGNED MESSAGE-----")
        if (b < 0) return t
        ctx.security = ctx.security ?: "pgp-signed"; ctx.notes += PGP_SIGNED
        val afterHeader = Regex("\\r?\\n\\r?\\n").find(t, b)?.range?.last?.plus(1) ?: return t
        val sig = t.indexOf("-----BEGIN PGP SIGNATURE-----", afterHeader)
        val end = t.indexOf("-----END PGP SIGNATURE-----", afterHeader)
        val signed = t.substring(afterHeader, if (sig > 0) sig else t.length).lines().joinToString("\n") { it.removePrefix("- ") }.trimEnd()
        return t.substring(0, b) + signed + (if (end > 0) t.substring(end + "-----END PGP SIGNATURE-----".length) else "")
    }

    /** RFC 3676 format=flowed: joins soft line breaks, removes space-stuffing. */
    fun unflow(t: String, delsp: Boolean): String {
        val out = StringBuilder()
        var para = StringBuilder()
        var paraQuote = -1
        fun flush() { if (paraQuote >= 0) { out.append(">".repeat(paraQuote)).append(if (paraQuote > 0) " " else "").append(para).append('\n') }; para = StringBuilder(); paraQuote = -1 }
        for (raw in t.replace("\r\n", "\n").split('\n')) {
            var q = 0
            while (q < raw.length && raw[q] == '>') q++
            var line = raw.substring(q)
            if (line.startsWith(" ")) line = line.substring(1)
            val soft = line.endsWith(" ") && line != "-- "
            if (paraQuote >= 0 && q != paraQuote) flush()
            paraQuote = q
            para.append(if (soft && delsp) line.dropLast(1) else line)
            if (!soft) flush()
        }
        flush()
        return out.toString().trimEnd('\n')
    }

    private fun attach(e: Entity, type: String, ctx: Ctx, related: Boolean = false, fallbackName: String? = null) {
        val bytes = e.decoded()
        val described = e.header("Content-Description")?.let { TextCodec.decodeHeader(it).trim() }?.takeIf { it.isNotEmpty() && it.length < 80 && !it.contains('/') && !it.contains('\\') }
            ?.let { if (it.contains('.')) it else it + MimeTypes.extensionFor(type) }
        val name = e.fileName ?: fallbackName ?: e.contentLocation?.substringAfterLast('/')?.substringBefore('?')?.takeIf { it.isNotBlank() && it.length < 120 }?.let { java.net.URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") } ?: described
            ?: ((if (related || e.contentId != null) "inline-" else "attachment-") + (ctx.atts.size + 1) + MimeTypes.extensionFor(type))
        val mime = if (type == "application/octet-stream" || type.isEmpty()) MimeTypes.byName(name).takeIf { it != "application/octet-stream" } ?: type.ifEmpty { "application/octet-stream" } else type
        ctx.atts += ParsedAttachment(name, mime, bytes, e.contentId, related, e.contentLocation)
    }

    private fun tnef(e: Entity, ctx: Ctx, depth: Int) {
        val bytes = e.decoded()
        val t = runCatching { Tnef.parse(bytes, depth + 1) }.getOrNull()
        if (t == null) { attach(e, "application/ms-tnef", ctx); ctx.notes += "The winmail.dat part could not be read; it is listed as an attachment."; return }
        ctx.atts += t.attachments
        // Outlook's rich text (RTF or HTML) is the real body; the text/plain part beside it is a copy.
        if (ctx.html == null && t.bodyHtml != null) ctx.appendHtml(t.bodyHtml)
        if (ctx.text.isBlank() && t.bodyText.isNotBlank()) ctx.text.append(t.bodyText)
        ctx.notes += t.notes
        ctx.notes += "Outlook rich-text content (winmail.dat) was unpacked: ${t.attachments.size} attachment(s)."
    }

    private fun smime(e: Entity, ctx: Ctx, depth: Int) {
        val st = e.param("smime-type")?.lowercase()
        val content = Cms.signedContent(e.decoded())
        if (content != null && st != "enveloped-data") {
            ctx.security = ctx.security ?: "smime-signed"; ctx.notes += SMIME_SIGNED
            walk(parseEntity(content), ctx, depth + 1, null)
            return
        }
        ctx.security = "smime-encrypted"; ctx.notes += SMIME_ENCRYPTED
        attach(e, "application/pkcs7-mime", ctx, fallbackName = "smime.p7m")
    }

    const val SMIME_SIGNED = "This message is signed with S/MIME. Anne's Post shows the signed content but does not verify the signature."
    const val SMIME_ENCRYPTED = "This message is encrypted with S/MIME. Anne's Post cannot decrypt it; open it in a mail program that holds your certificate."
    const val PGP_SIGNED = "This message is signed with OpenPGP. Anne's Post shows the signed text but does not verify the signature."
    const val PGP_ENCRYPTED = "This message is encrypted with OpenPGP. Anne's Post cannot decrypt it; open it with your OpenPGP software."
}

/** Content-Type / Content-Disposition values with RFC 2231 continuations, charsets and lenient quoting. */
object HeaderParams {
    fun parse(value: String): Pair<String, Map<String, String>> {
        val parts = split(value)
        val main = parts.firstOrNull()?.trim()?.lowercase()?.let { if (it.contains('=') && !it.contains('/')) "" else it.substringBefore(' ') } ?: ""
        val raw = LinkedHashMap<String, String>()
        for (p in parts.drop(if (main.isEmpty() && parts.firstOrNull()?.contains('=') == true) 0 else 1)) {
            val eq = p.indexOf('=')
            if (eq <= 0) continue
            val k = p.substring(0, eq).trim().lowercase()
            var v = p.substring(eq + 1).trim()
            if (v.startsWith("\"")) v = v.removePrefix("\"").let { if (it.endsWith("\"")) it.dropLast(1) else it }.replace("\\\"", "\"").replace("\\\\", "\\")
            raw.putIfAbsent(k, v)
        }
        val out = LinkedHashMap<String, String>()
        // RFC 2231: name*0*=, name*1=, name*= charset'lang'value
        val groups = raw.keys.groupBy { it.substringBefore('*') }
        for ((base, keys) in groups) {
            if (keys.size == 1 && keys[0] == base) { out[base] = raw.getValue(base); continue }
            val segs = keys.filter { it != base }.mapNotNull { k ->
                val rest = k.substringAfter('*', "")
                val idx = rest.trimEnd('*').toIntOrNull() ?: if (rest == "" || rest == "*") 0 else null
                idx?.let { Triple(it, k.endsWith("*"), raw.getValue(k)) }
            }.sortedBy { it.first }
            if (segs.isEmpty()) { raw[base]?.let { out[base] = it }; continue }
            var charset: String? = null
            val bytes = java.io.ByteArrayOutputStream()
            segs.forEachIndexed { i, (_, enc, v0) ->
                var v = v0
                if (enc && i == 0 && v.count { it == '\'' } >= 2) { charset = v.substringBefore('\''); v = v.substringAfter('\'').substringAfter('\'') }
                if (enc) bytes.write(pctDecode(v)) else bytes.write(v.toByteArray(Charsets.ISO_8859_1))
            }
            out[base] = TextCodec.decode(bytes.toByteArray(), charset ?: "utf-8")
        }
        return main to out
    }

    private fun pctDecode(s: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            if (s[i] == '%' && i + 3 <= s.length) { val h = s.substring(i + 1, i + 3).toIntOrNull(16); if (h != null) { out.write(h); i += 3; continue } }
            out.write(s[i].code and 0xff); i++
        }
        return out.toByteArray()
    }

    private fun split(v: String): List<String> {
        val out = ArrayList<String>(); val cur = StringBuilder(); var q = false; var i = 0
        while (i < v.length) {
            val c = v[i]
            when {
                c == '\\' && q && i + 1 < v.length -> { cur.append(c).append(v[i + 1]); i++ }
                c == '"' -> { q = !q; cur.append(c) }
                c == ';' && !q -> { out += cur.toString(); cur.setLength(0) }
                else -> cur.append(c)
            }
            i++
        }
        out += cur.toString()
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }
}

/** text/enriched (RFC 1896) and text/richtext (RFC 1341) to HTML. */
object Enriched {
    fun toHtml(src: String, richtext: Boolean = false): String {
        val out = StringBuilder()
        var i = 0
        var nofill = 0
        var paramDepth = 0
        val param = StringBuilder()
        val stack = ArrayList<String>()
        val s = src.replace("\r\n", "\n")
        while (i < s.length) {
            val c = s[i]
            if (c == '<') {
                if (i + 1 < s.length && s[i + 1] == '<') { (if (paramDepth > 0) param else out).append("&lt;"); i += 2; continue }
                val end = s.indexOf('>', i)
                if (end < 0) { out.append("&lt;"); i++; continue }
                val tag = s.substring(i + 1, end).trim().lowercase()
                i = end + 1
                val closing = tag.startsWith("/")
                val name = tag.removePrefix("/")
                if (name == "param") { if (closing) { paramDepth--; applyParam(out, stack, param.toString()); param.setLength(0) } else paramDepth++; continue }
                if (name == "nofill") { if (closing) nofill-- else nofill++ }
                if (richtext && name == "nl") { out.append("<br>"); continue }
                if (richtext && name == "lt") { out.append("&lt;"); continue }
                val open = mapOf("bold" to "<b>", "italic" to "<i>", "underline" to "<u>", "fixed" to "<tt>", "center" to "<div style=\"text-align:center\">",
                    "flushleft" to "<div style=\"text-align:left\">", "flushright" to "<div style=\"text-align:right\">", "flushboth" to "<div style=\"text-align:justify\">",
                    "nofill" to "<pre style=\"white-space:pre-wrap;font-family:inherit\">", "excerpt" to "<blockquote>", "bigger" to "<big>", "smaller" to "<small>",
                    "indent" to "<div style=\"margin-left:2em\">", "indentright" to "<div style=\"margin-right:2em\">", "subscript" to "<sub>", "superscript" to "<sup>",
                    "color" to "<span>", "fontfamily" to "<span>", "paraindent" to "<div>", "lang" to "<span>")
                val close = mapOf("bold" to "</b>", "italic" to "</i>", "underline" to "</u>", "fixed" to "</tt>", "center" to "</div>", "flushleft" to "</div>", "flushright" to "</div>",
                    "flushboth" to "</div>", "nofill" to "</pre>", "excerpt" to "</blockquote>", "bigger" to "</big>", "smaller" to "</small>", "indent" to "</div>",
                    "indentright" to "</div>", "subscript" to "</sub>", "superscript" to "</sup>", "color" to "</span>", "fontfamily" to "</span>", "paraindent" to "</div>", "lang" to "</span>")
                if (closing) { close[name]?.let { out.append(it) }; stack.remove(name) } else { open[name]?.let { out.append(it); stack += name } }
                continue
            }
            if (paramDepth > 0) { param.append(c); i++; continue }
            if (c == '\n' && nofill == 0 && !richtext) {
                var n = 0
                while (i < s.length && s[i] == '\n') { n++; i++ }
                out.append(if (n == 1) " " else "<br>".repeat(n - 1))
                continue
            }
            if (richtext && c == '\n') { out.append(' '); i++; continue }
            out.append(when (c) { '&' -> "&amp;"; '>' -> "&gt;"; '"' -> "&quot;"; else -> c.toString() })
            i++
        }
        return out.toString()
    }

    private fun applyParam(out: StringBuilder, stack: List<String>, p: String) {
        val last = stack.lastOrNull() ?: return
        val idx = out.lastIndexOf("<span>")
        if (idx < 0) return
        val v = p.trim().replace("\"", "")
        val style = when (last) {
            "color" -> if (Regex("^[0-9a-fA-F]{4},[0-9a-fA-F]{4},[0-9a-fA-F]{4}$").matches(v)) "color:#" + v.split(',').joinToString("") { it.take(2) } else "color:$v"
            "fontfamily" -> "font-family:'$v'"
            else -> return
        }
        out.replace(idx, idx + 6, "<span style=\"$style\">")
    }
}

/** Just enough DER/BER to take the signed content out of an opaque S/MIME (CMS SignedData) blob. */
object Cms {
    private val SIGNED_DATA = byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x07, 0x02)
    private val ENVELOPED_DATA = byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x07, 0x03)

    private class Tlv(val tag: Int, val constructed: Boolean, val start: Int, val end: Int, val next: Int)

    private fun read(b: ByteArray, at: Int, limit: Int): Tlv? {
        if (at + 2 > limit) return null
        val tag = b[at].toInt() and 0xff
        var p = at + 1
        var len = b[p++].toInt() and 0xff
        val indefinite = len == 0x80
        if (len > 0x80) { val n = len and 0x7f; if (n > 4 || p + n > limit) return null; len = 0; repeat(n) { len = (len shl 8) or (b[p++].toInt() and 0xff) } }
        val constructed = tag and 0x20 != 0
        if (indefinite) {
            var q = p
            while (q + 2 <= limit) {
                if (b[q].toInt() == 0 && b[q + 1].toInt() == 0) return Tlv(tag, constructed, p, q, q + 2)
                val c = read(b, q, limit) ?: return null
                q = c.next
            }
            return null
        }
        if (len < 0 || p + len > limit) return null
        return Tlv(tag, constructed, p, p + len, p + len)
    }

    private fun children(b: ByteArray, t: Tlv): List<Tlv> {
        val out = ArrayList<Tlv>(); var p = t.start
        while (p < t.end) { val c = read(b, p, t.end) ?: break; out += c; p = c.next }
        return out
    }

    private fun octets(b: ByteArray, t: Tlv, out: java.io.ByteArrayOutputStream) {
        if (!t.constructed) out.write(b, t.start, t.end - t.start) else for (c in children(b, t)) octets(b, c, out)
    }

    fun isCms(b: ByteArray): Boolean = contentType(b) != null
    fun isEnveloped(b: ByteArray): Boolean = contentType(b)?.contentEquals(ENVELOPED_DATA) == true

    private fun contentType(b: ByteArray): ByteArray? {
        val ci = read(b, 0, b.size)?.takeIf { it.tag == 0x30 } ?: return null
        val oid = children(b, ci).firstOrNull()?.takeIf { it.tag == 0x06 } ?: return null
        return b.copyOfRange(oid.start, oid.end)
    }

    /** The encapsulated content of a SignedData ContentInfo, or null. */
    fun signedContent(b: ByteArray): ByteArray? = runCatching {
        val ci = read(b, 0, b.size)?.takeIf { it.tag == 0x30 } ?: return null
        val kids = children(b, ci)
        if (kids.size < 2 || !b.copyOfRange(kids[0].start, kids[0].end).contentEquals(SIGNED_DATA)) return null
        val sd = children(b, kids[1]).firstOrNull()?.takeIf { it.tag == 0x30 } ?: return null
        val encap = children(b, sd).getOrNull(2)?.takeIf { it.tag == 0x30 } ?: return null
        val explicit = children(b, encap).getOrNull(1)?.takeIf { it.tag == 0xA0 } ?: return null
        val os = children(b, explicit).firstOrNull() ?: return null
        val out = java.io.ByteArrayOutputStream(); octets(b, os, out)
        out.toByteArray().takeIf { it.isNotEmpty() }
    }.getOrNull()
}
