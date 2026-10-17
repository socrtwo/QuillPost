package info.socrtwo.quillpost.web

import info.socrtwo.quillpost.web.mailstore.Detection
import info.socrtwo.quillpost.web.mailstore.FolderKind
import info.socrtwo.quillpost.web.mailstore.ImportedMimeMessage
import info.socrtwo.quillpost.web.mailstore.MailStore
import info.socrtwo.quillpost.web.mailstore.MailStores
import info.socrtwo.quillpost.web.mailstore.MsgFlags
import info.socrtwo.quillpost.web.mailstore.PimItem
import info.socrtwo.quillpost.web.mailstore.Sniffer
import info.socrtwo.quillpost.web.mailstore.StoreFolder
import info.socrtwo.quillpost.web.mailstore.localSession
import jakarta.mail.Flags
import jakarta.mail.Folder
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

@Serializable
data class ImportFolderDto(val id: String, val name: String, val path: String, val kind: String, val count: Int, val unread: Int, val depth: Int)

@Serializable
data class MailboxAnalysis(
    val upload: String,
    val name: String,
    val detection: Detection,
    val folders: List<ImportFolderDto> = emptyList(),
    val totalMessages: Int = 0,
    val totalItems: Int = 0,
    val warnings: List<String> = emptyList(),
    /** For a single message file: where it was opened (the Opened files folder of Local Archives). */
    val opened: MessageRef? = null
)

@Serializable
data class ImportRequest(
    val upload: String,
    /** Folder ids to import; empty = all. */
    val folders: List<String> = emptyList(),
    /** "archive" (Local Archives) or "imap" (the signed-in account). */
    val destination: String = "archive",
    /** IMAP: the folder the imported tree goes under ("" = top level). */
    val imapParent: String = "",
    val archiveName: String = "",
    /** Import contacts, calendar items and tasks into People, Calendar and To Do. */
    val includePim: Boolean = true,
    /** Also import messages the source had marked as deleted. */
    val includeDeleted: Boolean = false
)

@Serializable
data class ImportJobDto(
    val id: String,
    val state: String,
    val total: Int,
    val done: Int,
    val imported: Int,
    val skipped: Int,
    val current: String = "",
    val contacts: Int = 0,
    val events: Int = 0,
    val tasks: Int = 0,
    val report: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val archiveId: String? = null,
    val firstFolder: String? = null,
    val error: String? = null
)

/**
 * The import pipeline for one account: receives uploaded files (one file, many files with their
 * relative paths, or a .zip/.tar/.gz), works out what they are, opens mail stores, and copies the
 * chosen folders into an IMAP folder of the account (APPEND with the original flags and arrival
 * time) or into Local Archives — in the background, with progress, cancel and a report.
 */
class MailImport(private val ctx: AccountContext, private val tmpRoot: File) {
    private class Upload(val id: String, val dir: File) {
        var store: MailStore? = null
        var index: Map<String, StoreFolder> = emptyMap()
        var name: String = ""
        var detection: Detection? = null
        val created = System.currentTimeMillis()
    }

