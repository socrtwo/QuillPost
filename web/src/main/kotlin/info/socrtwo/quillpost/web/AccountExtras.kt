package info.socrtwo.quillpost.web

import info.socrtwo.quillpost.web.spam.HeaderParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// Features layered on top of the core mailbox: Focused Inbox, conversations, snooze, scheduled
// send, automatic replies, meeting invitations, folder management and the local calendar,
// contacts and tasks. Everything here is an extension of AccountContext so the core stays small.

private val AUTOMATED_LOCAL_PART = Regex("(^|[._+-])(no-?reply|do-?not-?reply|donotreply|notifications?|newsletters?|marketing|mailer-daemon|postmaster|bounces?|digest|automated)([._+-]|$)", RegexOption.IGNORE_CASE)

private fun RawSummary.header(name: String): String? = headers[name]?.firstOrNull()?.trim()

private fun RawSummary.isBulk(): Boolean {
    val precedence = header("Precedence")?.lowercase()
    val auto = header("Auto-Submitted")?.lowercase()
    return header("List-Unsubscribe") != null || header("List-Id") != null || header("Feedback-ID") != null ||
        precedence in setOf("bulk", "list", "junk") || (auto != null && auto != "no") ||
        AUTOMATED_LOCAL_PART.containsMatchIn(fromAddress.substringBefore('@'))
}

private fun senderMatches(address: String, entries: List<String>): Boolean {
    val a = address.trim().lowercase()
    val domain = HeaderParser.addressDomain(a)
    return entries.any { raw ->
        val e = raw.trim().lowercase()
        when {
            e.isEmpty() -> false
            e.contains('@') && !e.startsWith("@") -> e == a
            else -> e.removePrefix("@").let { d -> domain == d || domain.endsWith(".$d") }
        }
    }
}

/**
 * Focused Inbox: people you deal with stay Focused; newsletters, notifications and other bulk
 * mail go to Other. Your own "Always move to Focused / Other" choices win.
 */
internal fun AccountContext.isFocused(s: RawSummary): Boolean {
    val f = settings.focused
    if (!f.enabled) return true
    val from = s.fromAddress.lowercase()
    if (senderMatches(from, f.alwaysFocused)) return true
    if (senderMatches(from, f.alwaysOther)) return false
    if (from in ownAddresses()) return true
    val rep = history.reputation(from)
    if (rep.replied > 0 || rep.ham > 0) return true
    if (from in pim.contactEmails()) return true
    return !s.isBulk()
}

/** Message-IDs that tie a message to its conversation: References, In-Reply-To and its own id. */
internal fun threadIds(s: RawSummary): List<String> {
    val refs = (s.headers["References"]?.joinToString(" ") ?: "").let { Mapping.messageIds(it) }
    val irt = (s.headers["In-Reply-To"]?.joinToString(" ") ?: "").let { Mapping.messageIds(it) }
    return (refs + irt + listOfNotNull(s.messageId.takeIf { it.isNotBlank() })).distinct()
}

// --- automatic replies ---------------------------------------------------------------------------

internal fun AccountContext.shouldAutoReply(raw: RawMessage): Boolean {
    val ar = settings.autoReply
    if (!ar.enabled) return false
    val now = System.currentTimeMillis()
    if (ar.start != null && now < ar.start) return false
    if (ar.end != null && now > ar.end) return false
    val s = raw.summary
    val from = s.fromAddress.lowercase()
    if (!from.contains('@') || from in ownAddresses()) return false
    if (s.isBulk()) return false
    if (s.headers["X-Auto-Response-Suppress"]?.any { it.contains("all", true) || it.contains("oof", true) } == true) return false
    if (ar.contactsOnly && from !in pim.contactEmails()) return false
    val last = pim.autoRepliedAt(from)
    return last == null || now - last >= ar.oncePerDays.coerceAtLeast(1) * 86_400_000L
}

