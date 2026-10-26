package info.socrtwo.quillpost.web.mailstore

import kotlinx.serialization.Serializable
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/*
 * The common model every mailbox reader produces: a tree of folders, each yielding messages as raw
 * RFC 5322 bytes plus the status the source program kept (read, replied, flagged…), the arrival
 * time (IMAP INTERNALDATE) and keywords (categories, labels, tags). Contacts, calendar and task
 * folders yield vCard / iCalendar text instead. Readers never load a whole mail store into memory:
 * a folder lists its messages lazily and each message reads its bytes when asked.
 */

/** What a file or folder is, decided from its bytes (the extension is only a tie-breaker). */
@Serializable
data class Detection(
    /** mailbox, message, document, spreadsheet, presentation, calendar, contacts, archive, unknown */
    val category: String,
    /** Short id: mbox, maildir, mh, emlx, apple-mail, eudora, dbx, oe4, pst, ost, pegasus, thebat, nsf, eml, msg, docx… */
    val format: String,
    /** Human description: "Outlook data file (Unicode PST)". */
    val label: String,
    /** The program (family) that writes it. */
    val program: String = "",
    /** mboxo / mboxrd / mboxcl / mboxcl2, ANSI / Unicode, … */
    val variant: String = "",
    /** False when Anne's Post recognises the format but cannot read it (the note says why). */
    val supported: Boolean = true,
    val note: String = "",
    val mime: String = ""
)

/** Message status as the source kept it. */
@Serializable
data class MsgFlags(
    val seen: Boolean = false,
    val answered: Boolean = false,
    val flagged: Boolean = false,
    val deleted: Boolean = false,
    val draft: Boolean = false,
    val forwarded: Boolean = false
) {
    /** Compact form for indexes: S A F D T W. */
    fun code(): String = buildString {
        if (seen) append('S'); if (answered) append('A'); if (flagged) append('F')
        if (deleted) append('D'); if (draft) append('T'); if (forwarded) append('W')
    }

    companion object {
        fun parse(s: String?): MsgFlags {
            val c = s.orEmpty()
            return MsgFlags('S' in c, 'A' in c, 'F' in c, 'D' in c, 'T' in c, 'W' in c)
        }
    }
}

/** One message of a mail store. [raw] reads (and, for binary stores, converts) it on demand. */
class StoreMessage(
    val flags: MsgFlags = MsgFlags(),
    /** When the message arrived (epoch ms) — becomes the IMAP INTERNALDATE; null = take it from the headers. */
    val internalDate: Long? = null,
    /** Categories, labels and tags. */
    val keywords: List<String> = emptyList(),
    /** Problems found while reading this message (e.g. an attachment that could not be resolved). */
    val notes: List<String> = emptyList(),
    private val loader: () -> ByteArray
) {
    fun raw(): ByteArray = loader()
}

enum class FolderKind { MAIL, CONTACTS, CALENDAR, TASKS, NOTES, JOURNAL }

/** A contact (as [contact], or a vCard 3.0 group), event (VEVENT) or task (VTODO) taken from a PIM folder. */
data class PimItem(val kind: FolderKind, val text: String = "", val contact: info.socrtwo.quillpost.web.ContactDto? = null)

/** A folder in the source store. The root folder of a store is a container; its own messages (if any) are shown as a folder too. */
class StoreFolder(
    val name: String,
    val kind: FolderKind = FolderKind.MAIL,
    private val counter: (() -> Int)? = null,
    private val lister: () -> Sequence<StoreMessage> = { emptySequence() },
    private val pimLister: () -> List<PimItem> = { emptyList() }
) {
    val children: MutableList<StoreFolder> = ArrayList()
    /** Stable id within the store ("1", "1.3"…), assigned by [MailStore.index]. */
    var id: String = ""
    /** Unread count where the source records it cheaply (-1 unknown). */
    var unread: Int = -1

    private var cachedCount: Int? = null
    /** Number of items (messages, or contacts/events/tasks). */
    val count: Int
        get() = cachedCount ?: (counter?.invoke() ?: if (kind == FolderKind.MAIL || kind == FolderKind.NOTES || kind == FolderKind.JOURNAL) lister().count() else pimLister().size).also { cachedCount = it }

    fun messages(): Sequence<StoreMessage> = lister()
    fun pimItems(): List<PimItem> = pimLister()

    fun add(child: StoreFolder): StoreFolder { children += child; return child }

    /** This folder and every descendant, depth first, with their display paths. */
    fun walk(prefix: List<String> = emptyList(), out: MutableList<Pair<List<String>, StoreFolder>> = ArrayList()): List<Pair<List<String>, StoreFolder>> {
        for (c in children) {
            val p = prefix + c.name
            out += p to c
            c.walk(p, out)
        }
        return out
    }
}

