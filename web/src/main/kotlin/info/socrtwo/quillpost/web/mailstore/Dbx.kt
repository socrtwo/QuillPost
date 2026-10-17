package info.socrtwo.quillpost.web.mailstore

import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Outlook Express 5/6 (.dbx) and Outlook Express 4 (.mbx/.idx) mail folders.
 *
 * Microsoft never published these formats; QuillPost follows the community descriptions of them
 * (Arne Schloh's "Outlook Express DBX file format" notes and others):
 *
 *  - DBX: signature CF AD 12 FE followed by a class id (…C5 FD 74 6F for a message folder,
 *    …C6 FD 74 6F for Folders.dbx). The header gives the message count (0xC4) and the root of a
 *    B-tree (0xE4) whose entries point at "indexed info" objects; index 0x04 of each is the first
 *    block of the raw RFC 822 message, stored as a chain of blocks (16-byte header: own offset,
 *    block size, data length, next block). Index 0x01 holds status flags, 0x12 the arrival time.
 *    Folders.dbx lists every folder (index 0x00 id, 0x01 parent id, 0x02 name, 0x03 .dbx file),
 *    which gives the folder tree. When the tree is damaged, message chains are found by scanning
 *    the file for block headers instead.
 *  - OE4 .mbx: "JMF6" signature; each message is preceded by a 16-byte header with the marker
 *    7F 00 7F 00, its number, the record size and the message size.
 */
object Dbx {
    val MAGIC = intArrayOf(0xCF, 0xAD, 0x12, 0xFE)

    fun isDbx(head: ByteArray) = head.startsWithBytes(*MAGIC)
    fun isFolders(head: ByteArray) = isDbx(head) && head.size > 8 && head.u8(4) == 0xC6
    fun isMessages(head: ByteArray) = isDbx(head) && head.size > 8 && head.u8(4) == 0xC5

    class Info(val values: Map<Int, ByteArray>)

    /** Reads an "indexed info" object at [off]. */
    private fun info(fb: FileBytes, off: Long): Info? {
        if (off <= 0 || off + 12 > fb.length) return null
        val h = fb.read(off, 12)
        if (h.u32(0) != off) return null
        val bodyLen = h.u32(4)
        val count = h.u8(10)
        if (bodyLen > 1_000_000 || count == 0 || count * 4 > bodyLen) return null
        val body = fb.readUpTo(off + 12, bodyLen.toInt())
        val dataStart = count * 4
        val out = HashMap<Int, ByteArray>()
        for (i in 0 until count) {
            val idx = body.u8(i * 4)
            val v = body.u8(i * 4 + 1) or (body.u8(i * 4 + 2) shl 8) or (body.u8(i * 4 + 3) shl 16)
            if (idx and 0x80 != 0) out[idx and 0x7F] = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), 0)
            else {
                val s = dataStart + v
                if (s < body.size) out[idx] = body.copyOfRange(s, body.size)
            }
        }
        return Info(out)
    }

    private fun Info.int(i: Int): Long? = values[i]?.takeIf { it.size >= 4 }?.u32(0)
    private fun Info.str(i: Int): String? = values[i]?.let { b -> val e = b.indexOf(0).let { if (it < 0) b.size else it }; String(b, 0, e, Charsets.ISO_8859_1) }
    private fun Info.time(i: Int): Long? = values[i]?.takeIf { it.size >= 8 }?.let { fileTimeToMillis(it.i64(0)) }

    /** The pointers stored in the B-tree rooted at [root]. */
    private fun treeValues(fb: FileBytes, root: Long, out: MutableList<Long>, depth: Int = 0, seen: HashSet<Long> = HashSet()) {
        if (root <= 0 || root + 0x18 > fb.length || depth > 30 || !seen.add(root)) return
        val h = fb.read(root, 0x18)
        if (h.u32(0) != root) return
        val child = h.u32(8)
        val n = h.u8(0x11)
        if (child != 0L) treeValues(fb, child, out, depth + 1, seen)
        val entries = fb.readUpTo(root + 0x18, n * 12)
        for (i in 0 until minOf(n, entries.size / 12)) {
            val value = entries.u32(i * 12)
            val sub = entries.u32(i * 12 + 4)
            if (value != 0L) out += value
            if (sub != 0L) treeValues(fb, sub, out, depth + 1, seen)
        }
    }

    /** Follows a message's block chain from [first]. */
    private fun chain(fb: FileBytes, first: Long): ByteArray? {
        val out = ByteArrayOutputStream()
        var p = first
        var guard = 0
        while (p != 0L) {
            if (p + 16 > fb.length || guard++ > 2_000_000) return null
            val h = fb.read(p, 16)
            if (h.u32(0) != p) return if (out.size() > 0) out.toByteArray() else null
            val len = h.u32(8).toInt()
            if (len < 0 || len > h.u32(4) || p + 16 + len > fb.length) return null
            out.write(fb.read(p + 16, len))
            p = h.u32(12)
        }
        return out.toByteArray()
    }

    data class Msg(val first: Long, val flags: MsgFlags, val date: Long?)

    /** The messages of a message .dbx, from its tree; falls back to scanning for block chains. */
    fun messages(fb: FileBytes, warnings: MutableList<String>, label: String): List<Msg> {
        val head = fb.readUpTo(0, 0x100)
        val out = ArrayList<Msg>()
        if (head.size >= 0xE8) {
            val ptrs = ArrayList<Long>()
            runCatching { treeValues(fb, head.u32(0xE4), ptrs) }
            for (ptr in ptrs) {
                val inf = info(fb, ptr) ?: continue
                val first = inf.int(0x04) ?: continue
                val fl = inf.int(0x01) ?: 0L
                out += Msg(first, MsgFlags(seen = fl and 0x80L != 0L, answered = fl and 0x80000L != 0L, forwarded = fl and 0x100000L != 0L), inf.time(0x12) ?: inf.time(0x02))
            }
        }
        if (out.isEmpty() && fb.length > 0x24BC) {
            val scanned = scanChains(fb)
            if (scanned.isNotEmpty()) warnings += "$label: the folder index is damaged or unreadable; ${scanned.size} messages were recovered by scanning the file (read status is not known)."
            out += scanned.map { Msg(it, MsgFlags(), null) }
        }
        return out
    }

    /** Finds message block chains by their headers (own offset, 0x200, length ≤ 0x200, next). */
    fun scanChains(fb: FileBytes): List<Long> {
        val heads = LinkedHashSet<Long>()
        val nexts = HashSet<Long>()
        var p = 0x24BCL
        val buf = 1 shl 20
        while (p < fb.length) {
            val chunk = fb.readUpTo(p, buf + 16)
            var i = 0
            while (i + 16 <= chunk.size && i < buf) {
                val at = p + i
                if (chunk.u32(i) == at && chunk.u32(i + 4) == 0x200L && chunk.u32(i + 8) <= 0x200L) {
                    heads += at
                    val next = chunk.u32(i + 12)
                    if (next != 0L) nexts += next
                    i += 4
                } else i += 4
            }
            p += buf
        }
        return heads.filter { it !in nexts }
    }

    /** A folder for one message .dbx file. */
    fun folder(file: File, name: String, warnings: MutableList<String>): Pair<StoreFolder, FileBytes> {
        val fb = FileBytes(file)
        val list by lazy { messages(fb, warnings, file.name) }
        val f = StoreFolder(name, counter = { list.size }, lister = {
            list.asSequence().mapNotNull { m ->
                StoreMessage(m.flags, m.date) { chain(fb, m.first) ?: throw MailStoreException("A message's data blocks are damaged") }
            }
        })
        return f to fb
    }

    data class FolderEntry(val id: Long, val parent: Long, val name: String, val file: String?)

    /** Folders.dbx: the folder list with parents and .dbx file names. */
    fun foldersList(file: File): List<FolderEntry> = FileBytes(file).use { fb ->
        val head = fb.readUpTo(0, 0x100)
        val ptrs = ArrayList<Long>()
        runCatching { treeValues(fb, head.u32(0xE4), ptrs) }
        ptrs.mapNotNull { p -> info(fb, p)?.let { i -> FolderEntry(i.int(0x00) ?: return@mapNotNull null, i.int(0x01) ?: 0L, i.str(0x02) ?: "Folder", i.str(0x03)?.takeIf { it.isNotBlank() }) } }
    }

    /** Opens a folder holding an Outlook Express store (Folders.dbx and the .dbx files). */
    fun openStore(dir: File, displayName: String, warnings: MutableList<String>, store: (StoreFolder) -> MailStore): MailStore {
        val files = dir.listFiles()?.filter { it.isFile } ?: emptyList()
        val byName = files.associateBy { it.name.lowercase() }
        val foldersFile = byName["folders.dbx"]
        val root = StoreFolder(displayName)
        val opened = ArrayList<FileBytes>()
        val used = HashSet<String>()
        val entries = foldersFile?.let { runCatching { foldersList(it) }.getOrNull() }.orEmpty()
        if (entries.isNotEmpty()) {
            val nodes = HashMap<Long, StoreFolder>()
            val byId = entries.associateBy { it.id }
            fun node(e: FolderEntry): StoreFolder = nodes.getOrPut(e.id) {
                val f = e.file?.lowercase()?.let { byName[it] }
                if (f != null) { used += f.name.lowercase(); val (sf, fb) = folder(f, e.name, warnings); opened += fb; sf } else StoreFolder(e.name)
            }
            for (e in entries.sortedBy { it.id }) {
                if (e.id == 0L) continue
                val n = node(e)
                val parent = byId[e.parent]?.takeIf { it.id != e.id && it.id != 0L }
                if (parent != null) node(parent).add(n) else root.add(n)
            }
            // Prune the containers OE keeps for accounts and news servers when they hold nothing.
            fun prune(f: StoreFolder): Boolean { f.children.removeAll { !prune(it) }; return f.children.isNotEmpty() || f.count > 0 }
            root.children.removeAll { !prune(it) }
        }
        for (f in files.filter { it.name.endsWith(".dbx", true) && it.name.lowercase() != "folders.dbx" && it.name.lowercase() !in used && it.name.lowercase() != "offline.dbx" && it.name.lowercase() != "pop3uidl.dbx" }) {
            if (!isMessages(head(f, 16))) continue
            val (sf, fb) = folder(f, f.nameWithoutExtension, warnings); opened += fb; root.add(sf)
        }
        return store(root).also { it.onClose { opened.forEach { fb -> runCatching { fb.close() } } } }
    }

    // ------------------------------------------------------------------------------ OE 4 .mbx

    fun isOe4(head: ByteArray) = head.size >= 4 && head.ascii(0, 4) == "JMF6"

    /** Outlook Express 4 mailbox (.mbx): messages after 16-byte headers marked 7F 00 7F 00. */
    fun oe4Folder(file: File, name: String): Pair<StoreFolder, FileBytes> {
        val fb = FileBytes(file)
        val list by lazy {
            val out = ArrayList<Pair<Long, Int>>()
            var p = 0L
            val buf = 1 shl 20
            while (p + 16 <= fb.length) {
                val chunk = fb.readUpTo(p, buf + 16)
                var i = 0
                var jumped = false
                while (i + 16 <= chunk.size && i < buf) {
                    if (chunk.u32(i) == 0x007F007FL) {
                        val total = chunk.u32(i + 8); val size = chunk.u32(i + 12)
                        if (size in 1..total && p + i + 16 + size <= fb.length) {
                            out += (p + i + 16) to size.toInt()
                            p += i + 16 + size
                            jumped = true
                            break
                        }
                    }
                    i++
                }
                if (!jumped) p += buf
            }
            out
        }
        val f = StoreFolder(name, counter = { list.size }, lister = { list.asSequence().map { (o, n) -> StoreMessage(MsgFlags(seen = true)) { fb.read(o, n) } } })
        return f to fb
    }
}

/** Registers extra cleanup on a store (used when a store owns several open files). */
fun MailStore.onClose(action: () -> Unit): MailStore {
    extraClosers += action
    return this
}
