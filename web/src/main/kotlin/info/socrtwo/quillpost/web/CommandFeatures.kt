package info.socrtwo.quillpost.web

import info.socrtwo.quillpost.web.spam.HeaderParser
import info.socrtwo.quillpost.web.spam.PublicSuffixList
import jakarta.mail.FetchProfile
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.InternetHeaders
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.internet.MimeUtility
import jakarta.mail.search.AndTerm
import jakarta.mail.search.ComparisonTerm
import jakarta.mail.search.FlagTerm
import jakarta.mail.search.ReceivedDateTerm
import jakarta.mail.search.SearchTerm
import jakarta.mail.search.SizeTerm
import kotlinx.serialization.Serializable
import org.eclipse.angus.mail.smtp.SMTPMessage
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

/*
 * Mail operations behind the command set: redirect/bounce with Resent-* headers, IMAP keywords
 * (tags) and system flags, compact/expunge, mark read by date, folder statistics, large-message
 * search, attachment stripping, subject editing, mailing-list grouping, RFC 8058 one-click
 * unsubscribe and the header inspector. The pure parts live in small objects so they can be
 * unit-tested without a mail server.
 */

// --- DTOs ---------------------------------------------------------------------------------------

@Serializable
data class ResendRequest(val folder: String, val uid: Long, val to: List<String>, /** "bounce" (byte-identical) or "redirect" (Eudora: "by way of" you) */ val mode: String = "bounce")

@Serializable
data class FlagsEditRequest(val items: List<MessageRef>, val add: List<String> = emptyList(), val remove: List<String> = emptyList())

@Serializable
data class FolderRangeRequest(val path: String, val since: Long? = null, val before: Long? = null)

@Serializable
data class ArchiveRequest(val items: List<MessageRef> = emptyList(), /** none, year or month */ val granularity: String = "none", val path: String? = null, val olderThanDays: Int? = null, val dest: String? = null)

@Serializable
data class RewriteRequest(val folder: String, val uid: Long, val subject: String? = null, val stripAttachments: Boolean = false)

@Serializable
data class RewriteResponse(val folder: String, val uid: Long?, val removed: List<String> = emptyList())

@Serializable
data class FolderStatsDto(val path: String, val total: Int, val unread: Int, val flagged: Int, val deleted: Int, val sizeBytes: Long, val oldest: Long, val newest: Long)

@Serializable
data class FolderCopyRequest(val path: String, val parent: String? = null)

@Serializable
data class ListGroupDto(val listId: String, val name: String, val count: Int, val unread: Int, val latest: Long, val unsubscribe: String? = null, val uids: List<Long> = emptyList())

@Serializable
data class ShelfRequest(val items: List<MessageRef>)

@Serializable
data class ShelfItemDto(val folder: String, val uid: Long, val subject: String, val fromAddress: String, val date: Long, val index: Int, val fileName: String, val mimeType: String, val size: Long)

@Serializable
data class UnsubscribeResultDto(val method: String, val status: String, val url: String? = null, val detail: String = "")

@Serializable
data class OriginalMessageDto(val to: List<String>, val cc: List<String>, val subject: String, val text: String, val html: String? = null, val found: Boolean = true)

@Serializable
data class AuthCheckDto(val method: String, val result: String, val detail: String = "", val source: String = "")

@Serializable
data class HopDto(val from: String, val by: String, val ip: String?, val at: Long?, val delaySeconds: Long?)

@Serializable
data class HeaderReportDto(
    val fromDomain: String,
    val returnPathDomain: String,
    val replyToDomain: String,
    val checks: List<AuthCheckDto>,
    val dkimDomains: List<String>,
    val spfAligned: Boolean?,
    val dkimAligned: Boolean?,
    val dmarc: String,
    val hops: List<HopDto>,
    val totalDelaySeconds: Long?,
    val warnings: List<String>,
    val headers: List<List<String>>
)

@Serializable
data class AwaitingDto(val folder: String, val uid: Long, val messageId: String, val subject: String, val to: List<String>, val date: Long)

