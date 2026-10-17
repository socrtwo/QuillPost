package info.socrtwo.quillpost.web.mailstore

import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Test helper: writes small but structurally complete .pst files following [MS-PST] — header,
 * node and block B-trees (multi-level when needed), heap-on-node property and table contexts,
 * subnode trees, attachments, embedded messages, recipient tables and the named-property map —
 * in ANSI or Unicode form, optionally with the "compressible" permutation encoding. Used to
 * build fixtures; it is not a general PST writer (no free maps, CRCs are zero).
 */
class PstWriter(val unicode: Boolean, val encode: Boolean = false) {
    private val blocks = LinkedHashMap<Long, ByteArray>()   // bid -> stored bytes
    private val nodes = ArrayList<IntArray>()                // placeholder (unused)
    private class NodeEntry(val nid: Int, val data: Long, val sub: Long, val parent: Int)
    private val nbt = ArrayList<NodeEntry>()
    private var nextBid = 4L

    private fun newBid(internal: Boolean): Long { val b = nextBid; nextBid += 4; return if (internal) b or 2L else b }

    fun external(data: ByteArray): Long {
        val bid = newBid(false)
        blocks[bid] = if (encode) ByteArray(data.size) { PstFile.PERMUTE_ENCODE[data[it].toInt() and 0xFF].toByte() } else data
        return bid
    }

    private fun internalBlock(data: ByteArray): Long { val bid = newBid(true); blocks[bid] = data; return bid }

    fun node(nid: Int, data: Long, sub: Long, parent: Int) { nbt += NodeEntry(nid, data, sub, parent) }

    // ------------------------------------------------------------------------- heap / PC / TC

