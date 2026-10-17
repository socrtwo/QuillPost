package info.socrtwo.quillbox.web

import jakarta.activation.DataHandler
import jakarta.mail.Authenticator
import jakarta.mail.FetchProfile
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.MessagingException
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Store
import jakarta.mail.Transport
import jakarta.mail.UIDFolder
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.internet.MimeUtility
import jakarta.mail.search.BodyTerm
import jakarta.mail.search.FromStringTerm
import jakarta.mail.search.OrTerm
import jakarta.mail.search.SubjectTerm
import jakarta.mail.util.ByteArrayDataSource
import org.eclipse.angus.mail.imap.IMAPFolder
import java.util.Base64
import java.util.Properties

/** Envelope-level view of a message, enough for the list pane and for header-only rules. */
data class RawSummary(
    val uid: Long,
    val messageId: String,
    val fromName: String,
    val fromAddress: String,
    val to: List<String>,
    val cc: List<String>,
    val replyTo: String,
    val subject: String,
    val date: Long,
    val seen: Boolean,
    val flagged: Boolean,
    val answered: Boolean,
    val size: Long,
    val hasAttachments: Boolean,
    val headers: Map<String, List<String>>,
    /** IMAP keywords such as \$Forwarded and \$MDNSent. */
    val keywords: Set<String> = emptySet()
) {
    val forwarded: Boolean get() = keywords.any { it.equals("\$Forwarded", true) || it.equals("Forwarded", true) }
    val mdnSent: Boolean get() = keywords.any { it.equals("\$MDNSent", true) }
}

/** Full message: summary plus bodies, attachment metadata and the headers the engine needs. */
data class RawMessage(
    val summary: RawSummary,
    val bodyText: String,
    val bodyHtml: String?,
    val attachments: List<AttachmentDto>,
    val inReplyTo: String?,
    val references: String?,
    /** The text/calendar part or .ics attachment, when the message carries one. */
    val calendar: String? = null
)

data class FolderInfo(
    val path: String,
    val name: String,
    val delimiter: String,
    val role: String,
    val unread: Int,
    val total: Int,
    val depth: Int
)

/**
 * One live connection to an account's mail server. All methods block; callers dispatch to an
 * IO thread and serialise access through the session lock. The Store is kept open between
 * calls and transparently reconnected if the server drops it.
 */
class MailSession(val account: AccountDto) {
    val isImap: Boolean = account.protocol.uppercase() != "POP3"
    private val session: Session = Session.getInstance(incomingProperties())
    private var store: Store? = null
    internal val roleCache = HashMap<String, String?>()

    companion object {
        val LIST_HEADERS = listOf(
            "Received", "Authentication-Results", "ARC-Authentication-Results", "Reply-To", "List-Unsubscribe",
            "Precedence", "X-Originating-IP", "X-Mailer", "Message-ID", "In-Reply-To", "References", "X-Priority", "Return-Path",
            "Importance", "Priority", "List-Id", "Auto-Submitted", "Feedback-ID", "Disposition-Notification-To", "X-Auto-Response-Suppress"
        )
        const val INBOX = "INBOX"
    }

    // --- connection ---------------------------------------------------------------------

    @Synchronized
    fun connect(): Store {
        store?.let { if (it.isConnected) return it }
        val s = session.getStore(storeProtocol())
        s.connect(account.incomingHost, account.incomingPort, account.username, account.password)
        store = s
        return s
    }

    @Synchronized
    fun close() {
        runCatching { store?.close() }
        store = null
    }

    fun testIncoming(): Result<Unit> = runCatching {
        val s = session.getStore(storeProtocol())
        s.connect(account.incomingHost, account.incomingPort, account.username, account.password)
        s.close()
    }

    fun testSmtp(): Result<Unit> = runCatching {
        val t = smtpSession().getTransport("smtp")
        t.connect(account.smtpHost, account.smtpPort, account.username, account.password)
        t.close()
    }

