package info.socrtwo.quillpost.web.fileformats

/**
 * Read-only Compound File Binary reader ([MS-CFB]), the container of Outlook .msg/.oft files and
 * of the binary Office formats (.doc, .xls, .ppt and their relatives). Written from the published
 * specification: header, DIFAT, FAT, mini FAT, mini stream and the red-black directory tree.
 * Damaged files are read as far as possible; loops and out-of-range sectors end a chain.
 */
class Cfb private constructor(private val data: ByteArray) {

    class Entry(
        val name: String,
        /** 1 storage, 2 stream, 5 root storage. */
        val type: Int,
        internal val start: Int,
        val size: Long,
        internal val left: Int,
        internal val right: Int,
        internal val child: Int
    ) {
        val children = ArrayList<Entry>()
        val isStorage: Boolean get() = type == 1 || type == 5
        val isStream: Boolean get() = type == 2
        /** The child named [n] (names compare case-insensitively, as in the specification). */
        fun child(n: String): Entry? = children.firstOrNull { it.name.equals(n, ignoreCase = true) }
        override fun toString() = "$name(${if (isStorage) "storage" else "$size bytes"})"
    }

    private val sectorShift: Int
    private val sectorSize: Int
    private val miniSectorSize: Int
    private val miniCutoff: Long
    private val fat: IntArray
    private val miniFat: IntArray
    private val entries: List<Entry>
    val root: Entry
    private val miniStream: ByteArray

    init {
        require(isCfb(data)) { "Not a compound file" }
        val major = u16(0x1A)
        sectorShift = u16(0x1E).let { if (it == 9 || it == 12) it else if (major == 4) 12 else 9 }
        sectorSize = 1 shl sectorShift
        miniSectorSize = 1 shl (u16(0x20).takeIf { it in 1..12 } ?: 6)
        miniCutoff = u32(0x38).toLong().takeIf { it in 1..0x100000 } ?: 4096L
        val numFat = u32(0x2C)
        val firstDir = u32(0x30)
        val firstMiniFat = u32(0x3C)
        val numMiniFat = u32(0x40)
        var difatSector = u32(0x44)
        // DIFAT: 109 entries in the header, the rest in a chain of DIFAT sectors.
        val fatSectors = ArrayList<Int>()
        for (i in 0 until 109) { val s = u32(0x4C + i * 4); if (s >= 0) fatSectors += s }
        var guard = 0
        val perDifat = sectorSize / 4 - 1
        while (difatSector >= 0 && guard++ < 50_000 && fatSectors.size < numFat.coerceAtLeast(0) + 1) {
            val off = sectorOffset(difatSector)
            if (off < 0 || off + sectorSize > data.size) break
            for (i in 0 until perDifat) { val s = u32(off + i * 4); if (s >= 0) fatSectors += s }
            difatSector = u32(off + perDifat * 4)
        }
        val fatList = ArrayList<Int>()
        for (s in fatSectors.take(if (numFat > 0) numFat else fatSectors.size)) {
            val off = sectorOffset(s)
            if (off < 0 || off + sectorSize > data.size) continue
            for (i in 0 until sectorSize / 4) fatList += u32(off + i * 4)
        }
        fat = fatList.toIntArray()
        // Directory
        val dirBytes = readChain(firstDir, Long.MAX_VALUE, fat, sectorSize) { sectorOffset(it) }
        val list = ArrayList<Entry>()
        var p = 0
        while (p + 128 <= dirBytes.size) {
            val nameLen = (dirBytes[p + 64].toInt() and 0xff) or ((dirBytes[p + 65].toInt() and 0xff) shl 8)
            val chars = ((nameLen.coerceIn(0, 64)) / 2 - 1).coerceAtLeast(0)
            val sb = StringBuilder()
            for (i in 0 until chars) sb.append(((dirBytes[p + i * 2].toInt() and 0xff) or ((dirBytes[p + i * 2 + 1].toInt() and 0xff) shl 8)).toChar())
            val type = dirBytes[p + 66].toInt() and 0xff
            val e = Entry(sb.toString(), type, le32(dirBytes, p + 116), le64(dirBytes, p + 120).let { if (major == 3) it and 0xffffffffL else it },
                le32(dirBytes, p + 68), le32(dirBytes, p + 72), le32(dirBytes, p + 76))
            list += e
            p += 128
        }
        entries = list
        root = list.firstOrNull { it.type == 5 } ?: throw IllegalArgumentException("The compound file has no root entry")
        miniFat = if (numMiniFat > 0 || firstMiniFat >= 0) {
            val b = readChain(firstMiniFat, Long.MAX_VALUE, fat, sectorSize) { sectorOffset(it) }
            IntArray(b.size / 4) { le32(b, it * 4) }
        } else IntArray(0)
        miniStream = if (root.start >= 0 && root.size > 0) readChain(root.start, root.size, fat, sectorSize) { sectorOffset(it) } else ByteArray(0)
        // Build the tree (each storage's children hang off a red-black tree of siblings).
        val seen = HashSet<Int>()
        fun collect(idx: Int, into: Entry, depth: Int) {
            if (idx < 0 || idx >= entries.size || depth > 2000 || !seen.add(idx)) return
            val e = entries[idx]
            collect(e.left, into, depth + 1)
            if (e.type == 1 || e.type == 2) into.children += e
            collect(e.right, into, depth + 1)
            if (e.isStorage && e.child >= 0) collect(e.child, e, depth + 1)
        }
        seen += entries.indexOf(root)
        collect(root.child, root, 0)
    }