    /** A heap being filled; [alloc] returns HIDs. */
    class Heap(val clientSig: Int) {
        val items = ArrayList<ByteArray>()
        var userRoot = 0
        fun alloc(b: ByteArray): Int { items += b; return items.size shl 5 }
        fun bytes(): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(ByteArray(12))
            val offs = ArrayList<Int>()
            for (i in items) { offs += out.size(); out.write(i) }
            offs += out.size()
            if (out.size() % 2 != 0) out.write(0)
            val pm = out.size()
            out.write(le16(items.size)); out.write(le16(0))
            offs.forEach { out.write(le16(it)) }
            val b = out.toByteArray()
            b[0] = pm.toByte(); b[1] = (pm shr 8).toByte(); b[2] = 0xEC.toByte(); b[3] = clientSig.toByte()
            le32(userRoot).copyInto(b, 4)
            return b
        }
    }

    sealed class V {
        class I16(val v: Int) : V(); class I32(val v: Int) : V(); class Bool(val v: Boolean) : V(); class I64(val v: Long) : V()
        class Time(val ms: Long) : V(); class Str(val s: String) : V(); class Bin(val b: ByteArray) : V(); class MultiStr(val l: List<String>) : V()
        class Obj(val nid: Int, val size: Int) : V(); class Double(val d: kotlin.Double) : V()
        /** A value stored in a subnode instead of the heap (large values). */
        class Big(val b: ByteArray, val type: Int) : V()
        /** A value whose data is an existing subnode [nid] (e.g. a data tree built separately). */
        class SubRef(val nid: Int, val type: Int) : V()
    }

    class Sub(val nid: Int, val data: Long, val sub: Long)

    /** Builds a property context; values too big for the heap go to subnodes added to [subs]. */
    fun pc(props: Map<Int, V>, subs: MutableList<Sub>): ByteArray {
        val h = Heap(0xBC)
        val recs = ByteArrayOutputStream()
        for ((id, v) in props.toSortedMap()) {
            val (type, value) = when (v) {
                is V.I16 -> 0x0002 to v.v
                is V.I32 -> 0x0003 to v.v
                is V.Bool -> 0x000B to (if (v.v) 1 else 0)
                is V.I64 -> 0x0014 to h.alloc(le64(v.v))
                is V.Double -> 0x0005 to h.alloc(le64(java.lang.Double.doubleToLongBits(v.d)))
                is V.Time -> 0x0040 to h.alloc(le64((v.ms + 11_644_473_600_000L) * 10_000L))
                is V.Str -> (if (unicode) 0x001F else 0x001E) to h.alloc(str(v.s))
                is V.Bin -> 0x0102 to h.alloc(v.b)
                is V.MultiStr -> (if (unicode) 0x101F else 0x101E) to h.alloc(multi(v.l))
                is V.Obj -> 0x000D to h.alloc(le32(v.nid) + le32(v.size))
                is V.SubRef -> v.type to v.nid
                is V.Big -> {
                    val nid = (0x4000 + subs.size) shl 5 or 0x1F
                    subs += Sub(nid, external(v.b), 0)
                    v.type to nid
                }
            }
            recs.write(le16(id)); recs.write(le16(type)); recs.write(le32(value))
        }
        val recHid = h.alloc(recs.toByteArray())
        h.userRoot = h.alloc(byteArrayOf(0xB5.toByte(), 2, 6, 0) + le32(recHid))
        return h.bytes()
    }

    fun str(s: String): ByteArray = if (unicode) s.toByteArray(Charsets.UTF_16LE) else s.toByteArray(Charsets.ISO_8859_1)

    private fun multi(l: List<String>): ByteArray {
        val data = l.map { str(it) }
        val out = ByteArrayOutputStream()
        out.write(le32(l.size))
        var off = 4 + 4 * l.size
        for (d in data) { out.write(le32(off)); off += d.size }
        data.forEach { out.write(it) }
        return out.toByteArray()
    }

    /** Recipient table: rows of (type, name, email, address type, SMTP address). */
    fun recipientTable(rows: List<List<Any>>): ByteArray {
        val h = Heap(0x7C)
        // Columns: row id, row version, recipient type, display name, email, address type, SMTP address.
        val cols = listOf(0x67F20003 to 0, 0x67F30003 to 4, 0x0C150003 to 8, 0x3001001F to 12, 0x3003001F to 16, 0x3002001F to 20, 0x39FE001F to 24)
            .map { (tag, ib) -> (if (!unicode && tag and 0xFFFF == 0x1F) tag - 1 else tag) to ib }
        val rowSize = 28 + 1
        val rowData = ByteArrayOutputStream()
        val index = ByteArrayOutputStream()
        rows.forEachIndexed { i, r ->
            val row = ByteArray(rowSize)
            le32(i + 1).copyInto(row, 0); le32(0).copyInto(row, 4); le32(r[0] as Int).copyInto(row, 8)
            for ((k, s) in listOf(r[1], r[2], r[3], r[4]).withIndex()) le32(h.alloc(str(s as String))).copyInto(row, 12 + k * 4)
            row[28] = 0xFE.toByte() // all 7 columns present
            rowData.write(row)
            index.write(le32(i + 1)); index.write(if (unicode) le32(i) else le16(i))
        }
        val rowsHid = h.alloc(rowData.toByteArray())
        val idxRecs = h.alloc(index.toByteArray())
        val idxHdr = h.alloc(byteArrayOf(0xB5.toByte(), 4, if (unicode) 4 else 2, 0) + le32(idxRecs))
        val info = ByteArrayOutputStream()
        info.write(0x7C); info.write(cols.size)
        info.write(le16(28)); info.write(le16(28)); info.write(le16(28)); info.write(le16(rowSize))
        info.write(le32(idxHdr)); info.write(le32(rowsHid)); info.write(le32(0))
        cols.forEachIndexed { i, (tag, ib) -> info.write(le32(tag)); info.write(le16(ib)); info.write(4); info.write(i) }
        h.userRoot = h.alloc(info.toByteArray())
        return h.bytes()
    }

    /** A subnode tree block (SLBLOCK) for [subs]; 0 when empty. */
    fun subnodes(subs: List<Sub>): Long {
        if (subs.isEmpty()) return 0L
        val out = ByteArrayOutputStream()
        out.write(0x02); out.write(0); out.write(le16(subs.size))
        if (unicode) out.write(le32(0))
        for (s in subs.sortedBy { it.nid }) {
            if (unicode) { out.write(le64(s.nid.toLong())); out.write(le64(s.data)); out.write(le64(s.sub)) }
            else { out.write(le32(s.nid)); out.write(le32(s.data.toInt())); out.write(le32(s.sub.toInt())) }
        }
        return internalBlock(out.toByteArray())
    }

    /** Splits [data] over several external blocks joined by an XBLOCK (to exercise data trees). */
    fun dataTree(data: ByteArray, chunk: Int): Long {
        val bids = data.toList().chunked(chunk).map { external(it.toByteArray()) }
        val out = ByteArrayOutputStream()
        out.write(0x01); out.write(1); out.write(le16(bids.size)); out.write(le32(data.size))
        bids.forEach { out.write(if (unicode) le64(it) else le32(it.toInt())) }
        return internalBlock(out.toByteArray())
    }

    // ------------------------------------------------------------------------------ file layout

    fun write(file: File) {
        val pageSize = 512
        val out = ByteArrayOutputStream()
        val headerSize = 0x4400
        out.write(ByteArray(headerSize))
        val blockIb = HashMap<Long, Long>()
        for ((bid, data) in blocks) {
            blockIb[bid] = out.size().toLong()
            val trailer = if (unicode) 16 else 12
            val total = (data.size + trailer + 63) / 64 * 64
            val b = ByteArray(total)
            data.copyInto(b)
            val t = total - trailer
            le16(data.size).copyInto(b, t); b[t + 2] = 0; b[t + 3] = 0
            if (unicode) le64(bid).copyInto(b, t + 8) else le32(bid.toInt()).copyInto(b, t + 4)
            out.write(b)
        }
        while (out.size() % pageSize != 0) out.write(0)
        // B-tree pages, bottom-up.
        val keyLen = if (unicode) 8 else 4
        val entryArea = if (unicode) 488 else 496
        var pageBid = 0x100000L
        fun writeTree(ptype: Int, leafEntries: List<Pair<Long, ByteArray>>, leafSize: Int): Pair<Long, Long> {
            var level = 0
            var entries: List<Pair<Long, ByteArray>> = leafEntries
            var size = leafSize
            while (true) {
                val perPage = entryArea / size
                val chunks = entries.chunked(perPage).ifEmpty { listOf(emptyList()) }
                val made = ArrayList<Triple<Long, Long, Long>>() // first key, bid, ib
                for (c in chunks) {
                    val p = ByteArray(pageSize)
                    c.forEachIndexed { i, e -> e.second.copyInto(p, i * size) }
                    val bid = pageBid; pageBid += 4
                    if (unicode) { p[488] = c.size.toByte(); p[489] = perPage.toByte(); p[490] = size.toByte(); p[491] = level.toByte(); p[496] = ptype.toByte(); p[497] = ptype.toByte(); le64(bid).copyInto(p, 504) }
                    else { p[496] = c.size.toByte(); p[497] = perPage.toByte(); p[498] = size.toByte(); p[499] = level.toByte(); p[500] = ptype.toByte(); p[501] = ptype.toByte(); le32(bid.toInt()).copyInto(p, 508) }
                    val ib = out.size().toLong()
                    out.write(p)
                    made += Triple(c.firstOrNull()?.first ?: 0L, bid, ib)
                }
                if (made.size == 1) return made[0].second to made[0].third
                level++
                size = keyLen * 3
                entries = made.map { (k, bid, ib) -> k to (if (unicode) le64(k) + le64(bid) + le64(ib) else le32(k.toInt()) + le32(bid.toInt()) + le32(ib.toInt())) }
            }
        }
        val bbtLeaves = blocks.keys.sorted().map { bid ->
            val ib = blockIb[bid]!!; val cb = blocks[bid]!!.size
            bid to (if (unicode) le64(bid) + le64(ib) + le16(cb) + le16(1) + le32(0) else le32(bid.toInt()) + le32(ib.toInt()) + le16(cb) + le16(1))
        }
        val (bbtBid, bbtIb) = writeTree(0x80, bbtLeaves, if (unicode) 24 else 12)
        val nbtLeaves = nbt.sortedBy { it.nid }.map { n ->
            n.nid.toLong() to (if (unicode) le64(n.nid.toLong()) + le64(n.data) + le64(n.sub) + le32(n.parent) + le32(0) else le32(n.nid) + le32(n.data.toInt()) + le32(n.sub.toInt()) + le32(n.parent))
        }
        val (nbtBid, nbtIb) = writeTree(0x81, nbtLeaves, if (unicode) 32 else 16)
        val bytes = out.toByteArray()
        // Header.
        "!BDN".toByteArray().copyInto(bytes, 0)
        bytes[8] = 'S'.code.toByte(); bytes[9] = 'M'.code.toByte()
        le16(if (unicode) 23 else 14).copyInto(bytes, 10); le16(19).copyInto(bytes, 12)
        bytes[14] = 1; bytes[15] = 1
        if (unicode) {
            le64(bytes.size.toLong()).copyInto(bytes, 184)
            le64(nbtBid).copyInto(bytes, 216); le64(nbtIb).copyInto(bytes, 224)
            le64(bbtBid).copyInto(bytes, 232); le64(bbtIb).copyInto(bytes, 240)
            bytes[512] = 0x80.toByte(); bytes[513] = if (encode) 1 else 0
        } else {
            le32(bytes.size).copyInto(bytes, 168)
            le32(nbtBid.toInt()).copyInto(bytes, 184); le32(nbtIb.toInt()).copyInto(bytes, 188)
            le32(bbtBid.toInt()).copyInto(bytes, 192); le32(bbtIb.toInt()).copyInto(bytes, 196)
            bytes[460] = 0x80.toByte(); bytes[461] = if (encode) 1 else 0
        }
        file.writeBytes(bytes)
    }

    companion object {
        fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
        fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
        fun le64(v: Long) = ByteArray(8) { (v shr (8 * it)).toByte() }
        fun guid(s: String): ByteArray {
            val h = s.replace("-", "")
            val b = ByteArray(16) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            // Data1..Data3 little-endian.
            return byteArrayOf(b[3], b[2], b[1], b[0], b[5], b[4], b[7], b[6]) + b.copyOfRange(8, 16)
        }
    }
}
