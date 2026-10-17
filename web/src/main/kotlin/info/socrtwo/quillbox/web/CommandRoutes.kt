package info.socrtwo.quillbox.web

import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.Collections
import java.util.WeakHashMap

/**
 * Endpoints for the command set (`/api/x/…`): redirect and bounce, tags (IMAP keywords) and flags,
 * compact, mark read by date, archive by year/month and archive old mail, folder statistics and
 * copying, large messages, recover \Deleted messages, attachment shelf, stripping attachments,
 * editing a stored subject, mailing lists, one-click unsubscribe, the header inspector, "waiting for
 * reply", Send Again, flushing the outbox, conversation routes (mute / always move) and a small
 * per-account state store for client features (screener, VIPs, saved searches, snippets…).
 */
internal fun Routing.commandRoutes(sessions: Sessions) {
    fun ApplicationCall.ctx(): AccountContext = sessions.get(token()).also { it.touch(); CommandJobs.ensure(it) }
    fun ApplicationCall.q(name: String): String? = request.queryParameters[name]?.takeIf { it.isNotBlank() }
    fun ApplicationCall.uid(): Long = q("uid")?.toLongOrNull() ?: throw IllegalArgumentException("uid is required")
    fun ApplicationCall.folder(): String = q("folder") ?: MailSession.INBOX

    post("/api/x/resend") {
        call.handle {
            val ctx = call.ctx(); val r = call.receive<ResendRequest>()
            val n = ctx.io { ctx.mail.resend(r.folder, r.uid, r.to, r.mode) }
            ctx.bump(); ApiStatus("sent to $n")
        }
    }
    post("/api/x/flags") {
        call.handle {
            val ctx = call.ctx(); val r = call.receive<FlagsEditRequest>()
            var n = 0
            for ((f, refs) in r.items.groupBy { it.folder }) n += ctx.io { ctx.mail.editFlags(f, refs.map { it.uid }, r.add, r.remove) }
            ctx.bump(); BatchResult(n, r.items.size - n)
        }
    }
    get("/api/x/keywords") {
        call.handle {
            val ctx = call.ctx()
            val (allowed, used) = ctx.io { ctx.mail.folderKeywords(call.folder()) }
            KeywordsDto(allowed, used)
        }
    }
    post("/api/x/compact") { call.handle { val ctx = call.ctx(); val r = call.receive<FolderPathRequest>(); val n = ctx.io { ctx.mail.compact(r.path) }; ctx.bump(); BatchResult(n, 0) } }
    post("/api/x/markread-range") {
        call.handle { val ctx = call.ctx(); val r = call.receive<FolderRangeRequest>(); val n = ctx.io { ctx.mail.markReadRange(r.path, r.since, r.before) }; ctx.bump(); BatchResult(n, 0) }
    }
    post("/api/x/archive") { call.handle { call.ctx().archiveBy(call.receive()) } }
    get("/api/x/folder-stats") { call.handle { val ctx = call.ctx(); ctx.io { ctx.mail.folderStats(call.folder()) } } }
    post("/api/x/folder-copy") {
        call.handle { val ctx = call.ctx(); val r = call.receive<FolderCopyRequest>(); val (p, n) = ctx.io { ctx.mail.copyFolder(r.path, r.parent) }; ctx.bump(); FolderCopyResult(p, n) }
    }
    get("/api/x/large") {
        call.handle {
            val ctx = call.ctx()
            val min = (call.q("minKb")?.toIntOrNull() ?: 1024).coerceAtLeast(1) * 1024
            val limit = (call.q("limit")?.toIntOrNull() ?: 50).coerceIn(1, 300)
            val paths = if (call.q("scope") == "all" && ctx.mail.isImap) ctx.io { ctx.mail.listFolders() }.map { it.path } else listOf(call.folder())
            val out = ArrayList<MessageSummaryDto>()
            for (p in paths) runCatching { ctx.io { ctx.mail.largeMessages(p, min, limit) } }.getOrDefault(emptyList()).forEach { out += ctx.summary(it, p) }
            SearchResponse("*", "", out.sortedByDescending { it.size }.take(limit))
        }
    }
    get("/api/x/deleted") {
        call.handle { val ctx = call.ctx(); val f = call.folder(); SearchResponse(f, "", ctx.io { ctx.mail.deletedSummaries(f, 300) }.map { ctx.summary(it, f) }) }
    }
    post("/api/x/shelf") { call.handle { call.ctx().shelf(call.receive<ShelfRequest>().items) } }
    post("/api/x/rewrite") {
        call.handle { val ctx = call.ctx(); val r = call.receive<RewriteRequest>(); val out = ctx.io { ctx.mail.rewrite(r.folder, r.uid, r.subject?.trim(), r.stripAttachments) }; ctx.bump(); out }
    }
    get("/api/x/lists") {
        call.handle { val ctx = call.ctx(); ctx.io { ctx.mail.mailingLists(call.folder(), (call.q("limit")?.toIntOrNull() ?: 300).coerceIn(10, 500)) } }
    }
    post("/api/x/unsubscribe") { call.handle { val r = call.receive<MessageRef>(); call.ctx().unsubscribe(r.folder, r.uid) } }
    get("/api/x/inspect") {
        call.handle { val ctx = call.ctx(); val hs = ctx.io { ctx.mail.headerList(call.folder(), call.uid()) }; HeaderInspector.inspect(hs) }
    }
    get("/api/x/original") { call.handle { val ctx = call.ctx(); ctx.io { ctx.mail.bouncedOriginal(call.folder(), call.uid()) } } }
    get("/api/x/awaiting") { call.handle { call.ctx().awaiting((call.q("days")?.toIntOrNull() ?: 14).coerceIn(1, 90)) } }
    post("/api/x/outbox/flush") { call.handle { call.ctx().flushOutbox() } }
    post("/api/x/routes") { call.handle { call.ctx().addRoute(call.receive()) } }
    get("/api/x/routes") { call.handle { CommandState.routes(call.ctx()) } }

    get("/api/x/state/{name}") {
        call.handle { CommandState.read(call.ctx(), call.parameters["name"] ?: "") ?: JsonNull }
    }
    put("/api/x/state/{name}") {
        call.handle {
            val text = call.receiveText()
            require(text.length <= 1_000_000) { "Too large" }
            val el = Json.parseToJsonElement(text)
            CommandState.write(call.ctx(), call.parameters["name"] ?: "", el)
            ApiStatus("saved")
        }
    }
}

