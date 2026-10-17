package info.socrtwo.quillpost.web.fileformats

/**
 * Outlook item files: .msg (saved messages, contacts, appointments, tasks, notes) and .oft
 * templates, read as described in [MS-OXMSG] on top of the compound-file reader [Cfb].
 */
object OutlookMsg {
    private const val PROPS = "__properties_version1.0"
    private const val NAMEID = "__nameid_version1.0"
    private const val MAX_DEPTH = 8

    /** True when [cfb] is an Outlook item (a property stream plus property sub-streams at the root). */
    fun isMsg(cfb: Cfb): Boolean = cfb.root.child(PROPS) != null && cfb.root.children.any { it.name.startsWith("__substg1.0_", true) }

    fun parse(bytes: ByteArray, fileName: String = ""): ParsedMessage {
        val cfb = Cfb.open(bytes) ?: throw IllegalArgumentException("Not an Outlook item (no compound-file header)")
        require(isMsg(cfb)) { "This compound file is not an Outlook item" }
        val names = namedMap(cfb)
        val template = fileName.lowercase().endsWith(".oft")
        return readMessage(cfb, cfb.root, 32, names, if (template) "oft" else "msg", 0)
    }

    private fun readMessage(cfb: Cfb, storage: Cfb.Entry, headerSize: Int, names: Map<Int, String>, format: String, depth: Int): ParsedMessage {
        val props = readProps(cfb, storage, headerSize, names)
        val recips = storage.children.filter { it.isStorage && it.name.startsWith("__recip_version1.0_", true) }
            .sortedBy { it.name.substringAfter('#').toIntOrNull(16) ?: 0 }
            .map { readProps(cfb, it, 8, names) }
        val notes = ArrayList<String>()
        val atts = storage.children.filter { it.isStorage && it.name.startsWith("__attach_version1.0_", true) }
            .sortedBy { it.name.substringAfter('#').toIntOrNull(16) ?: 0 }
            .map { st ->
                val ap = readProps(cfb, st, 8, names)
                val obj = st.child("__substg1.0_3701000D")
                val embedded = if (obj != null && obj.isStorage && obj.child(PROPS) != null && depth < MAX_DEPTH)
                    runCatching { readMessage(cfb, obj, 24, names, "msg", depth + 1) }.getOrNull()
                else null
                if (obj != null && embedded == null && (ap.int(Mapi.ATTACH_METHOD) ?: 0) == 6) notes += "An embedded OLE object (\"${ap.string(Mapi.DISPLAY_NAME) ?: "object"}\") cannot be shown."
                MapiAttachment(ap, embedded)
            }
        return Mapi.toParsed(format, props, recips, atts, notes)
    }

    /** Reads the fixed-size values from the property stream and every variable-size property stream. */
    private fun readProps(cfb: Cfb, storage: Cfb.Entry, headerSize: Int, names: Map<Int, String>): MapiProps {
        val p = MapiProps()
        p.names = names
        storage.child(PROPS)?.let { e ->
            val b = cfb.read(e)
            var off = headerSize
            while (off + 16 <= b.size) {
                val tag = Cfb.le32(b, off)
                val type = tag and 0xffff
                val id = (tag ushr 16) and 0xffff
                val v = off + 8
                when (type) {
                    0x0002 -> p.put(id, ((b[v].toInt() and 0xff) or (b[v + 1].toInt() shl 8)).toShort().toInt())
                    0x0003, 0x000A -> p.put(id, Cfb.le32(b, v))
                    0x0004 -> p.put(id, java.lang.Float.intBitsToFloat(Cfb.le32(b, v)).toDouble())
                    0x0005, 0x0007 -> p.put(id, java.lang.Double.longBitsToDouble(Cfb.le64(b, v)))
                    0x0006, 0x0014 -> p.put(id, Cfb.le64(b, v))
                    0x000B -> p.put(id, b[v].toInt() != 0)
                    0x0040 -> p.put(id, MapiProps.FileTime(Cfb.le64(b, v)))
                }
                off += 16
            }
        }
        p.codepage = p.int(Mapi.MESSAGE_CODEPAGE) ?: p.int(Mapi.INTERNET_CPID) ?: 0
        for (e in storage.children) {
            if (!e.isStream || !e.name.startsWith("__substg1.0_", true) || e.name.length < 20) continue
            val tag = e.name.substring(12, 20).toLongOrNull(16)?.toInt() ?: continue
            val type = tag and 0xffff
            val id = (tag ushr 16) and 0xffff
            when (type) {
                0x001F -> p.put(id, String(cfb.read(e), Charsets.UTF_16LE))
                0x001E -> p.put(id, MapiProps.Ansi(cfb.read(e)))
                0x0102, 0x0048 -> if (!p.has(id) || p.values[id] !is String) p.put(id, cfb.read(e))
                0x101F -> p.put(id, multiStrings(cfb, storage, e, tag))
            }
        }
        return p
    }

    /** A multi-valued Unicode string property: a length stream plus one stream per value. */
    private fun multiStrings(cfb: Cfb, storage: Cfb.Entry, lengths: Cfb.Entry, tag: Int): String {
        val n = (cfb.read(lengths).size / 4)
        val base = "__substg1.0_%08X".format(tag)
        return (0 until n).mapNotNull { i -> storage.child("$base-%08X".format(i))?.let { String(cfb.read(it), Charsets.UTF_16LE).trimEnd('\u0000') } }.joinToString("; ")
    }

    /** Property id (0x8000 + index) → "GUID:dispid" or "GUID:name" from the named property mapping storage. */
    private fun namedMap(cfb: Cfb): Map<Int, String> {
        val st = cfb.root.child(NAMEID) ?: return emptyMap()
        val guids = st.child("__substg1.0_00020102")?.let { cfb.read(it) } ?: ByteArray(0)
        val entries = st.child("__substg1.0_00030102")?.let { cfb.read(it) } ?: return emptyMap()
        val strings = st.child("__substg1.0_00040102")?.let { cfb.read(it) } ?: ByteArray(0)
        val out = HashMap<Int, String>()
        var off = 0
        while (off + 8 <= entries.size) {
            val nameOrId = Cfb.le32(entries, off)
            val indexKind = (entries[off + 4].toInt() and 0xff) or ((entries[off + 5].toInt() and 0xff) shl 8)
            val propIndex = (entries[off + 6].toInt() and 0xff) or ((entries[off + 7].toInt() and 0xff) shl 8)
            val guidIndex = indexKind ushr 1
            val guid = when (guidIndex) {
                1 -> Mapi.PS_MAPI
                2 -> Mapi.PS_PUBLIC_STRINGS
                else -> { val g = (guidIndex - 3) * 16; if (g >= 0 && g + 16 <= guids.size) MapiProps.guidString(guids, g) else "" }
            }
            val key = if (indexKind and 1 == 0) nameOrId.toString() else {
                val len = Cfb.le32(strings, nameOrId)
                if (len in 0..4096 && nameOrId + 4 + len <= strings.size) String(strings, nameOrId + 4, len, Charsets.UTF_16LE) else ""
            }
            if (guid.isNotEmpty()) out[0x8000 + propIndex] = "$guid:$key"
            off += 8
        }
        return out
    }
}