internal suspend fun AccountContext.sendAutoReply(raw: RawMessage) {
    val ar = settings.autoReply
    val s = raw.summary
    val html = ar.html.ifBlank { "I am away and will reply when I return." }
    val refs = (threadIds(s)).joinToString(" ")
    io {
        mail.send(SendRequest(
            to = listOf(s.fromAddress), subject = ar.subject.ifBlank { "Automatic reply: ${s.subject}" },
            body = HeaderParser.htmlToText(html), html = html,
            inReplyTo = s.messageId.takeIf { it.isNotBlank() }, references = refs.ifBlank { null },
            extraHeaders = mapOf("Auto-Submitted" to "auto-replied", "X-Auto-Response-Suppress" to "All", "Precedence" to "auto_reply")
        ))
    }
    pim.markAutoReplied(s.fromAddress)
}

// --- meeting invitations ---------------------------------------------------------------------------

private fun zoneFor(tz: String?): ZoneId = tz?.let { ICal.resolveZone(it) } ?: ZoneId.systemDefault()

/** Reads the meeting request / reply / cancellation in a message and relates it to the calendar. */
internal fun AccountContext.inviteFrom(ics: String, tz: String): InviteDto? {
    val parsed = ICal.toEvents(ics, zoneFor(tz))
    val ev = parsed.events.firstOrNull { it.recurrenceId == null } ?: parsed.events.firstOrNull() ?: return null
    val method = parsed.method ?: "PUBLISH"
    val own = ownAddresses()
    val sameUid = pim.eventsByUid(ev.uid)
    var existing = sameUid.firstOrNull { it.recurrenceId == ev.recurrenceId } ?: sameUid.firstOrNull()
    val isOrganizer = ev.organizer?.email?.lowercase() in own
    var replyFrom: String? = null
    var replyStatus: String? = null
    if (method == "REPLY") {
        val who = ev.attendees.firstOrNull()
        replyFrom = who?.email; replyStatus = who?.status
        // Track the answer on our copy of the meeting, as the organizer's calendar would.
        if (who != null && existing != null && pim.calendar(existing.calendarId)?.readOnly != true) {
            val updated = existing.copy(attendees = existing.attendees.map { if (it.email.equals(who.email, true)) it.copy(status = who.status) else it }
                .let { list -> if (list.any { it.email.equals(who.email, true) }) list else list + who })
            if (updated != existing) existing = runCatching { pim.upsertEvent(updated) }.getOrDefault(existing)
        }
    }
    val mine = ev.attendees.firstOrNull { it.email.lowercase() in own }
    return InviteDto(method, ev.copy(myStatus = mine?.status), replyFrom, replyStatus, existing?.id, existing?.myStatus, isOrganizer)
}

private fun describeWhen(e: EventDto): String {
    return runCatching {
        if (e.allDay) {
            val s = LocalDate.parse(e.start.take(10)); val end = LocalDate.parse(e.end.take(10)).minusDays(1)
            val f = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.US)
            if (end.isAfter(s)) "${s.format(f)} – ${end.format(f)} (all day)" else "${s.format(f)} (all day)"
        } else {
            val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
            val s = LocalDateTime.parse(e.start, fmt); val end = LocalDateTime.parse(e.end, fmt)
            val day = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy h:mm a", Locale.US); val t = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
            val endText = if (end.toLocalDate() == s.toLocalDate()) end.format(t) else end.format(day)
            "${s.format(day)} – $endText (${e.tz})"
        }
    }.getOrDefault(e.start)
}

