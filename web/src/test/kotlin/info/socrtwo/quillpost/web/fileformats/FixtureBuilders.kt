package info.socrtwo.quillpost.web.fileformats

import java.io.ByteArrayOutputStream

/**
 * Builders for test fixtures, written from the published specifications: [CfbWriter] ([MS-CFB]),
 * [MsgBuilder] ([MS-OXMSG]), [TnefBuilder] ([MS-OXTNEF]) and [RtfCompressor] ([MS-OXRTFCP]).
 * They exist so the readers are tested against files assembled byte by byte, not against output
 * of the readers' own assumptions.
 */
object CfbWriter {
    class Node(val name: String, val data: ByteArray? = null, val children: MutableList<Node> = ArrayList()) {
        val isStorage get() = data == null
        fun storage(n: String): Node = children.firstOrNull { it.name == n && it.isStorage } ?: Node(n).also { children += it }
        fun stream(n: String, d: ByteArray): Node { children.removeAll { it.name == n }; return Node(n, d).also { children += it } }
    }

    private const val FREE = -1
    private const val END = -2
    private const val FATSECT = -3
    private const val NOSTREAM = -1

    fun write(root: Node): ByteArray {
        // Flatten (root first), children of each storage chained through right-sibling links.
        val entries = ArrayList<Node>()
        val ids = HashMap<Node, Int>()
        fun add(n: Node) { ids[n] = entries.size; entries += n; n.children.sortedWith(compareBy({ it.name.length }, { it.name.uppercase() })).forEach { add(it) } }
        add(root)
        val sortedKids = entries.associateWith { it.children.sortedWith(compareBy({ it.name.length }, { it.name.uppercase() })) }
        val right = HashMap<Node, Int>()
        for ((_, kids) in sortedKids) for (k in kids.indices) right[kids[k]] = if (k + 1 < kids.size) ids.getValue(kids[k + 1]) else NOSTREAM

        // Mini stream for streams under 4096 bytes.
        val mini = ByteArrayOutputStream()
        val miniFat = ArrayList<Int>()
        val start = HashMap<Node, Int>()
        val big = ArrayList<Node>()
        for (n in entries) {
            val d = n.data ?: continue
            if (d.isEmpty()) { start[n] = END; continue }
            if (d.size < 4096) {
                val first = miniFat.size
                val count = (d.size + 63) / 64
                for (k in 0 until count) miniFat += if (k == count - 1) END else first + k + 1
                mini.write(d); mini.write(ByteArray(count * 64 - d.size))
                start[n] = first
            } else big += n
        }
        val dirSectors = (entries.size * 128 + 511) / 512
        val miniFatSectors = (miniFat.size * 4 + 511) / 512
        val miniStreamSectors = (mini.size() + 511) / 512
        val bigSectors = big.sumOf { (it.data!!.size + 511) / 512 }
        val nonFat = dirSectors + miniFatSectors + miniStreamSectors + bigSectors
        var fatSectors = 1
        while (fatSectors * 128 < nonFat + fatSectors) fatSectors++
        require(fatSectors <= 109) { "fixture too large" }
        val fat = IntArray(fatSectors * 128) { FREE }
        var next = 0
        for (k in 0 until fatSectors) fat[next++] = FATSECT
        fun chain(count: Int): Int {
            if (count == 0) return END
            val first = next
            for (k in 0 until count) { fat[next] = if (k == count - 1) END else next + 1; next++ }
            return first
        }
        val firstDir = chain(dirSectors)
        val firstMiniFat = chain(miniFatSectors)
        val firstMini = chain(miniStreamSectors)
        for (n in big) start[n] = chain((n.data!!.size + 511) / 512)

        val out = ByteArrayOutputStream()
        val h = ByteArray(512)
        byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()).copyInto(h)
        put16(h, 0x18, 0x3E); put16(h, 0x1A, 3); put16(h, 0x1C, 0xFFFE); put16(h, 0x1E, 9); put16(h, 0x20, 6)
        put32(h, 0x2C, fatSectors); put32(h, 0x30, firstDir); put32(h, 0x38, 4096)
        put32(h, 0x3C, if (miniFatSectors > 0) firstMiniFat else END); put32(h, 0x40, miniFatSectors)
        put32(h, 0x44, END); put32(h, 0x48, 0)
        for (k in 0 until 109) put32(h, 0x4C + k * 4, if (k < fatSectors) k else FREE)
        out.write(h)
        val fatBytes = ByteArray(fatSectors * 512); for (k in fat.indices) put32(fatBytes, k * 4, fat[k]); out.write(fatBytes)
        val dir = ByteArray(dirSectors * 512)
        for ((i, n) in entries.withIndex()) {
            val o = i * 128
            val nameChars = n.name.take(31)
            for ((k, c) in nameChars.withIndex()) put16(dir, o + k * 2, c.code)
            put16(dir, o + 64, (nameChars.length + 1) * 2)
            dir[o + 66] = (if (i == 0) 5 else if (n.isStorage) 1 else 2).toByte()
            dir[o + 67] = 1
            put32(dir, o + 68, NOSTREAM)
            put32(dir, o + 72, right[n] ?: NOSTREAM)
            put32(dir, o + 76, sortedKids.getValue(n).firstOrNull()?.let { ids.getValue(it) } ?: NOSTREAM)
            if (i == 0) { put32(dir, o + 116, if (miniStreamSectors > 0) firstMini else END); put32(dir, o + 120, mini.size()) }
            else if (!n.isStorage) { put32(dir, o + 116, start[n] ?: END); put32(dir, o + 120, n.data!!.size) }
        }
        // Unused directory slots are empty entries with no siblings.
        for (i in entries.size until dirSectors * 4) { val o = i * 128; put32(dir, o + 68, NOSTREAM); put32(dir, o + 72, NOSTREAM); put32(dir, o + 76, NOSTREAM) }
        out.write(dir)
        val mf = ByteArray(miniFatSectors * 512) { 0xFF.toByte() }; for (k in miniFat.indices) put32(mf, k * 4, miniFat[k]); out.write(mf)
        out.write(mini.toByteArray()); out.write(ByteArray(miniStreamSectors * 512 - mini.size()))
        for (n in big) { val d = n.data!!; out.write(d); out.write(ByteArray((d.size + 511) / 512 * 512 - d.size)) }
        return out.toByteArray()
    }

    fun put16(b: ByteArray, o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte() }
    fun put32(b: ByteArray, o: Int, v: Int) { for (k in 0 until 4) b[o + k] = (v shr (8 * k)).toByte() }
    fun put64(b: ByteArray, o: Int, v: Long) { for (k in 0 until 8) b[o + k] = (v shr (8 * k)).toByte() }
}

