package info.socrtwo.quillpost.web

import info.socrtwo.quillpost.web.mailstore.ImportedMimeMessage
import info.socrtwo.quillpost.web.mailstore.Mbox
import info.socrtwo.quillpost.web.mailstore.MsgFlags
import info.socrtwo.quillpost.web.mailstore.localSession
import jakarta.mail.Flags
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class ArchiveFolderMeta(
    val id: String,
    val name: String,
    val parentId: String? = null,
    val count: Int = 0,
    val unread: Int = 0
)

@Serializable
data class ArchiveMeta(
    val id: String,
    val name: String,
    val sourceName: String = "",
    val format: String = "",
    val formatLabel: String = "",
    val created: Long = 0,
    val folders: List<ArchiveFolderMeta> = emptyList()
)

/** One message in an archive folder's index (positions in the folder's mbox file plus list-pane fields). */
@Serializable
data class ArchiveEntry(
    val uid: Long,
    val o: Long,
    val l: Int,
    val date: Long = 0,
    val internalDate: Long? = null,
    val subject: String = "",
    val fromName: String = "",
    val fromAddress: String = "",
    val to: List<String> = emptyList(),
    val cc: List<String> = emptyList(),
    val messageId: String = "",
    val inReplyTo: String? = null,
    val references: List<String> = emptyList(),
    val importance: String = "normal",
    val size: Long = 0,
    val att: Boolean = false,
    val flags: String = "",
    val keywords: List<String> = emptyList(),
    val preview: String = ""
)

/**
 * "Local Archives": a read-only mail store in the account's data folder for mailboxes imported
 * from other programs. Each archive (one per import) holds one mbox file plus a JSON index per
 * folder; the folders appear in the folder pane under Local Archives with paths
 * `@archive/<archive>/<folder>`, and can be listed, read, searched and exported like server
 * folders. Message content cannot change; read and flag status can.
 *
 * The archive "opened" holds single message files opened with File ▸ Open.
 */
class LocalArchives(private val dir: File, private val mail: MailSession) {
    companion object {
        const val PREFIX = "@archive/"
        const val OPENED = "opened"
        fun isArchive(path: String?) = path != null && path.startsWith(PREFIX)
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        private val entryList = ListSerializer(ArchiveEntry.serializer())
    }

    private val indexCache = ConcurrentHashMap<String, MutableList<ArchiveEntry>>()

    init { dir.mkdirs() }

    private fun archiveDir(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Bad archive id" }
        return File(dir, id)
    }

    fun list(): List<ArchiveMeta> = (dir.listFiles() ?: emptyArray()).filter { it.isDirectory }.mapNotNull { meta(it.name) }
        .sortedWith(compareBy({ it.id != OPENED }, { it.created }))

    fun meta(id: String): ArchiveMeta? = runCatching { json.decodeFromString(ArchiveMeta.serializer(), File(archiveDir(id), "archive.json").readText()) }.getOrNull()

    private fun saveMeta(m: ArchiveMeta) {
        val f = File(archiveDir(m.id), "archive.json")
        f.parentFile.mkdirs()
        val tmp = File(f.parentFile, "archive.json.tmp")
        tmp.writeText(json.encodeToString(ArchiveMeta.serializer(), m))
        tmp.renameTo(f) || run { f.delete(); tmp.renameTo(f) }
    }

    fun delete(id: String): Boolean {
        val d = archiveDir(id)
        indexCache.keys.removeIf { it.startsWith("$id/") }
        return d.isDirectory && d.deleteRecursively()
    }

    /** Folder-pane entries: one top-level entry per archive and its folders below it. */
    fun folders(): List<FolderDto> {
        val out = ArrayList<FolderDto>()
        for (a in list()) {
            val byParent = a.folders.groupBy { it.parentId }
            fun add(f: ArchiveFolderMeta, depth: Int) {
                out += FolderDto("$PREFIX${a.id}/${f.id}", f.name, "localarchive", "/", f.unread, f.count, depth)
                byParent[f.id].orEmpty().forEach { add(it, depth + 1) }
            }
            byParent[null].orEmpty().forEach { add(it, 1) }
        }
        return out
    }

    data class Ref(val archive: String, val folder: String)

    fun ref(path: String): Ref {
        require(isArchive(path)) { "Not a Local Archives folder" }
        val rest = path.removePrefix(PREFIX)
        val a = rest.substringBefore('/'); val f = rest.substringAfter('/', "")
        require(a.isNotEmpty() && f.matches(Regex("[A-Za-z0-9_.-]{1,64}"))) { "Bad archive folder" }
        archiveDir(a)
        return Ref(a, f)
    }

