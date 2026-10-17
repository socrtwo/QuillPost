package info.socrtwo.quillpost.web.mailstore

import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.internet.MimeBodyPart
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Apple Mail .emlx (Mail 2 and later; one file per message): a decimal byte count on the first
 * line, the RFC 5322 message, then an XML property list with the status flags and the arrival
 * date. A .partial.emlx has its attachments removed and stored next to it under
 * Attachments/<message number>/<MIME part number>/<file>; they are put back when present.
 */
object Emlx {
    data class Parsed(val message: ByteArray, val flags: MsgFlags, val received: Long?, val keywords: List<String>, val flagBits: Long)

    fun looksLike(head: ByteArray): Boolean {
        val s = String(head, 0, minOf(head.size, 24), Charsets.ISO_8859_1)
        val m = Regex("^\\s*(\\d{1,12})\\s*\\r?\\n").find(s) ?: return false
        val rest = String(head, m.range.last + 1, minOf(head.size - m.range.last - 1, 400), Charsets.ISO_8859_1)
        return Regex("^[!-9;-~]+:", RegexOption.MULTILINE).containsMatchIn(rest.lineSequence().firstOrNull().orEmpty())
    }

    fun parse(bytes: ByteArray): Parsed {
        var i = 0
        while (i < bytes.size && bytes[i] != '\n'.code.toByte()) i++
        val count = String(bytes, 0, i, Charsets.ISO_8859_1).trim().toLongOrNull() ?: throw MailStoreException("Not an .emlx file (no byte count)")
        val start = i + 1
        val end = minOf(bytes.size.toLong(), start + count).toInt()
        val msg = bytes.copyOfRange(start, end)
        val plist = if (end < bytes.size) String(bytes, end, bytes.size - end, Charsets.UTF_8) else ""
        val flags = plistInteger(plist, "flags") ?: 0L
        val received = plistInteger(plist, "date-received")?.let { it * 1000 } ?: plistReal(plist, "date-received")?.let { (it * 1000).toLong() }
        val f = MsgFlags(
            seen = flags and (1L shl 0) != 0L, deleted = flags and (1L shl 1) != 0L, answered = flags and (1L shl 2) != 0L,
            flagged = flags and (1L shl 4) != 0L, draft = flags and (1L shl 6) != 0L, forwarded = flags and (1L shl 8) != 0L
        )
        val kw = ArrayList<String>()
        if (f.flagged) {
            // Apple Mail keeps the flag colour in bits 41-43 and shows it on IMAP as $MailFlagBit0..2.
            val color = ((flags shr 41) and 0x7).toInt()
            for (b in 0..2) if (color and (1 shl b) != 0) kw += "\$MailFlagBit$b"
        }
        if (flags and (1L shl 24) != 0L) kw += "\$Junk"
        if (flags and (1L shl 25) != 0L) kw += "\$NotJunk"
        if (flags and (1L shl 9) != 0L) kw += "\$Redirected"
        return Parsed(msg, f, received, kw, flags)
    }

    private fun plistInteger(plist: String, key: String): Long? =
        Regex("<key>\\s*${Regex.escape(key)}\\s*</key>\\s*<integer>\\s*(-?\\d+)\\s*</integer>").find(plist)?.groupValues?.get(1)?.toLongOrNull()

    private fun plistReal(plist: String, key: String): Double? =
        Regex("<key>\\s*${Regex.escape(key)}\\s*</key>\\s*<real>\\s*([-0-9.eE+]+)\\s*</real>").find(plist)?.groupValues?.get(1)?.toDoubleOrNull()

    /** One .emlx file as a store message; [attachmentsDir] is the bundle's Attachments folder, if any. */
    fun message(file: File, attachmentsDir: File?): StoreMessage {
        val parsed = parse(metadataBytes(file))
        val partial = file.name.endsWith(".partial.emlx", true)
        return StoreMessage(parsed.flags, parsed.received, parsed.keywords) {
            val p = parse(file.readBytes())
            if (partial) restoreAttachments(p.message, attachmentsDir?.let { File(it, file.name.substringBefore('.')) }) else p.message
        }
    }

    /** The byte-count line plus the property list, skipping the message itself (for listing). */
    private fun metadataBytes(file: File): ByteArray = java.io.RandomAccessFile(file, "r").use { raf ->
        val firstLine = head(file, 32).let { h -> String(h, Charsets.ISO_8859_1).substringBefore('\n') }
        val count = firstLine.trim().toLongOrNull() ?: return@use head(file, 64)
        val start = firstLine.length + 1L
        val plistStart = minOf(raf.length(), start + count)
        val tail = ByteArray((raf.length() - plistStart).toInt().coerceIn(0, 1 shl 20))
        raf.seek(plistStart); raf.readFully(tail)
        // A message of zero length followed by the plist parses the same way as the full file.
        "0\n".toByteArray() + tail
    }

    /** Puts the attachments of a .partial.emlx back into the message, where Apple Mail stored them. */
    fun restoreAttachments(message: ByteArray, dir: File?): ByteArray {
        if (dir == null || !dir.isDirectory) return message
        val msg = ImportedMimeMessage(localSession, ByteArrayInputStream(message))
        var changed = false
        fun visit(p: Part, number: String) {
            val content = runCatching { p.content }.getOrNull()
            if (content is Multipart) {
                for (i in 0 until content.count) visit(content.getBodyPart(i), if (number.isEmpty()) "${i + 1}" else "$number.${i + 1}")
                return
            }
            if (p !is MimeBodyPart) return
            val stripped = p.getHeader("X-Apple-Content-Length") != null
            if (!stripped) return
            val partDir = File(dir, number.ifEmpty { "1" })
            val f = partDir.listFiles()?.firstOrNull { it.isFile } ?: return
            val type = p.contentType ?: "application/octet-stream"
            p.setDataHandler(jakarta.activation.DataHandler(jakarta.mail.util.ByteArrayDataSource(f.readBytes(), type)))
            p.setHeader("Content-Type", type)
            p.removeHeader("X-Apple-Content-Length")
            p.setHeader("Content-Transfer-Encoding", "base64")
            changed = true
        }
        val top = runCatching { msg.content }.getOrNull()
        if (top is Multipart) for (i in 0 until top.count) visit(top.getBodyPart(i), "${i + 1}")
        if (!changed) return message
        msg.saveChanges()
        return msg.bytes()
    }
}
