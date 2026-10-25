package info.socrtwo.quillpost.web

import jakarta.mail.FetchProfile
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.MessagingException
import jakarta.mail.internet.MimeMessage
import jakarta.mail.search.AndTerm
import jakarta.mail.search.BodyTerm
import jakarta.mail.search.ComparisonTerm
import jakarta.mail.search.FlagTerm
import jakarta.mail.search.FromStringTerm
import jakarta.mail.search.HeaderTerm
import jakarta.mail.search.OrTerm
import jakarta.mail.search.ReceivedDateTerm
import jakarta.mail.search.RecipientStringTerm
import jakarta.mail.search.SearchTerm
import jakarta.mail.search.SubjectTerm
import org.eclipse.angus.mail.imap.IMAPFolder
import org.eclipse.angus.mail.imap.IMAPStore
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Advanced search criteria; blank fields are ignored. */
data class SearchCriteria(
    val text: String = "",
    val from: String = "",
    val to: String = "",
    val subject: String = "",
    val body: String = "",
    val hasAttachments: Boolean = false,
    val unread: Boolean = false,
    val flagged: Boolean = false,
    val since: Long? = null,
    val before: Long? = null
) {
    fun isEmpty() = text.isBlank() && from.isBlank() && to.isBlank() && subject.isBlank() && body.isBlank() && !hasAttachments && !unread && !flagged && since == null && before == null
}

// --- folders ---------------------------------------------------------------------------------

private fun MailSession.requireImap() { if (!isImap) throw MessagingException("POP3 accounts have no server-side folders") }

fun MailSession.invalidateRoles() = synchronized(this) { roleCache.clear() }

/** Creates [name] under [parent] (or at the top level) and returns its full path. */
fun MailSession.createFolder(name: String, parent: String?): String = synchronized(this) {
    requireImap()
    val clean = name.trim()
    require(clean.isNotEmpty()) { "Folder name is required" }
    val s = connect()
    val base = if (parent.isNullOrBlank()) s.defaultFolder else s.getFolder(parent)
    val f = base.getFolder(clean)
    if (f.exists()) throw IllegalArgumentException("A folder named \"$clean\" already exists here")
    val ok = runCatching { f.create(Folder.HOLDS_MESSAGES or Folder.HOLDS_FOLDERS) }.getOrDefault(false) ||
        runCatching { f.create(Folder.HOLDS_MESSAGES) }.getOrDefault(false)
    if (!ok) throw MessagingException("The server refused to create \"$clean\"")
    runCatching { f.setSubscribed(true) }
    f.fullName
}

fun MailSession.renameFolder(path: String, newName: String): String = synchronized(this) {
    requireImap()
    require(!path.equals(MailSession.INBOX, true)) { "The Inbox cannot be renamed" }
    val s = connect()
    val f = s.getFolder(path)
    if (!f.exists()) throw MessagingException("Folder not found: $path")
    val parent = f.parent
    val target = (parent ?: s.defaultFolder).getFolder(newName.trim())
    if (target.exists()) throw IllegalArgumentException("A folder named \"${newName.trim()}\" already exists here")
    if (f.isOpen) runCatching { f.close(false) }
    if (!f.renameTo(target)) throw MessagingException("The server refused to rename the folder")
    runCatching { target.setSubscribed(true) }
    roleCache.clear()
    target.fullName
}

fun MailSession.moveFolder(path: String, newParent: String?): String = synchronized(this) {
    requireImap()
    require(!path.equals(MailSession.INBOX, true)) { "The Inbox cannot be moved" }
    val s = connect()
    val f = s.getFolder(path)
    if (!f.exists()) throw MessagingException("Folder not found: $path")
    val parent = if (newParent.isNullOrBlank()) s.defaultFolder else s.getFolder(newParent)
    if (!newParent.isNullOrBlank() && (newParent == path || newParent.startsWith(path + f.separator))) throw IllegalArgumentException("A folder cannot be moved into itself")
    val target = parent.getFolder(f.name)
    if (target.exists()) throw IllegalArgumentException("That folder already contains a folder named \"${f.name}\"")
    if (!f.renameTo(target)) throw MessagingException("The server refused to move the folder")
    runCatching { target.setSubscribed(true) }
    roleCache.clear()
    target.fullName
}

/**
 * Deletes a folder and its subfolders. Messages are moved to Deleted Items first (unless the
 * folder is Deleted Items itself), so deleting a folder never silently destroys mail.
 */