    private fun mboxFile(r: Ref) = File(archiveDir(r.archive), "${r.folder}.mbox")
    private fun indexFile(r: Ref) = File(archiveDir(r.archive), "${r.folder}.idx.json")

    @Synchronized
    fun entries(r: Ref): MutableList<ArchiveEntry> = indexCache.getOrPut("${r.archive}/${r.folder}") {
        val f = indexFile(r)
        if (f.isFile) runCatching { json.decodeFromString(entryList, f.readText()).toMutableList() }.getOrElse { ArrayList() } else ArrayList()
    }

    @Synchronized
    private fun saveEntries(r: Ref, list: List<ArchiveEntry>) {
        val f = indexFile(r)
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(json.encodeToString(entryList, list))
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    fun raw(path: String, uid: Long): ByteArray? {
        val r = ref(path)
        val e = entries(r).firstOrNull { it.uid == uid } ?: return null
        val bytes = RandomAccessFile(mboxFile(r), "r").use { raf -> ByteArray(e.l).also { raf.seek(e.o); raf.readFully(it) } }
        return Mbox.unquote(bytes)
    }

    fun mime(path: String, uid: Long): ImportedMimeMessage? {
        val raw = raw(path, uid) ?: return null
        val e = entries(ref(path)).first { it.uid == uid }
        return ImportedMimeMessage(localSession, ByteArrayInputStream(raw)).also { m ->
            val fl = MsgFlags.parse(e.flags)
            if (fl.seen) m.setFlag(Flags.Flag.SEEN, true)
            if (fl.flagged) m.setFlag(Flags.Flag.FLAGGED, true)
            if (fl.answered) m.setFlag(Flags.Flag.ANSWERED, true)
            if (fl.draft) m.setFlag(Flags.Flag.DRAFT, true)
            val kw = e.keywords + (if (fl.forwarded) listOf("\$Forwarded") else emptyList())
            if (kw.isNotEmpty()) m.setFlags(Flags().apply { kw.forEach { add(it) } }, true)
        }
    }

    fun summary(path: String, e: ArchiveEntry): MessageSummaryDto {
        val f = MsgFlags.parse(e.flags)
        return MessageSummaryDto(
            uid = e.uid, folder = path, messageId = e.messageId, fromName = e.fromName, fromAddress = e.fromAddress, to = e.to,
            subject = e.subject, preview = e.preview, date = e.date, read = f.seen, flagged = f.flagged, answered = f.answered,
            hasAttachments = e.att, size = e.size, cc = e.cc, inReplyTo = e.inReplyTo, references = e.references,
            importance = e.importance, forwarded = f.forwarded, categories = e.keywords.filter { !it.startsWith("$") }, keywords = e.keywords
        )
    }

    fun listMessages(path: String, limit: Int, offset: Int): MessageListResponse {
        val list = entries(ref(path)).sortedByDescending { it.date }
        val page = list.drop(offset).take(limit)
        return MessageListResponse(path, list.size, list.count { !MsgFlags.parse(it.flags).seen }, page.map { summary(path, it) })
    }

    /** Matches the query in sender, recipients and subject, then in the bodies of the remaining messages. */
    fun search(path: String, q: String, limit: Int): SearchResponse {
        val r = ref(path)
        val needle = q.lowercase()
        val list = entries(r).sortedByDescending { it.date }
        val hits = ArrayList<ArchiveEntry>()
        var scanned = 0
        for (e in list) {
            if (hits.size >= limit) break
            val head = "${e.subject} ${e.fromName} ${e.fromAddress} ${e.to.joinToString(" ")} ${e.cc.joinToString(" ")} ${e.preview} ${e.keywords.joinToString(" ")}".lowercase()
            if (head.contains(needle)) { hits += e; continue }
            if (scanned++ > 20000) continue
            val body = runCatching { mime(path, e.uid)?.let { m -> val raw = mail.localMessage(m, e.uid); raw.bodyText + " " + (raw.bodyHtml ?: "") + " " + raw.attachments.joinToString(" ") { it.fileName } } }.getOrNull()
            if (body != null && body.lowercase().contains(needle)) hits += e
        }
        return SearchResponse(path, q, hits.map { summary(path, it) })
    }

    @Synchronized
    fun setFlags(path: String, uids: Collection<Long>, read: Boolean?, flagged: Boolean?): Int {
        val r = ref(path)
        val list = entries(r)
        var n = 0
        for (i in list.indices) {
            val e = list[i]
            if (e.uid !in uids) continue
            var f = MsgFlags.parse(e.flags)
            read?.let { f = f.copy(seen = it) }
            flagged?.let { f = f.copy(flagged = it) }
            list[i] = e.copy(flags = f.code()); n++
        }
        if (n > 0) { saveEntries(r, list); refreshCounts(r) }
        return n
    }

    private fun refreshCounts(r: Ref) {
        val m = meta(r.archive) ?: return
        val list = entries(r)
        saveMeta(m.copy(folders = m.folders.map { if (it.id == r.folder) it.copy(count = list.size, unread = list.count { e -> !MsgFlags.parse(e.flags).seen }) else it }))
    }

    // ------------------------------------------------------------------------------- writing

    /** Starts a new archive; folders and messages are added through the returned writer. */
    fun create(name: String, sourceName: String, format: String, formatLabel: String): Writer {
        val id = "a" + System.currentTimeMillis().toString(36) + (1000..9999).random()
        val m = ArchiveMeta(id, name, sourceName, format, formatLabel, System.currentTimeMillis())
        saveMeta(m)
        return Writer(m)
    }

    /** The archive that holds opened message files. */
    fun opened(): Writer {
        val m = meta(OPENED) ?: ArchiveMeta(OPENED, "Opened files", "", "", "", System.currentTimeMillis(), listOf(ArchiveFolderMeta("files", "Opened files"))).also { saveMeta(it) }
        return Writer(m)
    }

    inner class Writer(private var meta: ArchiveMeta) {
        val id: String get() = meta.id
        private var nextFolder = (meta.folders.mapNotNull { it.id.removePrefix("f").toIntOrNull() }.maxOrNull() ?: 0) + 1

        @Synchronized
        fun addFolder(name: String, parentId: String?): String {
            val fid = "f${nextFolder++}"
            meta = meta.copy(folders = meta.folders + ArchiveFolderMeta(fid, name, parentId))
            saveMeta(meta)
            return fid
        }

        /** Appends a message; returns its uid in the folder. */
        @Synchronized
        fun append(folderId: String, raw: ByteArray, flags: MsgFlags, internalDate: Long?, keywords: List<String>): Long {
            val r = Ref(meta.id, folderId)
            val list = entries(r)
            val uid = (list.maxOfOrNull { it.uid } ?: 0L) + 1
            val file = mboxFile(r)
            val start = if (file.exists()) file.length() else 0L
            val m = ImportedMimeMessage(localSession, ByteArrayInputStream(raw))
            val parsed = runCatching { mail.localMessage(m, uid) }.getOrNull()
            val s = parsed?.summary
            val date = s?.date?.takeIf { it > 0 } ?: internalDate ?: 0L
            val (sepLen, len) = FileOutputStream(file, true).buffered().use { out -> Mbox.writeEntry(out, raw, internalDate ?: date.takeIf { it > 0 }, s?.fromAddress ?: "") }
            list += ArchiveEntry(
                uid = uid, o = start + sepLen, l = len.toInt(), date = date, internalDate = internalDate,
                subject = s?.subject ?: "(no subject)", fromName = s?.fromName.orEmpty(), fromAddress = s?.fromAddress.orEmpty(),
                to = s?.to.orEmpty(), cc = s?.cc.orEmpty(), messageId = s?.messageId.orEmpty(),
                inReplyTo = s?.headers?.get("In-Reply-To")?.firstOrNull()?.let { Mapping.messageIds(it).lastOrNull() },
                references = s?.headers?.get("References")?.joinToString(" ")?.let { Mapping.messageIds(it) } ?: emptyList(),
                importance = s?.headers?.let { Mapping.importanceOf(it) } ?: "normal",
                size = raw.size.toLong(), att = parsed?.attachments?.any { !it.inline } ?: false,
                flags = flags.code(), keywords = keywords,
                preview = parsed?.let { Mapping.preview(it.bodyText, it.bodyHtml) }.orEmpty()
            )
            return uid
        }

        /** Writes the folder indexes and counts. */
        @Synchronized
        fun finish() {
            meta = meta(meta.id) ?: meta
            meta = meta.copy(folders = meta.folders.map { f ->
                val r = Ref(meta.id, f.id)
                val list = entries(r)
                saveEntries(r, list)
                f.copy(count = list.size, unread = list.count { !MsgFlags.parse(it.flags).seen })
            })
            saveMeta(meta)
        }
    }
}
