package info.socrtwo.quillpost.web.mailstore

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.nio.charset.Charset
import java.util.zip.Inflater

/**
 * Outlook Personal Folders (.pst), Offline Folders (.ost) and Personal Address Book (.pab) files,
 * read clean-room from Microsoft's published specification [MS-PST] (Outlook Personal Folders
 * File Format):
 *
 *  - NDB layer: the file header (ANSI wVer 14/15, Unicode 23, Unicode with 4 KB pages 36+ as
 *    written by Outlook 2013 and later for .ost files), the node and block B-trees (NBT/BBT), data
 *    trees (XBLOCK/XXBLOCK), subnode trees (SLBLOCK/SIBLOCK), the "compressible" permutation
 *    encoding (NDB_CRYPT_PERMUTE) and zlib-compressed blocks of 4 KB-page files.
 *  - LTP layer: heap-on-node (HN), B-tree-on-heap (BTH), property contexts (PC) and table contexts (TC).
 *
 * The messaging layer (folders, messages, attachments, recipients, named properties) is in
 * [PstStore]. "High" (cyclic) encryption is not supported: it needs key tables that are not in
 * Anne's Post; such files are reported as unreadable.
 */
class PstFile(val fb: FileBytes) : Closeable {
    val version: Int
    val unicode: Boolean
    val fourK: Boolean
    val crypt: Int
    /** "pst", "ost" or "pab". */
    val kind: String
    private val pageSize: Int
    private val nbtRoot: Long
    private val bbtRoot: Long

    class Node(val nid: Int, val bidData: Long, val bidSub: Long, val parent: Int)
    class Sub(val nid: Int, val bidData: Long, val bidSub: Long)
    private class BlockRef(val ib: Long, val cb: Int, val inflated: Int)

    init {
        val h = fb.readUpTo(0, 580)
        if (h.size < 512 || !h.startsWithBytes(0x21, 0x42, 0x44, 0x4E)) throw MailStoreException("Not an Outlook data file (no !BDN signature)")
        kind = when (h.ascii(8, 2)) { "SM" -> "pst"; "SO" -> "ost"; "AB" -> "pab"; else -> "pst" }
        version = h.u16(10)
        unicode = version >= 23
        fourK = version >= 36
        pageSize = if (fourK) 4096 else 512
        if (unicode) { nbtRoot = h.i64(224); bbtRoot = h.i64(240); crypt = h.u8(513) }
        else { nbtRoot = h.u32(188); bbtRoot = h.u32(196); crypt = h.u8(461) }
        if (crypt == 2) throw MailStoreException("This Outlook data file uses \"high\" (cyclic) encryption, which Anne's Post cannot read. Open it in Outlook and export it to a new .pst with compressible encryption, then import that.")
    }

    val variant: String get() = when {
        fourK -> "Unicode, 4 KB pages (Outlook 2013 and later)"
        unicode -> "Unicode (Outlook 2003 and later)"
        else -> "ANSI (Outlook 97–2002)"
    }

    override fun close() = fb.close()

    // ------------------------------------------------------------------------------- B-trees

    private class Page(val d: ByteArray, val cEnt: Int, val cbEnt: Int, val level: Int, val ptype: Int)

    private fun page(ib: Long): Page {
        val d = fb.read(ib, pageSize)
        val p = when {
            fourK -> Page(d, d.u16(4056), d.u8(4060), d.u8(4061), d.u8(4072))
            unicode -> Page(d, d.u8(488), d.u8(490), d.u8(491), d.u8(496))
            else -> Page(d, d.u8(496), d.u8(498), d.u8(499), d.u8(500))
        }
        if (p.ptype != 0x80 && p.ptype != 0x81) throw MailStoreException("Damaged B-tree page at offset $ib")
        if (p.cbEnt == 0 || p.cEnt * p.cbEnt > pageSize) throw MailStoreException("Damaged B-tree page at offset $ib")
        return p
    }

    private val keySize get() = if (unicode) 8 else 4
    private fun key(d: ByteArray, o: Int) = if (unicode) d.i64(o) else d.u32(o)