/** Typed MAPI values for the builders. */
sealed class PV {
    class Str(val s: String) : PV()
    class Ansi(val b: ByteArray) : PV()
    class Bin(val b: ByteArray) : PV()
    class I32(val v: Int) : PV()
    class Bool(val v: Boolean) : PV()
    class Time(val epochMs: Long) : PV() { val ticks get() = (epochMs + 11_644_473_600_000L) * 10_000 }
    /** PtypObject (TNEF attached messages). */
    class Obj(val b: ByteArray) : PV()
    val type: Int get() = when (this) { is Str -> 0x001F; is Ansi -> 0x001E; is Bin -> 0x0102; is I32 -> 0x0003; is Bool -> 0x000B; is Time -> 0x0040; is Obj -> 0x000D }
}

fun guidBytes(g: String): ByteArray {
    val h = g.replace("-", "")
    fun b(i: Int) = h.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    return byteArrayOf(b(3), b(2), b(1), b(0), b(5), b(4), b(7), b(6)) + (8 until 16).map { b(it) }.toByteArray()
}

class MsgBuilder {
    val props = LinkedHashMap<Int, PV>()
    /** (guid, dispid) → value */
    val named = LinkedHashMap<Pair<String, Int>, PV>()
    val recipients = ArrayList<Map<Int, PV>>()
    val attachments = ArrayList<Pair<Map<Int, PV>, MsgBuilder?>>()

    fun prop(id: Int, v: PV) = apply { props[id] = v }
    fun named(guid: String, dispid: Int, v: PV) = apply { named[guid to dispid] = v }
    fun recipient(type: Int, name: String, smtp: String) = apply { recipients += mapOf(0x0C15 to PV.I32(type), 0x3001 to PV.Str(name), 0x3003 to PV.Str(smtp), 0x3002 to PV.Str("SMTP"), 0x39FE to PV.Str(smtp)) }
    fun attach(props: Map<Int, PV>, embedded: MsgBuilder? = null) = apply { attachments += props to embedded }

