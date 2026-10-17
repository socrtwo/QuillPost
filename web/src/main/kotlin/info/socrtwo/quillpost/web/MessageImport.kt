package info.socrtwo.quillpost.web

import info.socrtwo.quillpost.web.mailstore.Emlx
import info.socrtwo.quillpost.web.mailstore.ImportedMimeMessage
import info.socrtwo.quillpost.web.mailstore.Mbox
import info.socrtwo.quillpost.web.mailstore.MsgFlags
import info.socrtwo.quillpost.web.mailstore.localSession
import jakarta.mail.internet.MimeUtility
import java.io.ByteArrayInputStream

/**
 * A single message file turned into the form QuillPost stores, shows and uploads: RFC 5322 bytes.
 *
 * [format] is a short id ("eml", "emlx", "msg", "tnef"…), [formatLabel] the description shown to
 * the user, [flags] and [internalDate] what the file recorded about the message (when it does),
 * and [warnings] anything that could not be carried over.
 */
data class ParsedMessage(
    val format: String,
    val formatLabel: String,
    val mime: ByteArray,
    val subject: String = "",
    val from: String = "",
    val date: Long? = null,
    val flags: MsgFlags = MsgFlags(seen = true),
    val internalDate: Long? = null,
    val warnings: List<String> = emptyList()
)

/**
 * Single-message files → [ParsedMessage]. Used by File ▸ Open, drag-and-drop, the "Open with"
 * file associations and the mailbox readers (for .eml / .msg files inside a folder).
 *
 * The RFC 5322/MIME path (.eml, .emlx, a one-message mbox, .cnm) is built in. Parsers for other
 * single-message formats (Outlook .msg/.oft, TNEF winmail.dat, …) are added to [parsers]; each
 * returns null when the bytes are not its format, and the first non-null result wins.
 */
object MessageImport {
    /** Extra single-message parsers: (bytes, file name) → message, or null when not this format. */
    val parsers: MutableList<(ByteArray, String?) -> ParsedMessage?> = java.util.concurrent.CopyOnWriteArrayList()

    fun parse(bytes: ByteArray, fileName: String? = null): ParsedMessage {
        for (p in parsers) runCatching { p(bytes, fileName) }.getOrNull()?.let { return it }
        return parseMime(bytes, fileName)
    }

    /** RFC 5322 / MIME (with the Apple .emlx wrapper and a one-message mbox handled too). */
    fun parseMime(bytes: ByteArray, fileName: String? = null): ParsedMessage {
        var raw = bytes
        var format = "eml"
        var label = "Internet message (RFC 5322)"
        var flags = MsgFlags(seen = true)
        var internal: Long? = null
        val head = bytes.copyOf(minOf(bytes.size, 4096))
        when {
            Emlx.looksLike(head) -> {
                val p = Emlx.parse(bytes)
                raw = p.message; format = "emlx"; label = "Apple Mail message (.emlx)"; flags = p.flags; internal = p.received
            }
            Mbox.looksLike(head) -> {
                val text = String(head, Charsets.ISO_8859_1)
                internal = Mbox.fromLineDate(text.substringBefore('\n'))
                val nl = bytes.indexOf('\n'.code.toByte())
                raw = Mbox.unquote(bytes.copyOfRange(nl + 1, bytes.size))
                format = "mbox"; label = "Mailbox with one message"
            }
        }
        val msg = ImportedMimeMessage(localSession, ByteArrayInputStream(raw))
        val subject = runCatching { msg.subject }.getOrNull().orEmpty()
        val from = runCatching { msg.from?.firstOrNull()?.toString() }.getOrNull()?.let { runCatching { MimeUtility.decodeText(it) }.getOrDefault(it) }.orEmpty()
        val date = runCatching { msg.sentDate?.time }.getOrNull()
        val warnings = if (msg.getHeader("From") == null && msg.getHeader("Subject") == null && msg.getHeader("Date") == null)
            listOf("${fileName ?: "The file"} has no message headers; it is shown as a plain message.") else emptyList()
        return ParsedMessage(format, label, raw, subject, from, date, flags, internal, warnings)
    }
}
