package info.socrtwo.quillpost.web.fileformats

import info.socrtwo.quillpost.web.AttendeeDto
import info.socrtwo.quillpost.web.ContactDto
import info.socrtwo.quillpost.web.EventDto
import info.socrtwo.quillpost.web.LabeledValue
import info.socrtwo.quillpost.web.PostalAddress
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** A MAPI property bag as stored in .msg files ([MS-OXMSG]) and TNEF streams ([MS-OXTNEF]). */
class MapiProps {
    /** 8-bit string bytes, decoded with the item's code page when read. */
    class Ansi(val bytes: ByteArray)

    /** FILETIME (100 ns ticks since 1601-01-01 UTC). */
    class FileTime(val ticks: Long) { fun epochMillis(): Long = ticks / 10_000 - 11_644_473_600_000L }

    val values = HashMap<Int, Any>()
    /** Property id (0x8000+) → named property key "GUID:dispid" or "GUID:name". */
    var names: Map<Int, String> = emptyMap()
    var codepage: Int = 0

    fun put(id: Int, value: Any) {
        val prev = values[id]
        // Keep a Unicode string over an 8-bit one when an item carries both.
        if (prev is String && value is Ansi) return
        values[id] = value
    }

    fun has(id: Int) = values.containsKey(id)

    fun string(id: Int): String? = when (val v = values[id]) {
        is String -> v.trimEnd('\u0000')
        is Ansi -> String(v.bytes, TextCodec.codepage(if (codepage > 0) codepage else 1252)).trimEnd('\u0000')
        else -> null
    }?.takeIf { it.isNotEmpty() }

    fun bytes(id: Int): ByteArray? = when (val v = values[id]) {
        is ByteArray -> v
        is Ansi -> v.bytes
        is String -> v.toByteArray(Charsets.UTF_8)
        else -> null
    }

    fun int(id: Int): Int? = when (val v = values[id]) { is Int -> v; is Long -> v.toInt(); is Short -> v.toInt(); is Boolean -> if (v) 1 else 0; else -> null }
    fun bool(id: Int): Boolean? = when (val v = values[id]) { is Boolean -> v; is Int -> v != 0; is Short -> v.toInt() != 0; else -> null }
    fun time(id: Int): Long? = (values[id] as? FileTime)?.epochMillis()?.takeIf { it > -62_000_000_000_000L && it < 32_000_000_000_000L }

    /** The id assigned to a named property in this item, or null. */
    fun namedId(guid: String, dispid: Int): Int? = names.entries.firstOrNull { it.value.equals("$guid:$dispid", true) }?.key
    fun namedIdByName(guid: String, name: String): Int? = names.entries.firstOrNull { it.value.equals("$guid:$name", true) }?.key
    fun namedString(guid: String, dispid: Int): String? = namedId(guid, dispid)?.let { string(it) }
    fun namedTime(guid: String, dispid: Int): Long? = namedId(guid, dispid)?.let { time(it) }
    fun namedBool(guid: String, dispid: Int): Boolean? = namedId(guid, dispid)?.let { bool(it) }
    fun namedInt(guid: String, dispid: Int): Int? = namedId(guid, dispid)?.let { int(it) }

    companion object {
        fun guidString(b: ByteArray, off: Int = 0): String {
            fun h(i: Int) = "%02X".format(b[off + i].toInt() and 0xff)
            return h(3) + h(2) + h(1) + h(0) + "-" + h(5) + h(4) + "-" + h(7) + h(6) + "-" + h(8) + h(9) + "-" + (10..15).joinToString("") { h(it) }
        }
    }
}

/** An attachment as read from a MAPI attachment object. */
class MapiAttachment(val props: MapiProps, val embedded: ParsedMessage? = null)

/** Property ids and named-property sets used to turn MAPI items into messages, contacts and events. */
object Mapi {
    const val PS_MAPI = "00020328-0000-0000-C000-000000000046"
    const val PS_PUBLIC_STRINGS = "00020329-0000-0000-C000-000000000046"
    const val PSETID_APPOINTMENT = "00062002-0000-0000-C000-000000000046"
    const val PSETID_TASK = "00062003-0000-0000-C000-000000000046"
    const val PSETID_ADDRESS = "00062004-0000-0000-C000-000000000046"
    const val PSETID_COMMON = "00062008-0000-0000-C000-000000000046"
    const val PSETID_MEETING = "6ED8DA90-450B-101B-98DA-00AA003F1305"