    private class Job(val id: String) {
        @Volatile var state = "running"
        @Volatile var total = 0
        @Volatile var done = 0
        @Volatile var imported = 0
        @Volatile var skipped = 0
        @Volatile var current = ""
        @Volatile var contacts = 0
        @Volatile var events = 0
        @Volatile var tasks = 0
        @Volatile var cancelled = false
        @Volatile var archiveId: String? = null
        @Volatile var firstFolder: String? = null
        @Volatile var error: String? = null
        val report: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())
        val warnings: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())
        fun dto() = ImportJobDto(id, state, total, done, imported, skipped, current, contacts, events, tasks, report.take(500), warnings.take(200), archiveId, firstFolder, error)
    }

    private val uploads = ConcurrentHashMap<String, Upload>()
    private val jobs = ConcurrentHashMap<String, Job>()

    private fun newId() = java.util.UUID.randomUUID().toString().replace("-", "").take(20)

    private fun upload(id: String): Upload = uploads[id] ?: throw IllegalArgumentException("That upload has expired; open the file again")

    /** Saves one uploaded file ([relPath] keeps folder structure for folder uploads). Returns the upload id. */
    fun receive(uploadId: String?, relPath: String, input: InputStream): String {
        cleanup()
        val up = uploadId?.takeIf { it.isNotBlank() }?.let { upload(it) } ?: Upload(newId(), File(tmpRoot, newId())).also { uploads[it.id] = it }
        val safe = relPath.replace('\\', '/').split('/').filter { it.isNotBlank() && it != "." && it != ".." }.joinToString("/") { it.take(200) }.ifBlank { "upload.bin" }
        val target = File(File(up.dir, "in"), safe)
        require(target.canonicalPath.startsWith(File(up.dir, "in").canonicalPath + File.separator)) { "Bad file name" }
        target.parentFile.mkdirs()
        target.outputStream().use { input.copyTo(it, 1 shl 16) }
        up.store?.close(); up.store = null
        return up.id
    }

    fun discard(uploadId: String) {
        uploads.remove(uploadId)?.let { u -> runCatching { u.store?.close() }; u.dir.deleteRecursively() }
    }

    private fun cleanup() {
        val cutoff = System.currentTimeMillis() - 6 * 3600_000L
        for (u in uploads.values.toList()) if (u.created < cutoff && jobs.values.none { it.state == "running" }) discard(u.id)
    }

    /** Unpacks archives and decides what was uploaded. */
    fun analyze(uploadId: String): MailboxAnalysis {
        val up = upload(uploadId)
        val input = File(up.dir, "in")
        val files = input.walk().filter { it.isFile }.toList()
        require(files.isNotEmpty()) { "Nothing was uploaded" }
        var target: File = if (files.size == 1) files[0] else input.listFiles()?.singleOrNull()?.takeIf { it.isDirectory } ?: input
        var name = if (files.size == 1) files[0].name else target.name.takeIf { target != input } ?: files.first().relativeTo(input).path.substringBefore('/')
        // Unpack .zip / .gz / .tar (once or twice, e.g. .tgz) unless the zip is an Office/OpenDocument file.
        repeat(2) {
            if (!target.isFile) return@repeat
            val det = Sniffer.sniffFile(target, name)
            if (det.category != "archive") return@repeat
            if (!det.supported) throw IllegalArgumentException(det.note)
            val out = File(up.dir, "x${System.nanoTime()}")
            when (det.format) {
                "zip" -> unzip(target, out)
                "gzip" -> { out.mkdirs(); val f = File(out, MailStores.baseName(name).ifBlank { "file" }); GZIPInputStream(target.inputStream()).use { i -> f.outputStream().use { i.copyTo(it) } } }
                "tar" -> untar(target.inputStream(), out)
            }
            val inner = out.walk().filter { it.isFile }.toList()
            target = if (inner.size == 1) inner[0] else out.listFiles()?.singleOrNull()?.takeIf { it.isDirectory } ?: out
            name = if (inner.size == 1) inner[0].name else MailStores.baseName(name)
        }
        val det = if (target.isDirectory) Sniffer.sniffDir(target) else Sniffer.sniffFile(target, name)
        up.name = MailStores.baseName(name); up.detection = det
        if (det.category == "message") {
            val parsed = MessageImport.parse(target.readBytes(), name)
            val ref = openMessage(parsed)
            return MailboxAnalysis(up.id, name, det.copy(label = parsed.formatLabel.ifBlank { det.label }), warnings = parsed.warnings, opened = ref)
        }
        if (det.category != "mailbox" || !det.supported) return MailboxAnalysis(up.id, name, det)
        up.store?.close()
        val store = MailStores.open(target, name)
        up.store = store
        up.index = store.index()
        val folders = store.root.walk().map { (path, f) ->
            ImportFolderDto(f.id, f.name, path.joinToString("/"), f.kind.name.lowercase(), runCatching { f.count }.getOrDefault(0), f.unread, path.size - 1)
        }
        return MailboxAnalysis(
            up.id, name, store.detection.copy(note = store.detection.note.ifBlank { det.note }), folders,
            folders.filter { it.kind == "mail" || it.kind == "notes" || it.kind == "journal" }.sumOf { it.count },
            folders.filter { it.kind == "contacts" || it.kind == "calendar" || it.kind == "tasks" }.sumOf { it.count },
            store.warnings.toList()
        )
    }

    /** Stores a single message in Local Archives ▸ Opened files and returns where it is. */
    fun openMessage(p: ParsedMessage): MessageRef {
        val w = ctx.archives.opened()
        val uid = w.append("files", p.mime, p.flags, p.internalDate, emptyList())
        w.finish()
        return MessageRef("${LocalArchives.PREFIX}${w.id}/files", uid)
    }

    private fun unzip(zip: File, out: File) {
        out.mkdirs()
        val base = out.canonicalPath + File.separator
        var total = 0L
        ZipFile(zip).use { z ->
            for (e in z.entries()) {
                val f = File(out, e.name)
                require(f.canonicalPath.startsWith(base)) { "The archive contains an unsafe path: ${e.name}" }
                if (e.isDirectory) { f.mkdirs(); continue }
                f.parentFile.mkdirs()
                z.getInputStream(e).use { i -> f.outputStream().use { o -> total += i.copyTo(o) } }
                require(total < 64L * 1024 * 1024 * 1024) { "The archive is too large" }
            }
        }
    }

    /** Minimal ustar reader (Google Takeout and Unix backups ship .tgz). */
    private fun untar(input: InputStream, out: File) {
        out.mkdirs()
        val base = out.canonicalPath + File.separator
        val raw = if (input.markSupported()) input else input.buffered()
        raw.use { s ->
            val hdr = ByteArray(512)
            var longName: String? = null
            while (true) {
                if (s.readNBytes(hdr, 0, 512) < 512 || hdr.all { it == 0.toByte() }) break
                val name0 = String(hdr, 0, 100, Charsets.UTF_8).substringBefore('\u0000')
                val prefix = String(hdr, 345, 155, Charsets.UTF_8).substringBefore('\u0000')
                val size = String(hdr, 124, 12, Charsets.US_ASCII).trim('\u0000', ' ').toLongOrNull(8) ?: 0L
                val type = hdr[156].toInt().toChar()
                val name = longName ?: if (prefix.isNotEmpty()) "$prefix/$name0" else name0
                longName = null
                val padded = (size + 511) / 512 * 512
                when (type) {
                    'L' -> { longName = String(s.readNBytes(size.toInt()), Charsets.UTF_8).substringBefore('\u0000'); s.skipNBytes(padded - size); continue }
                    '0', '\u0000' -> {
                        val f = File(out, name)
                        require(f.canonicalPath.startsWith(base)) { "The archive contains an unsafe path: $name" }
                        f.parentFile.mkdirs()
                        f.outputStream().use { o -> var left = size; val buf = ByteArray(65536); while (left > 0) { val n = s.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n <= 0) break; o.write(buf, 0, n); left -= n } }
                        s.skipNBytes(padded - size)
                    }
                    '5' -> { File(out, name).mkdirs(); s.skipNBytes(padded) }
                    else -> s.skipNBytes(padded)
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------- jobs

    fun job(id: String): ImportJobDto = (jobs[id] ?: throw IllegalArgumentException("Unknown import")).dto()

    fun cancel(id: String): ImportJobDto { val j = jobs[id] ?: throw IllegalArgumentException("Unknown import"); j.cancelled = true; return j.dto() }

    fun start(req: ImportRequest): ImportJobDto {
        val up = upload(req.upload)
        val store = up.store ?: throw IllegalArgumentException("Open the mailbox again before importing")
        require(req.destination == "archive" || req.destination == "imap") { "Unknown destination" }
        if (req.destination == "imap") require(ctx.mail.isImap) { "This account uses POP3, which has no server folders; import into Local Archives instead" }
        require(jobs.values.none { it.state == "running" }) { "Another import is still running" }
        val chosen = if (req.folders.isEmpty()) up.index.values.filter { it.id.isNotEmpty() }.toSet() else req.folders.mapNotNull { up.index[it] }.toSet()
        require(chosen.isNotEmpty()) { "Choose at least one folder" }
        val job = Job(newId())
        jobs[job.id] = job
        job.total = chosen.sumOf { runCatching { it.count }.getOrDefault(0) }
        ctx.scope.launch {
            try {
                run(job, up, store, chosen, req)
                job.state = if (job.cancelled) "cancelled" else "done"
            } catch (e: Throwable) {
                job.error = e.message ?: e.javaClass.simpleName
                job.state = "failed"
            } finally {
                job.current = ""
                job.warnings += store.warnings.filter { it !in job.warnings }
                ctx.bump()
            }
        }
        return job.dto()
    }

    private suspend fun run(job: Job, up: Upload, store: MailStore, chosen: Set<StoreFolder>, req: ImportRequest) {
        val tree = store.root.walk()
        val pathOf = tree.associate { (p, f) -> f to p }
        val writer = if (req.destination == "archive") ctx.archives.create(req.archiveName.ifBlank { up.name.ifBlank { "Imported mail" } }, up.name, store.detection.format, store.detection.label).also { job.archiveId = it.id } else null
        val archiveIds = HashMap<StoreFolder, String>()
        val delim = if (writer == null) ctx.io { ctx.mail.connect().defaultFolder.separator.toString() } else "/"
        fun clean(n: String) = n.replace(delim, "_").replace(Regex("[\\u0000-\\u001f*%]"), "_").trim().ifBlank { "Folder" }

        if (writer == null && req.imapParent.isNotBlank()) {
            // Create the destination chain first, so it is a real (listable) folder on every server.
            val parts = req.imapParent.split(delim).filter { it.isNotBlank() }
            for (i in parts.indices) ensureImapFolder(parts.take(i + 1).joinToString(delim))
        }
        fun parentChainSelected(f: StoreFolder): Boolean = tree.any { (p, g) -> g in chosen && pathOf[g]!!.size > pathOf[f]!!.size && pathOf[g]!!.take(pathOf[f]!!.size) == pathOf[f] }

        for ((path, folder) in tree) {
            if (job.cancelled) break
            val selected = folder in chosen
            if (!selected && !(writer != null && parentChainSelected(folder))) continue
            val parentPath = path.dropLast(1)
            val parent = tree.firstOrNull { it.first == parentPath }?.second
            job.current = path.joinToString(" / ")
            if (folder.kind == FolderKind.CONTACTS || folder.kind == FolderKind.CALENDAR || folder.kind == FolderKind.TASKS) {
                if (selected && req.includePim) importPim(job, folder)
                else if (selected) { job.skipped += folder.count; job.report += "${path.joinToString("/")}: ${folder.count} ${folder.kind.name.lowercase()} items not imported (not selected)" }
                job.done += if (selected) folder.count else 0
                continue
            }
            if (writer != null) {
                val fid = writer.addFolder(folder.name, parent?.let { archiveIds[it] })
                archiveIds[folder] = fid
                if (job.firstFolder == null && selected && folder.count > 0) job.firstFolder = "${LocalArchives.PREFIX}${writer.id}/$fid"
                if (!selected) continue
                for (m in folder.messages()) {
                    if (job.cancelled) break
                    if (m.flags.deleted && !req.includeDeleted) { job.skipped++; job.done++; job.report += "${path.joinToString("/")}: a message marked as deleted was skipped"; continue }
                    runCatching { writer.append(fid, m.raw(), m.flags, m.internalDate, m.keywords) }
                        .onSuccess { job.imported++ }
                        .onFailure { job.skipped++; job.report += "${path.joinToString("/")}: ${it.message ?: "unreadable message"}" }
                    m.notes.forEach { job.report += "${path.joinToString("/")}: $it" }
                    job.done++
                }
            } else {
                val target = (listOf(req.imapParent).filter { it.isNotBlank() } + path.map { clean(it) }).joinToString(delim)
                ensureImapFolder(target)
                if (job.firstFolder == null && folder.count > 0) job.firstFolder = target
                val batch = ArrayList<ImportedMimeMessage>()
                suspend fun flush() {
                    if (batch.isEmpty()) return
                    val ok = runCatching { ctx.io { appendBatch(target, batch) } }
                    if (ok.isSuccess) job.imported += batch.size
                    else for (msg in batch) {
                        // Retry one by one, then without keywords (some servers refuse new keywords).
                        val single = runCatching { ctx.io { appendBatch(target, listOf(msg)) } }.recoverCatching {
                            val sys = Flags(); msg.flags.systemFlags.forEach { sf -> sys.add(sf) }
                            msg.setFlags(msg.flags, false); msg.setFlags(sys, true)
                            ctx.io { appendBatch(target, listOf(msg)) }
                        }
                        if (single.isSuccess) job.imported++ else { job.skipped++; job.report += "${path.joinToString("/")}: ${runCatching { msg.subject }.getOrNull() ?: "message"} — ${single.exceptionOrNull()?.message}" }
                    }
                    batch.clear()
                }
                for (m in folder.messages()) {
                    if (job.cancelled) break
                    if (m.flags.deleted && !req.includeDeleted) { job.skipped++; job.done++; job.report += "${path.joinToString("/")}: a message marked as deleted was skipped"; continue }
                    val msg = runCatching { toMime(m.raw(), m.flags, m.internalDate, m.keywords) }
                    msg.onSuccess { batch += it }.onFailure { job.skipped++; job.report += "${path.joinToString("/")}: ${it.message ?: "unreadable message"}" }
                    m.notes.forEach { job.report += "${path.joinToString("/")}: $it" }
                    job.done++
                    if (batch.size >= 20) flush()
                }
                flush()
            }
        }
        writer?.finish()
        if (job.cancelled) job.report += "Cancelled by you after ${job.done} of ${job.total} items; everything copied before that was kept."
    }

    private fun toMime(raw: ByteArray, flags: MsgFlags, internalDate: Long?, keywords: List<String>): ImportedMimeMessage {
        val m = ImportedMimeMessage(localSession, ByteArrayInputStream(raw))
        m.internalDate = internalDate?.let { Date(it) }
        val f = Flags()
        if (flags.seen) f.add(Flags.Flag.SEEN)
        if (flags.answered) f.add(Flags.Flag.ANSWERED)
        if (flags.flagged) f.add(Flags.Flag.FLAGGED)
        if (flags.draft) f.add(Flags.Flag.DRAFT)
        if (flags.forwarded) f.add("\$Forwarded")
        for (k in keywords) imapKeyword(k)?.let { f.add(it) }
        m.setFlags(f, true)
        return m
    }

    /** IMAP keywords are atoms: no spaces, parentheses, quotes, brackets, % * or backslash (except system $ forms). */
    private fun imapKeyword(k: String): String? {
        val s = k.trim().replace(Regex("[\\s(){}\\[\\]\"%*\\\\]+"), "_").take(100)
        return s.takeIf { it.isNotEmpty() && it.all { c -> c.code in 0x21..0x7E } }
    }

    private fun appendBatch(path: String, msgs: List<ImportedMimeMessage>) {
        val f = ctx.mail.connect().getFolder(path)
        if (!f.exists()) f.create(Folder.HOLDS_MESSAGES or Folder.HOLDS_FOLDERS)
        f.appendMessages(msgs.toTypedArray())
    }

    private suspend fun ensureImapFolder(path: String) = ctx.io {
        val s = ctx.mail.connect()
        val f = s.getFolder(path)
        if (!f.exists()) {
            if (!runCatching { f.create(Folder.HOLDS_MESSAGES or Folder.HOLDS_FOLDERS) }.getOrDefault(false)) f.create(Folder.HOLDS_MESSAGES)
            runCatching { f.setSubscribed(true) }
        }
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun importPim(job: Job, folder: StoreFolder) {
        val items: List<PimItem> = folder.pimItems()
        when (folder.kind) {
            FolderKind.CONTACTS -> {
                val (groups, cards) = items.partition { it.text.contains("KIND:group") }
                if (cards.isNotEmpty()) {
                    val r = ctx.importContacts(cards.joinToString("") { it.text }, "vcf")
                    job.contacts += r.imported + r.updated; job.imported += r.imported + r.updated
                    r.errors.forEach { job.report += "${folder.name}: $it" }
                }
                for (g in groups) {
                    val name = Regex("(?m)^FN:(.*)$").find(g.text)?.groupValues?.get(1)?.trim()?.replace("\\,", ",").orEmpty().ifBlank { "Contact group" }
                    val members = Regex("(?m)^MEMBER:mailto:(.*)$").findAll(g.text).map { ListMember(email = it.groupValues[1].trim()) }.toList()
                    runCatching { ctx.pim.upsert("contactLists", json.encodeToJsonElement(ContactListDto.serializer(), ContactListDto(name = name, members = members)) as JsonObject) }
                        .onSuccess { job.contacts++; job.imported++ }.onFailure { job.report += "${folder.name}: contact group \"$name\" — ${it.message}" }
                }
            }
            FolderKind.CALENDAR -> {
                if (items.isEmpty()) return
                val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//QuillPost//Mailbox import//EN\r\n" + items.joinToString("\r\n") { it.text } + "\r\nEND:VCALENDAR\r\n"
                val r = ctx.importIcs(ics, null, "")
                job.events += r.imported + r.updated; job.imported += r.imported + r.updated
                r.errors.forEach { job.report += "${folder.name}: $it" }
            }
            FolderKind.TASKS -> for (t in items) {
                val task = vtodoToTask(t.text) ?: continue
                runCatching { ctx.pim.upsert("tasks", json.encodeToJsonElement(TaskDto.serializer(), task) as JsonObject) }
                    .onSuccess { job.tasks++; job.imported++ }.onFailure { job.report += "${folder.name}: task \"${task.title}\" — ${it.message}" }
            }
            else -> {}
        }
        ctx.bump()
    }

    private fun vtodoToTask(text: String): TaskDto? {
        fun prop(n: String) = Regex("(?m)^$n(;[^:\\r\\n]*)?:(.*)$").find(text)?.groupValues?.get(2)?.trim()?.replace("\\n", "\n")?.replace("\\,", ",")?.replace("\\;", ";")
        val title = prop("SUMMARY")?.takeIf { it.isNotBlank() } ?: return null
        val due = prop("DUE")?.take(8)?.takeIf { it.length == 8 }?.let { "${it.substring(0, 4)}-${it.substring(4, 6)}-${it.substring(6)}" }
        val done = prop("STATUS") == "COMPLETED"
        val cats = prop("CATEGORIES")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        return TaskDto(title = title, notes = prop("DESCRIPTION").orEmpty(), due = due, important = prop("PRIORITY") == "1", completed = done, categories = cats)
    }
}