    /** Every node of the file, from the node B-tree. */
    val nodes: Map<Int, Node> by lazy {
        val out = HashMap<Int, Node>()
        fun walk(ib: Long, depth: Int) {
            if (depth > 12) throw MailStoreException("Node B-tree is too deep (damaged file)")
            val p = page(ib)
            for (i in 0 until p.cEnt) {
                val o = i * p.cbEnt
                if (p.level > 0) walk(key(p.d, o + 2 * keySize), depth + 1)
                else if (unicode) out[p.d.i32(o)] = Node(p.d.i32(o), p.d.i64(o + 8), p.d.i64(o + 16), p.d.i32(o + 24))
                else out[p.d.i32(o)] = Node(p.d.i32(o), p.d.u32(o + 4), p.d.u32(o + 8), p.d.i32(o + 12))
            }
        }
        walk(nbtRoot, 0)
        out
    }

    private val pageCache = object : LinkedHashMap<Long, Page>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Page>?) = size > 256
    }

    @Synchronized
    private fun cachedPage(ib: Long): Page = pageCache.getOrPut(ib) { page(ib) }

    private fun findBlock(bid: Long): BlockRef? {
        val want = bid and 1L.inv()
        var ib = bbtRoot
        repeat(12) {
            val p = cachedPage(ib)
            if (p.level > 0) {
                var child = -1L
                for (i in 0 until p.cEnt) {
                    val o = i * p.cbEnt
                    if ((key(p.d, o) and 1L.inv()) <= want) child = key(p.d, o + 2 * keySize) else break
                }
                if (child < 0) return null
                ib = child
            } else {
                for (i in 0 until p.cEnt) {
                    val o = i * p.cbEnt
                    if ((key(p.d, o) and 1L.inv()) == want) {
                        return when {
                            fourK -> BlockRef(p.d.i64(o + 8), p.d.u16(o + 16), p.d.u16(o + 18))
                            unicode -> BlockRef(p.d.i64(o + 8), p.d.u16(o + 16), 0)
                            else -> BlockRef(p.d.u32(o + 4), p.d.u16(o + 8), 0)
                        }
                    }
                }
                return null
            }
        }
        return null
    }

    // -------------------------------------------------------------------------------- blocks

    private fun isInternal(bid: Long) = bid and 2L != 0L

    private fun rawBlock(bid: Long): Pair<ByteArray, Boolean> {
        val r = findBlock(bid) ?: throw MailStoreException("A data block is missing (the file is damaged)")
        val d = fb.read(r.ib, r.cb)
        return if (fourK && r.inflated > r.cb) d to true else d to false
    }

    private fun inflate(d: ByteArray): ByteArray? = runCatching {
        val inf = Inflater()
        inf.setInput(d)
        val out = ByteArrayOutputStream(d.size * 4)
        val buf = ByteArray(8192)
        while (!inf.finished()) {
            val n = inf.inflate(buf)
            if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
            out.write(buf, 0, n)
        }
        inf.end()
        out.toByteArray()
    }.getOrNull()

    /** An external (data) block, decoded and decompressed. */
    fun external(bid: Long): ByteArray {
        val (d, compressed) = rawBlock(bid)
        if (!compressed) return decode(d)
        return inflate(d) ?: inflate(decode(d)) ?: throw MailStoreException("A compressed block could not be expanded")
    }

    private fun internal(bid: Long): ByteArray {
        val (d, compressed) = rawBlock(bid)
        return if (compressed) inflate(d) ?: throw MailStoreException("A compressed block could not be expanded") else d
    }

    private fun decode(d: ByteArray): ByteArray {
        if (crypt != 1) return d
        val out = ByteArray(d.size)
        for (i in d.indices) out[i] = PERMUTE_DECODE[d[i].toInt() and 0xFF].toByte()
        return out
    }

    /** The data of a node: its external blocks in order (data trees expanded). */
    fun nodeData(bid: Long, depth: Int = 0): List<ByteArray> {
        if (bid == 0L) return emptyList()
        if (!isInternal(bid)) return listOf(external(bid))
        if (depth > 4) throw MailStoreException("Data tree is too deep (damaged file)")
        val b = internal(bid)
        if (b.u8(0) != 0x01) throw MailStoreException("Unexpected block type in a data tree")
        val level = b.u8(1)
        val n = b.u16(2)
        val step = if (unicode) 8 else 4
        val out = ArrayList<ByteArray>(n)
        for (i in 0 until n) {
            val child = if (unicode) b.i64(8 + i * step) else b.u32(8 + i * step)
            if (level <= 1) out += external(child) else out += nodeData(child, depth + 1)
        }
        return out
    }

    fun nodeBytes(bid: Long): ByteArray {
        val parts = nodeData(bid)
        if (parts.size == 1) return parts[0]
        val out = ByteArrayOutputStream(parts.sumOf { it.size })
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }

    /** The subnodes of a node (attachments, recipient table, large property values…). */
    fun subnodes(bidSub: Long, depth: Int = 0): Map<Int, Sub> {
        if (bidSub == 0L) return emptyMap()
        if (depth > 4) throw MailStoreException("Subnode tree is too deep (damaged file)")
        val b = internal(bidSub)
        if (b.u8(0) != 0x02) throw MailStoreException("Unexpected block type in a subnode tree")
        val level = b.u8(1)
        val n = b.u16(2)
        val base = if (unicode) 8 else 4
        val out = LinkedHashMap<Int, Sub>()
        for (i in 0 until n) {
            if (level == 0) {
                if (unicode) { val o = base + i * 24; out[b.i32(o)] = Sub(b.i32(o), b.i64(o + 8), b.i64(o + 16)) }
                else { val o = base + i * 12; out[b.i32(o)] = Sub(b.i32(o), b.u32(o + 4), b.u32(o + 8)) }
            } else {
                val child = if (unicode) b.i64(base + i * 16 + 8) else b.u32(base + i * 8 + 4)
                out.putAll(subnodes(child, depth + 1))
            }
        }
        return out
    }

    companion object {
        /** Decoding table for NDB_CRYPT_PERMUTE ("compressible encryption"), [MS-PST] 5.1. */
        private val PERMUTE_DECODE = intArrayOf(
            0x47, 0xf1, 0xb4, 0xe6, 0x0b, 0x6a, 0x72, 0x48, 0x85, 0x4e, 0x9e, 0xeb, 0xe2, 0xf8, 0x94, 0x53,
            0xe0, 0xbb, 0xa0, 0x02, 0xe8, 0x5a, 0x09, 0xab, 0xdb, 0xe3, 0xba, 0xc6, 0x7c, 0xc3, 0x10, 0xdd,
            0x39, 0x05, 0x96, 0x30, 0xf5, 0x37, 0x60, 0x82, 0x8c, 0xc9, 0x13, 0x4a, 0x6b, 0x1d, 0xf3, 0xfb,
            0x8f, 0x26, 0x97, 0xca, 0x91, 0x17, 0x01, 0xc4, 0x32, 0x2d, 0x6e, 0x31, 0x95, 0xff, 0xd9, 0x23,
            0xd1, 0x00, 0x5e, 0x79, 0xdc, 0x44, 0x3b, 0x1a, 0x28, 0xc5, 0x61, 0x57, 0x20, 0x90, 0x3d, 0x83,
            0xb9, 0x43, 0xbe, 0x67, 0xd2, 0x46, 0x42, 0x76, 0xc0, 0x6d, 0x5b, 0x7e, 0xb2, 0x0f, 0x16, 0x29,
            0x3c, 0xa9, 0x03, 0x54, 0x0d, 0xda, 0x5d, 0xdf, 0xf6, 0xb7, 0xc7, 0x62, 0xcd, 0x8d, 0x06, 0xd3,
            0x69, 0x5c, 0x86, 0xd6, 0x14, 0xf7, 0xa5, 0x66, 0x75, 0xac, 0xb1, 0xe9, 0x45, 0x21, 0x70, 0x0c,
            0x87, 0x9f, 0x74, 0xa4, 0x22, 0x4c, 0x6f, 0xbf, 0x1f, 0x56, 0xaa, 0x2e, 0xb3, 0x78, 0x33, 0x50,
            0xb0, 0xa3, 0x92, 0xbc, 0xcf, 0x19, 0x1c, 0xa7, 0x63, 0xcb, 0x1e, 0x4d, 0x3e, 0x4b, 0x1b, 0x9b,
            0x4f, 0xe7, 0xf0, 0xee, 0xad, 0x3a, 0xb5, 0x59, 0x04, 0xea, 0x40, 0x55, 0x25, 0x51, 0xe5, 0x7a,
            0x89, 0x38, 0x68, 0x52, 0x7b, 0xfc, 0x27, 0xae, 0xd7, 0xbd, 0xfa, 0x07, 0xf4, 0xcc, 0x8e, 0x5f,
            0xef, 0x35, 0x9c, 0x84, 0x2b, 0x15, 0xd5, 0x77, 0x34, 0x49, 0xb6, 0x12, 0x0a, 0x7f, 0x71, 0x88,
            0xfd, 0x9d, 0x18, 0x41, 0x7d, 0x93, 0xd8, 0x58, 0x2c, 0xce, 0xfe, 0x24, 0xaf, 0xde, 0xb8, 0x36,
            0xc8, 0xa1, 0x80, 0xa6, 0x99, 0x98, 0xa8, 0x2f, 0x0e, 0x81, 0x65, 0x73, 0xe4, 0xc2, 0xa2, 0x8a,
            0xd4, 0xe1, 0x11, 0xd0, 0x08, 0x8b, 0x2a, 0xf2, 0xed, 0x9a, 0x64, 0x3f, 0xc1, 0x6c, 0xf9, 0xec
        )

        /** The encoding direction (used by tests that build files). */
        val PERMUTE_ENCODE: IntArray by lazy { IntArray(256).also { e -> for (i in 0 until 256) e[PERMUTE_DECODE[i]] = i } }

        const val NID_MESSAGE_STORE = 0x21
        const val NID_NAME_TO_ID_MAP = 0x61
        const val NID_ROOT_FOLDER = 0x122
        const val NID_RECIPIENT_TABLE = 0x692
        const val NID_ATTACHMENT_TABLE = 0x671

        fun nidType(nid: Int) = nid and 0x1F
    }
}

