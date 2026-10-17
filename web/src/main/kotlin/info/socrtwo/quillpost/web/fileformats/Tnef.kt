package info.socrtwo.quillpost.web.fileformats

import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Transport Neutral Encapsulation Format ([MS-OXTNEF]): the winmail.dat / application/ms-tnef
 * part Outlook and Exchange attach to messages. Yields the message's MAPI properties (subject,
 * RTF or HTML body, sender, recipients) and its attachments, including attached messages.
 */
object Tnef {
    const val SIGNATURE = 0x223E9F78

    private const val LVL_MESSAGE = 1
    private const val LVL_ATTACHMENT = 2

    // Attribute ids (low word id, high word type), [MS-OXTNEF] 2.1.3.
    private const val ATT_FROM = 0x00008000
    private const val ATT_SUBJECT = 0x00018004
    private const val ATT_DATE_SENT = 0x00038005
    private const val ATT_MESSAGE_CLASS = 0x00078008
    private const val ATT_MESSAGE_ID = 0x00018009
    private const val ATT_BODY = 0x0002800C
    private const val ATT_PRIORITY = 0x0004800D
    private const val ATT_ATTACH_DATA = 0x0006800F
    private const val ATT_ATTACH_TITLE = 0x00018010
    private const val ATT_ATTACH_RENDDATA = 0x00069002
    private const val ATT_MSG_PROPS = 0x00069003
    private const val ATT_RECIP_TABLE = 0x00069004
    private const val ATT_ATTACHMENT = 0x00069005
    private const val ATT_OEM_CODEPAGE = 0x00069007

    private const val MAX_DEPTH = 8

    fun isTnef(b: ByteArray): Boolean = b.size >= 6 && Cfb.le32(b, 0) == SIGNATURE