    fun build(): ByteArray {
        val registry = LinkedHashMap<Pair<String, Int>, Int>()
        fun collect(b: MsgBuilder) { b.named.keys.forEach { registry.getOrPut(it) { registry.size } }; b.attachments.forEach { it.second?.let { e -> collect(e) } } }
        collect(this)
        val root = CfbWriter.Node("Root Entry")
        fill(root, 32, registry)
        // Named property mapping.
        val nameid = root.storage("__nameid_version1.0")
        val guids = registry.keys.map { it.first }.distinct().filter { it != Mapi.PS_MAPI && it != Mapi.PS_PUBLIC_STRINGS }
        nameid.stream("__substg1.0_00020102", guids.fold(ByteArray(0)) { a, g -> a + guidBytes(g) })
        val entries = ByteArray(registry.size * 8)
        for ((key, idx) in registry) {
            val gi = when (key.first) { Mapi.PS_MAPI -> 1; Mapi.PS_PUBLIC_STRINGS -> 2; else -> 3 + guids.indexOf(key.first) }
            CfbWriter.put32(entries, idx * 8, key.second)
            CfbWriter.put16(entries, idx * 8 + 4, gi shl 1)
            CfbWriter.put16(entries, idx * 8 + 6, idx)
        }
        nameid.stream("__substg1.0_00030102", entries)
        nameid.stream("__substg1.0_00040102", ByteArray(0))
        return CfbWriter.write(root)
    }

    private fun fill(st: CfbWriter.Node, headerSize: Int, registry: Map<Pair<String, Int>, Int>) {
        val all = LinkedHashMap<Int, PV>(props)
        for ((k, v) in named) all[0x8000 + registry.getValue(k)] = v
        writeProps(st, all, headerSize, recipients.size, attachments.size)
        recipients.forEachIndexed { i, r -> writeProps(st.storage("__recip_version1.0_#%08X".format(i)), r, 8, 0, 0) }
        attachments.forEachIndexed { i, (p, emb) ->
            val a = st.storage("__attach_version1.0_#%08X".format(i))
            writeProps(a, if (emb != null) p + (0x3705 to PV.I32(5)) else p, 8, 0, 0)
            if (emb != null) emb.fill(a.storage("__substg1.0_3701000D"), 24, registry)
        }
    }

    private fun writeProps(st: CfbWriter.Node, p: Map<Int, PV>, headerSize: Int, nRecip: Int, nAtt: Int) {
        val out = ByteArray(headerSize + p.size * 16)
        if (headerSize >= 24) { CfbWriter.put32(out, 8, nRecip); CfbWriter.put32(out, 12, nAtt); CfbWriter.put32(out, 16, nRecip); CfbWriter.put32(out, 20, nAtt) }
        var o = headerSize
        for ((id, v) in p) {
            CfbWriter.put32(out, o, (id shl 16) or v.type)
            CfbWriter.put32(out, o + 4, 6)
            when (v) {
                is PV.I32 -> CfbWriter.put32(out, o + 8, v.v)
                is PV.Bool -> out[o + 8] = if (v.v) 1 else 0
                is PV.Time -> CfbWriter.put64(out, o + 8, v.ticks)
                is PV.Str -> { val b = (v.s + "\u0000").toByteArray(Charsets.UTF_16LE); CfbWriter.put32(out, o + 8, b.size); st.stream("__substg1.0_%04X%04X".format(id, v.type), v.s.toByteArray(Charsets.UTF_16LE) + byteArrayOf(0, 0)) }
                is PV.Ansi -> { CfbWriter.put32(out, o + 8, v.b.size + 1); st.stream("__substg1.0_%04X%04X".format(id, v.type), v.b + byteArrayOf(0)) }
                is PV.Bin -> { CfbWriter.put32(out, o + 8, v.b.size); st.stream("__substg1.0_%04X%04X".format(id, v.type), v.b) }
                is PV.Obj -> { CfbWriter.put32(out, o + 8, v.b.size); st.stream("__substg1.0_%04X0102".format(id), v.b) }
            }
            o += 16
        }
        st.stream("__properties_version1.0", out)
    }
}

class TnefBuilder {
    private val out = ByteArrayOutputStream()
    init { val h = ByteArray(6); CfbWriter.put32(h, 0, Tnef.SIGNATURE); CfbWriter.put16(h, 4, 0x0001); out.write(h) }

    fun attr(level: Int, id: Int, data: ByteArray) = apply {
        val h = ByteArray(9); h[0] = level.toByte(); CfbWriter.put32(h, 1, id); CfbWriter.put32(h, 5, data.size)
        out.write(h); out.write(data)
        val sum = data.fold(0) { a, b -> (a + (b.toInt() and 0xff)) and 0xffff }
        out.write(byteArrayOf(sum.toByte(), (sum shr 8).toByte()))
    }