/** Heap-on-node: the allocation layer under property and table contexts. */
class PstHeap(val blocks: List<ByteArray>) {
    val clientSig: Int
    val userRoot: Int

    init {
        val b0 = blocks.firstOrNull() ?: throw MailStoreException("Empty heap")
        if (b0.size < 12 || b0.u8(2) != 0xEC) throw MailStoreException("Damaged heap (bad signature)")
        clientSig = b0.u8(3)
        userRoot = b0.i32(4)
    }

    fun get(hid: Int): ByteArray? {
        if (hid == 0 || hid and 0x1F != 0) return null
        val idx = (hid ushr 5) and 0x7FF
        val b = blocks.getOrNull(hid ushr 16) ?: return null
        val pm = b.u16(0)
        if (pm + 4 > b.size) return null
        val cAlloc = b.u16(pm)
        if (idx < 1 || idx > cAlloc || pm + 4 + idx * 2 + 2 > b.size) return null
        val s = b.u16(pm + 4 + (idx - 1) * 2)
        val e = b.u16(pm + 4 + idx * 2)
        if (s > e || e > b.size) return null
        return b.copyOfRange(s, e)
    }

    /** B-tree-on-heap records (key, data). */
    fun bth(hid: Int): List<Pair<ByteArray, ByteArray>> {
        val h = get(hid) ?: return emptyList()
        if (h.size < 8 || h.u8(0) != 0xB5) throw MailStoreException("Damaged B-tree on heap")
        val cbKey = h.u8(1); val cbEnt = h.u8(2); val levels = h.u8(3)
        val out = ArrayList<Pair<ByteArray, ByteArray>>()
        fun walk(hid: Int, level: Int) {
            val d = get(hid) ?: return
            if (level > 0) {
                val step = cbKey + 4
                for (o in 0 until d.size - step + 1 step step) walk(d.i32(o + cbKey), level - 1)
            } else {
                val step = cbKey + cbEnt
                if (step == 0) return
                for (o in 0 until d.size - step + 1 step step) out += d.copyOfRange(o, o + cbKey) to d.copyOfRange(o + cbKey, o + step)
            }
        }
        walk(h.i32(4), levels)
        return out
    }
}