    /** Runs [block] with an open folder, reconnecting once if the store went away. */
    @Synchronized
    fun <T> withFolder(path: String, mode: Int = Folder.READ_ONLY, block: (Folder) -> T): T {
        var attempt = 0
        while (true) {
            try {
                val s = connect()
                val folder = if (!isImap) s.getFolder(INBOX) else s.getFolder(path)
                if (!folder.exists()) throw MessagingException("Folder not found: $path")
                folder.open(mode)
                try {
                    return block(folder)
                } finally {
                    // IMAP deletions expunge explicitly, so closing does not: a message marked \Deleted
                    // (Pine/mutt "mark for deletion") stays until Compact. POP3 deletes on close.
                    if (folder.isOpen) runCatching { folder.close(mode == Folder.READ_WRITE && !isImap) }
                }
            } catch (e: MessagingException) {
                if (attempt++ >= 1 || e is jakarta.mail.FolderNotFoundException) throw e
                close()
            } catch (e: IllegalStateException) {
                if (attempt++ >= 1) throw e
                close()
            }
        }
    }

    // --- folders --------------------------------------------------------------------------

    @Synchronized
    fun listFolders(): List<FolderInfo> {
        val s = connect()
        if (!isImap) {
            val inbox = s.getFolder(INBOX)
            val (total, unread) = counts(inbox)
            return listOf(FolderInfo(INBOX, "Inbox", "/", "inbox", unread, total, 0))
        }
        val all = s.defaultFolder.list("*").filter { (it.type and Folder.HOLDS_MESSAGES) != 0 }
        val out = ArrayList<FolderInfo>()
        for (f in all) {
            val delim = runCatching { f.separator.toString() }.getOrDefault("/")
            val depth = f.fullName.count { it.toString() == delim }
            val role = roleOf(f)
            val (total, unread) = if (role in setOf("inbox", "junk", "drafts") || depth == 0) counts(f) else (-1 to -1)
            out += FolderInfo(f.fullName, displayName(f, role), delim, role, unread, total, depth)
        }
        if (out.none { it.role == "inbox" }) {
            val inbox = s.getFolder(INBOX)
            if (inbox.exists()) { val (t, u) = counts(inbox); out.add(0, FolderInfo(INBOX, "Inbox", "/", "inbox", u, t, 0)) }
        }
        val order = listOf("inbox", "drafts", "sent", "junk", "trash", "archive", "snoozed", "other")
        return out.sortedWith(compareBy({ order.indexOf(it.role).let { i -> if (i < 0) 99 else i } }, { it.path.lowercase() }))
    }

    private fun counts(f: Folder): Pair<Int, Int> = runCatching {
        (f.messageCount to f.unreadMessageCount)
    }.getOrDefault(-1 to -1)

    private fun displayName(f: Folder, role: String): String = when (role) {
        "inbox" -> "Inbox"; "junk" -> "Junk Email"; "sent" -> "Sent Items"; "drafts" -> "Drafts"
        "trash" -> "Deleted Items"; "archive" -> "Archive"; "snoozed" -> "Snoozed"
        else -> f.name
    }

    internal fun roleOf(f: Folder): String {
        val name = f.fullName
        if (name.equals(INBOX, ignoreCase = true)) return "inbox"
        val attrs = (f as? IMAPFolder)?.let { runCatching { it.attributes.toList() }.getOrDefault(emptyList()) } ?: emptyList()
        attrs.forEach { a ->
            when (a.lowercase()) {
                "\\junk" -> return "junk"; "\\sent" -> return "sent"; "\\drafts" -> return "drafts"
                "\\trash" -> return "trash"; "\\archive" -> return "archive"
            }
        }
        val leaf = f.name.lowercase().trim()
        return when {
            leaf in setOf("junk", "spam", "junk e-mail", "junk email", "bulk mail", "bulk", "[gmail]/spam", "unerwünscht", "courrier indésirable") -> "junk"
            leaf in setOf("sent", "sent items", "sent mail", "sent messages", "[gmail]/sent mail") -> "sent"
            leaf in setOf("drafts", "draft", "[gmail]/drafts") -> "drafts"
            leaf in setOf("trash", "deleted", "deleted items", "deleted messages", "bin", "[gmail]/trash", "[gmail]/bin") -> "trash"
            leaf in setOf("archive", "archives", "all mail", "[gmail]/all mail") -> "archive"
            leaf == "snoozed" -> "snoozed"
            else -> "other"
        }
    }

    /** Path of the folder that plays [role] (junk/sent/drafts/trash/archive), or null. */
    @Synchronized
    fun folderForRole(role: String): String? {
        roleCache[role]?.let { return it }
        if (!isImap) return null
        val found = listFolders().firstOrNull { it.role == role }?.path
        if (found != null) roleCache[role] = found
        return found
    }