    fun bytes(): ByteArray = out.toByteArray()

    companion object {
        /** MAPI property list: count, then (tag, [named id], [count], value...) with 4-byte padding. */
        fun propList(props: List<Triple<Int, PV, Pair<String, Int>?>>): ByteArray {
            val o = ByteArrayOutputStream()
            fun u32(v: Int) { val b = ByteArray(4); CfbWriter.put32(b, 0, v); o.write(b) }
            fun pad(n: Int) { o.write(ByteArray(((n + 3) and 3.inv()) - n)) }
            u32(props.size)
            for ((id, v, name) in props) {
                u32((id shl 16) or v.type)
                if (name != null) { o.write(guidBytes(name.first)); u32(0); u32(name.second) }
                when (v) {
                    is PV.I32 -> u32(v.v)
                    is PV.Bool -> u32(if (v.v) 1 else 0)
                    is PV.Time -> { val b = ByteArray(8); CfbWriter.put64(b, 0, v.ticks); o.write(b) }
                    is PV.Str -> { val b = (v.s + "\u0000").toByteArray(Charsets.UTF_16LE); u32(1); u32(b.size); o.write(b); pad(b.size) }
                    is PV.Ansi -> { val b = v.b + byteArrayOf(0); u32(1); u32(b.size); o.write(b); pad(b.size) }
                    is PV.Bin -> { u32(1); u32(v.b.size); o.write(v.b); pad(v.b.size) }
                    is PV.Obj -> { u32(1); u32(v.b.size); o.write(v.b); pad(v.b.size) }
                }
            }
            return o.toByteArray()
        }

        /** IID_IMessage {00020307-0000-0000-C000-000000000046} followed by an embedded TNEF stream. */
        fun embeddedMessage(tnef: ByteArray): ByteArray = guidBytes("00020307-0000-0000-C000-000000000046") + tnef

        fun date(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int): ByteArray {
            val b = ByteArray(14); listOf(y, mo, d, h, mi, s, 0).forEachIndexed { i, v -> CfbWriter.put16(b, i * 2, v) }; return b
        }
    }
}

/** LZ77 encoder for compressed RTF, producing what [Rtf.decompress] must read back. */
object RtfCompressor {
    fun compress(raw: ByteArray): ByteArray {
        val dict = ByteArray(4096)
        Rtf.PRESET_DICTIONARY.copyInto(dict)
        var write = Rtf.PRESET_DICTIONARY.size
        val body = ByteArrayOutputStream()
        var i = 0
        var done = false
        while (!done) {
            var control = 0
            val tokens = ByteArrayOutputStream()
            for (bit in 0 until 8) {
                if (i >= raw.size) {
                    control = control or (1 shl bit)
                    tokens.write((write shr 4) and 0xff); tokens.write((write and 0xf) shl 4)
                    done = true; break
                }
                var bestLen = 0; var bestOff = 0
                for (off in 0 until 4096) {
                    if (off == write) continue
                    var len = 0
                    while (len < 17 && i + len < raw.size && dict[(off + len) and 0xfff] == raw[i + len]) {
                        val pos = (off + len) and 0xfff
                        // stay clear of the bytes this token itself will write
                        if (((pos - write) and 0xfff) < 17) break
                        len++
                    }
                    if (len > bestLen) { bestLen = len; bestOff = off }
                }
                if (bestLen >= 2) {
                    control = control or (1 shl bit)
                    val ref = (bestOff shl 4) or (bestLen - 2)
                    tokens.write(ref shr 8); tokens.write(ref and 0xff)
                    for (k in 0 until bestLen) { dict[write] = raw[i + k]; write = (write + 1) and 0xfff }
                    i += bestLen
                } else {
                    tokens.write(raw[i].toInt()); dict[write] = raw[i]; write = (write + 1) and 0xfff; i++
                }
            }
            body.write(control); body.write(tokens.toByteArray())
        }
        val data = body.toByteArray()
        val h = ByteArray(16)
        CfbWriter.put32(h, 0, data.size + 12); CfbWriter.put32(h, 4, raw.size); CfbWriter.put32(h, 8, Rtf.COMPRESSED); CfbWriter.put32(h, 12, Rtf.crc32(data))
        return h + data
    }
}