    const val MESSAGE_CLASS = 0x001A
    const val SUBJECT = 0x0037
    const val IMPORTANCE = 0x0017
    const val CLIENT_SUBMIT_TIME = 0x0039
    const val SENT_REPRESENTING_NAME = 0x0042
    const val SENT_REPRESENTING_ADDRTYPE = 0x0064
    const val SENT_REPRESENTING_EMAIL = 0x0065
    const val TRANSPORT_HEADERS = 0x007D
    const val SENDER_NAME = 0x0C1A
    const val SENDER_ADDRTYPE = 0x0C1E
    const val SENDER_EMAIL = 0x0C1F
    const val RECIPIENT_TYPE = 0x0C15
    const val DISPLAY_BCC = 0x0E02
    const val DISPLAY_CC = 0x0E03
    const val DISPLAY_TO = 0x0E04
    const val DELIVERY_TIME = 0x0E06
    const val MESSAGE_FLAGS = 0x0E07
    const val BODY = 0x1000
    const val RTF_COMPRESSED = 0x1009
    const val BODY_HTML = 0x1013
    const val INTERNET_MESSAGE_ID = 0x1035
    const val INTERNET_REFERENCES = 0x1039
    const val IN_REPLY_TO = 0x1042
    const val DISPLAY_NAME = 0x3001
    const val ADDRTYPE = 0x3002
    const val EMAIL_ADDRESS = 0x3003
    const val CREATION_TIME = 0x3007
    const val ATTACH_DATA = 0x3701
    const val ATTACH_FILENAME = 0x3704
    const val ATTACH_METHOD = 0x3705
    const val ATTACH_LONG_FILENAME = 0x3707
    const val ATTACH_MIME_TAG = 0x370E
    const val ATTACH_CONTENT_ID = 0x3712
    const val ATTACH_CONTENT_LOCATION = 0x3713
    const val ATTACH_FLAGS = 0x3714
    const val SMTP_ADDRESS = 0x39FE
    const val INTERNET_CPID = 0x3FDE
    const val MESSAGE_CODEPAGE = 0x3FFD
    const val SENDER_SMTP = 0x5D01
    const val SENT_REPRESENTING_SMTP = 0x5D02
    const val RECIPIENT_DISPLAY_NAME = 0x5FF6
    const val ATTACHMENT_HIDDEN = 0x7FFE

    private val LOCAL_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

    /** An SMTP address for an address entry, given the property ids that may hold it. */
    private fun smtp(p: MapiProps, smtpId: Int, emailId: Int, typeId: Int): String {
        p.string(smtpId)?.let { if (it.contains('@')) return it }
        val e = p.string(emailId) ?: return ""
        val t = p.string(typeId)?.uppercase()
        if (e.contains('@') && (t == null || t == "SMTP" || !e.startsWith("/"))) return e.removePrefix("SMTP:").removePrefix("smtp:")
        return ""
    }