private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/** Sends (or cancels) the invitation for one of our events to its attendees. */
suspend fun AccountContext.sendInvite(req: InviteSendRequest): ApiStatus {
    touch()
    val e0 = pim.event(req.eventId) ?: throw IllegalArgumentException("Event not found")
    require(e0.attendees.isNotEmpty()) { "Add at least one attendee first" }
    val cancel = req.method.uppercase() == "CANCEL"
    val me = AttendeeDto(account.email, account.displayName)
    // Every resend after the first is an update: bump SEQUENCE so calendars replace the old copy.
    val e = e0.copy(organizer = me, sequence = if (e0.organizer != null) e0.sequence + 1 else e0.sequence)
    if (!cancel) pim.upsertEvent(e)
    val zone = zoneFor(e.tz)
    val method = if (cancel) "CANCEL" else "REQUEST"
    val ics = ICal.calendar(listOf(e.copy(cancelled = cancel)), zone, method)
    val lines = listOfNotNull(
        "When: " + describeWhen(e),
        e.location.takeIf { it.isNotBlank() }?.let { "Where: $it" },
        e.onlineUrl.takeIf { it.isNotBlank() }?.let { "Join online: $it" },
        recurrenceLine(e)
    )
    val plain = (listOf(req.comment).filter { it.isNotBlank() } + lines + listOf("", e.notes)).joinToString("\n").trim()
    val html = (if (req.comment.isNotBlank()) "<p>${esc(req.comment).replace("\n", "<br>")}</p>" else "") +
        "<table style=\"border-left:4px solid #0f6cbd;padding-left:10px\">" + lines.joinToString("") { "<tr><td>${esc(it)}</td></tr>" } + "</table>" +
        (if (e.notes.isNotBlank()) "<p>${esc(e.notes).replace("\n", "<br>")}</p>" else "")
    deliver(SendRequest(
        to = e.attendees.map { it.email }, subject = (if (cancel) "Canceled: " else "") + e.title,
        body = plain, html = html, calendarPart = ics, calendarMethod = method
    ))
    return ApiStatus(if (cancel) "canceled" else "sent")
}

private fun recurrenceLine(e: EventDto): String? = e.recurrence?.let { "Repeats: " + ICal.describe(it) }

/** Accept / tentatively accept / decline a meeting request, or remove a cancelled meeting. */
suspend fun AccountContext.respondInvite(req: InviteRespondRequest): EventDto? {
    touch()
    val raw = io { mail.getMessage(req.folder, req.uid) } ?: throw IllegalArgumentException("Message not found")
    val ics = raw.calendar ?: throw IllegalArgumentException("This message has no meeting request")
    val zone = zoneFor(req.tz)
    val parsed = ICal.toEvents(ics, zone)
    val events = parsed.events
    val main = events.firstOrNull { it.recurrenceId == null } ?: events.firstOrNull() ?: throw IllegalArgumentException("The meeting request is empty")
    val own = ownAddresses()
    val response = req.response.uppercase()
    val me = (main.attendees.firstOrNull { it.email.lowercase() in own } ?: AttendeeDto(account.email, account.displayName)).copy(status = if (response == "REMOVE") "DECLINED" else response)
    var saved: EventDto? = null
    when (response) {
        "REMOVE", "DECLINED" -> pim.removeEvents { it.uid == main.uid && (main.recurrenceId == null || it.recurrenceId == main.recurrenceId) }
        "ACCEPTED", "TENTATIVE" -> {
            for (e in events) {
                val existing = pim.eventsByUid(e.uid).firstOrNull { it.recurrenceId == e.recurrenceId }
                val cal = existing?.calendarId ?: req.calendarId?.takeIf { pim.calendar(it)?.readOnly == false } ?: PimStore.DEFAULT_CALENDAR
                val s = pim.upsertEvent(e.copy(
                    id = existing?.id ?: "", calendarId = cal, myStatus = response,
                    showAs = if (response == "TENTATIVE") "tentative" else e.showAs,
                    reminderMinutes = existing?.reminderMinutes ?: e.reminderMinutes ?: 15,
                    attendees = e.attendees.map { if (it.email.lowercase() in own) it.copy(status = response) else it }
                ))
                if (e === main) saved = s
            }
        }
        else -> throw IllegalArgumentException("Unknown response $response")
    }
    val organizer = main.organizer
    if (req.sendResponse && response != "REMOVE" && organizer != null && organizer.email.lowercase() !in own && organizer.email.contains('@')) {
        val word = when (response) { "ACCEPTED" -> "Accepted"; "TENTATIVE" -> "Tentative"; else -> "Declined" }
        val replyIcs = ICal.calendar(listOf(main), zone, "REPLY", onlyAttendee = me)
        io {
            mail.send(SendRequest(
                to = listOf(organizer.email), subject = "$word: ${main.title}",
                body = req.comment.ifBlank { "$word." }, calendarPart = replyIcs, calendarMethod = "REPLY"
            ))
        }
    }
    // Like a desktop client, file the request away once it has been answered.
    if (mail.isImap) runCatching {
        val trash = io { mail.ensureFolderForRole("trash") }
        if (trash != req.folder) io { mail.move(req.folder, listOf(req.uid), trash) }
    }
    bump()
    return saved
}

