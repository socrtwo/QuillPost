package info.socrtwo.quillpost.web.fileformats

import java.util.Base64

/**
 * Attachment encodings that predate or sit beside MIME: uuencode (and begin-base64), yEnc,
 * BinHex 4.0, and Apple's AppleSingle / AppleDouble wrappers. Each decoder returns the file
 * name and the data fork; resource forks are dropped (no non-Mac system can use them).
 */
object LegacyEncodings {

    class Decoded(val fileName: String, val bytes: ByteArray, val encoding: String)

    /** A file found in a text body, and where its encoded block was. */
    class Found(val file: Decoded, val range: IntRange)

    // ------------------------------------------------------------------ uuencode

    private val UU_BEGIN = Regex("(?m)^begin(-base64)? +([0-7]{3,4}) +(.+?)\\r?$")

    /** Every uuencoded (or begin-base64) file in [text]. */
    fun findUuencoded(text: String): List<Found> {
        val out = ArrayList<Found>()
        var from = 0
        while (true) {
            val m = UU_BEGIN.find(text, from) ?: break
            val base64 = m.groupValues[1].isNotEmpty()
            val name = m.groupValues[3].trim()
            val bodyStart = (text.indexOf('\n', m.range.first).takeIf { it >= 0 } ?: break) + 1
            val endRe = if (base64) Regex("(?m)^====\\s*$") else Regex("(?m)^end\\s*$")
            val e = endRe.find(text, bodyStart)
            val bodyEnd = e?.range?.first ?: text.length
            val body = text.substring(bodyStart, bodyEnd)
            val bytes = if (base64) runCatching { Base64.getMimeDecoder().decode(body.replace(Regex("\\s"), "")) }.getOrNull() else uudecode(body)
            if (bytes != null && bytes.isNotEmpty()) out += Found(Decoded(name, bytes, if (base64) "begin-base64" else "uuencode"), m.range.first until (e?.range?.last?.plus(1) ?: text.length))
            from = (e?.range?.last ?: text.length) + 1
            if (from >= text.length) break
        }
        return out
    }

    /** Decodes uuencoded lines (without the begin/end lines); damaged lines are skipped. */
    fun uudecode(body: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (raw in body.split('\n')) {
            val line = raw.trimEnd('\r')
            if (line.isEmpty()) continue
            val n = (line[0].code - 32) and 63
            if (n == 0) continue
            var i = 1
            var written = 0
            while (written < n && i < line.length) {
                fun v(k: Int): Int = if (i + k < line.length) (line[i + k].code - 32) and 63 else 0
                val a = v(0); val b = v(1); val c = v(2); val d = v(3)
                val bytes = intArrayOf((a shl 2) or (b shr 4), ((b and 0xf) shl 4) or (c shr 2), ((c and 3) shl 6) or d)
                for (x in bytes) if (written < n) { out.write(x and 0xff); written++ }
                i += 4
            }
        }
        return out.toByteArray()
    }

    // ------------------------------------------------------------------ yEnc

    private val Y_BEGIN = Regex("(?m)^=ybegin (.*)$")

    fun findYenc(text: String): List<Found> {
        val out = ArrayList<Found>()
        var from = 0
        while (true) {
            val m = Y_BEGIN.find(text, from) ?: break
            val params = m.groupValues[1]
            val name = Regex("name=(.+?)\\r?$").find(params)?.groupValues?.get(1)?.trim() ?: "yenc.bin"
            var bodyStart = (text.indexOf('\n', m.range.first).takeIf { it >= 0 } ?: break) + 1
            if (text.startsWith("=ypart", bodyStart)) bodyStart = (text.indexOf('\n', bodyStart).takeIf { it >= 0 } ?: break) + 1
            val e = Regex("(?m)^=yend.*$").find(text, bodyStart)
            val bodyEnd = e?.range?.first ?: text.length
            val bytes = ydecode(text.substring(bodyStart, bodyEnd))
            out += Found(Decoded(name, bytes, "yEnc"), m.range.first until (e?.range?.last?.plus(1) ?: text.length))
            from = (e?.range?.last ?: text.length) + 1
            if (from >= text.length) break
        }
        return out
    }

    /** yEnc data lines; the text must have been read as ISO-8859-1 so every byte is one char. */
    fun ydecode(body: String): ByteArray {
        val out = java.io.ByteArrayOutputStream(body.length)
        var i = 0
        while (i < body.length) {
            val c = body[i].code and 0xff
            when {
                c == '\r'.code || c == '\n'.code -> {}
                c == '='.code && i + 1 < body.length -> { i++; out.write(((body[i].code and 0xff) - 64 - 42) and 0xff) }
                else -> out.write((c - 42) and 0xff)
            }
            i++
        }
        return out.toByteArray()
    }