@Serializable data class KeywordsDto(val allowed: Boolean, val keywords: List<String>)
@Serializable data class FolderCopyResult(val path: String, val copied: Int)

/** Small per-account JSON documents for client features, stored next to the account's settings. */
internal object CommandState {
    private val nameRe = Regex("^[a-z][a-zA-Z0-9-]{1,30}$")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun file(ctx: AccountContext, name: String): File {
        require(nameRe.matches(name)) { "Bad state name" }
        return File(ctx.store.accountDir(ctx.account.email), "x-state-$name.json")
    }

    @Synchronized fun read(ctx: AccountContext, name: String): JsonElement? {
        val f = file(ctx, name)
        if (!f.exists()) return null
        return runCatching { Json.parseToJsonElement(f.readText()) }.getOrNull()
    }

    @Synchronized fun write(ctx: AccountContext, name: String, value: JsonElement) {
        ctx.store.writeAtomic(file(ctx, name), value.toString())
    }

    @Synchronized fun routes(ctx: AccountContext): List<ConversationRouteDto> =
        read(ctx, "routes")?.let { runCatching { json.decodeFromJsonElement(ListSerializer(ConversationRouteDto.serializer()), it) }.getOrNull() } ?: emptyList()

    @Synchronized fun saveRoutes(ctx: AccountContext, list: List<ConversationRouteDto>) =
        write(ctx, "routes", json.encodeToJsonElement(ListSerializer(ConversationRouteDto.serializer()), list.takeLast(500)))
}

/** One background job per signed-in account: files new replies of muted / routed conversations. */
internal object CommandJobs {
    private val jobs: MutableMap<AccountContext, Job> = Collections.synchronizedMap(WeakHashMap())

    fun ensure(ctx: AccountContext) {
        if (!ctx.mail.isImap) return
        synchronized(jobs) {
            if (jobs[ctx]?.isActive == true) return
            jobs[ctx] = ctx.scope.launch {
                delay(15_000)
                while (isActive) {
                    runCatching { ctx.applyRoutes() }
                    delay(60_000)
                }
            }
        }
    }
}

