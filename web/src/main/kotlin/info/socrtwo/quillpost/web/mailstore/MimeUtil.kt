package info.socrtwo.quillpost.web.mailstore

import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Date
import java.util.Properties

/** Shared Jakarta Mail session for parsing and building imported messages (no network use). */
internal val localSession: Session = Session.getInstance(Properties().apply {
    put("mail.mime.address.strict", "false")
    put("mail.mime.decodetext.strict", "false")
    put("mail.mime.decodefilename", "true")
    put("mail.mime.parameters.strict", "false")
})

/**
 * A MimeMessage that keeps the original Message-ID when it is re-saved (Jakarta Mail would
 * invent a new one) and that reports [internalDate] as its received date, which IMAP APPEND
 * sends as the INTERNALDATE.
 */
class ImportedMimeMessage : MimeMessage {
    var internalDate: Date? = null

    constructor(session: Session = localSession) : super(session)
    constructor(session: Session = localSession, input: InputStream) : super(session, input)

    override fun updateMessageID() {
        if (getHeader("Message-ID") == null) super.updateMessageID()
    }

    override fun getReceivedDate(): Date? = internalDate ?: super.getReceivedDate()

    fun bytes(): ByteArray = ByteArrayOutputStream().also { writeTo(it) }.toByteArray()
}

/** Guesses a MIME type from a file name (used for attachments that carry no type). */
internal fun mimeTypeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "txt", "log", "ini", "cfg" -> "text/plain"
    "htm", "html" -> "text/html"
    "csv" -> "text/csv"
    "rtf" -> "application/rtf"
    "pdf" -> "application/pdf"
    "png" -> "image/png"
    "jpg", "jpeg", "jpe" -> "image/jpeg"
    "gif" -> "image/gif"
    "bmp" -> "image/bmp"
    "tif", "tiff" -> "image/tiff"
    "svg" -> "image/svg+xml"
    "webp" -> "image/webp"
    "ics", "vcs" -> "text/calendar"
    "vcf" -> "text/vcard"
    "eml" -> "message/rfc822"
    "msg" -> "application/vnd.ms-outlook"
    "zip" -> "application/zip"
    "doc", "dot" -> "application/msword"
    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    "xls", "xlt" -> "application/vnd.ms-excel"
    "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    "ppt", "pps" -> "application/vnd.ms-powerpoint"
    "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    "odt" -> "application/vnd.oasis.opendocument.text"
    "ods" -> "application/vnd.oasis.opendocument.spreadsheet"
    "odp" -> "application/vnd.oasis.opendocument.presentation"
    "mp3" -> "audio/mpeg"
    "wav" -> "audio/wav"
    "mp4" -> "video/mp4"
    else -> "application/octet-stream"
}