    private fun splitDisplay(s: String?): List<MailAddress> =
        s?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }?.map { if (it.contains('@') && !it.contains(' ')) MailAddress("", it) else MailAddress(it, "") } ?: emptyList()

    /**
     * Builds a [ParsedMessage] from an Outlook item's properties. [rfcHeaders] are the item's
     * transport headers when present (used for fields MAPI does not keep).
     */
    fun toParsed(format: String, p: MapiProps, recipients: List<MapiProps>, attachments: List<MapiAttachment>, notes: List<String> = emptyList()): ParsedMessage {
        val cls = p.string(MESSAGE_CLASS) ?: "IPM.Note"
        val headersText = p.string(TRANSPORT_HEADERS)
        val headers = headersText?.let { MimeReader.parseHeaderBlock(it) } ?: emptyList()
        fun h(n: String) = headers.firstOrNull { it.first.equals(n, true) }?.second

        val from = MailAddress(p.string(SENT_REPRESENTING_NAME) ?: p.string(SENDER_NAME) ?: "",
            smtp(p, SENT_REPRESENTING_SMTP, SENT_REPRESENTING_EMAIL, SENT_REPRESENTING_ADDRTYPE).ifEmpty { smtp(p, SENDER_SMTP, SENDER_EMAIL, SENDER_ADDRTYPE) })
            .let { if (it.address.isEmpty()) h("From")?.let { f -> MimeReader.addresses(f).firstOrNull() } ?: it else it }
        val senderAddr = smtp(p, SENDER_SMTP, SENDER_EMAIL, SENDER_ADDRTYPE)
        val sender = if (senderAddr.isNotEmpty() && !senderAddr.equals(from.address, true)) MailAddress(p.string(SENDER_NAME) ?: "", senderAddr) else null

        val to = ArrayList<MailAddress>(); val cc = ArrayList<MailAddress>(); val bcc = ArrayList<MailAddress>()
        for (r in recipients) {
            val a = MailAddress(r.string(DISPLAY_NAME) ?: r.string(RECIPIENT_DISPLAY_NAME) ?: "", smtp(r, SMTP_ADDRESS, EMAIL_ADDRESS, ADDRTYPE))
            if (a.name.isEmpty() && a.address.isEmpty()) continue
            when (r.int(RECIPIENT_TYPE)?.and(0xf)) { 2 -> cc += a; 3 -> bcc += a; else -> to += a }
        }
        if (recipients.isEmpty()) {
            to += h("To")?.let { MimeReader.addresses(it) } ?: splitDisplay(p.string(DISPLAY_TO))
            cc += h("Cc")?.let { MimeReader.addresses(it) } ?: splitDisplay(p.string(DISPLAY_CC))
            bcc += splitDisplay(p.string(DISPLAY_BCC))
        }

        // Bodies: HTML property, else HTML encapsulated in RTF, else RTF rendered, else plain text.
        val outNotes = ArrayList(notes)
        var html: String? = p.bytes(BODY_HTML)?.let { decodeHtml(it, p) }?.takeIf { it.isNotBlank() }
        var text = p.string(BODY) ?: ""
        val rtfBytes = p.bytes(RTF_COMPRESSED)?.let { runCatching { Rtf.decompress(it) }.onFailure { outNotes += "The rich-text body is damaged and could not be read." }.getOrNull() }
        if (rtfBytes != null && html == null) {
            html = runCatching {
                Rtf.deEncapsulateHtml(rtfBytes) ?: if (Rtf.isEncapsulatedText(rtfBytes) && text.isNotBlank()) null else Rtf.toHtml(rtfBytes)
            }.getOrNull()
            if (text.isBlank()) text = runCatching { Rtf.toText(rtfBytes) }.getOrDefault("")
        }

        if (text.isBlank() && html != null) text = Rtf.htmlToText(html)
        val atts = attachments.mapNotNull { attachment(it, html) }
        val date = p.time(CLIENT_SUBMIT_TIME) ?: p.time(DELIVERY_TIME) ?: h("Date")?.let { MimeReader.parseDate(it) } ?: p.time(CREATION_TIME)
        val importance = when (p.int(IMPORTANCE)) { 0 -> "low"; 2 -> "high"; else -> "normal" }
        val flags = p.int(MESSAGE_FLAGS) ?: 0

        var contacts = emptyList<ContactDto>()
        var events = emptyList<EventDto>()
        when {
            cls.startsWith("IPM.Contact", true) -> contacts = listOfNotNull(contactOf(p, text))
            cls.startsWith("IPM.Appointment", true) || cls.startsWith("IPM.Schedule.Meeting", true) -> {
                events = listOfNotNull(eventOf(p, text, from, to + cc, outNotes))
            }
            cls.startsWith("IPM.Task", true) -> {
                val due = p.namedTime(PSETID_TASK, 0x8105)
                val pct = (p.namedId(PSETID_TASK, 0x8102)?.let { p.values[it] as? Double })
                outNotes += buildString {
                    append("Outlook task")
                    if (due != null) append(", due ").append(Instant.ofEpochMilli(due).atZone(ZoneOffset.UTC).toLocalDate())
                    if (pct != null) append(", ").append((pct * 100).toInt()).append("% complete")
                    append(".")
                }
            }
            cls.startsWith("IPM.StickyNote", true) -> outNotes += "Outlook note."
        }
        if (cls.contains("SMIME", true)) outNotes += "This item was signed or encrypted with S/MIME. QuillPost shows what it can read but does not verify signatures or decrypt messages."

        return ParsedMessage(
            format = format,
            subject = p.string(SUBJECT) ?: h("Subject")?.let { TextCodec.decodeHeader(it) } ?: "",
            from = from.takeIf { it.name.isNotEmpty() || it.address.isNotEmpty() },
            sender = sender,
            to = to, cc = cc, bcc = bcc,
            replyTo = h("Reply-To")?.let { MimeReader.addresses(it) } ?: emptyList(),
            date = date,
            messageId = p.string(INTERNET_MESSAGE_ID) ?: h("Message-ID") ?: "",
            inReplyTo = p.string(IN_REPLY_TO) ?: h("In-Reply-To") ?: "",
            references = p.string(INTERNET_REFERENCES) ?: h("References") ?: "",
            headers = headers,
            bodyText = text.replace("\r\n", "\n"),
            bodyHtml = html,
            attachments = atts,
            importance = importance,
            messageClass = cls,
            notes = outNotes,
            contacts = contacts,
            events = events,
            template = format == "oft" || (flags and 0x08) != 0 && from.address.isEmpty()
        )
    }

    private fun decodeHtml(b: ByteArray, p: MapiProps): String {
        val head = String(b, 0, minOf(b.size, 2048), Charsets.ISO_8859_1)
        val meta = Regex("(?i)<meta[^>]+charset=[\"']?([A-Za-z0-9_.:-]+)").find(head)?.groupValues?.get(1)
        if (meta != null) return TextCodec.decode(b, meta)
        val cp = p.int(INTERNET_CPID) ?: p.codepage.takeIf { it > 0 }
        return if (cp != null) String(b, TextCodec.codepage(cp)) else TextCodec.auto(b)
    }

    private fun attachment(a: MapiAttachment, html: String?): ParsedAttachment? {
        val p = a.props
        val method = p.int(ATTACH_METHOD) ?: 1
        val name = (p.string(ATTACH_LONG_FILENAME) ?: p.string(ATTACH_FILENAME) ?: p.string(DISPLAY_NAME) ?: "").trim()
        val cid = p.string(ATTACH_CONTENT_ID)?.trim('<', '>', ' ')
        val loc = p.string(ATTACH_CONTENT_LOCATION)
        if (a.embedded != null || method == 5) {
            val e = a.embedded ?: return null
            val n = MimeWriter.safeFileName(name.ifBlank { e.subject }.ifBlank { "Attached message" }).removeSuffix(".msg") + ".eml"
            return ParsedAttachment(n, "message/rfc822", MimeWriter.toRfc822(e), embedded = e)
        }
        val data = p.bytes(ATTACH_DATA) ?: if (method == 6) ByteArray(0) else return null
        val mime = p.string(ATTACH_MIME_TAG)?.lowercase()?.substringBefore(';')?.trim() ?: MimeTypes.byName(name)
        val referenced = html != null && ((cid != null && html.contains("cid:$cid", true)) || (loc != null && html.contains(loc)))
        val hidden = p.bool(ATTACHMENT_HIDDEN) == true
        return ParsedAttachment(name.ifBlank { if (method == 6) "Embedded object" else "attachment" + MimeTypes.extensionFor(mime) }, mime, data,
            contentId = cid, inline = referenced || (hidden && cid != null), contentLocation = loc)
    }

    private fun contactOf(p: MapiProps, notes: String): ContactDto? {
        val emails = listOf(0x8083, 0x8093, 0x80A3).mapNotNull { id ->
            val v = p.namedString(PSETID_ADDRESS, id) ?: return@mapNotNull null
            val addr = if (v.contains('@')) v else p.namedString(PSETID_ADDRESS, id + 1)?.takeIf { it.contains('@') } ?: return@mapNotNull null
            LabeledValue(if (id == 0x8083) "work" else "other", addr)
        }
        val phones = listOf(0x3A08 to "work", 0x3A09 to "home", 0x3A1C to "mobile", 0x3A24 to "fax", 0x3A1A to "other", 0x3A1B to "work", 0x3A2F to "home")
            .mapNotNull { (id, label) -> p.string(id)?.let { LabeledValue(label, it) } }
        val work = PostalAddress("work", p.namedString(PSETID_ADDRESS, 0x8045) ?: p.string(0x3A29) ?: "", p.namedString(PSETID_ADDRESS, 0x8046) ?: p.string(0x3A27) ?: "",
            p.namedString(PSETID_ADDRESS, 0x8047) ?: p.string(0x3A28) ?: "", p.namedString(PSETID_ADDRESS, 0x8048) ?: p.string(0x3A2A) ?: "", p.namedString(PSETID_ADDRESS, 0x8049) ?: p.string(0x3A26) ?: "")
        val home = PostalAddress("home", p.string(0x3A5D) ?: "", p.string(0x3A59) ?: "", p.string(0x3A5C) ?: "", p.string(0x3A5B) ?: "", p.string(0x3A5A) ?: "")
        fun date(id: Int) = p.time(id)?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() } ?: ""
        val c = ContactDto(
            prefix = p.string(0x3A45) ?: "", firstName = p.string(0x3A06) ?: "", middleName = p.string(0x3A44) ?: "",
            lastName = p.string(0x3A11) ?: "", suffix = p.string(0x3A05) ?: "", nickname = p.string(0x3A4F) ?: "",
            displayName = p.string(DISPLAY_NAME) ?: p.string(SUBJECT) ?: "",
            emails = emails, phones = phones, addresses = listOf(work, home).filter { it.street.isNotBlank() || it.city.isNotBlank() || it.country.isNotBlank() },
            company = p.string(0x3A16) ?: "", department = p.string(0x3A18) ?: "", jobTitle = p.string(0x3A17) ?: "",
            birthday = date(0x3A42), anniversary = date(0x3A41),
            website = p.namedString(PSETID_ADDRESS, 0x802B) ?: p.string(0x3A51) ?: p.string(0x3A50) ?: "",
            notes = notes.trim()
        )
        return if (c.bestName().isBlank() && c.emails.isEmpty() && c.phones.isEmpty()) null else c
    }

    private fun eventOf(p: MapiProps, notes: String, organizer: MailAddress, attendees: List<MailAddress>, outNotes: MutableList<String>): EventDto? {
        val start = p.namedTime(PSETID_APPOINTMENT, 0x820D) ?: p.namedTime(PSETID_COMMON, 0x8516) ?: return null
        val end = p.namedTime(PSETID_APPOINTMENT, 0x820E) ?: p.namedTime(PSETID_COMMON, 0x8517) ?: start + 3_600_000
        val allDay = p.namedBool(PSETID_APPOINTMENT, 0x8215) == true
        val zone = ZoneId.systemDefault()
        fun local(ms: Long) = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), zone)
        if (p.namedBool(PSETID_APPOINTMENT, 0x8223) == true || p.namedId(PSETID_APPOINTMENT, 0x8216) != null && p.bytes(p.namedId(PSETID_APPOINTMENT, 0x8216)!!)?.isNotEmpty() == true)
            outNotes += "This is a recurring appointment; only its first occurrence is shown."
        val reminderSet = p.namedBool(PSETID_COMMON, 0x8503)
        return EventDto(
            uid = p.namedId(PSETID_MEETING, 0x23)?.let { p.bytes(it) }?.joinToString("") { "%02X".format(it) } ?: "",
            title = p.string(SUBJECT) ?: "",
            location = p.namedString(PSETID_APPOINTMENT, 0x8208) ?: "",
            notes = notes.trim(),
            start = if (allDay) local(start).toLocalDate().toString() else local(start).format(LOCAL_FMT),
            end = if (allDay) local(end).toLocalDate().toString() else local(end).format(LOCAL_FMT),
            allDay = allDay,
            tz = if (allDay) "" else zone.id,
            reminderMinutes = if (reminderSet == false) null else p.namedInt(PSETID_COMMON, 0x8501) ?: 15,
            showAs = when (p.namedInt(PSETID_APPOINTMENT, 0x8205)) { 0 -> "free"; 1 -> "tentative"; 3 -> "oof"; 4 -> "workingElsewhere"; else -> "busy" },
            organizer = organizer.takeIf { it.address.isNotEmpty() }?.let { AttendeeDto(it.address, it.name, "CHAIR", "ACCEPTED") },
            attendees = attendees.filter { it.address.isNotEmpty() }.map { AttendeeDto(it.address, it.name) }
        )
    }
}