@Serializable
data class ConversationRouteDto(val ids: List<String>, val dest: String, val kind: String, val subject: String = "", val created: Long = 0)

@Serializable
data class RouteRequest(val items: List<MessageRef>, /** mute (archive) or move */ val kind: String = "mute", val dest: String? = null, val enable: Boolean = true)

// --- pure helpers -----------------------------------------------------------------------------------

/** Resent-* header block for a redirected or bounced message (RFC 5322 §3.6.6). */
object Resent {
    private val rfc822 = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US)

    fun headers(fromAddress: String, fromName: String, to: List<String>, date: Date, messageId: String): List<Pair<String, String>> {
        val from = if (fromName.isBlank() || fromName == fromAddress) fromAddress else "${encodeName(fromName)} <$fromAddress>"
        return listOf(
            "Resent-From" to from,
            "Resent-To" to to.joinToString(", "),
            "Resent-Date" to synchronized(rfc822) { rfc822.format(date) },
            "Resent-Message-ID" to messageId
        )
    }

    /** Eudora's Redirect: the original author stays the author, "by way of" the person who redirected it. */
    fun byWayOf(fromHeader: String, me: String): String {
        val (name, address) = HeaderParser.splitAddress(fromHeader)
        val shown = name.ifBlank { address }
        return "${encodeName("$shown (by way of $me)")} <$address>"
    }

    fun newMessageId(domain: String): String = "<resent.${System.currentTimeMillis()}.${(Math.random() * 1e9).toLong()}@${domain.ifBlank { "quillpost.local" }}>"

    /** A display name as an RFC 5322 quoted string, or an RFC 2047 encoded word when it is not ASCII. */
    fun encodeName(n: String): String =
        if (n.all { it.code in 32..126 }) "\"" + n.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        else MimeUtility.encodeWord(n, "UTF-8", "Q")
}

/** IMAP keyword handling. Tags with spaces become atoms ("To Do" → "To_Do"); system flags pass through. */
object Keywords {
    val SYSTEM = mapOf("\\seen" to Flags.Flag.SEEN, "\\answered" to Flags.Flag.ANSWERED, "\\flagged" to Flags.Flag.FLAGGED, "\\deleted" to Flags.Flag.DELETED, "\\draft" to Flags.Flag.DRAFT)

    fun atom(name: String): String? {
        val t = name.trim()
        if (t.isEmpty()) return null
        if (t.startsWith("\\")) return t.takeIf { it.lowercase() in SYSTEM }
        val a = t.replace(Regex("\\s+"), "_").replace(Regex("[^A-Za-z0-9\$_.\\-]"), "")
        return a.take(64).takeIf { it.isNotEmpty() }
    }

    fun toFlags(names: List<String>): Flags {
        val f = Flags()
        for (n in names) {
            val a = atom(n) ?: continue
            val sys = SYSTEM[a.lowercase()]
            if (sys != null) f.add(sys) else f.add(a)
        }
        return f
    }
}

/** List-Unsubscribe / List-Unsubscribe-Post (RFC 2369, RFC 8058). */
object ListUnsubscribe {
    data class Parsed(val https: List<String>, val http: List<String>, val mailto: List<String>, val oneClick: Boolean)

    fun parse(header: String?, post: String?): Parsed {
        val urls = Regex("<([^>]+)>").findAll(header.orEmpty()).map { it.groupValues[1].trim() }.toList()
        val oneClick = post?.replace(Regex("\\s+"), "")?.equals("List-Unsubscribe=One-Click", ignoreCase = true) == true
        return Parsed(
            https = urls.filter { it.startsWith("https://", true) },
            http = urls.filter { it.startsWith("http://", true) },
            mailto = urls.filter { it.startsWith("mailto:", true) },
            oneClick = oneClick
        )
    }

