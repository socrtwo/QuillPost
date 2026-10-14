package info.socrtwo.quillbox.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.security.SecureRandom

/**
 * One account's calendars, contacts, tasks, per-message metadata and scheduled-send queue,
 * kept as JSON next to its settings (`pim.json`, `mail-meta.json`, `outbox.json`). All access
 * is synchronised; every change is written straight through with an atomic file replace.
 */
class PimStore(private val store: DataStore, private val email: String) {

    companion object {
        const val DEFAULT_CALENDAR = "calendar"
        const val DEFAULT_TASKS = "tasks"
        private val random = SecureRandom()
        private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

        fun newId(prefix: String): String = prefix + "-" + (1..14).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")

        val KINDS = setOf("calendars", "events", "contacts", "contactLists", "taskLists", "tasks")
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    private var pim: PimData = store.loadJson(email, "pim.json", PimData.serializer()) ?: PimData()
    private var meta: MailMetaFile = store.loadJson(email, "mail-meta.json", MailMetaFile.serializer()) ?: MailMetaFile()
    private var outbox: OutboxFile = store.loadJson(email, "outbox.json", OutboxFile.serializer()) ?: OutboxFile()

    init {
        var p = pim
        if (p.calendars.none { it.id == DEFAULT_CALENDAR }) p = p.copy(calendars = listOf(CalendarDto(DEFAULT_CALENDAR, "Calendar", "#0f6cbd")) + p.calendars)
        if (p.taskLists.none { it.id == DEFAULT_TASKS }) p = p.copy(taskLists = listOf(TaskListDto(DEFAULT_TASKS, "Tasks", created = System.currentTimeMillis())) + p.taskLists)
        if (p != pim) { pim = p; savePim() }
    }

    @Volatile private var emailCache: Set<String>? = null

    private fun savePim() { emailCache = null; store.saveJson(email, "pim.json", PimData.serializer(), pim) }
    private fun saveMeta() = store.saveJson(email, "mail-meta.json", MailMetaFile.serializer(), meta)
    private fun saveOutbox() = store.saveJson(email, "outbox.json", OutboxFile.serializer(), outbox)

    @Synchronized fun data(): PimData = pim

    // --- generic CRUD used by the REST API ----------------------------------------------------

    /** Inserts or replaces one item of [kind] and returns it as stored (with id and timestamps). */
    @Synchronized
    fun upsert(kind: String, body: JsonObject): JsonElement {
        val now = System.currentTimeMillis()
        return when (kind) {
            "calendars" -> {
                val c0 = json.decodeFromJsonElement(CalendarDto.serializer(), body)
                val existing = pim.calendars.firstOrNull { it.id == c0.id }
                // Only name, colour and visibility of a subscription may change from the UI.
                val c = (if (existing != null && existing.kind == "subscription") existing.copy(name = c0.name.ifBlank { existing.name }, color = c0.color, visible = c0.visible)
                         else c0.copy(kind = "local", readOnly = false, url = null)).let { if (it.id.isBlank()) it.copy(id = newId("cal")) else it }
                require(c.name.isNotBlank()) { "Calendar name is required" }
                pim = pim.copy(calendars = replace(pim.calendars, c) { it.id })
                savePim(); json.encodeToJsonElement(CalendarDto.serializer(), c)
            }
            "events" -> {
                val e0 = json.decodeFromJsonElement(EventDto.serializer(), body)
                val e = upsertEvent(e0)
                json.encodeToJsonElement(EventDto.serializer(), e)
            }
            "contacts" -> {
                val c0 = json.decodeFromJsonElement(ContactDto.serializer(), body)
                val id = c0.id.ifBlank { newId("ct") }
                val prev = pim.contacts.firstOrNull { it.id == id }
                val c = c0.copy(id = id, created = prev?.created ?: now, updated = now,
                    emails = c0.emails.map { it.copy(value = it.value.trim()) }.filter { it.value.isNotBlank() },
                    phones = c0.phones.filter { it.value.isNotBlank() })
                require(c.bestName().isNotBlank()) { "Enter a name or an email address" }
                pim = pim.copy(contacts = replace(pim.contacts, c) { it.id })
                savePim(); json.encodeToJsonElement(ContactDto.serializer(), c)
            }
            "contactLists" -> {
                val l0 = json.decodeFromJsonElement(ContactListDto.serializer(), body)
                val id = l0.id.ifBlank { newId("cl") }
                val prev = pim.contactLists.firstOrNull { it.id == id }
                val l = l0.copy(id = id, created = prev?.created ?: now, updated = now, members = l0.members.filter { it.email.contains('@') }.distinctBy { it.email.lowercase() })
                require(l.name.isNotBlank()) { "List name is required" }
                pim = pim.copy(contactLists = replace(pim.contactLists, l) { it.id })
                savePim(); json.encodeToJsonElement(ContactListDto.serializer(), l)
            }
            "taskLists" -> {
                val l0 = json.decodeFromJsonElement(TaskListDto.serializer(), body)
                val l = l0.copy(id = l0.id.ifBlank { newId("tl") }, created = if (l0.created == 0L) now else l0.created)
                require(l.name.isNotBlank()) { "List name is required" }
                pim = pim.copy(taskLists = replace(pim.taskLists, l) { it.id })
                savePim(); json.encodeToJsonElement(TaskListDto.serializer(), l)
            }
            "tasks" -> {
                val t0 = json.decodeFromJsonElement(TaskDto.serializer(), body)
                val id = t0.id.ifBlank { newId("tk") }
                val prev = pim.tasks.firstOrNull { it.id == id }
                val listId = t0.listId.takeIf { l -> pim.taskLists.any { it.id == l } } ?: DEFAULT_TASKS
                val t = t0.copy(id = id, listId = listId, created = prev?.created ?: now, updated = now,
                    completedAt = if (t0.completed) (prev?.completedAt?.takeIf { prev.completed } ?: now) else 0,
                    steps = t0.steps.map { if (it.id.isBlank()) it.copy(id = newId("st")) else it })
                require(t.title.isNotBlank()) { "Task title is required" }
                pim = pim.copy(tasks = replace(pim.tasks, t) { it.id })
                savePim(); json.encodeToJsonElement(TaskDto.serializer(), t)
            }
            else -> throw IllegalArgumentException("Unknown item kind: $kind")
        }
    }

    @Synchronized
    fun remove(kind: String, id: String): Boolean {
        val before = pim
        pim = when (kind) {
            "calendars" -> {
                require(id != DEFAULT_CALENDAR) { "The default calendar cannot be deleted" }
                pim.copy(calendars = pim.calendars.filter { it.id != id }, events = pim.events.filter { it.calendarId != id })
            }
            "events" -> pim.copy(events = pim.events.filter { it.id != id })
            "contacts" -> pim.copy(contacts = pim.contacts.filter { it.id != id })
            "contactLists" -> pim.copy(contactLists = pim.contactLists.filter { it.id != id })
            "taskLists" -> {
                require(id != DEFAULT_TASKS) { "The default task list cannot be deleted" }
                pim.copy(taskLists = pim.taskLists.filter { it.id != id }, tasks = pim.tasks.filter { it.listId != id })
            }
            "tasks" -> pim.copy(tasks = pim.tasks.filter { it.id != id })
            else -> throw IllegalArgumentException("Unknown item kind: $kind")
        }
        if (pim == before) return false
        savePim()
        return true
    }

    private fun <T> replace(list: List<T>, item: T, key: (T) -> String): List<T> {
        val k = key(item)
        return if (list.any { key(it) == k }) list.map { if (key(it) == k) item else it } else list + item
    }

    // --- calendars & events -------------------------------------------------------------------

    @Synchronized fun calendar(id: String): CalendarDto? = pim.calendars.firstOrNull { it.id == id }

    @Synchronized
    fun saveCalendar(c: CalendarDto): CalendarDto {
        pim = pim.copy(calendars = replace(pim.calendars, c) { it.id }); savePim(); return c
    }

    @Synchronized fun event(id: String): EventDto? = pim.events.firstOrNull { it.id == id }

    @Synchronized fun eventsByUid(uid: String): List<EventDto> = pim.events.filter { it.uid == uid }

    /** Validates and stores an event (writes to read-only calendars are refused). */
    @Synchronized
    fun upsertEvent(e0: EventDto): EventDto {
        val now = System.currentTimeMillis()
        val calId = e0.calendarId.ifBlank { DEFAULT_CALENDAR }
        val cal = calendar(calId) ?: throw IllegalArgumentException("Unknown calendar")
        require(!cal.readOnly) { "\"${cal.name}\" is a read-only subscribed calendar" }
        require(e0.start.isNotBlank()) { "Start time is required" }
        val id = e0.id.ifBlank { newId("ev") }
        val prev = pim.events.firstOrNull { it.id == id }
        val e = e0.copy(
            id = id, calendarId = calId, uid = e0.uid.ifBlank { prev?.uid ?: "$id@quillbox" },
            title = e0.title.ifBlank { "(No title)" },
            end = e0.end.ifBlank { e0.start },
            created = prev?.created ?: now, updated = now,
            attendees = e0.attendees.filter { it.email.contains('@') }.distinctBy { it.email.lowercase() }
        )
        pim = pim.copy(events = replace(pim.events, e) { it.id })
        savePim()
        return e
    }

    @Synchronized
    fun removeEvents(pred: (EventDto) -> Boolean): Int {
        val n = pim.events.count(pred)
        if (n > 0) { pim = pim.copy(events = pim.events.filterNot(pred)); savePim() }
        return n
    }

    /** Replaces all events of a subscribed calendar with a fresh download. */
    @Synchronized
    fun replaceCalendarEvents(calendarId: String, events: List<EventDto>) {
        val stamped = events.map { it.copy(id = it.id.ifBlank { newId("ev") }, calendarId = calendarId) }
        pim = pim.copy(events = pim.events.filter { it.calendarId != calendarId } + stamped)
        savePim()
    }

    /** Adds events, updating existing ones with the same UID (and RECURRENCE-ID) in that calendar. */
    @Synchronized
    fun importEvents(calendarId: String, events: List<EventDto>): ImportResult {
        var added = 0; var updated = 0
        var list = pim.events
        val now = System.currentTimeMillis()
        for (e in events) {
            val existing = list.firstOrNull { it.calendarId == calendarId && it.uid == e.uid && it.recurrenceId == e.recurrenceId }
            if (existing != null) {
                list = list.map { if (it.id == existing.id) e.copy(id = existing.id, calendarId = calendarId, created = existing.created, updated = now) else it }
                updated++
            } else {
                list = list + e.copy(id = newId("ev"), calendarId = calendarId, created = now, updated = now)
                added++
            }
        }
        pim = pim.copy(events = list)
        savePim()
        return ImportResult(added, updated)
    }

    // --- contacts ----------------------------------------------------------------------------

    /** Every email address in the address book (cached until contacts change). */
    fun contactEmails(): Set<String> = emailCache ?: synchronized(this) {
        pim.contacts.flatMap { c -> c.emails.map { it.value.trim().lowercase() } }.toSet().also { emailCache = it }
    }

    /**
     * Imports contacts, merging into an existing contact with the same email address (blank
     * fields are filled, new emails and phone numbers are added) instead of duplicating it.
     */
    @Synchronized
    fun importContacts(incoming: List<ContactDto>): ImportResult {
        var added = 0; var merged = 0
        var list = pim.contacts
        val now = System.currentTimeMillis()
        for (c in incoming) {
            val emails = c.emails.map { it.value.lowercase() }.toSet()
            val existing = list.firstOrNull { e -> e.emails.any { it.value.lowercase() in emails } }
                ?: list.firstOrNull { e -> emails.isEmpty() && e.bestName().isNotBlank() && e.bestName().equals(c.bestName(), true) }
            if (existing != null) {
                fun pick(a: String, b: String) = a.ifBlank { b }
                val m = existing.copy(
                    prefix = pick(existing.prefix, c.prefix), firstName = pick(existing.firstName, c.firstName), middleName = pick(existing.middleName, c.middleName),
                    lastName = pick(existing.lastName, c.lastName), suffix = pick(existing.suffix, c.suffix), nickname = pick(existing.nickname, c.nickname),
                    displayName = pick(existing.displayName, c.displayName), company = pick(existing.company, c.company), department = pick(existing.department, c.department),
                    jobTitle = pick(existing.jobTitle, c.jobTitle), birthday = pick(existing.birthday, c.birthday), anniversary = pick(existing.anniversary, c.anniversary),
                    website = pick(existing.website, c.website), notes = pick(existing.notes, c.notes),
                    emails = (existing.emails + c.emails).distinctBy { it.value.lowercase() },
                    phones = (existing.phones + c.phones).distinctBy { it.value.filter { ch -> ch.isDigit() } },
                    addresses = if (existing.addresses.isEmpty()) c.addresses else existing.addresses,
                    categories = (existing.categories + c.categories).distinct(), updated = now
                )
                list = list.map { if (it.id == existing.id) m else it }
                merged++
            } else {
                list = list + c.copy(id = newId("ct"), created = now, updated = now)
                added++
            }
        }
        pim = pim.copy(contacts = list)
        savePim()
        return ImportResult(added, merged)
    }

    // --- message metadata ------------------------------------------------------------------------

    @Synchronized fun meta(messageId: String): MessageMetaDto? = meta.meta[messageId.trim()]

    @Synchronized fun allMeta(): Map<String, MessageMetaDto> = meta.meta

    @Synchronized
    fun updateMeta(req: MetaUpdateRequest, info: Map<String, Pair<String, String>> = emptyMap()): Map<String, MessageMetaDto> {
        val now = System.currentTimeMillis()
        val map = HashMap(meta.meta)
        val out = HashMap<String, MessageMetaDto>()
        for (raw in req.messageIds) {
            val id = raw.trim()
            if (id.isEmpty()) continue
            var m = map[id] ?: MessageMetaDto()
            req.categoriesSet?.let { m = m.copy(categories = it.distinct()) }
            if (req.categoriesAdd.isNotEmpty()) m = m.copy(categories = (m.categories + req.categoriesAdd).distinct())
            if (req.categoriesRemove.isNotEmpty()) m = m.copy(categories = m.categories - req.categoriesRemove.toSet())
            req.pinned?.let { m = m.copy(pinned = it) }
            req.flagDue?.let { m = m.copy(flagDue = it.ifBlank { null }, flagCompleted = false) }
            req.flagStart?.let { m = m.copy(flagStart = it.ifBlank { null }) }
            req.flagCompleted?.let { m = m.copy(flagCompleted = it, flagCompletedAt = if (it) now else 0) }
            if (req.clearFlag) m = m.copy(flagDue = null, flagStart = null, flagCompleted = false, flagCompletedAt = 0, reminder = null)
            req.reminder?.let { m = m.copy(reminder = it) }
            if (req.clearReminder) m = m.copy(reminder = null)
            info[id]?.let { (subject, from) -> m = m.copy(subject = subject, from = from) }
            m = m.copy(updated = now)
            if (m.isEmpty()) map.remove(id) else map[id] = m
            out[id] = m
        }
        meta = meta.copy(meta = map)
        saveMeta()
        return out
    }

    @Synchronized
    fun setSnooze(messageId: String, until: Long?, from: String?, subject: String = "", sender: String = "") {
        val id = messageId.trim()
        if (id.isEmpty()) return
        val m = (meta.meta[id] ?: MessageMetaDto()).copy(snoozeUntil = until, snoozedFrom = if (until == null) null else from, subject = subject.ifBlank { meta.meta[id]?.subject.orEmpty() }, from = sender.ifBlank { meta.meta[id]?.from.orEmpty() }, updated = System.currentTimeMillis())
        meta = meta.copy(meta = if (m.isEmpty()) meta.meta - id else meta.meta + (id to m))
        saveMeta()
    }

    @Synchronized
    fun dueSnoozes(now: Long): List<Pair<String, MessageMetaDto>> =
        meta.meta.filter { it.value.snoozeUntil != null && it.value.snoozeUntil!! <= now }.map { it.key to it.value }

    @Synchronized fun ignoredThreads(): Set<String> = meta.ignoredThreads.toSet()

    @Synchronized
    fun setIgnored(roots: Collection<String>, ignore: Boolean) {
        val set = LinkedHashSet(meta.ignoredThreads)
        if (ignore) set.addAll(roots.filter { it.isNotBlank() }) else set.removeAll(roots.toSet())
        meta = meta.copy(ignoredThreads = set.toList().takeLast(2000))
        saveMeta()
    }

    @Synchronized fun autoRepliedAt(sender: String): Long? = meta.autoReplied[sender.lowercase()]

    @Synchronized
    fun markAutoReplied(sender: String) {
        val cutoff = System.currentTimeMillis() - 60L * 86_400_000
        meta = meta.copy(autoReplied = meta.autoReplied.filterValues { it > cutoff } + (sender.lowercase() to System.currentTimeMillis()))
        saveMeta()
    }

    // --- scheduled sends -------------------------------------------------------------------------

    @Synchronized
    fun schedule(req: SendRequest, sendAt: Long): ScheduledSendDto {
        // Never persist credentials: scheduled items are always sent through the signed-in account.
        val item = ScheduledSendDto(newId("out"), sendAt, req.copy(account = null, sendAt = null), System.currentTimeMillis())
        outbox = outbox.copy(items = outbox.items + item)
        saveOutbox()
        return item
    }

    @Synchronized fun outboxItems(): List<ScheduledSendDto> = outbox.items.sortedBy { it.sendAt }

    @Synchronized
    fun removeOutbox(id: String): ScheduledSendDto? {
        val item = outbox.items.firstOrNull { it.id == id } ?: return null
        outbox = outbox.copy(items = outbox.items.filter { it.id != id })
        saveOutbox()
        return item
    }

    @Synchronized
    fun dueOutbox(now: Long): List<ScheduledSendDto> = outbox.items.filter { it.sendAt <= now && it.attempts < 5 }

    /** Records a failed attempt; retries back off by five minutes per attempt. */
    @Synchronized
    fun outboxFailed(id: String, error: String) {
        outbox = outbox.copy(items = outbox.items.map {
            if (it.id == id) it.copy(error = error, attempts = it.attempts + 1, sendAt = System.currentTimeMillis() + (it.attempts + 1) * 300_000L) else it
        })
        saveOutbox()
    }
}
