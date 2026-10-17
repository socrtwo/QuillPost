package info.socrtwo.quillpost.web.fileformats

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.util.Base64

/**
 * Character-set handling for old and new mail: Windows code pages (MAPI, TNEF, RTF), MIME charset
 * names with the aliases real mailers wrote, UTF-7 (RFC 2152, which Java does not ship), and a
 * forgiving RFC 2047 encoded-word decoder.
 */
object TextCodec {

    private val CODEPAGES = mapOf(
        437 to "IBM437", 708 to "ISO-8859-6", 720 to "x-IBM720", 737 to "x-IBM737", 775 to "IBM775", 850 to "IBM850", 852 to "IBM852",
        855 to "IBM855", 857 to "IBM857", 858 to "IBM00858", 860 to "IBM860", 861 to "IBM861", 862 to "IBM862", 863 to "IBM863",
        864 to "IBM864", 865 to "IBM865", 866 to "IBM866", 869 to "IBM869", 874 to "x-windows-874", 932 to "windows-31j",
        936 to "GBK", 949 to "x-windows-949", 950 to "x-windows-950", 1200 to "UTF-16LE", 1201 to "UTF-16BE",
        1250 to "windows-1250", 1251 to "windows-1251", 1252 to "windows-1252", 1253 to "windows-1253", 1254 to "windows-1254",
        1255 to "windows-1255", 1256 to "windows-1256", 1257 to "windows-1257", 1258 to "windows-1258", 1361 to "x-Johab",
        10000 to "x-MacRoman", 10001 to "x-MacJapanese", 10006 to "x-MacGreek", 10007 to "x-MacCyrillic", 10029 to "x-MacCentralEurope",
        10079 to "x-MacIceland", 10081 to "x-MacTurkish", 12000 to "UTF-32LE", 12001 to "UTF-32BE", 20127 to "US-ASCII",
        20866 to "KOI8-R", 21866 to "KOI8-U", 20932 to "EUC-JP", 20936 to "GB2312", 28591 to "ISO-8859-1", 28592 to "ISO-8859-2",
        28593 to "ISO-8859-3", 28594 to "ISO-8859-4", 28595 to "ISO-8859-5", 28596 to "ISO-8859-6", 28597 to "ISO-8859-7",
        28598 to "ISO-8859-8", 28599 to "ISO-8859-9", 28603 to "ISO-8859-13", 28605 to "ISO-8859-15", 38598 to "ISO-8859-8",
        50220 to "ISO-2022-JP", 50221 to "ISO-2022-JP", 50222 to "ISO-2022-JP", 50225 to "ISO-2022-KR", 51932 to "EUC-JP",
        51936 to "GB2312", 51949 to "EUC-KR", 52936 to "GB2312", 54936 to "GB18030", 65000 to "UTF-7", 65001 to "UTF-8"
    )

    /** RTF \fcharset numbers → Windows code page. */
    val FCHARSET_CODEPAGE = mapOf(
        0 to 1252, 77 to 10000, 78 to 10001, 128 to 932, 129 to 949, 130 to 1361, 134 to 936, 136 to 950, 161 to 1253,
        162 to 1254, 163 to 1258, 177 to 1255, 178 to 1256, 186 to 1257, 204 to 1251, 222 to 874, 238 to 1250, 254 to 437, 255 to 850
    )

    private val ALIASES = mapOf(
        "unicode-1-1-utf-7" to "UTF-7", "utf7" to "UTF-7", "x-utf-7" to "UTF-7", "utf8" to "UTF-8", "unicode-1-1-utf-8" to "UTF-8",
        "x-unicode20utf8" to "UTF-8", "ks_c_5601-1987" to "x-windows-949", "ks_c_5601" to "x-windows-949", "ks_c_5601-1989" to "x-windows-949",
        "x-sjis" to "windows-31j", "shift-jis" to "windows-31j", "shift_jis" to "windows-31j", "sjis" to "windows-31j", "ms_kanji" to "windows-31j",
        "x-euc-jp" to "EUC-JP", "gb2312" to "GBK", "x-gbk" to "GBK", "cp936" to "GBK", "x-mac-roman" to "x-MacRoman", "macintosh" to "x-MacRoman",
        "x-mac-ce" to "x-MacCentralEurope", "x-mac-cyrillic" to "x-MacCyrillic", "x-mac-greek" to "x-MacGreek", "x-mac-turkish" to "x-MacTurkish",
        "ansi_x3.4-1968" to "US-ASCII", "ascii" to "US-ASCII", "us" to "US-ASCII", "iso-8859-8-i" to "ISO-8859-8", "iso-8859-6-i" to "ISO-8859-6",
        "x-user-defined" to "windows-1252", "unknown-8bit" to "windows-1252", "x-unknown" to "windows-1252", "unknown" to "windows-1252",
        "default" to "windows-1252", "iso-8859-1" to "windows-1252", "latin1" to "windows-1252", "latin-1" to "windows-1252", "l1" to "windows-1252",
        "iso8859-1" to "windows-1252", "8859-1" to "windows-1252", "iso_8859-1" to "windows-1252", "cp1252" to "windows-1252",
        "cp-850" to "IBM850", "cp850" to "IBM850", "cp437" to "IBM437", "dos-862" to "IBM862", "x-cp1250" to "windows-1250", "x-cp1251" to "windows-1251",
        "koi8" to "KOI8-R", "big5-hkscs" to "Big5-HKSCS", "x-x-big5" to "Big5", "cn-big5" to "Big5", "tis-620" to "x-windows-874", "windows-874" to "x-windows-874",
        "utf-16" to "UTF-16", "ucs-2" to "UTF-16", "iso-10646-ucs-2" to "UTF-16BE", "csunicode" to "UTF-16BE"
    )