    /** "mailto:list@x?subject=unsubscribe&body=…" → address, subject, body. */
    fun mailto(uri: String): Triple<String, String, String> {
        val s = uri.removePrefix("mailto:").removePrefix("MAILTO:")
        val addr = java.net.URLDecoder.decode(s.substringBefore('?'), "UTF-8")
        val q = s.substringAfter('?', "").split('&').filter { it.contains('=') }.associate {
            it.substringBefore('=').lowercase() to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
        return Triple(addr, q["subject"] ?: "unsubscribe", q["body"] ?: "unsubscribe")
    }
}

/** Refuses URLs that point into private networks, so a message cannot make the server probe the LAN. */
object UrlGuard {
    fun check(url: URL) {
        require(url.protocol == "https" || url.protocol == "http") { "Only http and https addresses are supported" }
        if (qpEnv("QUILLPOST_ALLOW_PRIVATE_URLS")?.equals("true", true) == true) return
        val addrs = runCatching { InetAddress.getAllByName(url.host).toList() }.getOrElse { throw IllegalArgumentException("Cannot resolve ${url.host}") }
        for (a in addrs) {
            val b = a.address
            val uniqueLocalV6 = b.size == 16 && (b[0].toInt() and 0xfe) == 0xfc
            require(!(a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress || a.isAnyLocalAddress || a.isMulticastAddress || uniqueLocalV6)) {
                "Addresses on private networks are not contacted from this server"
            }
        }
    }
}

/** Header inspector: authentication results, alignment and the Received chain with per-hop delays. */
object HeaderInspector {
    fun parseHeaders(raw: ByteArray): List<Pair<String, String>> {
        val ih = InternetHeaders(ByteArrayInputStream(raw))
        val out = ArrayList<Pair<String, String>>()
        val e = ih.allHeaders
        while (e.hasMoreElements()) {
            val h = e.nextElement()
            out += h.name to (h.value ?: "").replace(Regex("\\r?\\n[ \\t]+"), " ").trim()
        }
        return out
    }

    private fun domainOf(addressHeader: String?): String {
        if (addressHeader.isNullOrBlank()) return ""
        val addr = HeaderParser.splitAddress(addressHeader).second.ifBlank { addressHeader.trim('<', '>', ' ') }
        return addr.substringAfterLast('@', "").lowercase().trim('>', ' ')
    }

    private fun org(d: String) = if (d.isBlank()) "" else runCatching { PublicSuffixList.registrableDomain(d) }.getOrDefault(d)