/** A property value as stored (type + 4-byte value or HNID). */
class PstProp(val type: Int, val raw: ByteArray)

/** Property context: the properties of a folder, message, attachment or recipient. */
class PstProps(private val pst: PstFile, val heap: PstHeap, val subs: Map<Int, PstFile.Sub>, val props: Map<Int, PstProp>, var codepage: Charset = Charsets.ISO_8859_1) {

    companion object {
        fun of(pst: PstFile, bidData: Long, bidSub: Long): PstProps {
            val heap = PstHeap(pst.nodeData(bidData))
            if (heap.clientSig != 0xBC) throw MailStoreException("Not a property context")
            val subs = pst.subnodes(bidSub)
            val props = LinkedHashMap<Int, PstProp>()
            for ((k, v) in heap.bth(heap.userRoot)) if (v.size >= 6) props[k.u16(0)] = PstProp(v.u16(0), v.copyOfRange(2, 6))
            return PstProps(pst, heap, subs, props)
        }

        private val INLINE = setOf(0x0002, 0x0003, 0x0004, 0x000A, 0x000B)
    }

    fun has(id: Int) = props.containsKey(id)
    fun type(id: Int) = props[id]?.type

    /** The value bytes (fixed values ≤ 4 bytes are returned as stored). */
    fun bytes(id: Int): ByteArray? {
        val p = props[id] ?: return null
        if (p.type in INLINE) return p.raw
        return data(p.raw.i32(0))
    }