    /** Reads a TNEF stream; [notes] records anything the reader should mention. */
    fun parse(bytes: ByteArray, depth: Int = 0): ParsedMessage {
        require(isTnef(bytes)) { "Not a TNEF stream" }
        val msg = MapiProps()
        val recips = ArrayList<MapiProps>()
        val atts = ArrayList<Pair<MapiProps, ParsedMessage?>>()
        val notes = ArrayList<String>()
        var cur: MapiProps? = null
        var curEmbedded: ParsedMessage? = null
        var oemCp = 1252
        var sentFallback: Long? = null
        var off = 6
        fun closeAttachment() { cur?.let { atts += it to curEmbedded }; cur = null; curEmbedded = null }
        while (off + 9 <= bytes.size) {
            val level = bytes[off].toInt() and 0xff
            val attr = Cfb.le32(bytes, off + 1)
            val len = Cfb.le32(bytes, off + 5)
            val start = off + 9
            if (len < 0 || start + len > bytes.size) { notes += "The winmail.dat data is truncated; some content may be missing."; break }
            val data = bytes.copyOfRange(start, start + len)
            off = start + len + 2 // checksum
            when {
                level == LVL_MESSAGE -> when (attr) {
                    ATT_OEM_CODEPAGE -> if (data.size >= 4) { oemCp = Cfb.le32(data, 0).takeIf { it > 0 } ?: 1252; msg.codepage = oemCp }
                    ATT_MSG_PROPS -> readPropList(data, 0, msg, depth)
                    ATT_RECIP_TABLE -> {
                        val rows = Cfb.le32(data, 0)
                        var p = 4
                        for (r in 0 until rows.coerceIn(0, 10_000)) {
                            val rp = MapiProps(); rp.codepage = oemCp
                            p = readPropList(data, p, rp, depth)
                            if (p < 0) break
                            recips += rp
                        }
                    }
                    ATT_SUBJECT -> if (!msg.has(Mapi.SUBJECT)) msg.put(Mapi.SUBJECT, MapiProps.Ansi(data.trimNul()))
                    ATT_MESSAGE_CLASS -> if (!msg.has(Mapi.MESSAGE_CLASS)) msg.put(Mapi.MESSAGE_CLASS, MapiProps.Ansi(data.trimNul()))
                    ATT_BODY -> if (!msg.has(Mapi.BODY)) msg.put(Mapi.BODY, MapiProps.Ansi(data.trimNul()))
                    ATT_MESSAGE_ID -> {}
                    ATT_DATE_SENT -> sentFallback = dateOf(data)
                    ATT_PRIORITY -> if (data.size >= 2 && !msg.has(Mapi.IMPORTANCE)) msg.put(Mapi.IMPORTANCE, when (data[0].toInt()) { 1 -> 2; 3 -> 0; else -> 1 })
                    ATT_FROM -> triple(data)?.let { (name, addr) ->
                        if (!msg.has(Mapi.SENDER_NAME)) msg.put(Mapi.SENDER_NAME, name)
                        if (!msg.has(Mapi.SENDER_EMAIL) && addr.contains('@')) msg.put(Mapi.SENDER_EMAIL, addr)
                    }
                }
                level == LVL_ATTACHMENT -> when (attr) {
                    ATT_ATTACH_RENDDATA -> { closeAttachment(); cur = MapiProps().also { it.codepage = oemCp } }
                    ATT_ATTACH_TITLE -> cur?.let { if (!it.has(Mapi.ATTACH_FILENAME)) it.put(Mapi.ATTACH_FILENAME, MapiProps.Ansi(data.trimNul())) }
                    ATT_ATTACH_DATA -> cur?.let { if (!it.has(Mapi.ATTACH_DATA)) it.put(Mapi.ATTACH_DATA, data) }
                    ATT_ATTACHMENT -> {
                        val a = cur ?: MapiProps().also { it.codepage = oemCp; cur = it }
                        readPropList(data, 0, a, depth)
                        (a.values[Mapi.ATTACH_DATA] as? EmbeddedObject)?.let { obj ->
                            a.values.remove(Mapi.ATTACH_DATA)
                            curEmbedded = obj.message
                            a.put(Mapi.ATTACH_DATA, obj.raw)
                        }
                    }
                }
            }
        }
        closeAttachment()
        if (!msg.has(Mapi.CLIENT_SUBMIT_TIME) && sentFallback != null) msg.put(Mapi.CLIENT_SUBMIT_TIME, MapiProps.FileTime((sentFallback!! + 11_644_473_600_000L) * 10_000))
        if (msg.codepage == 0) msg.codepage = oemCp
        return Mapi.toParsed("tnef", msg, recips, atts.map { MapiAttachment(it.first, it.second) }, notes)
    }

    /** A PtypObject attachment value: an attached message is itself a TNEF stream. */
    private class EmbeddedObject(val raw: ByteArray, val message: ParsedMessage?)

    private fun ByteArray.trimNul(): ByteArray { var n = size; while (n > 0 && this[n - 1].toInt() == 0) n--; return copyOf(n) }