    fun inspect(headers: List<Pair<String, String>>): HeaderReportDto {
        fun all(n: String) = headers.filter { it.first.equals(n, true) }.map { it.second }
        val fromDomain = domainOf(all("From").firstOrNull())
        val rpDomain = domainOf(all("Return-Path").firstOrNull())
        val replyDomain = domainOf(all("Reply-To").firstOrNull())
        val checks = ArrayList<AuthCheckDto>()
        val methodRe = Regex("(?i)\\b(spf|dkim|dmarc|arc|dkim-atps|auth|iprev|bimi)\\s*=\\s*([a-z]+)([^;]*)")
        for ((name, src) in listOf("Authentication-Results" to "Authentication-Results", "ARC-Authentication-Results" to "ARC")) {
            for (v in all(name)) {
                val server = v.substringBefore(';').trim().substringAfterLast(' ')
                for (m in methodRe.findAll(v)) checks += AuthCheckDto(m.groupValues[1].lowercase(), m.groupValues[2].lowercase(), m.groupValues[3].trim(), "$src ($server)")
            }
        }
        for (v in all("Received-SPF")) checks += AuthCheckDto("spf", v.substringBefore(' ').lowercase(), v.substringAfter(' ', "").take(200), "Received-SPF")
        val dkimDomains = all("DKIM-Signature").mapNotNull { Regex("(?i)\\bd=([^;\\s]+)").find(it)?.groupValues?.get(1)?.lowercase() }.distinct()
        val passedDkim = checks.filter { it.method == "dkim" && it.result == "pass" }.mapNotNull { Regex("(?i)header\\.(?:d|i)=@?([^;\\s]+)").find(it.detail)?.groupValues?.get(1)?.substringAfter('@')?.lowercase() }
        val spfDomain = checks.firstOrNull { it.method == "spf" }?.let { Regex("(?i)smtp\\.mailfrom=([^;\\s]+)").find(it.detail)?.groupValues?.get(1)?.substringAfterLast('@')?.lowercase() } ?: rpDomain
        val fromOrg = org(fromDomain)
        val spfPass = checks.any { it.method == "spf" && it.result == "pass" }
        val spfAligned = if (fromDomain.isBlank() || spfDomain.isBlank()) null else spfPass && org(spfDomain) == fromOrg
        val dkimAligned = when {
            fromDomain.isBlank() -> null
            passedDkim.isNotEmpty() -> passedDkim.any { org(it) == fromOrg }
            checks.any { it.method == "dkim" } -> false
            else -> null
        }
        val dmarcReported = checks.firstOrNull { it.method == "dmarc" }?.result
        val dmarc = dmarcReported ?: when { spfAligned == true || dkimAligned == true -> "pass (computed)"; spfAligned == null && dkimAligned == null -> "none"; else -> "fail (computed)" }
        val hops = parseReceived(all("Received"))
        val warnings = ArrayList<String>()
        if (replyDomain.isNotBlank() && fromDomain.isNotBlank() && org(replyDomain) != fromOrg) warnings += "Replies go to another domain ($replyDomain) than the sender's ($fromDomain)."
        if (rpDomain.isNotBlank() && fromDomain.isNotBlank() && org(rpDomain) != fromOrg) warnings += "The envelope sender ($rpDomain) differs from the From domain; normal for mailing services, suspicious otherwise."
        if (checks.none { it.method == "dkim" } && dkimDomains.isEmpty()) warnings += "The message is not DKIM-signed."
        if (dmarc.startsWith("fail")) warnings += "DMARC failed: the From domain is not proven."
        val delays = hops.mapNotNull { it.delaySeconds }
        val total = if (hops.count { it.at != null } >= 2) (hops.mapNotNull { it.at }.max() - hops.mapNotNull { it.at }.min()) / 1000 else null
        if (delays.any { it > 3600 }) warnings += "One hop took more than an hour."
        return HeaderReportDto(fromDomain, rpDomain, replyDomain, checks, dkimDomains, spfAligned, dkimAligned, dmarc, hops, total, warnings,
            headers.map { listOf(it.first, it.second) })
    }