fun MailSession.deleteFolder(path: String, trash: String?): Int = synchronized(this) {
    requireImap()
    require(!path.equals(MailSession.INBOX, true)) { "The Inbox cannot be deleted" }
    val s = connect()
    val f = s.getFolder(path)
    if (!f.exists()) throw MessagingException("Folder not found: $path")
    val all = (listOf(f) + runCatching { f.list("*").toList() }.getOrDefault(emptyList())).sortedByDescending { it.fullName.length }
    var moved = 0
    for (sub in all) {
        if ((sub.type and Folder.HOLDS_MESSAGES) == 0) continue
        if (trash != null && sub.fullName != trash) {
            moved += withFolder(sub.fullName, Folder.READ_WRITE) { open ->
                val msgs = open.messages
                if (msgs.isEmpty()) 0 else {
                    val target = s.getFolder(trash)
                    if (open is IMAPFolder) runCatching { open.moveMessages(msgs, target) }.getOrElse { open.copyMessages(msgs, target); open.setFlags(msgs, Flags(Flags.Flag.DELETED), true); open.expunge() }
                    else { open.copyMessages(msgs, target); open.setFlags(msgs, Flags(Flags.Flag.DELETED), true); open.expunge() }
                    msgs.size
                }
            }
        }
    }
    for (sub in all) { if (sub.isOpen) runCatching { sub.close(false) }; runCatching { sub.delete(true) } }
    if (f.exists() && !f.delete(true)) throw MessagingException("The server refused to delete the folder")
    roleCache.clear()
    moved
}

/** Empties a folder: permanently for Deleted Items and Junk, otherwise into Deleted Items. */
fun MailSession.emptyFolder(path: String, permanent: Boolean, trash: String?): Int = withFolder(path, Folder.READ_WRITE) { f ->
    val msgs = f.messages
    if (msgs.isEmpty()) return@withFolder 0
    if (permanent || trash == null || trash == path) {
        f.setFlags(msgs, Flags(Flags.Flag.DELETED), true)
        if (isImap) f.expunge()
    } else {
        val target = connect().getFolder(trash)
        if (f is IMAPFolder) runCatching { f.moveMessages(msgs, target) }.getOrElse { f.copyMessages(msgs, target); f.setFlags(msgs, Flags(Flags.Flag.DELETED), true); f.expunge() }
        else { f.copyMessages(msgs, target); f.setFlags(msgs, Flags(Flags.Flag.DELETED), true); f.expunge() }
    }
    msgs.size
}

fun MailSession.markAllRead(path: String): Int = withFolder(path, Folder.READ_WRITE) { f ->
    val unread = f.search(FlagTerm(Flags(Flags.Flag.SEEN), false))
    if (unread.isNotEmpty()) f.setFlags(unread, Flags(Flags.Flag.SEEN), true)
    unread.size
}

fun MailSession.copy(path: String, uids: List<Long>, dest: String): Int = withFolder(path) { f ->
    val msgs = messagesByUid(f, uids).values.toTypedArray()
    if (msgs.isNotEmpty()) f.copyMessages(msgs, connect().getFolder(dest))
    msgs.size
}

/** Sets or clears an IMAP keyword (\$Forwarded, \$MDNSent, …). Servers without keyword support ignore it. */
fun MailSession.setKeyword(path: String, uids: List<Long>, keyword: String, value: Boolean) {
    if (!isImap) return
    runCatching {
        withFolder(path, Folder.READ_WRITE) { f ->
            val msgs = messagesByUid(f, uids).values.toTypedArray()
            if (msgs.isNotEmpty()) f.setFlags(msgs, Flags(keyword), value)
        }
    }
}

// --- search ----------------------------------------------------------------------------------

private fun SearchCriteria.toTerm(): SearchTerm? {
    val terms = ArrayList<SearchTerm>()
    if (text.isNotBlank()) terms += OrTerm(arrayOf(FromStringTerm(text), SubjectTerm(text), BodyTerm(text), RecipientStringTerm(Message.RecipientType.TO, text)))
    // HEADER searches as well as FROM/TO: some servers only match the address part with FROM/TO.
    if (from.isNotBlank()) terms += OrTerm(FromStringTerm(from), HeaderTerm("From", from))
    if (to.isNotBlank()) terms += OrTerm(arrayOf(RecipientStringTerm(Message.RecipientType.TO, to), RecipientStringTerm(Message.RecipientType.CC, to), HeaderTerm("To", to), HeaderTerm("Cc", to)))
    if (subject.isNotBlank()) terms += SubjectTerm(subject)
    if (body.isNotBlank()) terms += BodyTerm(body)
    if (unread) terms += FlagTerm(Flags(Flags.Flag.SEEN), false)
    if (flagged) terms += FlagTerm(Flags(Flags.Flag.FLAGGED), true)
    since?.let { terms += ReceivedDateTerm(ComparisonTerm.GE, Date(it)) }
    before?.let { terms += ReceivedDateTerm(ComparisonTerm.LT, Date(it)) }
    return when (terms.size) { 0 -> null; 1 -> terms[0]; else -> AndTerm(terms.toTypedArray()) }
}

