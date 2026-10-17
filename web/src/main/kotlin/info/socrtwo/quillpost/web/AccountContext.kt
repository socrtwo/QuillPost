package info.socrtwo.quillpost.web

import info.socrtwo.quillpost.web.Mapping.preview
import info.socrtwo.quillpost.web.Mapping.toConfig
import info.socrtwo.quillpost.web.Mapping.toDto
import info.socrtwo.quillpost.web.Mapping.toFacts
import info.socrtwo.quillpost.web.Mapping.toRule
import info.socrtwo.quillpost.web.Mapping.toSummaryDto
import info.socrtwo.quillpost.web.spam.BayesClassifier
import info.socrtwo.quillpost.web.spam.BlacklistChecker
import info.socrtwo.quillpost.web.spam.HeaderParser
import info.socrtwo.quillpost.web.spam.MessageFacts
import info.socrtwo.quillpost.web.spam.Rule
import info.socrtwo.quillpost.web.spam.RuleAction
import info.socrtwo.quillpost.web.spam.RuleEngine
import info.socrtwo.quillpost.web.spam.RuleField
import info.socrtwo.quillpost.web.spam.RuleProposer
import info.socrtwo.quillpost.web.spam.SpamEngine
import info.socrtwo.quillpost.web.spam.SpamLevel
import info.socrtwo.quillpost.web.spam.SpamReason
import jakarta.mail.Flags
import jakarta.mail.MessagingException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Everything the server keeps for one signed-in account: the live mail connection, the
 * account's settings and rules, its learned Bayes model, the per-message analysis cache and
 * the background analyser that scans new mail and files spam into Junk.
 */