    /** The bytes of [e] (a stream). */
    fun read(e: Entry): ByteArray {
        if (!e.isStream || e.size <= 0) return ByteArray(0)
        val size = e.size.coerceAtMost(data.size.toLong() * 2)
        return if (size < miniCutoff) readMini(e.start, size) else readChain(e.start, size, fat, sectorSize) { sectorOffset(it) }
    }

    private fun readMini(start: Int, size: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream(size.toInt().coerceAtLeast(0))
        var s = start
        var guard = 0
        while (s >= 0 && out.size() < size && guard++ < 10_000_000) {
            val off = s.toLong() * miniSectorSize
            if (off + miniSectorSize > miniStream.size) {
                if (off < miniStream.size) out.write(miniStream, off.toInt(), miniStream.size - off.toInt())
                break
            }
            out.write(miniStream, off.toInt(), miniSectorSize)
            s = if (s < miniFat.size) miniFat[s] else -2
        }
        val b = out.toByteArray()
        return if (b.size > size) b.copyOf(size.toInt()) else b
    }

    /** Finds a stream or storage by a path of names ("__nameid_version1.0/__substg1.0_00020102"). */
    fun find(path: String, from: Entry = root): Entry? {
        var cur: Entry = from
        for (part in path.split('/').filter { it.isNotEmpty() }) cur = cur.child(part) ?: return null
        return cur
    }

    /** Every stream and storage path, depth first ("Root Entry" omitted); used by the detectors. */
    fun paths(): List<String> {
        val out = ArrayList<String>()
        fun walk(e: Entry, prefix: String, depth: Int) {
            if (depth > 64) return
            for (c in e.children) { val p = prefix + c.name; out += p; if (c.isStorage) walk(c, "$p/", depth + 1) }
        }
        walk(root, "", 0)
        return out
    }

    private fun sectorOffset(s: Int): Int {
        if (s < 0) return -1
        val off = (s.toLong() + 1) shl sectorShift
        return if (off >= data.size) -1 else off.toInt()
    }

    private inline fun readChain(start: Int, size: Long, table: IntArray, unit: Int, offset: (Int) -> Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var s = start
        var guard = 0
        val limit = table.size + 1
        while (s >= 0 && out.size() < size && guard++ <= limit) {
            val off = offset(s)
            if (off < 0) break
            val n = minOf(unit, data.size - off)
            if (n <= 0) break
            out.write(data, off, n)
            s = if (s < table.size) table[s] else -2
        }
        val b = out.toByteArray()
        return if (size != Long.MAX_VALUE && b.size > size) b.copyOf(size.toInt()) else b
    }

    private fun u16(off: Int): Int = if (off + 2 > data.size) 0 else (data[off].toInt() and 0xff) or ((data[off + 1].toInt() and 0xff) shl 8)
    private fun u32(off: Int): Int = if (off + 4 > data.size) -1 else le32(data, off)

    companion object {
        private val MAGIC = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())

        fun isCfb(b: ByteArray): Boolean = b.size >= 512 && (0 until 8).all { b[it] == MAGIC[it] }

        /** Opens [bytes], or null when they are not a readable compound file. */
        fun open(bytes: ByteArray): Cfb? = if (!isCfb(bytes)) null else runCatching { Cfb(bytes) }.getOrNull()

        internal fun le32(b: ByteArray, off: Int): Int =
            if (off < 0 || off + 4 > b.size) -1
            else (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8) or ((b[off + 2].toInt() and 0xff) shl 16) or ((b[off + 3].toInt() and 0xff) shl 24)

        internal fun le64(b: ByteArray, off: Int): Long =
            if (off < 0 || off + 8 > b.size) 0L
            else (le32(b, off).toLong() and 0xffffffffL) or ((le32(b, off + 4).toLong() and 0xffffffffL) shl 32)
    }
}