/** Answers an invitation from the calendar (the event was added from a meeting request earlier). */
suspend fun AccountContext.respondEvent(req: EventRespondRequest): EventDto? {
    touch()
    val e = pim.event(req.eventId) ?: throw IllegalArgumentException("Event not found")
    val response = req.response.uppercase()
    require(response in setOf("ACCEPTED", "TENTATIVE", "DECLINED")) { "Unknown response $response" }
    val own = ownAddresses()
    val me = (e.attendees.firstOrNull { it.email.lowercase() in own } ?: AttendeeDto(account.email, account.displayName)).copy(status = response)
    val saved = if (response == "DECLINED") { pim.removeEvents { it.uid == e.uid }; null }
        else pim.upsertEvent(e.copy(myStatus = response, showAs = if (response == "TENTATIVE") "tentative" else if (e.showAs == "tentative") "busy" else e.showAs,
            attendees = e.attendees.map { if (it.email.lowercase() in own) it.copy(status = response) else it }))
    val organizer = e.organizer
    if (req.sendResponse && organizer != null && organizer.email.contains('@') && organizer.email.lowercase() !in own) {
        val word = when (response) { "ACCEPTED" -> "Accepted"; "TENTATIVE" -> "Tentative"; else -> "Declined" }
        val ics = ICal.calendar(listOf(e), zoneFor(e.tz), "REPLY", onlyAttendee = me)
        io { mail.send(SendRequest(to = listOf(organizer.email), subject = "$word: ${e.title}", body = req.comment.ifBlank { "$word." }, calendarPart = ics, calendarMethod = "REPLY")) }
    }
    bump()
    return saved
}

suspend fun AccountContext.translate(req: TranslateRequest): TranslateResponse {
    touch()
    val raw = io { mail.getMessage(req.folder, req.uid) } ?: throw IllegalArgumentException("Message not found")
    val text = raw.bodyText.ifBlank { raw.bodyHtml?.let { HeaderParser.htmlToText(it) } ?: "" }
    require(text.isNotBlank()) { "This message has no text to translate" }
    val out = withContext(Dispatchers.IO) { Ollama(settings.ollama).translate(text, req.language) }
    return TranslateResponse(req.language, out)
}

/** All attachments of a message as one zip file. */
suspend fun AccountContext.attachmentsZip(folder: String, uid: Long, out: java.io.OutputStream): Int {
    touch()
    val raw = io { mail.getMessage(folder, uid) } ?: throw IllegalArgumentException("Message not found")
    val atts = raw.attachments.filter { !it.inline }
    val zip = java.util.zip.ZipOutputStream(out)
    val used = HashSet<String>()
    var n = 0
    for (a in atts) {
        val data = io { mail.getAttachment(folder, uid, a.index, null) } ?: continue
        var name = data.fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "attachment-${a.index}" }
        var k = 1
        while (!used.add(name.lowercase())) name = name.substringBeforeLast('.') + " (${k++})" + (if (name.contains('.')) "." + name.substringAfterLast('.') else "")
        zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write(data.bytes); zip.closeEntry(); n++
    }
    zip.finish(); zip.flush()
    return n
}

// --- scheduler: scheduled sends, snoozes, calendar subscriptions -----------------------------------

internal fun AccountContext.startScheduler() {
    scope.launch {
        delay(5_000)
        while (isActive) {
            runCatching { tick() }
            delay(30_000)
        }
    }
}

internal suspend fun AccountContext.tick() {
    val now = System.currentTimeMillis()
    for (item in pim.dueOutbox(now)) {
        runCatching { deliver(item.request) }
            .onSuccess { pim.removeOutbox(item.id); bump() }
            .onFailure { pim.outboxFailed(item.id, it.message ?: "send failed"); bump() }
    }
    for ((id, m) in pim.dueSnoozes(now)) runCatching { returnSnoozed(id, m) }
    for (cal in pim.data().calendars) {
        if (cal.kind == "subscription" && now - cal.lastSync > 6 * 3_600_000L) runCatching { refreshSubscription(cal.id) }
    }
}