    fun data(hnid: Int): ByteArray? {
        if (hnid == 0) return ByteArray(0)
        if (hnid and 0x1F == 0) return heap.get(hnid)
        val s = subs[hnid] ?: return null
        return pst.nodeBytes(s.bidData)
    }

    fun subnode(nid: Int): PstFile.Sub? = subs[nid]

    fun string(id: Int): String? {
        val p = props[id] ?: return null
        val b = bytes(id) ?: return null
        return when (p.type) {
            0x001F -> String(b, Charsets.UTF_16LE).trimEnd('\u0000')
            0x001E -> String(b, codepage).trimEnd('\u0000')
            0x0102 -> String(b, codepage).trimEnd('\u0000')
            else -> null
        }
    }

    fun int(id: Int): Int? {
        val p = props[id] ?: return null
        return when (p.type) {
            0x0002 -> p.raw.u16(0).toShort().toInt()
            0x0003, 0x000A -> p.raw.i32(0)
            0x000B -> p.raw.u8(0)
            0x0014 -> bytes(id)?.takeIf { it.size >= 8 }?.i64(0)?.toInt()
            else -> null
        }
    }

    fun bool(id: Int): Boolean? = int(id)?.let { it != 0 }

    fun long(id: Int): Long? {
        val p = props[id] ?: return null
        return when (p.type) {
            0x0014, 0x0040, 0x0006 -> bytes(id)?.takeIf { it.size >= 8 }?.i64(0)
            0x0003 -> p.raw.i32(0).toLong()
            else -> null
        }
    }

    /** A PtypTime value as epoch milliseconds. */
    fun time(id: Int): Long? = if (props[id]?.type == 0x0040) long(id)?.let { fileTimeToMillis(it) } else null

    fun binary(id: Int): ByteArray? = if (props[id]?.type == 0x0102 || props[id]?.type == 0x000D) bytes(id) else null