/** Moves Inbox messages that belong to a muted or routed conversation. Returns the number moved. */
internal suspend fun AccountContext.applyRoutes(): Int {
    val routes = CommandState.routes(this)
    if (routes.isEmpty()) return 0
    var moved = 0
    for ((dest, group) in routes.groupBy { it.dest }) {
        val target = if (dest == "__archive") runCatching { io { mail.ensureFolderForRole("archive") } }.getOrNull() ?: continue else dest
        val ids = group.flatMap { it.ids }.distinct()
        for (chunk in ids.chunked(12)) {
            val hits = runCatching { io { mail.threadMessages(listOf(MailSession.INBOX), chunk) } }.getOrDefault(emptyList())
            val uids = hits.filter { it.first == MailSession.INBOX }.map { it.second.uid }.distinct()
            if (uids.isNotEmpty() && target != MailSession.INBOX) runCatching { io { mail.move(MailSession.INBOX, uids, target) } }.onSuccess { moved += it.size }
        }
    }
    if (moved > 0) bump()
    return moved
}

internal suspend fun AccountContext.addRoute(req: RouteRequest): BatchResult {
    val ids = LinkedHashSet<String>()
    var subject = ""
    for ((folder, refs) in req.items.groupBy { it.folder }) io { mail.summaries(folder, refs.map { it.uid }) }.forEach { ids.addAll(threadIds(it)); if (subject.isBlank()) subject = it.subject }
    require(ids.isNotEmpty()) { "These messages have no Message-ID, so the conversation cannot be recognised" }
    val dest = when (req.kind) { "mute" -> "__archive"; else -> req.dest?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("Choose a folder") }
    val current = CommandState.routes(this).filter { r -> r.ids.none { it in ids } }
    CommandState.saveRoutes(this, if (req.enable) current + ConversationRouteDto(ids.toList(), dest, req.kind, subject, System.currentTimeMillis()) else current)
    if (!req.enable) return BatchResult(0, 0)
    // File what is already in the conversation, then let the job take care of later replies.
    val target = if (dest == "__archive") io { mail.ensureFolderForRole("archive") } else dest
    var n = 0
    for ((folder, refs) in req.items.groupBy { it.folder }) if (folder != target) runCatching { io { mail.move(folder, refs.map { it.uid }, target) } }.onSuccess { n += it.size }
    n += applyRoutes()
    bump()
    return BatchResult(n, 0)
}