    /** attDateSent etc.: seven little-endian words (year, month, day, hour, minute, second, weekday), UTC. */
    private fun dateOf(d: ByteArray): Long? {
        if (d.size < 12) return null
        fun w(i: Int) = (d[i * 2].toInt() and 0xff) or ((d[i * 2 + 1].toInt() and 0xff) shl 8)
        return runCatching { LocalDateTime.of(w(0), w(1), w(2), w(3), w(4), w(5)).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
    }

    /** attFrom: a TRP structure followed by the display name and address. */
    private fun triple(d: ByteArray): Pair<String, String>? {
        if (d.size < 8) return null
        fun w(i: Int) = (d[i].toInt() and 0xff) or ((d[i + 1].toInt() and 0xff) shl 8)
        val nameLen = w(4); val addrLen = w(6)
        if (8 + nameLen + addrLen > d.size) return null
        val name = String(d, 8, nameLen, TextCodec.WINDOWS_1252).trimEnd('\u0000')
        val addr = String(d, 8 + nameLen, addrLen, TextCodec.WINDOWS_1252).trimEnd('\u0000').removePrefix("SMTP:")
        return name to addr
    }

    /**
     * Reads a MAPI property list (count, then properties) from [d] at [start] into [into];
     * returns the offset after it, or -1 when the data ends early.
     */
    private fun readPropList(d: ByteArray, start: Int, into: MapiProps, depth: Int): Int {
        val count = Cfb.le32(d, start)
        if (count < 0) return -1
        var p = start + 4
        val names = HashMap(into.names)
        for (k in 0 until count.coerceAtMost(100_000)) {
            if (p + 4 > d.size) return -1
            val tag = Cfb.le32(d, p); p += 4
            val type = tag and 0xffff
            var id = (tag ushr 16) and 0xffff
            if (id >= 0x8000) {
                if (p + 20 > d.size) return -1
                val guid = MapiProps.guidString(d, p); p += 16
                val kind = Cfb.le32(d, p); p += 4
                val key = if (kind == 0) { val v = Cfb.le32(d, p); p += 4; v.toString() } else {
                    val n = Cfb.le32(d, p); p += 4
                    if (n < 0 || p + n > d.size) return -1
                    val s = String(d, p, n, Charsets.UTF_16LE).trimEnd('\u0000'); p += pad4(n); s
                }
                names[id] = "$guid:$key"
            }
            val multi = type and 0x1000 != 0
            val base = type and 0x0fff
            val variable = base == 0x001E || base == 0x001F || base == 0x0102 || base == 0x000D
            val n = if (multi || variable) { val c = Cfb.le32(d, p); p += 4; c } else 1
            if (n < 0 || n > 1_000_000) return -1
            var first: Any? = null
            val list = ArrayList<String>()
            for (i in 0 until n) {
                val value: Any?
                when (base) {
                    0x0002, 0x000B -> { if (p + 4 > d.size) return -1; val v = (d[p].toInt() and 0xff) or ((d[p + 1].toInt() and 0xff) shl 8); value = if (base == 0x000B) v != 0 else v.toShort().toInt(); p += 4 }
                    0x0003, 0x000A -> { if (p + 4 > d.size) return -1; value = Cfb.le32(d, p); p += 4 }
                    0x0004 -> { if (p + 4 > d.size) return -1; value = java.lang.Float.intBitsToFloat(Cfb.le32(d, p)).toDouble(); p += 4 }
                    0x0005, 0x0007 -> { if (p + 8 > d.size) return -1; value = java.lang.Double.longBitsToDouble(Cfb.le64(d, p)); p += 8 }
                    0x0006, 0x0014 -> { if (p + 8 > d.size) return -1; value = Cfb.le64(d, p); p += 8 }
                    0x0040 -> { if (p + 8 > d.size) return -1; value = MapiProps.FileTime(Cfb.le64(d, p)); p += 8 }
                    0x0048 -> { if (p + 16 > d.size) return -1; value = d.copyOfRange(p, p + 16); p += 16 }
                    0x001E, 0x001F, 0x0102, 0x000D -> {
                        val len = Cfb.le32(d, p); p += 4
                        if (len < 0 || p + len > d.size) return -1
                        val raw = d.copyOfRange(p, p + len); p += pad4(len)
                        value = when (base) {
                            0x001F -> String(raw, Charsets.UTF_16LE).trimEnd('\u0000')
                            0x001E -> MapiProps.Ansi(raw.trimNul())
                            0x000D -> {
                                // 16-byte interface id, then the object; IID_IMessage objects are TNEF streams.
                                val inner = if (raw.size > 16) raw.copyOfRange(16, raw.size) else ByteArray(0)
                                val msg = if (depth < MAX_DEPTH && isTnef(inner)) runCatching { parse(inner, depth + 1) }.getOrNull() else null
                                EmbeddedObject(inner, msg)
                            }
                            else -> raw
                        }
                    }
                    else -> return -1 // unknown type: the rest cannot be located
                }
                if (i == 0) first = value
                if (multi) (value as? String ?: (value as? MapiProps.Ansi)?.let { String(it.bytes, TextCodec.codepage(into.codepage.takeIf { c -> c > 0 } ?: 1252)) })?.let { list += it }
            }
            when {
                multi && list.isNotEmpty() -> into.put(id, list.joinToString("; "))
                multi -> {}
                first != null -> into.values[id] = first
            }
        }
        into.names = names
        return p
    }

    private fun pad4(n: Int) = (n + 3) and 3.inv()
}