fun MailSession.searchAdvanced(path: String, c: SearchCriteria, limit: Int): List<RawSummary> = withFolder(path) { f ->
    val term = c.toTerm()
    val hits = when {
        term == null -> f.messages
        else -> try { f.search(term) } catch (e: MessagingException) {
            // Some servers reject parts of SEARCH ("BAD … not supported"): match the newest messages here instead.
            val n = f.messageCount
            val recent = if (n == 0) emptyArray() else f.getMessages(maxOf(1, n - 1999), n)
            val fp = FetchProfile().apply { add(FetchProfile.Item.ENVELOPE); add(FetchProfile.Item.FLAGS) }
            f.fetch(recent, fp)
            recent.filter { m -> runCatching { term.match(m) }.getOrDefault(false) }.toTypedArray()
        }
    }
    if (hits.isEmpty()) return@withFolder emptyList()
    // Attachment filter needs the body structure, so over-fetch a little before filtering.
    val slice = hits.takeLast(if (c.hasAttachments) limit * 3 else limit).toTypedArray()
    f.fetch(slice, listProfile())
    slice.map { summarise(f, it) }.filter { !c.hasAttachments || it.hasAttachments }.takeLast(limit).reversed()
}

/** Messages in [paths] that belong to the conversation identified by [ids] (Message-IDs). */
fun MailSession.threadMessages(paths: List<String>, ids: List<String>): List<Pair<String, RawSummary>> {
    val clean = ids.map { it.trim() }.filter { it.length > 3 }.distinct().takeLast(12)
    if (clean.isEmpty()) return emptyList()
    val terms = clean.flatMap { id -> listOf(HeaderTerm("Message-ID", id), HeaderTerm("References", id), HeaderTerm("In-Reply-To", id)) }
    val term = OrTerm(terms.toTypedArray())
    val out = ArrayList<Pair<String, RawSummary>>()
    for (p in paths.distinct()) {
        runCatching {
            withFolder(p) { f ->
                val hits = runCatching { f.search(term) }.getOrDefault(emptyArray()).takeLast(100).toTypedArray()
                if (hits.isNotEmpty()) { f.fetch(hits, listProfile()); hits.forEach { out += p to summarise(f, it) } }
            }
        }
    }
    return out
}

/** Envelope summaries for specific UIDs (no bodies). */
fun MailSession.summaries(path: String, uids: List<Long>): List<RawSummary> = withFolder(path) { f ->
    val msgs = messagesByUid(f, uids).values.toTypedArray()
    if (msgs.isEmpty()) return@withFolder emptyList()
    f.fetch(msgs, listProfile())
    msgs.map { summarise(f, it) }
}

/** Finds a message by Message-ID in [path] and returns its UID. */
fun MailSession.findByMessageId(path: String, messageId: String): Long? = runCatching {
    withFolder(path) { f -> f.search(jakarta.mail.search.MessageIDTerm(messageId)).lastOrNull()?.let { uidOf(f, it) } }
}.getOrNull()

/**
 * Sends a read receipt (RFC 8098 message disposition notification) for [original] to [to].
 */
fun MailSession.sendMdn(original: RawSummary, to: String) {
    val session = smtpSession()
    val msg = MimeMessage(session)
    msg.setFrom(jakarta.mail.internet.InternetAddress(account.email, account.displayName.ifBlank { account.email }, "UTF-8"))
    msg.setRecipients(Message.RecipientType.TO, jakarta.mail.internet.InternetAddress.parse(to))
    msg.setSubject("Read: " + original.subject, "UTF-8")
    msg.setHeader("Auto-Submitted", "auto-replied")
    msg.sentDate = Date()
    val report = object : jakarta.mail.internet.MimeMultipart("report") {
        init {
            val ct = jakarta.mail.internet.ContentType(contentType)
            ct.setParameter("report-type", "disposition-notification")
            contentType = ct.toString()
        }
    }
    val sent = if (original.date > 0) Date(original.date).toString() else ""
    report.addBodyPart(jakarta.mail.internet.MimeBodyPart().apply {
        setText("Your message\n\n  To: ${account.email}\n  Subject: ${original.subject}\n  Sent: $sent\n\nwas read on ${Date()}.", "UTF-8")
    })
    report.addBodyPart(jakarta.mail.internet.MimeBodyPart().apply {
        setContent(
            "Reporting-UA: Anne's Post\r\nFinal-Recipient: rfc822;${account.email}\r\n" +
                (if (original.messageId.isNotBlank()) "Original-Message-ID: ${original.messageId}\r\n" else "") +
                "Disposition: manual-action/MDN-sent-manually; displayed\r\n",
            "message/disposition-notification"
        )
    })
    msg.setContent(report)
    msg.saveChanges()
    jakarta.mail.Transport.send(msg, account.username, account.password)
}

