package info.socrtwo.quillpost.web.fileformats

import info.socrtwo.quillpost.web.ContactDto
import info.socrtwo.quillpost.web.EventDto

/** A mailbox ("Name <address>"); either part may be empty. */
data class MailAddress(val name: String = "", val address: String = "") {
    /** "Name <address>", or whichever part exists. */
    fun display(): String = when {
        name.isNotBlank() && address.isNotBlank() && !name.equals(address, true) -> "$name <$address>"
        address.isNotBlank() -> address
        else -> name
    }
}

/** One attachment or inline resource of a message. */
class ParsedAttachment(
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray,
    val contentId: String? = null,
    /** Shown in the body (an image referenced by cid:) rather than in the attachment list. */
    val inline: Boolean = false,
    val contentLocation: String? = null,
    /** A message attached whole (message/rfc822, an embedded Outlook item, an attached TNEF message). */
    val embedded: ParsedMessage? = null
) {
    val size: Int get() = bytes.size
}

/**
 * A single message read from any of the formats in [MessageFormats]: what a reader window needs
 * to show it as the original program did, plus the contacts or events it carries.
 */
data class ParsedMessage(
    /** Format id from [MessageFormats.detect] (eml, msg, oft, tnef, emlx, mhtml, nws, …). */
    val format: String,
    val subject: String = "",
    val from: MailAddress? = null,
    /** The mailbox that actually sent it when different from [from] ("on behalf of"). */
    val sender: MailAddress? = null,
    val to: List<MailAddress> = emptyList(),
    val cc: List<MailAddress> = emptyList(),
    val bcc: List<MailAddress> = emptyList(),
    val replyTo: List<MailAddress> = emptyList(),
    /** Epoch milliseconds, or null when the item carries no date. */
    val date: Long? = null,
    val messageId: String = "",
    val inReplyTo: String = "",
    val references: String = "",
    /** All header fields in order, decoded (for "View headers"). */
    val headers: List<Pair<String, String>> = emptyList(),
    val bodyText: String = "",
    /** The HTML body, unsanitised — the UI sanitises it like any message. */
    val bodyHtml: String? = null,
    val attachments: List<ParsedAttachment> = emptyList(),
    /** "high", "normal" or "low". */
    val importance: String = "normal",
    /** MAPI message class for Outlook items (IPM.Note, IPM.Contact, IPM.Appointment, …). */
    val messageClass: String = "IPM.Note",
    /** Things the reader should tell the person (signatures not verified, content that could not be shown, …). */
    val notes: List<String> = emptyList(),
    /** smime-signed, smime-encrypted, pgp-signed, pgp-encrypted, or null. */
    val security: String? = null,
    /** An iCalendar object carried by the message (invitation), as text. */
    val calendar: String? = null,
    /** Contacts carried by the item (an Outlook contact). */
    val contacts: List<ContactDto> = emptyList(),
    /** Events carried by the item (an Outlook appointment or meeting). */
    val events: List<EventDto> = emptyList(),
    /** An Outlook template (.oft) or an unsent item: opened as a new draft. */
    val template: Boolean = false,
    /** Newsgroups the article was posted to (.nws). */
    val newsgroups: String = "",
    /** The message as RFC 822 bytes when the source already was MIME (saved to a folder unchanged). */
    val rfc822: ByteArray? = null
)