    fun strings(id: Int): List<String> {
        val p = props[id] ?: return emptyList()
        val b = bytes(id) ?: return emptyList()
        if (p.type != 0x101F && p.type != 0x101E) return string(id)?.let { listOf(it) } ?: emptyList()
        if (b.size < 4) return emptyList()
        val n = b.i32(0)
        if (n < 0 || 4 + n * 4 > b.size) return emptyList()
        val offs = (0 until n).map { b.i32(4 + it * 4) } + b.size
        return (0 until n).mapNotNull { i ->
            val s = offs[i]; val e = offs[i + 1]
            if (s < 0 || e > b.size || s > e) null
            else if (p.type == 0x101F) String(b, s, e - s, Charsets.UTF_16LE).trimEnd('\u0000') else String(b, s, e - s, codepage).trimEnd('\u0000')
        }
    }
}

/** Table context (recipient, attachment, hierarchy and contents tables). */
class PstTable(private val pst: PstFile, private val heap: PstHeap, private val subs: Map<Int, PstFile.Sub>) {
    class Col(val type: Int, val id: Int, val ib: Int, val cb: Int, val bit: Int)

    val cols: List<Col>
    private val rowSize: Int
    private val cebStart: Int
    val rows: List<ByteArray>

    init {
        if (heap.clientSig != 0x7C) throw MailStoreException("Not a table context")
        val info = heap.get(heap.userRoot) ?: throw MailStoreException("Damaged table")
        val nCols = info.u8(1)
        cebStart = info.u16(6)
        rowSize = info.u16(8)
        val hnidRows = info.i32(14)
        cols = (0 until nCols).map { i -> val o = 22 + i * 8; val tag = info.i32(o); Col(tag and 0xFFFF, tag ushr 16, info.u16(o + 4), info.u8(o + 6), info.u8(o + 7)) }
        val blocks: List<ByteArray> = when {
            hnidRows == 0 || rowSize == 0 -> emptyList()
            hnidRows and 0x1F == 0 -> listOfNotNull(heap.get(hnidRows))
            else -> subs[hnidRows]?.let { pst.nodeData(it.bidData) } ?: emptyList()
        }
        val rowCount = runCatching { heap.bth(info.i32(10)).size }.getOrDefault(Int.MAX_VALUE)
        val list = ArrayList<ByteArray>()
        for (b in blocks) for (o in 0 until b.size / rowSize) { if (list.size >= rowCount) break; list += b.copyOfRange(o * rowSize, (o + 1) * rowSize) }
        rows = list
    }

    private fun present(row: ByteArray, c: Col): Boolean {
        val i = cebStart + c.bit / 8
        return i < row.size && (row.u8(i) and (0x80 ushr (c.bit % 8))) != 0
    }

    /** The row as property values keyed by property id. */
    fun row(row: ByteArray): Map<Int, Any?> {
        val out = HashMap<Int, Any?>()
        for (c in cols) {
            if (!present(row, c) || c.ib + c.cb > row.size) continue
            out[c.id] = when (c.type) {
                0x0002 -> row.u16(c.ib).toShort().toInt()
                0x0003 -> row.i32(c.ib)
                0x000B -> row.u8(c.ib) != 0
                0x0014 -> row.i64(c.ib)
                0x0040 -> fileTimeToMillis(row.i64(c.ib))
                0x001F -> data(row.i32(c.ib))?.let { String(it, Charsets.UTF_16LE).trimEnd('\u0000') }
                0x001E -> data(row.i32(c.ib))?.let { String(it, Charsets.ISO_8859_1).trimEnd('\u0000') }
                0x0102 -> data(row.i32(c.ib))
                else -> null
            }
        }
        return out
    }

    private fun data(hnid: Int): ByteArray? = when {
        hnid == 0 -> ByteArray(0)
        hnid and 0x1F == 0 -> heap.get(hnid)
        else -> subs[hnid]?.let { pst.nodeBytes(it.bidData) }
    }
}