private suspend fun AccountContext.returnSnoozed(messageId: String, m: MessageMetaDto) {
    val snoozed = mail.folderForRole("snoozed")
    val back = m.snoozedFrom?.takeIf { it.isNotBlank() && it != snoozed } ?: MailSession.INBOX
    if (snoozed != null) {
        val uid = io { mail.findByMessageId(snoozed, messageId) }
        if (uid != null) {
            val moved = io { mail.move(snoozed, listOf(uid), back) }
            // A returning snoozed message shows up as new.
            moved.values.firstOrNull()?.let { nu -> runCatching { io { mail.setFlag(back, listOf(nu), jakarta.mail.Flags.Flag.SEEN, false) } } }
        }
    }
    pim.setSnooze(messageId, null, null)
    bump()
}

/** Snoozes messages into the Snoozed folder until [SnoozeRequest.until], or brings them back now. */
suspend fun AccountContext.snooze(req: SnoozeRequest): BatchResult {
    touch()
    if (!mail.isImap) throw IllegalArgumentException("Snooze needs an IMAP account")
    var ok = 0
    val errors = ArrayList<String>()
    val target = io { mail.ensureFolderForRole("snoozed") }
    for ((folder, refs) in req.items.groupBy { it.folder }) {
        val sums = io { mail.summaries(folder, refs.map { it.uid }) }
        if (req.until == null) {
            for (s in sums) {
                val m = pim.meta(s.messageId)
                val back = m?.snoozedFrom?.takeIf { it != target } ?: MailSession.INBOX
                runCatching { io { mail.move(folder, listOf(s.uid), back) } }.onSuccess { ok++ }.onFailure { errors += it.message.orEmpty() }
                pim.setSnooze(s.messageId, null, null)
            }
        } else {
            val withId = sums.filter { it.messageId.isNotBlank() }
            if (withId.size < sums.size) errors += "${sums.size - withId.size} message(s) have no Message-ID and cannot be snoozed"
            for (s in withId) pim.setSnooze(s.messageId, req.until, folder, s.subject, s.fromAddress)
            if (folder != target && withId.isNotEmpty()) runCatching { io { mail.move(folder, withId.map { it.uid }, target) } }
                .onSuccess { ok += withId.size }.onFailure { errors += it.message.orEmpty() }
            else ok += withId.size
        }
    }
    bump()
    return BatchResult(ok, req.items.size - ok, errors.filter { it.isNotBlank() })
}

/** Ignore conversation: delete it now and send later messages in it straight to Deleted Items. */
suspend fun AccountContext.ignore(req: IgnoreRequest): BatchResult {
    touch()
    val ids = LinkedHashSet<String>()
    for ((folder, refs) in req.items.groupBy { it.folder }) io { mail.summaries(folder, refs.map { it.uid }) }.forEach { ids.addAll(threadIds(it)) }
    pim.setIgnored(ids, req.ignore)
    return if (req.ignore) delete(DeleteRequest(req.items)) else BatchResult(req.items.size, 0)
}

fun AccountContext.updateMeta(req: MetaUpdateRequest): Map<String, MessageMetaDto> {
    touch()
    val out = pim.updateMeta(req)
    bump()
    return out
}

// --- scheduled sends ----------------------------------------------------------------------------------

fun AccountContext.outbox(): List<OutboxItemDto> = pim.outboxItems().map { OutboxItemDto(it.id, it.sendAt, it.request.to, it.request.subject, it.created, it.error) }

/** Cancels a scheduled message; with [toDrafts] it is saved to Drafts for editing. */
suspend fun AccountContext.cancelScheduled(id: String, toDrafts: Boolean): DraftResponse? {
    touch()
    val item = pim.removeOutbox(id) ?: throw IllegalArgumentException("That message has already been sent")
    bump()
    if (!toDrafts) return null
    val r = item.request
    return saveDraft(DraftRequest(null, r.to, r.cc, r.bcc, r.subject, r.body, r.html, r.inReplyTo, r.references, r.attachments, r.importance))
}