    private val cpCache = java.util.concurrent.ConcurrentHashMap<Int, Charset>()

    fun codepage(cp: Int): Charset = cpCache.getOrPut(cp) { CODEPAGES[cp]?.let { charsetOrNull(it) } ?: charsetOrNull("windows-$cp") ?: charsetOrNull("cp$cp") ?: WINDOWS_1252 }

    /** The charset a MIME label names, or null when neither Java nor the alias table knows it (UTF-7 excluded). */
    fun forName(label: String?): Charset? {
        val l = label?.trim()?.trim('"', '\'')?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        ALIASES[l]?.let { if (it == "UTF-7") return null; return charsetOrNull(it) }
        Regex("^(?:windows|cp|ms|x-cp)-?(\\d{3,5})$").find(l)?.let { return codepage(it.groupValues[1].toInt()) }
        return charsetOrNull(l)
    }

    val WINDOWS_1252: Charset = Charset.forName("windows-1252")

    private fun charsetOrNull(n: String): Charset? = runCatching { Charset.forName(n) }.getOrNull()

    fun isUtf7(label: String?): Boolean = label?.trim()?.trim('"')?.lowercase()?.let { ALIASES[it] == "UTF-7" || it == "utf-7" } == true

    /**
     * Decodes [bytes] in the charset [label]. Unknown labels fall back to UTF-8 when the bytes are
     * valid UTF-8, else windows-1252 — what a person would see in the original mailer most often.
     */
    fun decode(bytes: ByteArray, label: String?): String {
        if (isUtf7(label)) return decodeUtf7(String(bytes, Charsets.ISO_8859_1))
        bom(bytes)?.let { (cs, skip) -> return String(bytes, skip, bytes.size - skip, cs) }
        val cs = forName(label)
        if (cs != null) {
            // A message labelled US-ASCII or ISO-8859-1 that is really UTF-8 is common; prefer UTF-8 then.
            if ((cs.name() == "US-ASCII" || cs.name() == "windows-1252" || cs.name() == "ISO-8859-1") && hasHighBytes(bytes) && isValidUtf8(bytes)) return String(bytes, Charsets.UTF_8)
            return String(bytes, cs)
        }
        return auto(bytes)
    }

    /** UTF-8 when valid, else windows-1252 (BOMs honoured, UTF-16 without BOM recognised by its zero bytes). */
    fun auto(bytes: ByteArray): String {
        bom(bytes)?.let { (cs, skip) -> return String(bytes, skip, bytes.size - skip, cs) }
        if (bytes.size >= 4) {
            val n = minOf(bytes.size, 512)
            var zeroOdd = 0; var zeroEven = 0
            for (i in 0 until n) if (bytes[i].toInt() == 0) { if (i % 2 == 0) zeroEven++ else zeroOdd++ }
            if (zeroOdd > n / 4 && zeroEven == 0) return String(bytes, Charsets.UTF_16LE)
            if (zeroEven > n / 4 && zeroOdd == 0) return String(bytes, Charsets.UTF_16BE)
        }
        return if (isValidUtf8(bytes)) String(bytes, Charsets.UTF_8) else String(bytes, WINDOWS_1252)
    }

    fun bom(b: ByteArray): Pair<Charset, Int>? = when {
        b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte() -> Charsets.UTF_8 to 3
        b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() -> Charsets.UTF_16LE to 2
        b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() -> Charsets.UTF_16BE to 2
        else -> null
    }

    fun hasHighBytes(b: ByteArray): Boolean = b.any { it < 0 }

    fun isValidUtf8(b: ByteArray): Boolean {
        val dec: CharsetDecoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        return runCatching { dec.decode(ByteBuffer.wrap(b)); true }.getOrDefault(false)
    }