/** An opened mail store: a folder tree and anything worth reporting about how it was read. */
open class MailStore(
    val detection: Detection,
    val root: StoreFolder,
    private val closer: () -> Unit = {},
    /** Problems found while reading; readers may add to it later, while messages are read. */
    val warnings: MutableList<String> = newWarnings()
) : Closeable {
    internal val extraClosers = ArrayList<() -> Unit>()

    /** Gives every folder a stable id ("1", "1.2"…) and returns them by id. */
    fun index(): Map<String, StoreFolder> {
        val out = LinkedHashMap<String, StoreFolder>()
        fun visit(f: StoreFolder, id: String) {
            f.id = id
            out[id] = f
            f.children.forEachIndexed { i, c -> visit(c, if (id.isEmpty()) "${i + 1}" else "$id.${i + 1}") }
        }
        visit(root, "")
        return out
    }

    override fun close() {
        runCatching { closer() }
        extraClosers.forEach { runCatching { it() } }
    }
}

class MailStoreException(message: String) : Exception(message)

// ------------------------------------------------------------------------------------------- bytes

internal fun ByteArray.u8(o: Int): Int = this[o].toInt() and 0xFF
internal fun ByteArray.u16(o: Int): Int = u8(o) or (u8(o + 1) shl 8)
internal fun ByteArray.i32(o: Int): Int = u16(o) or (u16(o + 2) shl 16)
internal fun ByteArray.u32(o: Int): Long = i32(o).toLong() and 0xFFFFFFFFL
internal fun ByteArray.i64(o: Int): Long = u32(o) or (u32(o + 4) shl 32)
internal fun ByteArray.startsWithBytes(vararg b: Int, at: Int = 0): Boolean =
    size >= at + b.size && b.indices.all { (this[at + it].toInt() and 0xFF) == b[it] }
internal fun ByteArray.ascii(o: Int, n: Int): String = String(this, o, minOf(n, size - o).coerceAtLeast(0), Charsets.ISO_8859_1)

/** Windows FILETIME (100 ns since 1601) to epoch milliseconds; null for 0 and out-of-range values. */
internal fun fileTimeToMillis(ft: Long): Long? {
    if (ft <= 0L) return null
    val ms = ft / 10_000L - 11_644_473_600_000L
    return if (ms in -2_208_988_800_000L..4_102_444_800_000L) ms else null // 1900…2100
}

/** Random access to a file without loading it into memory. */
class FileBytes(val file: File) : Closeable {
    private val raf = RandomAccessFile(file, "r")
    val length: Long = raf.length()

    @Synchronized
    fun read(off: Long, len: Int): ByteArray {
        if (off < 0 || len < 0 || off + len > length) throw MailStoreException("Read past the end of ${file.name} (offset $off, $len bytes) — the file is truncated or damaged")
        val b = ByteArray(len)
        raf.seek(off)
        raf.readFully(b)
        return b
    }

    /** Up to [len] bytes from [off] (fewer at the end of the file). */
    fun readUpTo(off: Long, len: Int): ByteArray = if (off >= length) ByteArray(0) else read(off, minOf(len.toLong(), length - off).toInt())

    override fun close() = raf.close()
}

/** The first [n] bytes of a file. */
internal fun head(file: File, n: Int = 65536): ByteArray = file.inputStream().use { s ->
    val buf = ByteArray(minOf(n.toLong(), file.length()).toInt())
    var o = 0
    while (o < buf.size) { val r = s.read(buf, o, buf.size - o); if (r <= 0) break; o += r }
    if (o < buf.size) buf.copyOf(o) else buf
}

internal fun newWarnings(): MutableList<String> = java.util.Collections.synchronizedList(ArrayList())