// --- folders, search, conversations ----------------------------------------------------------------------

suspend fun AccountContext.createFolder(req: FolderCreateRequest): FolderPathRequest {
    touch(); val p = io { mail.createFolder(req.name, req.parent) }; bump(); return FolderPathRequest(p)
}

suspend fun AccountContext.renameFolder(req: FolderRenameRequest): FolderPathRequest {
    touch(); val p = io { mail.renameFolder(req.path, req.name) }
    settingsReplaceFavorite(req.path, p); bump(); return FolderPathRequest(p)
}

suspend fun AccountContext.moveFolder(req: FolderMoveRequest): FolderPathRequest {
    touch(); val p = io { mail.moveFolder(req.path, req.parent) }
    settingsReplaceFavorite(req.path, p); bump(); return FolderPathRequest(p)
}

private fun AccountContext.settingsReplaceFavorite(old: String, new: String) {
    if (settings.favorites.contains(old)) updateSettings(settings.copy(favorites = settings.favorites.map { if (it == old) new else it }))
}

suspend fun AccountContext.deleteFolder(req: FolderPathRequest): BatchResult {
    touch()
    val trash = runCatching { io { mail.ensureFolderForRole("trash") } }.getOrNull()
    require(req.path != trash) { "Deleted Items cannot be deleted; empty it instead" }
    val n = io { mail.deleteFolder(req.path, trash) }
    if (settings.favorites.contains(req.path)) updateSettings(settings.copy(favorites = settings.favorites - req.path))
    bump()
    return BatchResult(n, 0)
}

suspend fun AccountContext.emptyFolder(req: FolderPathRequest): BatchResult {
    touch()
    val trash = runCatching { io { mail.ensureFolderForRole("trash") } }.getOrNull()
    val junk = mail.folderForRole("junk")
    val n = io { mail.emptyFolder(req.path, permanent = req.path == trash || req.path == junk, trash = trash) }
    bump()
    return BatchResult(n, 0)
}

suspend fun AccountContext.markAllRead(req: FolderPathRequest): BatchResult {
    touch(); val n = io { mail.markAllRead(req.path) }; bump(); return BatchResult(n, 0)
}

/** Advanced search in one folder, or across every folder except Junk and Deleted Items. */
suspend fun AccountContext.searchAdvanced(folder: String, c: SearchCriteria, allFolders: Boolean, limit: Int): SearchResponse {
    touch()
    if (!allFolders || !mail.isImap) {
        val raws = io { mail.searchAdvanced(folder, c, limit) }
        return SearchResponse(folder, c.text, raws.map { summary(it, folder) })
    }
    val folders = io { mail.listFolders() }.filter { it.role !in setOf("junk", "trash") }
    val hits = ArrayList<MessageSummaryDto>()
    for (f in folders) {
        val raws = try { io { mail.searchAdvanced(f.path, c, limit) } } catch (e: Exception) {
            org.slf4j.LoggerFactory.getLogger("quillpost").warn("Search in ${f.path} failed: ${e.message}"); emptyList()
        }
        raws.forEach { hits += summary(it, f.path) }
    }
    return SearchResponse("*", c.text, hits.sortedByDescending { it.date }.take(limit))
}

/** Every message of the conversation [uid] belongs to, from this folder, the Inbox, Sent Items and Archive. */
suspend fun AccountContext.thread(folder: String, uid: Long): ThreadResponse {
    touch()
    val base = io { mail.summaries(folder, listOf(uid)) }.firstOrNull() ?: return ThreadResponse(emptyList())
    if (!mail.isImap) return ThreadResponse(listOf(summary(base, folder)))
    val ids = threadIds(base)
    val paths = listOfNotNull(folder, MailSession.INBOX, mail.folderForRole("sent"), mail.folderForRole("archive")).distinct()
    val found = io { mail.threadMessages(paths, ids) }
    val seen = HashSet<String>()
    val out = ArrayList<MessageSummaryDto>()
    // Prefer the copy in the folder being viewed.
    for ((p, s) in (listOf(folder to base) + found).sortedBy { if (it.first == folder) 0 else 1 }) {
        val k = s.messageId.ifBlank { "$p:${s.uid}" }
        if (seen.add(k)) out += summary(s, p)
    }
    return ThreadResponse(out.sortedBy { it.date })
}

