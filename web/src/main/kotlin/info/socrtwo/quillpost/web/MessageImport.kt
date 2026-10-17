package info.socrtwo.quillpost.web

import info.socrtwo.quillpost.web.fileformats.MessageFormats
import info.socrtwo.quillpost.web.mailstore.Emlx
import info.socrtwo.quillpost.web.mailstore.ImportedMimeMessage
import info.socrtwo.quillpost.web.mailstore.Mbox
import info.socrtwo.quillpost.web.mailstore.MsgFlags
import info.socrtwo.quillpost.web.mailstore.localSession
import java.io.ByteArrayInputStream

/**
 * A single message file in the form the mail stores and the import pipeline use: RFC 5322 bytes
 * plus whatever status the file recorded (Apple Mail .emlx keeps read/replied/flagged and the
 * arrival time).
 */
data class ImportedMessage(
    val format: String,
    val formatLabel: String,
    val mime: ByteArray,
    val subject: String = "",
    val flags: MsgFlags = MsgFlags(seen = true),
    val internalDate: Long? = null
)

/**
 * Single message files found inside mail stores (folders of .eml/.msg, Pegasus .cnm, MH, Apple
 * Mail .emlx) → RFC 5322. Every format other than plain MIME — Outlook .msg/.oft, TNEF
 * winmail.dat, S/MIME and the rest — is converted by the shared message readers
 * (fileformats/MessageFormats.kt); Apple's .emlx wrapper and a one-message mbox are unwrapped here
 * because their status lives outside the message.
 */
object MessageImport {
    fun parse(bytes: ByteArray, fileName: String? = null): ImportedMessage {
        val name = fileName.orEmpty()
        val head = bytes.copyOf(minOf(bytes.size, 4096))
        if (Emlx.looksLike(head)) {
            val p = Emlx.parse(bytes)
            return ImportedMessage("emlx", "Apple Mail message (.emlx)", p.message, subjectOf(p.message), p.flags, p.received)
        }
        if (Mbox.looksLike(head)) {
            val nl = bytes.indexOf('\n'.code.toByte())
            val raw = Mbox.unquote(bytes.copyOfRange(nl + 1, bytes.size))
            return ImportedMessage("mbox", "Mailbox with one message", raw, subjectOf(raw), internalDate = Mbox.fromLineDate(String(head, Charsets.ISO_8859_1).substringBefore('\n')))
        }
        val format = MessageFormats.detect(bytes, name)?.first ?: "eml"
        val mime = if (format == "eml") bytes else MessageFormats.toRfc822(bytes, name)
        return ImportedMessage(format, MessageFormats.label(format), mime, subjectOf(mime))
    }

    private fun subjectOf(raw: ByteArray): String =
        runCatching { ImportedMimeMessage(localSession, ByteArrayInputStream(raw)).subject }.getOrNull().orEmpty()
}