/** Archive into yearly or monthly sub-folders (Thunderbird), optionally everything older than N days. */
internal suspend fun AccountContext.archiveBy(req: ArchiveRequest): BatchResult {
    if (!mail.isImap) throw IllegalArgumentException("Archiving needs an IMAP account")
    val base = req.dest?.takeIf { it.isNotBlank() } ?: io { mail.ensureFolderForRole("archive") }
    val summaries: List<Pair<String, RawSummary>> = if (req.path != null && req.olderThanDays != null) {
        val cutoff = System.currentTimeMillis() - req.olderThanDays * 86_400_000L
        io { mail.olderThan(req.path, cutoff, 2000) }.map { req.path to it }
    } else req.items.groupBy { it.folder }.flatMap { (f, refs) -> io { mail.summaries(f, refs.map { it.uid }) }.map { f to it } }
    if (summaries.isEmpty()) return BatchResult(0, 0)
    val folders = io { mail.listFolders() }
    val delim = folders.firstOrNull { it.path == base }?.delimiter ?: "/"
    val byDest = summaries.groupBy { (_, s) ->
        val c = Calendar.getInstance().apply { timeInMillis = if (s.date > 0) s.date else System.currentTimeMillis() }
        when (req.granularity) {
            "year" -> listOf(c.get(Calendar.YEAR).toString())
            "month" -> listOf(c.get(Calendar.YEAR).toString(), "%d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1))
            else -> emptyList()
        }
    }
    var ok = 0; val errors = ArrayList<String>()
    for ((parts, items) in byDest) {
        var dest = base
        for (p in parts) {
            val next = dest + delim + p
            if (folders.none { it.path == next }) runCatching { io { mail.createFolder(p, dest) } }
            dest = next
        }
        for ((f, group) in items.groupBy { it.first }) {
            if (f == dest) continue
            runCatching { io { mail.move(f, group.map { it.second.uid }, dest) } }.onSuccess { ok += it.size }.onFailure { errors += it.message.orEmpty() }
        }
    }
    bump()
    return BatchResult(ok, summaries.size - ok, errors.filter { it.isNotBlank() })
}

/** Every attachment of the given messages (the "attachment shelf" of a conversation). */
internal suspend fun AccountContext.shelf(items: List<MessageRef>): List<ShelfItemDto> {
    val out = ArrayList<ShelfItemDto>()
    for ((folder, refs) in items.take(60).groupBy { it.folder }) {
        val full = io { mail.fetchMessages(folder, refs.map { it.uid }) }
        for ((uid, raw) in full) for (a in raw.attachments) if (!a.inline) out += ShelfItemDto(folder, uid, raw.summary.subject, raw.summary.fromAddress, raw.summary.date, a.index, a.fileName, a.mimeType, a.size)
    }
    return out.sortedByDescending { it.date }
}

/**
 * Unsubscribes using the message's List-Unsubscribe header: RFC 8058 one-click POST when offered,
 * otherwise an unsubscribe e-mail for mailto:, otherwise the web page for the client to open.
 */
internal suspend fun AccountContext.unsubscribe(folder: String, uid: Long): UnsubscribeResultDto {
    val headers = io { mail.headerList(folder, uid) }
    fun h(n: String) = headers.firstOrNull { it.first.equals(n, true) }?.second
    val p = ListUnsubscribe.parse(h("List-Unsubscribe"), h("List-Unsubscribe-Post"))
    if (p.oneClick && p.https.isNotEmpty()) {
        val url = URL(p.https.first())
        UrlGuard.check(url)
        val status = io {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; instanceFollowRedirects = false
                connectTimeout = 10_000; readTimeout = 20_000
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("User-Agent", "Quillbox one-click unsubscribe (RFC 8058)")
            }
            try { conn.outputStream.use { it.write("List-Unsubscribe=One-Click".toByteArray()) }; conn.responseCode } finally { conn.disconnect() }
        }
        return if (status in 200..399) UnsubscribeResultDto("one-click", "done", url.toString(), "The list answered HTTP $status.")
        else UnsubscribeResultDto("one-click", "failed", url.toString(), "The list answered HTTP $status.")
    }
    if (p.mailto.isNotEmpty()) {
        val (to, subject, body) = ListUnsubscribe.mailto(p.mailto.first())
        io { mail.send(SendRequest(to = listOf(to), subject = subject, body = body, extraHeaders = mapOf("Auto-Submitted" to "auto-generated"))) }
        return UnsubscribeResultDto("mailto", "done", "mailto:$to", "An unsubscribe request was e-mailed to $to.")
    }
    val web = p.https.firstOrNull() ?: p.http.firstOrNull()
    if (web != null) return UnsubscribeResultDto("web", "open", web, "This list only offers an unsubscribe web page.")
    return UnsubscribeResultDto("none", "unavailable", null, "This message has no List-Unsubscribe header.")
}

/** Sent messages from the last [days] days that nobody has answered yet. */
internal suspend fun AccountContext.awaiting(days: Int): List<AwaitingDto> {
    if (!mail.isImap) return emptyList()
    val sent = mail.folderForRole("sent") ?: return emptyList()
    val cutoff = System.currentTimeMillis() - days * 86_400_000L
    val (_, raws) = io { mail.listMessages(sent, 200, 0) }
    val mine = ownAddresses()
    val candidates = raws.filter { it.date >= cutoff && it.messageId.isNotBlank() && it.to.any { a -> a.lowercase() !in mine } }.take(120)
    if (candidates.isEmpty()) return emptyList()
    val paths = listOfNotNull(MailSession.INBOX, mail.folderForRole("archive")).distinct()
    val answered = HashSet<String>()
    for (chunk in candidates.map { it.messageId }.chunked(12)) {
        val hits = runCatching { io { mail.threadMessages(paths, chunk) } }.getOrDefault(emptyList())
        for ((_, s) in hits) {
            if (s.fromAddress.lowercase() in mine) continue
            val refs = (s.headers["In-Reply-To"].orEmpty() + s.headers["References"].orEmpty()).joinToString(" ")
            for (id in chunk) if (refs.contains(id)) answered += id
        }
    }
    return candidates.filter { it.messageId !in answered }.map { AwaitingDto(sent, it.uid, it.messageId, it.subject, it.to, it.date) }
}

/** Sends every message waiting in the outbox now (Outlook "Send All", Eudora "Send Queued Messages"). */
internal suspend fun AccountContext.flushOutbox(): BatchResult {
    var ok = 0; val errors = ArrayList<String>()
    for (item in pim.outboxItems()) {
        runCatching { deliver(item.request) }
            .onSuccess { pim.removeOutbox(item.id); ok++ }
            .onFailure { pim.outboxFailed(item.id, it.message ?: "send failed"); errors += it.message.orEmpty() }
    }
    bump()
    return BatchResult(ok, errors.size, errors)
}