// --- receipts, quota, raw source, import ---------------------------------------------------------------

suspend fun AccountContext.sendReadReceipt(req: MdnRequest): ApiStatus {
    touch()
    val s = io { mail.summaries(req.folder, listOf(req.uid)) }.firstOrNull() ?: throw IllegalArgumentException("Message not found")
    val to = s.headers["Disposition-Notification-To"]?.firstOrNull()?.trim() ?: throw IllegalArgumentException("No read receipt was requested")
    io { mail.sendMdn(s, to) }
    io { mail.setKeyword(req.folder, listOf(req.uid), "\$MDNSent", true) }
    bump()
    return ApiStatus("sent")
}

/** Records that the user declined a read receipt, so it is not asked again. */
suspend fun AccountContext.declineReadReceipt(req: MdnRequest): ApiStatus {
    io { mail.setKeyword(req.folder, listOf(req.uid), "\$MDNSent", true) }
    return ApiStatus("ok")
}

suspend fun AccountContext.quota(): QuotaDto? = runCatching { io { mail.quota() } }.getOrNull()

suspend fun AccountContext.rawMessage(folder: String, uid: Long): ByteArray? {
    touch()
    if (LocalArchives.isArchive(folder)) return withContext(Dispatchers.IO) { archives.raw(folder, uid) }
    return io { mail.rawBytes(folder, uid) }
}

suspend fun AccountContext.importMessages(folder: String, bytes: ByteArray): ImportResult {
    touch()
    val messages = Mbox.split(bytes).filter { it.isNotEmpty() }
    if (messages.isEmpty()) return ImportResult(0, errors = listOf("No messages found in the file"))
    val n = io { mail.appendRaw(folder, messages) }
    bump()
    return ImportResult(n, skipped = messages.size - n)
}

suspend fun AccountContext.exportMbox(folder: String, out: java.io.OutputStream): Int =
    if (LocalArchives.isArchive(folder)) withContext(Dispatchers.IO) { archives.exportMbox(folder, out) } else io { mail.exportMbox(folder, out) }

// --- calendar & contacts import/export, subscriptions -----------------------------------------------------

fun AccountContext.importIcs(text: String, calendarId: String?, tz: String): ImportResult {
    val cal = pim.calendar(calendarId ?: PimStore.DEFAULT_CALENDAR) ?: throw IllegalArgumentException("Unknown calendar")
    require(!cal.readOnly) { "\"${cal.name}\" is read-only" }
    val parsed = ICal.toEvents(text, zoneFor(tz), cal.id)
    if (parsed.events.isEmpty()) return ImportResult(0, errors = parsed.warnings.ifEmpty { listOf("No events found in the file") })
    return pim.importEvents(cal.id, parsed.events.filter { !it.cancelled }).copy(errors = parsed.warnings)
}

fun AccountContext.exportIcs(calendarId: String?): String {
    val data = pim.data()
    val cals = if (calendarId.isNullOrBlank()) data.calendars else data.calendars.filter { it.id == calendarId }
    val ids = cals.map { it.id }.toSet()
    return ICal.calendar(data.events.filter { it.calendarId in ids }, ZoneId.systemDefault(), name = cals.singleOrNull()?.name ?: "Anne's Post")
}

fun AccountContext.importContacts(text: String, format: String): ImportResult {
    val list = when (format.lowercase()) {
        "csv" -> VCard.parseCsv(text)
        else -> VCard.parse(text)
    }
    if (list.isEmpty()) return ImportResult(0, errors = listOf("No contacts found in the file"))
    return pim.importContacts(list)
}

fun AccountContext.exportContacts(format: String): String {
    val contacts = pim.data().contacts.sortedBy { it.bestName().lowercase() }
    return if (format.lowercase() == "csv") VCard.writeCsv(contacts) else VCard.write(contacts)
}

/**
 * Internet calendars may point anywhere, so on a shared server only public addresses may be
 * fetched. The desktop and Android apps (single user) and QUILLPOST_ALLOW_PRIVATE_URLS=true
 * also allow calendars on the local network.
 */