    /** Finds or creates the folder for [role]; Junk is created as a sibling of INBOX. */
    @Synchronized
    fun ensureFolderForRole(role: String): String {
        folderForRole(role)?.let { return it }
        if (!isImap) throw MessagingException("POP3 accounts have no server-side folders")
        val s = connect()
        val inbox = s.getFolder(INBOX)
        val delim = runCatching { inbox.separator.toString() }.getOrDefault("/")
        val defaultName = when (role) { "junk" -> "Junk"; "sent" -> "Sent"; "drafts" -> "Drafts"; "trash" -> "Trash"; "archive" -> "Archive"; "snoozed" -> "Snoozed"; else -> role }
        // Some servers (e.g. Courier) require folders under INBOX.
        val candidates = listOf(defaultName, "$INBOX$delim$defaultName")
        for (name in candidates) {
            val f = s.getFolder(name)
            if (f.exists() || runCatching { f.create(Folder.HOLDS_MESSAGES) }.getOrDefault(false)) {
                runCatching { f.setSubscribed(true) }
                roleCache[role] = f.fullName
                return f.fullName
            }
        }
        throw MessagingException("Could not create a $defaultName folder on the server")
    }

    fun uidValidity(path: String): Long = withFolder(path) { f -> (f as? UIDFolder)?.uidValidity ?: 0L }

    // --- listing --------------------------------------------------------------------------

    /**
     * Newest [limit] messages starting [offset] from the top. Fetches envelopes, flags, size
     * and the headers the spam engine needs in one round trip.
     */
    fun listMessages(path: String, limit: Int, offset: Int = 0): Pair<Int, List<RawSummary>> = withFolder(path) { f ->
        val total = f.messageCount
        if (total == 0) return@withFolder 0 to emptyList()
        val end = total - offset
        val start = maxOf(1, end - limit + 1)
        if (end < 1) return@withFolder total to emptyList()
        val msgs = f.getMessages(start, end)
        f.fetch(msgs, listProfile())
        total to msgs.map { summarise(f, it) }.reversed()
    }

    internal fun listProfile() = FetchProfile().apply {
        add(FetchProfile.Item.ENVELOPE); add(FetchProfile.Item.FLAGS); add(FetchProfile.Item.SIZE)
        add(UIDFolder.FetchProfileItem.UID); add(FetchProfile.Item.CONTENT_INFO)
        LIST_HEADERS.forEach { add(it) }
    }

    fun uidOf(f: Folder, m: Message): Long = when (f) {
        is UIDFolder -> runCatching { f.getUID(m) }.getOrDefault(m.messageNumber.toLong())
        else -> m.messageNumber.toLong()
    }

    internal fun summarise(f: Folder, m: Message): RawSummary {
        val (fromName, fromAddress) = firstAddress(runCatching { m.from }.getOrNull())
        val headers = LinkedHashMap<String, List<String>>()
        for (h in LIST_HEADERS) runCatching { m.getHeader(h)?.toList() }.getOrNull()?.let { if (it.isNotEmpty()) headers[h] = it }
        val replyTo = runCatching { m.replyTo?.firstOrNull() as? InternetAddress }.getOrNull()?.address?.lowercase()
            ?.takeIf { it != fromAddress } ?: ""
        val ct = runCatching { m.contentType?.lowercase() }.getOrNull() ?: ""
        return RawSummary(
            uid = uidOf(f, m),
            messageId = runCatching { (m as? MimeMessage)?.messageID }.getOrNull() ?: headers["Message-ID"]?.firstOrNull() ?: "",
            fromName = fromName,
            fromAddress = fromAddress,
            to = addressList(runCatching { m.getRecipients(Message.RecipientType.TO) }.getOrNull()),
            cc = addressList(runCatching { m.getRecipients(Message.RecipientType.CC) }.getOrNull()),
            replyTo = replyTo,
            subject = decode(runCatching { m.subject }.getOrNull()) ?: "(no subject)",
            date = runCatching { m.sentDate?.time ?: m.receivedDate?.time }.getOrNull() ?: 0L,
            seen = runCatching { m.isSet(Flags.Flag.SEEN) }.getOrDefault(false),
            flagged = runCatching { m.isSet(Flags.Flag.FLAGGED) }.getOrDefault(false),
            answered = runCatching { m.isSet(Flags.Flag.ANSWERED) }.getOrDefault(false),
            keywords = runCatching { m.flags.let { fl -> fl.userFlags.toSet() + (if (fl.contains(Flags.Flag.DELETED)) setOf("\\Deleted") else emptySet()) } }.getOrDefault(emptySet()),
            size = runCatching { m.size.toLong() }.getOrDefault(0L),
            hasAttachments = ct.startsWith("multipart/mixed") || ct.startsWith("application/") || ct.startsWith("multipart/report"),
            headers = headers
        )
    }