// --- quota, import & export ----------------------------------------------------------------------

fun MailSession.quota(): QuotaDto? = synchronized(this) {
    if (!isImap) return null
    val s = connect() as? IMAPStore ?: return null
    val quotas = runCatching { s.getQuota(MailSession.INBOX) }.getOrNull() ?: return null
    for (q in quotas) for (r in q.resources) if (r.name.equals("STORAGE", true)) return QuotaDto(r.usage, r.limit)
    null
}

/** Appends raw RFC 822 messages to [path] (keeping them marked as read). */
fun MailSession.appendRaw(path: String, messages: List<ByteArray>): Int = synchronized(this) {
    requireImap()
    val s = connect()
    val folder = s.getFolder(path)
    if (!folder.exists()) folder.create(Folder.HOLDS_MESSAGES)
    var n = 0
    val session = jakarta.mail.Session.getInstance(java.util.Properties())
    for (chunk in messages.chunked(25)) {
        val msgs = chunk.mapNotNull { raw -> runCatching { MimeMessage(session, ByteArrayInputStream(raw)).also { it.setFlag(Flags.Flag.SEEN, true) } }.getOrNull() }
        if (msgs.isNotEmpty()) { folder.appendMessages(msgs.toTypedArray()); n += msgs.size }
    }
    n
}

private val MBOX_DATE = SimpleDateFormat("EEE MMM dd HH:mm:ss yyyy", Locale.US)

/** Writes [path] as an mbox (mboxrd quoting) to [out]; returns the number of messages. */
fun MailSession.exportMbox(path: String, out: OutputStream): Int = withFolder(path) { f ->
    val total = f.messageCount
    var n = 0
    var start = 1
    while (start <= total) {
        val end = minOf(total, start + 49)
        val msgs = f.getMessages(start, end)
        for (m in msgs) {
            val raw = runCatching { java.io.ByteArrayOutputStream().also { m.writeTo(it) }.toByteArray() }.getOrNull() ?: continue
            val date = runCatching { m.receivedDate ?: m.sentDate }.getOrNull() ?: Date()
            out.write(("From MAILER-DAEMON " + synchronized(MBOX_DATE) { MBOX_DATE.format(date) } + "\n").toByteArray())
            val text = String(raw, Charsets.ISO_8859_1).replace("\r\n", "\n")
            for (line in text.split('\n')) {
                val quoted = if (Regex("^>*From ").containsMatchIn(line)) ">$line" else line
                out.write((quoted + "\n").toByteArray(Charsets.ISO_8859_1))
            }
            out.write("\n".toByteArray())
            n++
        }
        start = end + 1
    }
    out.flush()
    n
}

object Mbox {
    /** Splits an mbox (or a single .eml) into raw messages, undoing mboxrd ">From " quoting. */
    fun split(bytes: ByteArray): List<ByteArray> {
        val text = String(bytes, Charsets.ISO_8859_1)
        if (!text.startsWith("From ")) return listOf(bytes)
        val out = ArrayList<ByteArray>()
        val cur = StringBuilder()
        var started = false
        var prevBlank = true
        for (line in text.replace("\r\n", "\n").split('\n')) {
            if (line.startsWith("From ") && prevBlank) {
                if (started && cur.isNotBlank()) out += cur.toString().trimEnd('\n').replace("\n", "\r\n").toByteArray(Charsets.ISO_8859_1)
                cur.setLength(0); started = true; prevBlank = false
                continue
            }
            val unq = if (Regex("^>+From ").containsMatchIn(line)) line.substring(1) else line
            cur.append(unq).append('\n')
            prevBlank = line.isEmpty()
        }
        if (started && cur.isNotBlank()) out += cur.toString().trimEnd('\n').replace("\n", "\r\n").toByteArray(Charsets.ISO_8859_1)
        return out
    }
}