    /** Received headers are newest first; the hops are returned oldest first with the delay since the previous hop. */
    fun parseReceived(received: List<String>): List<HopDto> {
        val dateFmt = listOf("EEE, d MMM yyyy HH:mm:ss Z", "d MMM yyyy HH:mm:ss Z", "EEE, d MMM yyyy HH:mm:ss z")
        val hops = received.reversed().map { r ->
            val from = Regex("(?i)\\bfrom\\s+(\\S+)").find(r)?.groupValues?.get(1) ?: ""
            val by = Regex("(?i)\\bby\\s+(\\S+)").find(r)?.groupValues?.get(1) ?: ""
            val ip = Regex("\\[((?:\\d{1,3}\\.){3}\\d{1,3}|[0-9a-fA-F:]+:[0-9a-fA-F:]*)]").find(r)?.groupValues?.get(1)
            val datePart = r.substringAfterLast(';', "").trim().replace(Regex("\\s*\\(.*\\)$"), "")
            val at = dateFmt.firstNotNullOfOrNull { f -> runCatching { SimpleDateFormat(f, Locale.US).parse(datePart)?.time }.getOrNull() }
            Triple(from, by, ip) to at
        }
        var prev: Long? = null
        return hops.map { (t, at) ->
            val delay = if (at != null && prev != null) (at - prev!!) / 1000 else null
            if (at != null) prev = at
            HopDto(t.first, t.second, t.third, at, delay)
        }
    }
}

// --- mail operations -----------------------------------------------------------------------------------

/**
 * Re-sends a received message unchanged to new recipients (mutt/Pine "bounce", Eudora "Redirect").
 * The envelope sender is this account; the original From, Subject, body and DKIM signature stay intact,
 * so replies still go to the original author.
 */
fun MailSession.resend(path: String, uid: Long, to: List<String>, mode: String): Int {
    val raw = rawBytes(path, uid) ?: throw IllegalArgumentException("Message not found")
    val recipients = to.flatMap { InternetAddress.parse(it).toList() }
    require(recipients.isNotEmpty()) { "Add at least one recipient" }
    val domain = account.email.substringAfter('@', "")
    // Resent-* fields go on top of the original header, as one block.
    val block = Resent.headers(account.email, account.displayName, recipients.map { it.toString() }, Date(), Resent.newMessageId(domain))
        .joinToString("") { (k, v) -> "$k: $v\r\n" }.toByteArray(Charsets.US_ASCII)
    val session = smtpSession()
    val msg = SMTPMessage(session, ByteArrayInputStream(block + raw))
    msg.envelopeFrom = account.email
    if (mode == "redirect") {
        msg.getHeader("From", null)?.let { msg.setHeader("From", Resent.byWayOf(MimeUtility.decodeText(it), account.displayName.ifBlank { account.email })) }
    }
    msg.removeHeader("Bcc")
    // sendMessage (unlike Transport.send) does not call saveChanges(), so the body and Message-ID stay as they were.
    val t = session.getTransport("smtp")
    try {
        t.connect(account.smtpHost, account.smtpPort, account.username, account.password)
        t.sendMessage(msg, recipients.toTypedArray())
    } finally { runCatching { t.close() } }
    setKeyword(path, listOf(uid), if (mode == "redirect") "\$Redirected" else "\$Bounced", true)
    return recipients.size
}

/** Adds and removes IMAP keywords and system flags (\Seen, \Answered, \Flagged, \Deleted, \Draft). */
fun MailSession.editFlags(path: String, uids: List<Long>, add: List<String>, remove: List<String>): Int {
    if (!isImap) throw IllegalArgumentException("Tags need an IMAP account")
    // Closing a folder opened read-write normally expunges it; a \Deleted mark must survive until Compact.
    return synchronized(this) {
        val f = connect().getFolder(path)
        if (!f.exists()) throw jakarta.mail.MessagingException("Folder not found: $path")
        f.open(Folder.READ_WRITE)
        try {
            val msgs = messagesByUid(f, uids).values.toTypedArray()
            if (msgs.isNotEmpty()) {
                val a = Keywords.toFlags(add); val r = Keywords.toFlags(remove)
                if (a.systemFlags.isNotEmpty() || a.userFlags.isNotEmpty()) f.setFlags(msgs, a, true)
                if (r.systemFlags.isNotEmpty() || r.userFlags.isNotEmpty()) f.setFlags(msgs, r, false)
            }
            msgs.size
        } finally { if (f.isOpen) runCatching { f.close(false) } }
    }
}

/** Keywords the folder allows and those in use on its newest messages. */
fun MailSession.folderKeywords(path: String): Pair<Boolean, List<String>> = withFolder(path) { f ->
    val permanent = runCatching { f.permanentFlags }.getOrNull()
    val anyAllowed = permanent?.contains(Flags.Flag.USER) ?: true
    val n = f.messageCount
    val used = LinkedHashSet<String>()
    permanent?.userFlags?.forEach { used += it }
    if (n > 0) {
        val msgs = f.getMessages(maxOf(1, n - 499), n)
        f.fetch(msgs, FetchProfile().apply { add(FetchProfile.Item.FLAGS) })
        msgs.forEach { m -> runCatching { m.flags.userFlags.forEach { used += it } } }
    }
    anyAllowed to used.filter { !it.startsWith("\$MDN") && !it.equals("\$Forwarded", true) && !it.equals("NonJunk", true) && !it.equals("\$NotJunk", true) && !it.equals("Junk", true) && !it.equals("\$Junk", true) }
}

/** Permanently removes messages marked \Deleted (Thunderbird "Compact", Pine "Expunge"). */
fun MailSession.compact(path: String): Int = withFolder(path, Folder.READ_WRITE) { f ->
    // Count by fetching flags: not every server implements SEARCH DELETED.
    val msgs = f.messages
    f.fetch(msgs, FetchProfile().apply { add(FetchProfile.Item.FLAGS) })
    val deleted = msgs.count { runCatching { it.isSet(Flags.Flag.DELETED) }.getOrDefault(false) }
    if (isImap) f.expunge()
    deleted
}

/** Marks every unread message received in [since, before) as read. */
fun MailSession.markReadRange(path: String, since: Long?, before: Long?): Int = withFolder(path, Folder.READ_WRITE) { f ->
    val terms = ArrayList<SearchTerm>()
    terms += FlagTerm(Flags(Flags.Flag.SEEN), false)
    since?.let { terms += ReceivedDateTerm(ComparisonTerm.GE, Date(it)) }
    before?.let { terms += ReceivedDateTerm(ComparisonTerm.LT, Date(it)) }
    val hits = f.search(if (terms.size == 1) terms[0] else AndTerm(terms.toTypedArray()))
    if (hits.isNotEmpty()) f.setFlags(hits, Flags(Flags.Flag.SEEN), true)
    hits.size
}

fun MailSession.folderStats(path: String): FolderStatsDto = withFolder(path) { f ->
    val n = f.messageCount
    if (n == 0) return@withFolder FolderStatsDto(path, 0, 0, 0, 0, 0, 0, 0)
    val msgs = f.messages
    f.fetch(msgs, FetchProfile().apply { add(FetchProfile.Item.FLAGS); add(FetchProfile.Item.SIZE); add(FetchProfile.Item.ENVELOPE) })
    var size = 0L; var unread = 0; var flagged = 0; var deleted = 0; var oldest = Long.MAX_VALUE; var newest = 0L
    for (m in msgs) {
        size += runCatching { m.size.toLong() }.getOrDefault(0L).coerceAtLeast(0)
        if (runCatching { !m.isSet(Flags.Flag.SEEN) }.getOrDefault(false)) unread++
        if (runCatching { m.isSet(Flags.Flag.FLAGGED) }.getOrDefault(false)) flagged++
        if (runCatching { m.isSet(Flags.Flag.DELETED) }.getOrDefault(false)) deleted++
        val d = runCatching { (m.receivedDate ?: m.sentDate)?.time }.getOrNull() ?: continue
        oldest = minOf(oldest, d); newest = maxOf(newest, d)
    }
    FolderStatsDto(path, n, unread, flagged, deleted, size, if (oldest == Long.MAX_VALUE) 0 else oldest, newest)
}

/** Messages larger than [minBytes], largest first. */
fun MailSession.largeMessages(path: String, minBytes: Int, limit: Int): List<RawSummary> = withFolder(path) { f ->
    val hits = runCatching { f.search(SizeTerm(ComparisonTerm.GT, minBytes)) }.getOrElse {
        val all = f.messages; f.fetch(all, FetchProfile().apply { add(FetchProfile.Item.SIZE) }); all.filter { m -> runCatching { m.size > minBytes }.getOrDefault(false) }.toTypedArray()
    }
    if (hits.isEmpty()) return@withFolder emptyList()
    f.fetch(hits, listProfile())
    hits.map { summarise(f, it) }.sortedByDescending { it.size }.take(limit)
}

/** UIDs of messages flagged \Deleted but not yet expunged. */
fun MailSession.deletedSummaries(path: String, limit: Int): List<RawSummary> = withFolder(path) { f ->
    val all = f.messages
    f.fetch(all, FetchProfile().apply { add(FetchProfile.Item.FLAGS) })
    val hits = all.filter { runCatching { it.isSet(Flags.Flag.DELETED) }.getOrDefault(false) }.takeLast(limit).toTypedArray()
    if (hits.isEmpty()) return@withFolder emptyList()
    f.fetch(hits, listProfile())
    hits.map { summarise(f, it) }.reversed()
}

/** Envelope summaries of messages received before [before]. */
fun MailSession.olderThan(path: String, before: Long, limit: Int): List<RawSummary> = withFolder(path) { f ->
    val hits = runCatching { f.search(ReceivedDateTerm(ComparisonTerm.LT, Date(before))) }.getOrDefault(emptyArray()).take(limit).toTypedArray()
    if (hits.isEmpty()) return@withFolder emptyList()
    f.fetch(hits, listProfile())
    hits.map { summarise(f, it) }
}

fun MailSession.allUids(path: String): List<Long> = withFolder(path) { f ->
    val msgs = f.messages
    f.fetch(msgs, FetchProfile().apply { add(jakarta.mail.UIDFolder.FetchProfileItem.UID) })
    msgs.map { uidOf(f, it) }
}

/** A parsed message whose Message-ID survives saveChanges(). */
private class KeptIdMessage(session: Session, bytes: ByteArray) : MimeMessage(session, ByteArrayInputStream(bytes)) {
    private val originalId: String? = getHeader("Message-ID", null)
    override fun updateMessageID() { if (originalId != null) setHeader("Message-ID", originalId) else super.updateMessageID() }
}

/** Replaces non-inline attachments with a short note; returns the removed file names. */
internal fun stripAttachments(part: Part, removed: MutableList<String>, depth: Int = 0): Boolean {
    if (depth > 12) return false
    val content = runCatching { part.content }.getOrNull()
    if (content !is Multipart) return false
    var changed = false
    var i = 0
    while (i < content.count) {
        val bp = content.getBodyPart(i)
        val ct = runCatching { bp.contentType.lowercase() }.getOrDefault("")
        val disp = runCatching { bp.disposition?.lowercase() }.getOrNull()
        val name = runCatching { bp.fileName }.getOrNull()?.let { runCatching { MimeUtility.decodeText(it) }.getOrDefault(it) }
        val isAttachment = !ct.startsWith("multipart/") && (disp == "attachment" || (name != null && disp != "inline")) && !ct.startsWith("text/calendar")
        if (isAttachment) {
            val size = runCatching { bp.size }.getOrDefault(-1)
            val label = name ?: "attachment"
            removed += label
            val note = MimeBodyPart().apply { setText("[Attachment \"$label\" (${if (size > 0) "${size / 1024} KB" else "unknown size"}, ${ct.substringBefore(';')}) was removed by Anne's Post on ${Date()}.]\n", "UTF-8") }
            (content as MimeMultipart).removeBodyPart(i)
            content.addBodyPart(note, i)
            changed = true
        } else if (ct.startsWith("multipart/")) {
            if (stripAttachments(bp, removed, depth + 1)) changed = true
        }
        i++
    }
    if (changed) part.setContent(content)
    return changed
}

/**
 * Rewrites a stored message (new subject and/or attachments removed): the new copy is appended with
 * the same flags and the original is deleted. Returns the new UID when the server reports it.
 */
fun MailSession.rewrite(path: String, uid: Long, subject: String?, strip: Boolean): RewriteResponse {
    if (!isImap) throw IllegalArgumentException("Editing stored messages needs an IMAP account")
    val raw = rawBytes(path, uid) ?: throw IllegalArgumentException("Message not found")
    val flags = withFolder(path) { f -> messagesByUid(f, listOf(uid))[uid]?.flags } ?: Flags()
    val msg = KeptIdMessage(Session.getInstance(Properties()), raw)
    val removed = ArrayList<String>()
    if (subject != null) msg.setSubject(subject, "UTF-8")
    if (strip) stripAttachments(msg, removed)
    if (subject == null && removed.isEmpty()) return RewriteResponse(path, uid, emptyList())
    msg.saveChanges()
    flags.remove(Flags.Flag.RECENT)
    val newUid = synchronized(this) { append(path, msg, flags) }
    deletePermanently(path, listOf(uid))
    return RewriteResponse(path, newUid, removed)
}

/** Groups recent messages by mailing list (List-Id), Opera M2's "Mailing lists" access point. */
fun MailSession.mailingLists(path: String, limit: Int): List<ListGroupDto> {
    val (_, raws) = listMessages(path, limit, 0)
    return raws.mapNotNull { s ->
        val id = s.headers["List-Id"]?.firstOrNull()?.trim() ?: return@mapNotNull null
        val key = Regex("<([^>]+)>").find(id)?.groupValues?.get(1)?.lowercase() ?: id.lowercase()
        Triple(key, id, s)
    }.groupBy { it.first }.map { (key, items) ->
        val name = runCatching { MimeUtility.decodeText(items.first().second.substringBefore('<').trim().trim('"')) }.getOrDefault("").ifBlank { key }
        ListGroupDto(key, name, items.size, items.count { !it.third.seen }, items.maxOf { it.third.date },
            items.firstNotNullOfOrNull { it.third.headers["List-Unsubscribe"]?.firstOrNull() }, items.map { it.third.uid })
    }.sortedByDescending { it.latest }
}

/** Finds the original message inside a delivery-failure report (Eudora "Send Again"). */
fun MailSession.bouncedOriginal(path: String, uid: Long): OriginalMessageDto {
    val raw = rawBytes(path, uid) ?: throw IllegalArgumentException("Message not found")
    val session = Session.getInstance(Properties())
    val outer = MimeMessage(session, ByteArrayInputStream(raw))
    fun find(p: Part, depth: Int): Part? {
        if (depth > 8) return null
        val ct = runCatching { p.contentType.lowercase() }.getOrDefault("")
        if (depth > 0 && (ct.startsWith("message/rfc822") || ct.startsWith("text/rfc822-headers") || ct.startsWith("message/global"))) return p
        val c = runCatching { p.content }.getOrNull() as? Multipart ?: return null
        for (i in 0 until c.count) find(c.getBodyPart(i), depth + 1)?.let { return it }
        return null
    }
    val inner = find(outer, 0) ?: return OriginalMessageDto(emptyList(), emptyList(), "", "", null, found = false)
    val bytes = inner.inputStream.use { it.readBytes() }
    val m = MimeMessage(session, ByteArrayInputStream(bytes))
    fun addrs(t: Message.RecipientType) = runCatching { m.getRecipients(t)?.map { it.toString() } }.getOrNull() ?: emptyList()
    var text = ""; var html: String? = null
    fun walk(p: Part, d: Int) {
        if (d > 8) return
        val ct = runCatching { p.contentType.lowercase() }.getOrDefault("")
        when {
            ct.startsWith("multipart/") -> (runCatching { p.content }.getOrNull() as? Multipart)?.let { mp -> for (i in 0 until mp.count) walk(mp.getBodyPart(i), d + 1) }
            ct.startsWith("text/plain") && text.isEmpty() -> text = runCatching { p.content.toString() }.getOrDefault("")
            ct.startsWith("text/html") && html == null -> html = runCatching { p.content.toString() }.getOrNull()
        }
    }
    runCatching { walk(m, 0) }
    val subject = runCatching { m.subject }.getOrNull() ?: ""
    return OriginalMessageDto(addrs(Message.RecipientType.TO), addrs(Message.RecipientType.CC), subject, text, html, found = true)
}

/** Raw header block of a message (for List-Unsubscribe-Post, DKIM-Signature and the inspector). */
fun MailSession.headerList(path: String, uid: Long): List<Pair<String, String>> {
    val raw = rawBytes(path, uid) ?: throw IllegalArgumentException("Message not found")
    return HeaderInspector.parseHeaders(raw)
}

/** Copies a folder's messages into a new folder of the same name under [parent] (no subfolders). */
fun MailSession.copyFolder(path: String, parent: String?): Pair<String, Int> {
    val name = synchronized(this) { connect().getFolder(path).name }
    val target = createFolder(name, parent)
    val uids = allUids(path)
    var n = 0
    for (chunk in uids.chunked(200)) n += copy(path, chunk, target)
    return target to n
}