    // ------------------------------------------------------------------ BinHex 4.0

    private const val BINHEX_ALPHABET = "!\"#$%&'()*+,-012345689@ABCDEFGHIJKLMNPQRSTUVXYZ[`abcdefhijklmpqr"
    private const val BINHEX_TAG = "(This file must be converted with BinHex"

    fun findBinHex(text: String): List<Found> {
        val out = ArrayList<Found>()
        var from = 0
        while (true) {
            val tag = text.indexOf(BINHEX_TAG, from).takeIf { it >= 0 } ?: break
            val start = text.indexOf(':', text.indexOf('\n', tag).coerceAtLeast(tag))
            if (start < 0) break
            val end = text.indexOf(':', start + 1)
            if (end < 0) break
            decodeBinHex(text.substring(start + 1, end))?.let { out += Found(it, tag..end) }
            from = end + 1
        }
        return out
    }

    fun decodeBinHex(data: String): Decoded? {
        val six = java.io.ByteArrayOutputStream()
        var acc = 0; var bits = 0
        for (ch in data) {
            val v = BINHEX_ALPHABET.indexOf(ch)
            if (v < 0) continue
            acc = (acc shl 6) or v; bits += 6
            if (bits >= 8) { bits -= 8; six.write((acc shr bits) and 0xff) }
        }
        // Run-length decoding: 0x90 n repeats the previous byte n-1 more times; 0x90 0 is a literal 0x90.
        val enc = six.toByteArray()
        val raw = java.io.ByteArrayOutputStream(enc.size)
        var prev = 0
        var i = 0
        while (i < enc.size) {
            val b = enc[i].toInt() and 0xff
            if (b == 0x90 && i + 1 < enc.size) {
                val n = enc[i + 1].toInt() and 0xff
                if (n == 0) { raw.write(0x90); prev = 0x90 } else repeat(n - 1) { raw.write(prev) }
                i += 2
            } else { raw.write(b); prev = b; i++ }
        }
        val r = raw.toByteArray()
        if (r.isEmpty()) return null
        val nameLen = r[0].toInt() and 0xff
        var p = 1 + nameLen + 1 // name, version byte
        if (p + 4 + 4 + 2 + 4 + 4 + 2 > r.size) return null
        val name = macName(r.copyOfRange(1, 1 + nameLen))
        p += 4 + 4 + 2
        val dataLen = be32(r, p); p += 4
        p += 4 // resource fork length
        p += 2 // header CRC
        if (dataLen < 0 || p + dataLen > r.size) return null
        return Decoded(name, r.copyOfRange(p, p + dataLen), "BinHex")
    }

    // ------------------------------------------------------------------ AppleSingle / AppleDouble

    const val APPLE_SINGLE = 0x00051600
    const val APPLE_DOUBLE = 0x00051607

    fun isAppleSingle(b: ByteArray) = b.size >= 26 && be32(b, 0) == APPLE_SINGLE
    fun isAppleDouble(b: ByteArray) = b.size >= 26 && be32(b, 0) == APPLE_DOUBLE

    /** Data fork and real name of an AppleSingle file, or the real name alone of an AppleDouble header. */
    fun decodeApple(b: ByteArray): Decoded? {
        if (!isAppleSingle(b) && !isAppleDouble(b)) return null
        val n = be16(b, 24)
        var name = ""
        var dataFork: ByteArray? = null
        for (k in 0 until n) {
            val e = 26 + k * 12
            if (e + 12 > b.size) break
            val id = be32(b, e); val off = be32(b, e + 4); val len = be32(b, e + 8)
            if (off < 0 || len < 0 || off + len > b.size) continue
            when (id) {
                1 -> dataFork = b.copyOfRange(off, off + len)
                3 -> name = macName(b.copyOfRange(off, off + len)).trim('\u0000')
            }
        }
        return Decoded(name, dataFork ?: ByteArray(0), if (isAppleSingle(b)) "AppleSingle" else "AppleDouble")
    }

    /** Mac file names: UTF-8 on Mac OS X when valid, else Mac Roman (classic Mac OS). */
    private fun macName(b: ByteArray): String = if (TextCodec.hasHighBytes(b) && TextCodec.isValidUtf8(b)) String(b, Charsets.UTF_8) else String(b, TextCodec.codepage(10000))

    private fun be32(b: ByteArray, o: Int): Int = if (o + 4 > b.size) -1 else ((b[o].toInt() and 0xff) shl 24) or ((b[o + 1].toInt() and 0xff) shl 16) or ((b[o + 2].toInt() and 0xff) shl 8) or (b[o + 3].toInt() and 0xff)
    private fun be16(b: ByteArray, o: Int): Int = if (o + 2 > b.size) 0 else ((b[o].toInt() and 0xff) shl 8) or (b[o + 1].toInt() and 0xff)
}