    /** RFC 2152 UTF-7. */
    fun decodeUtf7(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '+') { out.append(c); i++; continue }
            val end = s.indexOfFirst(i + 1) { !(it.isLetterOrDigit() && it.code < 128 || it == '+' || it == '/') }
            val stop = if (end < 0) s.length else end
            val b64 = s.substring(i + 1, stop)
            if (b64.isEmpty()) { out.append('+'); i = if (stop < s.length && s[stop] == '-') stop + 1 else stop; continue }
            val bits = runCatching { Base64.getDecoder().decode(b64.padEnd((b64.length + 3) / 4 * 4, '=')) }.getOrNull()
                ?: runCatching { Base64.getDecoder().decode(b64.dropLast(b64.length % 4).let { it.padEnd((it.length + 3) / 4 * 4, '=') }) }.getOrDefault(ByteArray(0))
            var k = 0
            while (k + 1 < bits.size) { out.append((((bits[k].toInt() and 0xff) shl 8) or (bits[k + 1].toInt() and 0xff)).toChar()); k += 2 }
            i = if (stop < s.length && s[stop] == '-') stop + 1 else stop
        }
        return out.toString()
    }

    private inline fun String.indexOfFirst(from: Int, pred: (Char) -> Boolean): Int {
        for (j in from until length) if (pred(this[j])) return j
        return -1
    }

    private val ENCODED_WORD = Regex("=\\?([^?\\s]+)\\?([bBqQ])\\?([^?]*?)\\?=")

    /**
     * Decodes RFC 2047 encoded words leniently: whitespace between adjacent encoded words is
     * dropped, words split in the middle of a multi-byte character are joined before decoding,
     * words inside quoted strings or glued to other text are decoded too, and RFC 2231 language
     * tags (charset*lang) are ignored.
     */
    fun decodeHeader(value: String?): String {
        if (value == null) return ""
        val v = value.replace(Regex("\\r?\\n[ \\t]+"), " ").replace("\r", "").replace("\n", "")
        if (!v.contains("=?")) return v
        val out = StringBuilder()
        var last = 0
        // Pending bytes from consecutive encoded words in the same charset (joined, then decoded).
        var pendCharset: String? = null
        val pend = java.io.ByteArrayOutputStream()
        fun flush() { if (pendCharset != null) { out.append(decode(pend.toByteArray(), pendCharset)); pend.reset(); pendCharset = null } }
        for (m in ENCODED_WORD.findAll(v)) {
            val between = v.substring(last, m.range.first)
            if (between.isNotBlank() || pendCharset == null) { flush(); out.append(between) }
            val cs = m.groupValues[1].substringBefore('*')
            val bytes = if (m.groupValues[2].equals("B", true)) b64(m.groupValues[3]) else q(m.groupValues[3])
            if (pendCharset != null && !pendCharset.equals(cs, true)) flush()
            if (isUtf7(cs)) { flush(); out.append(decodeUtf7(String(bytes, Charsets.ISO_8859_1))) }
            else { pendCharset = cs; pend.write(bytes) }
            last = m.range.last + 1
        }
        flush()
        out.append(v.substring(last))
        return out.toString()
    }

    private fun b64(s: String): ByteArray {
        val clean = s.filter { it.isLetterOrDigit() || it == '+' || it == '/' }
        val padded = clean.dropLast(if (clean.length % 4 == 1) 1 else 0).let { it.padEnd((it.length + 3) / 4 * 4, '=') }
        return runCatching { Base64.getDecoder().decode(padded) }.getOrDefault(ByteArray(0))
    }

    private fun q(s: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '_' -> out.write(0x20)
                c == '=' && i + 2 < s.length && s.substring(i + 1, i + 3).toIntOrNull(16) != null -> { out.write(s.substring(i + 1, i + 3).toInt(16)); i += 2 }
                else -> out.write(c.code and 0xff)
            }
            i++
        }
        return out.toByteArray()
    }

    /** Decodes quoted-printable body text (soft line breaks, lowercase hex and stray "=" tolerated). */
    fun decodeQuotedPrintable(s: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val b = s.toByteArray(Charsets.ISO_8859_1)
        var i = 0
        while (i < b.size) {
            val c = b[i].toInt() and 0xff
            if (c == '='.code) {
                if (i + 1 < b.size && (b[i + 1] == '\n'.code.toByte())) { i += 2; continue }
                if (i + 2 < b.size && b[i + 1] == '\r'.code.toByte() && b[i + 2] == '\n'.code.toByte()) { i += 3; continue }
                val h = if (i + 2 < b.size) String(b, i + 1, 2, Charsets.ISO_8859_1).toIntOrNull(16) else null
                if (h != null) { out.write(h); i += 3; continue }
            }
            out.write(c); i++
        }
        return out.toByteArray()
    }
}