private fun privateUrlsAllowed(): Boolean =
    qpProp("quillpost.dataDir") != null || qpEnv("QUILLPOST_ALLOW_PRIVATE_URLS")?.equals("true", true) == true

private fun checkUrl(url: URL) {
    require(url.protocol == "https" || url.protocol == "http") { "Only http and https calendar addresses are supported" }
    if (privateUrlsAllowed()) return
    val addrs = runCatching { InetAddress.getAllByName(url.host).toList() }.getOrElse { throw IllegalArgumentException("Cannot resolve ${url.host}") }
    for (a in addrs) {
        val b = a.address
        val uniqueLocalV6 = b.size == 16 && (b[0].toInt() and 0xfe) == 0xfc
        require(!(a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress || a.isAnyLocalAddress || a.isMulticastAddress || uniqueLocalV6)) {
            "Calendars on private network addresses cannot be subscribed to from this server"
        }
    }
}

private fun fetchCalendar(address: String): String {
    var current = URL(address.trim().replaceFirst(Regex("^webcals?://", RegexOption.IGNORE_CASE), "https://"))
    repeat(5) {
        checkUrl(current)
        val conn = (current.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 30_000; instanceFollowRedirects = false
            setRequestProperty("User-Agent", "QuillPost/1.4 (+https://github.com/socrtwo/QuillPost)")
            setRequestProperty("Accept", "text/calendar, */*")
        }
        try {
            val status = conn.responseCode
            if (status in 300..399) {
                conn.getHeaderField("Location")?.let { current = URL(current, it); return@repeat }
            }
            if (status != 200) throw IllegalArgumentException("The calendar server answered HTTP $status")
            val bytes = conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(65536)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > 25 * 1024 * 1024) throw IllegalArgumentException("The calendar is larger than 25 MB")
                }
                out.toByteArray()
            }
            return String(bytes, Charsets.UTF_8)
        } finally { conn.disconnect() }
    }
    throw IllegalArgumentException("Too many redirects")
}

suspend fun AccountContext.subscribe(req: SubscribeRequest): CalendarDto {
    touch()
    val url = req.url.trim()
    require(url.isNotBlank()) { "Enter the calendar's address (ends in .ics)" }
    val cal = CalendarDto(PimStore.newId("sub"), req.name.ifBlank { URL(url.replaceFirst(Regex("^webcals?://", RegexOption.IGNORE_CASE), "https://")).host }, req.color, "subscription", url, true, true)
    // Validate before saving so a bad address is reported straight away.
    val text = withContext(Dispatchers.IO) { fetchCalendar(url) }
    pim.saveCalendar(cal)
    return applySubscription(cal, text)
}

suspend fun AccountContext.refreshSubscription(id: String): CalendarDto {
    val cal = pim.calendar(id) ?: throw IllegalArgumentException("Unknown calendar")
    require(cal.kind == "subscription" && cal.url != null) { "Only subscribed calendars can be refreshed" }
    return runCatching { withContext(Dispatchers.IO) { fetchCalendar(cal.url) } }
        .fold({ applySubscription(cal, it) }, { e -> pim.saveCalendar(cal.copy(lastSync = System.currentTimeMillis(), error = e.message)).also { bump() } })
}

private fun AccountContext.applySubscription(cal: CalendarDto, text: String): CalendarDto {
    // Floating and UTC times are kept in the calendar's own zone (X-WR-TIMEZONE) or UTC; the
    // client converts to the viewer's zone, so the same feed is right for everyone.
    val wr = ICal.parse(text).firstOrNull()?.first("X-WR-TIMEZONE")?.value?.let { ICal.resolveZone(it) } ?: java.time.ZoneOffset.UTC
    val parsed = ICal.toEvents(text, wr, cal.id)
    pim.replaceCalendarEvents(cal.id, parsed.events.filter { !it.cancelled }.map { it.copy(id = "") })
    val saved = pim.saveCalendar(cal.copy(lastSync = System.currentTimeMillis(), error = if (parsed.events.isEmpty()) "No events found" else null))
    bump()
    return saved
}