class AccountContext(
    val account: AccountDto,
    internal val store: DataStore,
    blacklists: BlacklistChecker
) {
    val mail = MailSession(account)
    @Volatile var settings: AccountSettingsDto = store.loadSettings(account.email)
        private set
    val bayes: BayesClassifier = store.loadBayes(account.email)
    /** What this mailbox knows about each sender (replies, opens, junk marks, habitual link domains). */
    val history: SenderHistory = store.loadHistory(account.email)
    /** Calendars, contacts, tasks, message metadata and the scheduled-send queue. */
    val pim = PimStore(store, account.email)
    private val engine = SpamEngine(blacklists, bayes)
    /** Mailboxes imported from other programs, kept read-only in the data folder (LocalArchives.kt). */
    val archives: LocalArchives by lazy { LocalArchives(java.io.File(store.accountDir(account.email), "archives"), mail) }
    /** Uploads and background imports of mail stores (MailImport.kt). */
    val imports: MailImport by lazy { MailImport(this, java.io.File(store.root, "import-tmp/" + store.accountDir(account.email).name)) }

    internal val cache = ConcurrentHashMap<String, CachedAnalysis>()
    @Volatile private var initialised: Boolean
    @Volatile var version: Long = 0L
        private set
    @Volatile var lastUsed: Long = System.currentTimeMillis()

    private val ioLock = Mutex()
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = LinkedHashMap<String, LinkedHashSet<Long>>()
    private var draining = false
    internal val userAddresses: List<String> = listOf(account.email.lowercase(), account.username.lowercase()).distinct()

    /** The account's own addresses including configured aliases. */
    internal fun ownAddresses(): Set<String> = (userAddresses + settings.aliases.map { it.trim().lowercase() }).filter { it.contains('@') }.toSet()

    companion object {
        const val LOCAL_JUNK = "__local_junk__"
        private const val BATCH = 6
    }

    init {
        val loaded = store.loadCache(account.email)
        cache.putAll(loaded.entries)
        initialised = loaded.inboxWatermark.isNotEmpty() || settings.spam.scanExisting
        startScheduler()
    }

    val capabilities: Capabilities
        get() = Capabilities(folders = mail.isImap, move = mail.isImap, flags = mail.isImap, search = true, drafts = mail.isImap)

    internal suspend fun <T> io(block: () -> T): T = ioLock.withLock { withContext(Dispatchers.IO) { block() } }

    internal fun touch() { lastUsed = System.currentTimeMillis() }
    internal fun bump() { version++ }

    fun close() {
        scope.cancel()
        mail.close()
        persistCache()
    }

    // --- settings -----------------------------------------------------------------------

    @Synchronized
    fun updateSettings(new: AccountSettingsDto): AccountSettingsDto {
        settings = new.copy(version = 2)
        store.saveSettings(account.email, settings)
        bump()
        return settings
    }

    private fun spamRules(): List<Rule> = settings.rules.map { it.toRule() }

    internal fun key(s: RawSummary, folder: String): String = s.messageId.ifBlank { "uid:$folder:${s.uid}" }

    /** List-pane summary with cached verdict, Focused/Other and the message's categories, pin, flag and snooze. */
    internal fun summary(raw: RawSummary, folder: String): MessageSummaryDto {
        val dto = raw.toSummaryDto(folder, cache[key(raw, folder)])
        val m = raw.messageId.takeIf { it.isNotBlank() }?.let { pim.meta(it) }
        return dto.copy(
            focused = isFocused(raw),
            categories = m?.categories ?: emptyList(), pinned = m?.pinned ?: false,
            flagDue = m?.flagDue, flagCompleted = m?.flagCompleted ?: false, snoozeUntil = m?.snoozeUntil
        )
    }

    @Synchronized
    private fun persistCache() {
        runCatching {
            store.saveCache(account.email, AnalysisCache(entries = HashMap(cache), inboxWatermark = if (initialised) mapOf("initialised" to 1L) else emptyMap()))
        }
    }

    private fun persistBayes() = runCatching { store.saveBayes(account.email, bayes) }
    internal fun persistHistory() { if (history.dirty) runCatching { store.saveHistory(account.email, history) } }

    // --- folders ------------------------------------------------------------------------

    suspend fun folders(): List<FolderDto> {
        touch()
        val list = io { mail.listFolders() }.map { FolderDto(it.path, it.name, it.role, it.delimiter, it.unread, it.total, it.depth) }.toMutableList()
        if (mail.isImap) {
            if (list.none { it.role == "junk" }) {
                val path = runCatching { io { mail.ensureFolderForRole("junk") } }.getOrNull()
                if (path != null) list.add(FolderDto(path, "Junk Email", "junk", "/", 0, 0, 0))
            }
        } else {
            val junk = settings.pop3Junk.size
            list.add(FolderDto(LOCAL_JUNK, "Junk Email (local)", "junk", "/", 0, junk, 0))
        }
        return list
    }

    // --- listing ------------------------------------------------------------------------

    suspend fun listMessages(folder: String, limit: Int, offset: Int, rescan: Boolean = false): MessageListResponse {
        touch()
        if (LocalArchives.isArchive(folder)) return withContext(Dispatchers.IO) { archives.listMessages(folder, limit, offset) }
        if (!mail.isImap && folder == LOCAL_JUNK) return listLocalJunk(limit)
        val (total, raws0) = io { mail.listMessages(folder, limit, offset) }
        val raws = if (!mail.isImap && folder.equals(MailSession.INBOX, true)) raws0.filter { key(it, folder) !in settings.pop3Junk.toSet() } else raws0
        if (rescan) raws.forEach { cache.remove(key(it, folder)) }
        val missing = raws.filter { !cache.containsKey(key(it, folder)) }
        if (missing.isNotEmpty() && settings.spam.enabled) enqueue(folder, missing.map { it.uid })
        val messages = raws.map { summary(it, folder) }
        return MessageListResponse(
            folder = folder, total = total, unread = raws.count { !it.seen }, messages = messages,
            pending = if (settings.spam.enabled) missing.size else 0, version = version
        )
    }

    private suspend fun listLocalJunk(limit: Int): MessageListResponse {
        val junkKeys = settings.pop3Junk.toSet()
        val (_, raws) = io { mail.listMessages(MailSession.INBOX, maxOf(limit, 500), 0) }
        val junk = raws.filter { key(it, MailSession.INBOX) in junkKeys }
        return MessageListResponse(LOCAL_JUNK, junk.size, junk.count { !it.seen }, junk.map { summary(it, MailSession.INBOX) }, version = version)
    }

    suspend fun search(folder: String, q: String, limit: Int): SearchResponse {
        touch()
        if (LocalArchives.isArchive(folder)) return withContext(Dispatchers.IO) { archives.search(folder, q, limit) }
        val raws = io { mail.search(folder, q, limit) }
        return SearchResponse(folder, q, raws.map { summary(it, folder) })
    }

    // --- background analysis -------------------------------------------------------------

    private fun enqueue(folder: String, uids: List<Long>) {
        synchronized(queue) {
            queue.getOrPut(folder) { LinkedHashSet() }.addAll(uids)
            if (!draining) {
                draining = true
                scope.launch { drain() }
            }
        }
    }

    private suspend fun drain() {
        try {
            while (true) {
                val next: Pair<String, List<Long>> = synchronized(queue) {
                    val entry = queue.entries.firstOrNull { it.value.isNotEmpty() }
                    if (entry == null) { draining = false; null } else {
                        val batch = entry.value.take(BATCH)
                        entry.value.removeAll(batch.toSet())
                        if (entry.value.isEmpty()) queue.remove(entry.key)
                        entry.key to batch
                    }
                } ?: break
                runCatching { processBatch(next.first, next.second) }
                    .onFailure { bump() }
            }
        } finally {
            synchronized(queue) { draining = false }
        }
    }

    /** The number of messages still queued for analysis. */
    fun pending(): Int = synchronized(queue) { queue.values.sumOf { it.size } }

    private suspend fun processBatch(folder: String, uids: List<Long>) {
        val raws = io { mail.fetchMessages(folder, uids) }
        if (raws.isEmpty()) return
        val cfg = settings.spam.toConfig()
        val rules = spamRules()
        val isInbox = folder.equals(MailSession.INBOX, ignoreCase = true)
        val results = coroutineScope {
            raws.map { (uid, raw) ->
                async(Dispatchers.IO) { Triple(uid, raw, engine.analyze(raw.toFacts(userAddresses), cfg, rules, history.reputation(raw.summary.fromAddress))) }
            }.awaitAll()
        }
        val moves = HashMap<String, MutableList<Long>>()      // destination -> uids
        val copies = HashMap<String, MutableList<Long>>()
        val markRead = ArrayList<Long>()
        val flag = ArrayList<Long>()
        val forwards = ArrayList<Pair<Long, String>>()
        val autoReplies = ArrayList<RawMessage>()
        val ignored = pim.ignoredThreads()
        val ruleHits = HashMap<String, Int>()
        var moved = 0
        for ((uid, raw, verdict) in results) {
            val k = key(raw.summary, folder)
            val previous = cache[k]
            val userSaidHam = previous?.verdict?.trained == "ham"
            var dto = verdict.toDto().copy(trained = previous?.verdict?.trained)
            val rule = verdict.matchedRule
            var dest: String? = null
            if (isInbox && initialised && !userSaidHam && mail.isImap) {
                when {
                    rule != null && rule.action == RuleAction.DELETE -> dest = runCatching { mail.ensureFolderForRole("trash") }.getOrNull()
                    rule != null && rule.action == RuleAction.MOVE_TO_FOLDER && rule.targetFolder != null ->
                        dest = resolveFolder(rule.targetFolder)
                    verdict.isSpam && cfg.autoMoveToJunk -> dest = runCatching { io { mail.ensureFolderForRole("junk") } }.getOrNull()
                }
                if (rule != null && rule.action == RuleAction.MARK_READ) markRead += uid
                if (rule != null && rule.action == RuleAction.FLAG) flag += uid
                if (rule != null && rule.action == RuleAction.COPY_TO_FOLDER && rule.targetFolder != null)
                    resolveFolder(rule.targetFolder)?.let { copies.getOrPut(it) { ArrayList() }.add(uid) }
                if (rule != null && rule.action == RuleAction.CATEGORIZE && rule.categories.isNotEmpty() && raw.summary.messageId.isNotBlank())
                    pim.updateMeta(MetaUpdateRequest(listOf(raw.summary.messageId), categoriesAdd = rule.categories))
                if (rule != null && rule.action == RuleAction.PIN && raw.summary.messageId.isNotBlank())
                    pim.updateMeta(MetaUpdateRequest(listOf(raw.summary.messageId), pinned = true))
                if (rule != null && rule.action == RuleAction.FORWARD_TO && rule.forwardTo.contains('@') && rule.forwardTo.lowercase() !in ownAddresses())
                    forwards += uid to rule.forwardTo
                // "Ignore conversation": later messages in an ignored thread go straight to Deleted Items.
                if (dest == null && previous == null && ignored.isNotEmpty() && threadIds(raw.summary).any { it in ignored })
                    dest = runCatching { io { mail.ensureFolderForRole("trash") } }.getOrNull()
                if (previous == null && dest == null && !verdict.isSpam && shouldAutoReply(raw)) autoReplies += raw
            }
            if (isInbox && !mail.isImap && initialised && !userSaidHam && verdict.isSpam && cfg.autoMoveToJunk) {
                // POP3: remember locally that this message belongs in Junk.
                dest = LOCAL_JUNK
            }
            rule?.let { ruleHits[it.id] = (ruleHits[it.id] ?: 0) + 1 }
            if (dest != null && dest != folder) {
                moves.getOrPut(dest) { ArrayList() }.add(uid)
                dto = dto.copy(autoMoved = true, autoMovedTo = dest)
                moved++
            }
            if (previous == null && raw.summary.fromAddress.isNotBlank()) history.seen(raw.summary.fromAddress, verdict.linkDomains, verdict.isSpam)
            cache[k] = CachedAnalysis(dto, preview(raw.bodyText, raw.bodyHtml), System.currentTimeMillis(), raw.summary.subject, raw.summary.fromAddress)
        }
        // Forward before any move so the UIDs are still valid in this folder.
        for ((uid, to) in forwards) {
            val subject = raws[uid]?.summary?.subject.orEmpty()
            runCatching { io { mail.send(SendRequest(to = listOf(to), subject = "FW: $subject", body = "Forwarded automatically by a QuillPost rule.", attachMessages = listOf(MessageRef(folder, uid)))) } }
        }
        if (moves.isNotEmpty() || markRead.isNotEmpty() || flag.isNotEmpty() || copies.isNotEmpty()) {
            io {
                for ((dest, ids) in copies) runCatching { mail.copy(folder, ids, dest) }
                for ((dest, ids) in moves) {
                    if (dest == LOCAL_JUNK) {
                        val keys = ids.mapNotNull { id -> raws[id]?.summary?.let { key(it, folder) } }
                        synchronized(this) { settings = settings.copy(pop3Junk = (settings.pop3Junk + keys).distinct()); store.saveSettings(account.email, settings) }
                    } else runCatching { mail.move(folder, ids, dest) }
                }
                if (markRead.isNotEmpty()) runCatching { mail.setFlag(folder, markRead, Flags.Flag.SEEN, true) }
                if (flag.isNotEmpty()) runCatching { mail.setFlag(folder, flag, Flags.Flag.FLAGGED, true) }
            }
        }
        if (ruleHits.isNotEmpty()) synchronized(this) {
            settings = settings.copy(rules = settings.rules.map { r -> ruleHits[r.id]?.let { n -> r.copy(hitCount = r.hitCount + n) } ?: r })
            store.saveSettings(account.email, settings)
        }
        for (raw in autoReplies) runCatching { sendAutoReply(raw) }
        if (!initialised) initialised = true
        bump()
        persistCache()
        persistHistory()
    }

    /** Maps a rule's folder name to a real server path, creating it at the top level if needed. */
    internal suspend fun resolveFolder(name: String): String? {
        if (engine.isJunkFolder(name)) return runCatching { io { mail.ensureFolderForRole("junk") } }.getOrNull()
        val lower = name.lowercase()
        if (lower in setOf("trash", "deleted", "deleted items", "bin")) return runCatching { io { mail.ensureFolderForRole("trash") } }.getOrNull()
        if (lower in setOf("archive")) return runCatching { io { mail.ensureFolderForRole("archive") } }.getOrNull()
        val folders = runCatching { io { mail.listFolders() } }.getOrDefault(emptyList())
        folders.firstOrNull { it.path.equals(name, true) || it.name.equals(name, true) }?.let { return it.path }
        return runCatching {
            io {
                val s = mail.connect()
                val f = s.getFolder(name)
                if (!f.exists()) f.create(jakarta.mail.Folder.HOLDS_MESSAGES)
                f.fullName
            }
        }.getOrNull()
    }

    // --- single message -----------------------------------------------------------------

    suspend fun message(folder: String, uid: Long, tz: String = ""): MessageDetailDto? {
        touch()
        if (LocalArchives.isArchive(folder)) return archiveMessage(folder, uid, tz)
        val realFolder = if (folder == LOCAL_JUNK) MailSession.INBOX else folder
        val raw = io { mail.getMessage(realFolder, uid) } ?: return null
        val k = key(raw.summary, realFolder)
        if (raw.summary.fromAddress.isNotBlank() && !raw.summary.seen) { history.opened(raw.summary.fromAddress); persistHistory() }
        val cached = cache[k] ?: run {
            val verdict = withContext(Dispatchers.IO) { engine.analyze(raw.toFacts(userAddresses), settings.spam.toConfig(), spamRules(), history.reputation(raw.summary.fromAddress)) }
            CachedAnalysis(verdict.toDto(), preview(raw.bodyText, raw.bodyHtml), System.currentTimeMillis(), raw.summary.subject, raw.summary.fromAddress)
                .also { cache[k] = it; persistCache() }
        }
        val s = raw.summary
        val meta = s.messageId.takeIf { it.isNotBlank() }?.let { pim.meta(it) }
        val interesting = listOf("Message-ID", "Return-Path", "Reply-To", "X-Mailer", "Authentication-Results", "List-Unsubscribe", "X-Originating-IP", "Received")
        return MessageDetailDto(
            uid = s.uid, folder = folder, messageId = s.messageId, fromName = s.fromName, fromAddress = s.fromAddress,
            to = s.to, cc = s.cc, replyTo = s.replyTo, subject = s.subject, date = s.date, read = s.seen, flagged = s.flagged,
            answered = s.answered, bodyText = raw.bodyText, bodyHtml = raw.bodyHtml, attachments = raw.attachments,
            inReplyTo = raw.inReplyTo, references = raw.references, listUnsubscribe = s.headers["List-Unsubscribe"]?.firstOrNull(),
            headers = interesting.mapNotNull { h -> s.headers[h]?.let { h to it.joinToString("\n") } }.toMap(),
            verdict = cached.verdict,
            importance = Mapping.importanceOf(s.headers),
            forwarded = s.forwarded,
            readReceiptTo = s.headers["Disposition-Notification-To"]?.firstOrNull()?.trim()?.takeIf { !s.mdnSent && it.contains('@') && folder != (mail.folderForRole("sent") ?: "") },
            invite = raw.calendar?.let { runCatching { inviteFrom(it, tz) }.getOrNull() },
            categories = meta?.categories ?: emptyList(), pinned = meta?.pinned ?: false, flagDue = meta?.flagDue,
            flagCompleted = meta?.flagCompleted ?: false, snoozeUntil = meta?.snoozeUntil
        )
    }

    suspend fun attachment(folder: String, uid: Long, index: Int?, cid: String?): MailSession.AttachmentData? {
        touch()
        if (LocalArchives.isArchive(folder)) return withContext(Dispatchers.IO) { archives.mime(folder, uid)?.let { mail.localAttachment(it, index, cid) } }
        val realFolder = if (folder == LOCAL_JUNK) MailSession.INBOX else folder
        return io { mail.getAttachment(realFolder, uid, index, cid) }
    }

    // --- AI analysis & rule proposals -----------------------------------------------------

    suspend fun analyze(folder: String, uid: Long): AnalyzeResponse? {
        touch()
        val realFolder = if (folder == LOCAL_JUNK) MailSession.INBOX else folder
        val raw = io { mail.getMessage(realFolder, uid) } ?: return null
        val facts = raw.toFacts(userAddresses)
        val cfg = settings.spam.toConfig()
        val verdict = withContext(Dispatchers.IO) { engine.analyze(facts, cfg, spamRules(), history.reputation(facts.fromAddress)) }
        val junkName = if (mail.isImap) "Junk" else LOCAL_JUNK
        var proposal = RuleProposer.propose(facts, verdict, if (cfg.useBayes) bayes else null, junkName).toDto()
        val llm = if (settings.ollama.enabled) withContext(Dispatchers.IO) { Ollama(settings.ollama).assess(facts, verdict.senderDomain) } else null
        if (llm != null && llm.error == null) {
            val existing = proposal.rule.criteria.map { it.field + "|" + it.value.lowercase() }.toSet()
            val extra = llm.extraCriteria.filter { (it.field + "|" + it.value.lowercase()) !in existing }
            if (extra.isNotEmpty()) {
                proposal = proposal.copy(
                    rule = proposal.rule.copy(criteria = proposal.rule.criteria + extra),
                    criteria = proposal.criteria + extra.map { ProposedCriterionDto(it, "Suggested by the local ${llm.model} model.", 50) }
                )
            }
        }
        val k = key(raw.summary, realFolder)
        val prev = cache[k]
        cache[k] = CachedAnalysis(verdict.toDto().copy(autoMoved = prev?.verdict?.autoMoved ?: false, autoMovedTo = prev?.verdict?.autoMovedTo, trained = prev?.verdict?.trained),
            preview(raw.bodyText, raw.bodyHtml), System.currentTimeMillis(), raw.summary.subject, raw.summary.fromAddress)
        bump(); persistCache()
        return AnalyzeResponse(cache[k]!!.verdict, proposal, llm, bayesStats())
    }

    fun bayesStats() = BayesStatsDto(bayes.trainedSpam, bayes.trainedHam, bayes.vocabulary, bayes.ready)

    suspend fun previewRule(req: RulePreviewRequest): RulePreviewResponse {
        touch()
        val rule = req.rule.toRule()
        val folder = if (req.folder == LOCAL_JUNK) MailSession.INBOX else req.folder
        val (_, raws) = io { mail.listMessages(folder, req.limit, 0) }
        val needsBody = rule.criteria.any { it.field == RuleField.BODY || it.field == RuleField.ATTACHMENT_NAME }
        val matches = ArrayList<MessageSummaryDto>()
        if (needsBody) {
            val headerOnlyMatches = raws.filter { RuleEngine.matches(rule, it.toFacts(userAddresses)) }.map { it.uid }.toSet()
            for (chunk in raws.chunked(20)) {
                val full = io { mail.fetchMessages(folder, chunk.map { it.uid }) }
                for (s in chunk) {
                    val facts = full[s.uid]?.toFacts(userAddresses) ?: s.toFacts(userAddresses)
                    if (s.uid in headerOnlyMatches || RuleEngine.matches(rule, facts)) matches += summary(s, folder)
                }
            }
        } else {
            raws.filter { RuleEngine.matches(rule, it.toFacts(userAddresses)) }.forEach { matches += summary(it, folder) }
        }
        return RulePreviewResponse(matches, raws.size)
    }

    /** Applies [req.rule] to the messages currently in the folder ("send all similar current mail to Junk"). */
    suspend fun applyRule(req: ApplyRuleRequest): BatchResult {
        val preview = previewRule(RulePreviewRequest(req.rule, req.folder, req.limit))
        val rule = req.rule.toRule()
        val items = preview.matches.map { MessageRef(it.folder, it.uid) }
        if (items.isEmpty()) return BatchResult(0, 0)
        return when (rule.action) {
            RuleAction.MOVE_TO_FOLDER -> {
                val target = rule.targetFolder ?: "Junk"
                if (engine.isJunkFolder(target)) junk(JunkRequest(items, junk = true)) else {
                    val dest = resolveFolder(target) ?: return BatchResult(0, items.size, listOf("Folder $target not found"))
                    move(MoveRequest(items, dest))
                }
            }
            RuleAction.DELETE -> delete(DeleteRequest(items))
            RuleAction.MARK_READ -> flags(FlagRequest(items, read = true))
            RuleAction.FLAG -> flags(FlagRequest(items, flagged = true))
            RuleAction.MARK_SAFE -> BatchResult(items.size, 0)
            RuleAction.COPY_TO_FOLDER -> {
                val dest = rule.targetFolder?.let { resolveFolder(it) } ?: return BatchResult(0, items.size, listOf("Folder ${rule.targetFolder} not found"))
                var n = 0
                for ((f, refs) in items.groupBy { it.folder }) n += runCatching { io { mail.copy(f, refs.map { it.uid }, dest) } }.getOrDefault(0)
                BatchResult(n, items.size - n)
            }
            RuleAction.CATEGORIZE -> {
                pim.updateMeta(MetaUpdateRequest(preview.matches.map { it.messageId }.filter { it.isNotBlank() }, categoriesAdd = rule.categories)); bump()
                BatchResult(items.size, 0)
            }
            RuleAction.PIN -> {
                pim.updateMeta(MetaUpdateRequest(preview.matches.map { it.messageId }.filter { it.isNotBlank() }, pinned = true)); bump()
                BatchResult(items.size, 0)
            }
            RuleAction.FORWARD_TO -> BatchResult(0, items.size, listOf("Forwarding rules only act on newly arriving mail"))
        }
    }

    // --- junk / training ------------------------------------------------------------------

    suspend fun junk(req: JunkRequest): BatchResult {
        touch()
        if (req.items.any { LocalArchives.isArchive(it.folder) }) return archiveReadOnly(req.items.size)
        var ok = 0; val errors = ArrayList<String>()
        val junkPath = if (mail.isImap) runCatching { io { mail.ensureFolderForRole("junk") } }.getOrNull() else LOCAL_JUNK
        val byFolder = req.items.groupBy { it.folder }
        val newBlocked = LinkedHashSet<String>(); val newSafe = LinkedHashSet<String>()
        val localJunkAdd = ArrayList<String>(); val localJunkRemove = ArrayList<String>()
        val movedRefs = ArrayList<MessageRef>()
        for ((folder, refs) in byFolder) {
            val realFolder = if (folder == LOCAL_JUNK) MailSession.INBOX else folder
            val raws = runCatching { io { mail.fetchMessages(realFolder, refs.map { it.uid }) } }.getOrElse { errors += it.message.orEmpty(); emptyMap() }
            for ((uid, raw) in raws) {
                val facts = raw.toFacts(userAddresses)
                val k = key(raw.summary, realFolder)
                learn(k, facts, spam = req.junk)
                if (req.junk) {
                    if (req.blockSender && facts.fromAddress.isNotBlank()) newBlocked += facts.fromAddress
                    if (!mail.isImap) localJunkAdd += k
                } else {
                    if (req.safeSender && facts.fromAddress.isNotBlank()) newSafe += facts.fromAddress
                    if (!mail.isImap) localJunkRemove += k
                    val prev = cache[k]?.verdict
                    val cleared = (prev ?: VerdictDto(0, "CLEAN", emptyList(), BrandDto(), AuthDto())).copy(
                        score = 0, level = SpamLevel.CLEAN.name, trained = "ham", autoMoved = false, autoMovedTo = null,
                        reasons = listOf(SpamReasonDto("USER_NOT_JUNK", "Marked as not junk by you", "This message will never be moved automatically again.", -100)) +
                            (prev?.reasons?.filter { it.code != "USER_JUNK" } ?: emptyList())
                    )
                    cache[k] = (cache[k] ?: CachedAnalysis(cleared, preview(raw.bodyText, raw.bodyHtml), System.currentTimeMillis(), raw.summary.subject, raw.summary.fromAddress)).copy(verdict = cleared)
                }
                ok++
            }
            if (mail.isImap && raws.isNotEmpty()) {
                val dest = if (req.junk) junkPath else MailSession.INBOX
                if (dest != null && dest != realFolder) runCatching { io { mail.move(realFolder, raws.keys.toList(), dest) } }
                    .onSuccess { m -> m.forEach { (_, nu) -> if (nu != null) movedRefs += MessageRef(dest, nu) } }
                    .onFailure { errors += it.message.orEmpty() }
            } else if (!mail.isImap) {
                raws.keys.forEach { movedRefs += MessageRef(if (req.junk) LOCAL_JUNK else MailSession.INBOX, it) }
            }
        }
        synchronized(this) {
            var s = settings
            if (newBlocked.isNotEmpty()) s = s.copy(spam = s.spam.copy(blockedSenders = (s.spam.blockedSenders + newBlocked).distinct(), safeSenders = s.spam.safeSenders - newBlocked))
            if (newSafe.isNotEmpty()) s = s.copy(spam = s.spam.copy(safeSenders = (s.spam.safeSenders + newSafe).distinct(), blockedSenders = s.spam.blockedSenders - newSafe))
            if (localJunkAdd.isNotEmpty() || localJunkRemove.isNotEmpty()) s = s.copy(pop3Junk = (s.pop3Junk + localJunkAdd - localJunkRemove.toSet()).distinct())
            if (s != settings) { settings = s; store.saveSettings(account.email, s) }
        }
        persistBayes(); persistCache(); persistHistory(); bump()
        return BatchResult(ok, req.items.size - ok, errors.filter { it.isNotBlank() }, movedRefs)
    }

    suspend fun train(req: TrainRequest): BatchResult {
        touch()
        var ok = 0
        for ((folder, refs) in req.items.groupBy { it.folder }) {
            val realFolder = if (folder == LOCAL_JUNK) MailSession.INBOX else folder
            val raws = runCatching { io { mail.fetchMessages(realFolder, refs.map { it.uid }) } }.getOrDefault(emptyMap())
            for ((_, raw) in raws) { learn(key(raw.summary, realFolder), raw.toFacts(userAddresses), req.spam); ok++ }
        }
        persistBayes(); persistCache(); persistHistory(); bump()
        return BatchResult(ok, req.items.size - ok)
    }

    private fun learn(k: String, facts: MessageFacts, spam: Boolean) {
        if (facts.fromAddress.isNotBlank()) { if (spam) history.markedJunk(facts.fromAddress) else history.markedNotJunk(facts.fromAddress) }
        val tokens = BayesClassifier.tokenize(facts)
        val prev = cache[k]
        when (prev?.verdict?.trained) {
            "spam" -> if (!spam) bayes.untrain(tokens, spam = true)
            "ham" -> if (spam) bayes.untrain(tokens, spam = false)
        }
        if (prev?.verdict?.trained != (if (spam) "spam" else "ham")) bayes.train(tokens, spam)
        val label = if (spam) "spam" else "ham"
        val base = prev ?: CachedAnalysis(VerdictDto(if (spam) 100 else 0, if (spam) "SPAM" else "CLEAN", emptyList(), BrandDto(), AuthDto()), preview(facts.bodyText, facts.bodyHtml), System.currentTimeMillis(), facts.subject, facts.fromAddress)
        val reasons = base.verdict.reasons.filter { it.code != "USER_JUNK" && it.code != "USER_NOT_JUNK" }
        val v = if (spam) base.verdict.copy(trained = label, score = 100, level = "SPAM",
            reasons = listOf(SpamReasonDto("USER_JUNK", "Marked as junk by you", "The classifier has learned from this message.", 100)) + reasons)
        else base.verdict.copy(trained = label, score = 0, level = "CLEAN",
            reasons = listOf(SpamReasonDto("USER_NOT_JUNK", "Marked as not junk by you", "The classifier has learned from this message.", -100)) + reasons)
        cache[k] = base.copy(verdict = v, at = System.currentTimeMillis())
    }

    // --- plain mutations ---------------------------------------------------------------------

    suspend fun move(req: MoveRequest): BatchResult {
        touch()
        if (req.items.any { LocalArchives.isArchive(it.folder) } || LocalArchives.isArchive(req.to)) return archiveReadOnly(req.items.size)
        var ok = 0; val errors = ArrayList<String>(); val moved = ArrayList<MessageRef>()
        for ((folder, refs) in req.items.groupBy { it.folder }) {
            runCatching { io { mail.move(folder, refs.map { it.uid }, req.to) } }
                .onSuccess { m -> ok += m.size; m.forEach { (_, nu) -> if (nu != null) moved += MessageRef(req.to, nu) } }
                .onFailure { errors += it.message ?: "move failed" }
        }
        bump()
        return BatchResult(ok, req.items.size - ok, errors, moved)
    }

    suspend fun flags(req: FlagRequest): BatchResult {
        touch()
        var ok = 0; val errors = ArrayList<String>()
        for ((folder, refs) in req.items.groupBy { it.folder }) {
            if (LocalArchives.isArchive(folder)) { ok += withContext(Dispatchers.IO) { archives.setFlags(folder, refs.map { it.uid }.toSet(), req.read, req.flagged) }; continue }
            val realFolder = if (folder == LOCAL_JUNK) MailSession.INBOX else folder
            runCatching {
                io {
                    req.read?.let { mail.setFlag(realFolder, refs.map { r -> r.uid }, Flags.Flag.SEEN, it) }
                    req.flagged?.let { mail.setFlag(realFolder, refs.map { r -> r.uid }, Flags.Flag.FLAGGED, it) }
                }
            }.onSuccess { ok += refs.size }.onFailure { errors += it.message ?: "flag failed" }
        }
        bump()
        return BatchResult(ok, req.items.size - ok, errors)
    }

    suspend fun delete(req: DeleteRequest): BatchResult {
        touch()
        if (req.items.any { LocalArchives.isArchive(it.folder) }) return archiveReadOnly(req.items.size)
        var ok = 0; val errors = ArrayList<String>()
        val trash = if (mail.isImap) runCatching { io { mail.ensureFolderForRole("trash") } }.getOrNull() else null
        val moved = ArrayList<MessageRef>()
        for ((folder, refs) in req.items.groupBy { it.folder }) {
            val realFolder = if (folder == LOCAL_JUNK) MailSession.INBOX else folder
            val permanent = req.permanent || trash == null || realFolder == trash
            runCatching {
                io {
                    if (permanent) mail.deletePermanently(realFolder, refs.map { it.uid })
                    else mail.move(realFolder, refs.map { it.uid }, trash!!).also { m -> m.forEach { (_, nu) -> if (nu != null) moved += MessageRef(trash, nu) } }.size
                }
            }.onSuccess { ok += it }.onFailure { errors += it.message ?: "delete failed" }
        }
        bump()
        return BatchResult(ok, req.items.size - ok, errors, moved)
    }

    // --- sending / drafts ------------------------------------------------------------------------

    suspend fun send(req0: SendRequest): SendResponse {
        touch()
        val from = req0.from?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        require(from == null || from in ownAddresses()) { "$from is not one of this account's addresses (add it under Settings → Accounts → Aliases)" }
        val req = req0.copy(from = from)
        if (req.sendAt != null && req.sendAt > System.currentTimeMillis() + 1_000) {
            val item = pim.schedule(req, req.sendAt)
            // Undo send and near-term schedules fire on time instead of waiting for the next scheduler tick.
            if (item.sendAt - System.currentTimeMillis() < 120_000) scope.launch { kotlinx.coroutines.delay(maxOf(0, item.sendAt - System.currentTimeMillis())); runCatching { tick() } }
            // A draft that is now scheduled is no longer a draft.
            if (mail.isImap) req.draftUid?.let { uid -> runCatching { io { mail.deletePermanently(mail.ensureFolderForRole("drafts"), listOf(uid)) } } }
            bump()
            return SendResponse("scheduled", item.id, item.sendAt)
        }
        deliver(req)
        return SendResponse("sent")
    }

    /** Sends now, files a copy in Sent Items and updates answered/forwarded markers. */
    internal suspend fun deliver(req: SendRequest) {
        val message = io { mail.send(req) }
        (req.to + req.cc).map { HeaderParser.splitAddress(it).second.ifBlank { it } }.filter { it.contains('@') }.forEach { history.replied(it) }
        persistHistory()
        if (mail.isImap) {
            runCatching { io { mail.append(mail.ensureFolderForRole("sent"), message, Flags(Flags.Flag.SEEN)) } }
            req.replyTo?.let { ref -> runCatching { io { mail.setFlag(ref.folder, listOf(ref.uid), Flags.Flag.ANSWERED, true) } } }
            req.draftUid?.let { uid -> runCatching { io { mail.deletePermanently(mail.ensureFolderForRole("drafts"), listOf(uid)) } } }
            req.forwardOf?.let { ref -> runCatching { io { mail.setKeyword(ref.folder, listOf(ref.uid), "\$Forwarded", true) } } }
        }
        bump()
    }

    suspend fun saveDraft(req: DraftRequest): DraftResponse {
        touch()
        if (!mail.isImap) throw MessagingException("Drafts need an IMAP account")
        val drafts = io { mail.ensureFolderForRole("drafts") }
        val msg = io { mail.buildMessage(SendRequest(null, req.to, req.cc, req.bcc, req.subject, req.body, req.html, req.attachments, req.inReplyTo, req.references, importance = req.importance)) }
        val uid = io {
            req.uid?.let { runCatching { mail.deletePermanently(drafts, listOf(it)) } }
            mail.append(drafts, msg, Flags(Flags.Flag.DRAFT).apply { add(Flags.Flag.SEEN) })
        }
        bump()
        return DraftResponse(drafts, uid ?: -1L)
    }

    /** Messages QuillPost moved automatically after [since]; drives the "moved to Junk" toasts. */
    fun activity(since: Long): List<ActivityDto> = cache.values
        .filter { it.verdict.autoMoved && it.at > since }
        .sortedByDescending { it.at }
        .take(50)
        .map { ActivityDto(it.subject, it.fromAddress, it.at, it.verdict.autoMovedTo, it.verdict.score, it.verdict.level) }

    fun status(): SpamStatusDto = SpamStatusDto(
        bayes = bayesStats(),
        cached = cache.size,
        autoMoved = cache.values.count { it.verdict.autoMoved },
        rules = settings.rules.size,
        blacklists = settings.spam.blacklists
    )
}