    private fun firstAddress(addrs: Array<jakarta.mail.Address>?): Pair<String, String> {
        val a = addrs?.firstOrNull() ?: return "" to ""
        return if (a is InternetAddress) (decode(a.personal) ?: "") to (a.address ?: "").lowercase()
        else "" to a.toString().lowercase()
    }

    private fun addressList(addrs: Array<jakarta.mail.Address>?): List<String> =
        addrs?.map { (it as? InternetAddress)?.address?.lowercase() ?: it.toString() } ?: emptyList()

    private fun decode(s: String?): String? = s?.let { runCatching { MimeUtility.decodeText(it) }.getOrDefault(it) }

    // --- full messages --------------------------------------------------------------------

    /** Fetches complete messages for [uids] (bodies are peeked; \Seen is left untouched). */
    fun fetchMessages(path: String, uids: List<Long>): Map<Long, RawMessage> = withFolder(path) { f ->
        val msgs = messagesByUid(f, uids)
        if (msgs.isEmpty()) return@withFolder emptyMap()
        f.fetch(msgs.values.toTypedArray(), listProfile())
        msgs.mapValues { (_, m) -> toRaw(f, m) }
    }

    fun getMessage(path: String, uid: Long): RawMessage? = withFolder(path) { f ->
        val m = messagesByUid(f, listOf(uid))[uid] ?: return@withFolder null
        toRaw(f, m)
    }

    internal fun messagesByUid(f: Folder, uids: List<Long>): Map<Long, Message> {
        if (uids.isEmpty()) return emptyMap()
        return if (f is UIDFolder && isImap) {
            val arr = f.getMessagesByUID(uids.toLongArray()).filterNotNull()
            arr.associateBy { runCatching { f.getUID(it) }.getOrDefault(-1L) }.filterKeys { it >= 0 }
        } else {
            uids.mapNotNull { n -> runCatching { f.getMessage(n.toInt()) }.getOrNull()?.let { n to it } }.toMap()
        }
    }

    private class Extracted {
        val text = StringBuilder()
        var html: StringBuilder? = null
        val attachments = ArrayList<AttachmentDto>()
        var calendar: String? = null
    }

    /**
     * Downloads the complete RFC 822 message once and re-parses it locally. This avoids a
     * round trip per MIME part and sidesteps servers whose per-section header fetches are
     * incomplete (some drop Content-ID / Content-Disposition).
     */
    internal fun localCopy(m: Message): Message = runCatching {
        val bytes = java.io.ByteArrayOutputStream().also { m.writeTo(it) }.toByteArray()
        MimeMessage(session, java.io.ByteArrayInputStream(bytes)) as Message
    }.getOrDefault(m)

    private fun toRaw(f: Folder, m: Message): RawMessage {
        val summary = summarise(f, m)
        val ex = Extracted()
        val counter = intArrayOf(0)
        runCatching { walk(localCopy(m), ex, counter, 0) }
        val headers = summary.headers
        return RawMessage(
            summary = summary.copy(hasAttachments = ex.attachments.any { !it.inline }),
            bodyText = ex.text.toString().trim(),
            bodyHtml = ex.html?.toString()?.trim()?.takeIf { it.isNotBlank() },
            attachments = ex.attachments,
            inReplyTo = headers["In-Reply-To"]?.firstOrNull(),
            references = headers["References"]?.firstOrNull(),
            calendar = ex.calendar
        )
    }

    /** Depth-first walk; every non-body leaf gets a stable index used by [getAttachment]. */
    private fun walk(part: Part, out: Extracted, counter: IntArray, depth: Int) {
        if (depth > 12) return
        val ct = runCatching { part.contentType?.lowercase() }.getOrNull() ?: "application/octet-stream"
        val disposition = runCatching { part.disposition?.lowercase() }.getOrNull()
        val fileName = runCatching { part.fileName }.getOrNull()?.let { decode(it) }
        val isAttachment = disposition == Part.ATTACHMENT.lowercase() || (fileName != null && !ct.startsWith("multipart/"))
        when {
            ct.startsWith("multipart/") -> {
                val mp = runCatching { part.content as? Multipart }.getOrNull() ?: return
                val isAlternative = ct.startsWith("multipart/alternative")
                if (isAlternative) {
                    // Prefer HTML when both are present; still capture plain text for the engine.
                    for (i in 0 until mp.count) walk(mp.getBodyPart(i), out, counter, depth + 1)
                } else {
                    for (i in 0 until mp.count) walk(mp.getBodyPart(i), out, counter, depth + 1)
                }
            }
            !isAttachment && ct.startsWith("text/plain") -> {
                if (out.text.isEmpty()) out.text.append(runCatching { part.content?.toString() }.getOrNull().orEmpty())
            }
            ct.startsWith("text/calendar") || ct.startsWith("application/ics") || (fileName?.lowercase()?.endsWith(".ics") == true) -> {
                if (out.calendar == null) out.calendar = runCatching { part.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
                if (isAttachment) register(part, out, counter, ct, fileName, disposition)
            }
            !isAttachment && ct.startsWith("text/html") -> {
                val h = out.html ?: StringBuilder().also { out.html = it }
                h.append(runCatching { part.content?.toString() }.getOrNull().orEmpty())
            }
            ct.startsWith("message/rfc822") && !isAttachment -> {
                val inner = runCatching { part.content as? Message }.getOrNull()
                if (inner != null) walk(inner, out, counter, depth + 1) else register(part, out, counter, ct, fileName, disposition)
            }
            else -> register(part, out, counter, ct, fileName, disposition)
        }
    }

    private fun register(part: Part, out: Extracted, counter: IntArray, ct: String, fileName: String?, disposition: String?) {
        val cid = runCatching { part.getHeader("Content-ID")?.firstOrNull() }.getOrNull()?.trim('<', '>', ' ')
        val inline = disposition == Part.INLINE.lowercase() && cid != null
        val ext = ct.substringBefore(';').substringAfter('/').takeIf { it.length in 2..5 } ?: "bin"
        out.attachments += AttachmentDto(
            index = counter[0]++,
            fileName = fileName ?: (if (inline) "inline-${counter[0]}.$ext" else "attachment-${counter[0]}.$ext"),
            mimeType = ct.substringBefore(';').trim(),
            size = runCatching { part.size.toLong() }.getOrDefault(-1L),
            inline = inline,
            contentId = cid
        )
    }

    data class AttachmentData(val fileName: String, val mimeType: String, val bytes: ByteArray)

    fun getAttachment(path: String, uid: Long, index: Int?, cid: String?): AttachmentData? = withFolder(path) { f ->
        val m = messagesByUid(f, listOf(uid))[uid] ?: return@withFolder null
        val counter = intArrayOf(0)
        findPart(localCopy(m), counter, index, cid, 0)?.let { p ->
            val ct = runCatching { p.contentType?.substringBefore(';')?.trim() }.getOrNull() ?: "application/octet-stream"
            val name = runCatching { p.fileName }.getOrNull()?.let { decode(it) } ?: "attachment"
            AttachmentData(name, ct, p.inputStream.use { it.readBytes() })
        }
    }

    private fun findPart(part: Part, counter: IntArray, index: Int?, cid: String?, depth: Int): Part? {
        if (depth > 12) return null
        val ct = runCatching { part.contentType?.lowercase() }.getOrNull() ?: "application/octet-stream"
        val disposition = runCatching { part.disposition?.lowercase() }.getOrNull()
        val fileName = runCatching { part.fileName }.getOrNull()
        val isAttachment = disposition == Part.ATTACHMENT.lowercase() || (fileName != null && !ct.startsWith("multipart/"))
        if (ct.startsWith("multipart/")) {
            val mp = runCatching { part.content as? Multipart }.getOrNull() ?: return null
            for (i in 0 until mp.count) findPart(mp.getBodyPart(i), counter, index, cid, depth + 1)?.let { return it }
            return null
        }
        if (!isAttachment && (ct.startsWith("text/plain") || ct.startsWith("text/html"))) return null
        val isCalendar = ct.startsWith("text/calendar") || ct.startsWith("application/ics") || (fileName?.lowercase()?.endsWith(".ics") == true)
        if (isCalendar && !isAttachment) return null
        if (ct.startsWith("message/rfc822") && !isAttachment) {
            val inner = runCatching { part.content as? Message }.getOrNull()
            if (inner != null) return findPart(inner, counter, index, cid, depth + 1)
        }
        val myIndex = counter[0]++
        val myCid = runCatching { part.getHeader("Content-ID")?.firstOrNull() }.getOrNull()?.trim('<', '>', ' ')
        if (index != null && myIndex == index) return part
        if (cid != null && myCid == cid) return part
        return null
    }

    // --- mutations ------------------------------------------------------------------------

    fun setFlag(path: String, uids: List<Long>, flag: Flags.Flag, value: Boolean) = withFolder(path, Folder.READ_WRITE) { f ->
        val msgs = messagesByUid(f, uids).values.toTypedArray()
        if (msgs.isNotEmpty()) f.setFlags(msgs, Flags(flag), value)
    }

    /**
     * Moves messages and returns, for each source UID that was moved, its new UID in the
     * destination folder (null when the server does not report it and it cannot be found).
     */
    fun move(path: String, uids: List<Long>, dest: String): Map<Long, Long?> {
        if (!isImap) throw MessagingException("POP3 accounts cannot move messages on the server")
        if (path == dest) return emptyMap()
        val moved: Map<Long, Pair<String?, Long?>> = withFolder(path, Folder.READ_WRITE) { f ->
            val byUid = messagesByUid(f, uids)
            if (byUid.isEmpty()) return@withFolder emptyMap()
            val target = connect().getFolder(dest)
            if (!target.exists()) throw MessagingException("Folder not found: $dest")
            val ordered = byUid.entries.toList()
            val msgs = ordered.map { it.value }.toTypedArray()
            val ids = msgs.map { runCatching { (it as? MimeMessage)?.messageID }.getOrNull() }
            var newUids: List<Long?> = List(msgs.size) { null }
            var done = false
            if (f is IMAPFolder) {
                runCatching {
                    val res = f.moveUIDMessages(msgs, target)
                    if (res != null && res.size == msgs.size) newUids = res.map { it?.uid }
                    done = true
                }
            }
            if (!done) {
                f.copyMessages(msgs, target)
                f.setFlags(msgs, Flags(Flags.Flag.DELETED), true)
                f.expunge()
            }
            ordered.mapIndexed { i, e -> e.key to (ids[i] to newUids[i]) }.toMap()
        }
        if (moved.values.any { it.second == null && it.first != null }) {
            // Server did not report COPYUID; find the moved messages by Message-ID instead.
            val resolved = withFolder(dest) { t ->
                moved.filterValues { it.second == null && it.first != null }.mapValues { (_, v) ->
                    runCatching { t.search(jakarta.mail.search.MessageIDTerm(v.first)).lastOrNull()?.let { uidOf(t, it) } }.getOrNull()
                }
            }
            return moved.mapValues { (k, v) -> v.second ?: resolved[k] }
        }
        return moved.mapValues { it.value.second }
    }

    fun deletePermanently(path: String, uids: List<Long>): Int = withFolder(path, Folder.READ_WRITE) { f ->
        val msgs = messagesByUid(f, uids).values.toTypedArray()
        if (msgs.isEmpty()) return@withFolder 0
        f.setFlags(msgs, Flags(Flags.Flag.DELETED), true)
        if (isImap) f.expunge()
        msgs.size
    }

    fun search(path: String, query: String, limit: Int): List<RawSummary> = withFolder(path) { f ->
        val term = OrTerm(arrayOf(FromStringTerm(query), jakarta.mail.search.HeaderTerm("From", query), SubjectTerm(query), BodyTerm(query)))
        val hits = runCatching { f.search(term) }.getOrDefault(emptyArray())
        val slice = hits.takeLast(limit).toTypedArray()
        if (slice.isEmpty()) return@withFolder emptyList()
        f.fetch(slice, listProfile())
        slice.map { summarise(f, it) }.reversed()
    }

    /** Appends a message to [path] and returns its UID when the server reports it. */
    fun append(path: String, message: MimeMessage, flags: Flags): Long? {
        val s = connect()
        val folder = s.getFolder(path)
        if (!folder.exists()) folder.create(Folder.HOLDS_MESSAGES)
        message.setFlags(flags, true)
        return if (folder is IMAPFolder) {
            val res = folder.appendUIDMessages(arrayOf(message))
            res?.firstOrNull()?.uid
        } else {
            folder.appendMessages(arrayOf(message)); null
        }
    }

    // --- sending ----------------------------------------------------------------------------

    fun buildMessage(req: SendRequest, session: Session = smtpSession(), attached: List<Pair<String, ByteArray>> = emptyList()): MimeMessage {
        val fromAddress = req.from?.takeIf { it.isNotBlank() } ?: account.email
        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(fromAddress, account.displayName.ifBlank { fromAddress }, "UTF-8"))
            req.replyToAddress?.takeIf { it.isNotBlank() }?.let { replyTo = addresses(listOf(it)) }
            when (req.importance?.lowercase()) {
                "high" -> { setHeader("Importance", "High"); setHeader("X-Priority", "1 (Highest)") }
                "low" -> { setHeader("Importance", "Low"); setHeader("X-Priority", "5 (Lowest)") }
            }
            if (req.readReceipt) setHeader("Disposition-Notification-To", fromAddress)
            req.extraHeaders.forEach { (k, v) -> setHeader(k, v) }
            setRecipients(Message.RecipientType.TO, addresses(req.to))
            if (req.cc.isNotEmpty()) setRecipients(Message.RecipientType.CC, addresses(req.cc))
            if (req.bcc.isNotEmpty()) setRecipients(Message.RecipientType.BCC, addresses(req.bcc))
            setSubject(req.subject, "UTF-8")
            sentDate = java.util.Date()
            req.inReplyTo?.takeIf { it.isNotBlank() }?.let { setHeader("In-Reply-To", it) }
            req.references?.takeIf { it.isNotBlank() }?.let { setHeader("References", it) }
            setHeader("X-Mailer", "Quillbox")
        }
        val alternative = MimeMultipart("alternative").apply {
            addBodyPart(MimeBodyPart().apply { setText(req.body, "UTF-8") })
            req.html?.takeIf { it.isNotBlank() }?.let { html ->
                // Pictures pasted into the editor travel as data: URIs; mail programs expect cid: parts.
                addBodyPart(
                    if (html.contains("data:image/", ignoreCase = true)) MimeBodyPart().apply { setContent(relatedHtml(html)) }
                    else MimeBodyPart().apply { setContent(html, "text/html; charset=UTF-8") }
                )
            }
            req.calendarPart?.let { ics ->
                addBodyPart(MimeBodyPart().apply { setContent(ics, "text/calendar; charset=UTF-8; method=${req.calendarMethod ?: "REQUEST"}") })
            }
        }
        if (req.attachments.isEmpty() && attached.isEmpty() && req.calendarPart == null) {
            message.setContent(alternative)
        } else {
            val mixed = MimeMultipart("mixed")
            mixed.addBodyPart(MimeBodyPart().apply { setContent(alternative) })
            for (att in req.attachments) {
                val bytes = Base64.getDecoder().decode(att.base64.substringAfter(",", att.base64))
                mixed.addBodyPart(MimeBodyPart().apply {
                    dataHandler = DataHandler(ByteArrayDataSource(bytes, att.mimeType.ifBlank { "application/octet-stream" }))
                    fileName = att.fileName
                })
            }
            for ((name, raw) in attached) {
                mixed.addBodyPart(MimeBodyPart().apply {
                    dataHandler = DataHandler(ByteArrayDataSource(raw, "message/rfc822"))
                    fileName = name
                    disposition = Part.ATTACHMENT
                })
            }
            req.calendarPart?.let { ics ->
                mixed.addBodyPart(MimeBodyPart().apply {
                    dataHandler = DataHandler(ByteArrayDataSource(ics.toByteArray(Charsets.UTF_8), "application/ics"))
                    fileName = "invite.ics"
                    disposition = Part.ATTACHMENT
                })
            }
            message.setContent(mixed)
        }
        message.saveChanges()
        return message
    }

    /** Rewrites data: images in [html] as cid: references and returns a multipart/related body. */
    private fun relatedHtml(html: String): MimeMultipart {
        val related = MimeMultipart("related")
        val parts = ArrayList<MimeBodyPart>()
        var n = 0
        val rewritten = Regex("""src\s*=\s*(["'])data:(image/[a-zA-Z0-9.+-]+);base64,([A-Za-z0-9+/=\s]+)\1""").replace(html) { m ->
            val cid = "img${++n}.${System.nanoTime()}@quillbox"
            val bytes = runCatching { Base64.getMimeDecoder().decode(m.groupValues[3]) }.getOrNull() ?: return@replace m.value
            parts += MimeBodyPart().apply {
                dataHandler = DataHandler(ByteArrayDataSource(bytes, m.groupValues[2]))
                setHeader("Content-ID", "<$cid>")
                disposition = Part.INLINE
                fileName = "image$n." + m.groupValues[2].substringAfter('/').substringBefore('+').take(4)
            }
            "src=\"cid:$cid\""
        }
        related.addBodyPart(MimeBodyPart().apply { setContent(rewritten, "text/html; charset=UTF-8") })
        parts.forEach { related.addBodyPart(it) }
        return related
    }

    fun send(req: SendRequest): MimeMessage {
        val attached = req.attachMessages.mapNotNull { ref ->
            rawBytes(ref.folder, ref.uid)?.let { bytes ->
                val subject = runCatching { MimeMessage(session, java.io.ByteArrayInputStream(bytes)).subject }.getOrNull()
                ((subject ?: "message").replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80) + ".eml") to bytes
            }
        }
        val message = buildMessage(req, smtpSession(req.deliveryReceipt), attached)
        Transport.send(message, account.username, account.password)
        return message
    }

    /** The complete RFC 822 source of a message. */
    fun rawBytes(path: String, uid: Long): ByteArray? = withFolder(path) { f ->
        val m = messagesByUid(f, listOf(uid))[uid] ?: return@withFolder null
        java.io.ByteArrayOutputStream().also { m.writeTo(it) }.toByteArray()
    }

    private fun addresses(list: List<String>): Array<InternetAddress> =
        list.filter { it.isNotBlank() }.map { InternetAddress(it.trim()) }.toTypedArray()

    fun smtpSession(deliveryReceipt: Boolean = false): Session = Session.getInstance(outgoingProperties().apply {
        // RFC 3461 delivery status notification; only sent when the server advertises DSN.
        if (deliveryReceipt) { put("mail.smtp.dsn.notify", "SUCCESS,FAILURE,DELAY"); put("mail.smtp.dsn.ret", "HDRS") }
    }, object : Authenticator() {
        override fun getPasswordAuthentication() = PasswordAuthentication(account.username, account.password)
    })

    // --- properties --------------------------------------------------------------------------

    private fun ssl() = account.incomingSecurity.uppercase() == "SSL_TLS"

    private fun storeProtocol(): String = when {
        !isImap -> if (ssl()) "pop3s" else "pop3"
        else -> if (ssl()) "imaps" else "imap"
    }

    private fun incomingProperties(): Properties {
        val protos = if (isImap) listOf("imap", "imaps") else listOf("pop3", "pop3s")
        return Properties().apply {
            put("mail.store.protocol", storeProtocol())
            for (p in protos) {
                put("mail.$p.host", account.incomingHost)
                put("mail.$p.port", account.incomingPort.toString())
                when (account.incomingSecurity.uppercase()) {
                    "SSL_TLS" -> put("mail.$p.ssl.enable", "true")
                    "STARTTLS" -> { put("mail.$p.starttls.enable", "true"); put("mail.$p.starttls.required", "true") }
                }
                put("mail.$p.ssl.trust", account.incomingHost)
                put("mail.$p.connectiontimeout", "20000")
                put("mail.$p.timeout", "60000")
                put("mail.$p.writetimeout", "60000")
                put("mail.$p.peek", "true")
                put("mail.$p.fetchsize", "262144")
                put("mail.$p.partialfetch", "true")
                put("mail.$p.connectionpoolsize", "2")
            }
            put("mail.mime.decodetext.strict", "false")
            put("mail.mime.decodefilename", "true")
            put("mail.mime.address.strict", "false")
        }
    }

    private fun outgoingProperties(): Properties = Properties().apply {
        put("mail.transport.protocol", "smtp")
        put("mail.smtp.host", account.smtpHost)
        put("mail.smtp.port", account.smtpPort.toString())
        put("mail.smtp.auth", "true")
        when (account.smtpSecurity.uppercase()) {
            "SSL_TLS" -> put("mail.smtp.ssl.enable", "true")
            "STARTTLS" -> { put("mail.smtp.starttls.enable", "true"); put("mail.smtp.starttls.required", "true") }
        }
        put("mail.smtp.ssl.trust", account.smtpHost)
        put("mail.smtp.connectiontimeout", "20000")
        put("mail.smtp.timeout", "60000")
        put("mail.smtp.writetimeout", "60000")
    }
}
