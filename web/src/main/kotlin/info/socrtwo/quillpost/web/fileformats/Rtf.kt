package info.socrtwo.quillpost.web.fileformats

import java.nio.charset.Charset
import java.util.Base64

/**
 * Rich Text Format support for mail:
 *  - [decompress]: compressed RTF bodies of Outlook items and TNEF ([MS-OXRTFCP]);
 *  - [deEncapsulateHtml]: the original HTML of an HTML message stored as RTF ([MS-OXRTFEX]);
 *  - [toHtml]: an RTF-only body (Outlook "Rich Text" messages, .rtf attachments) rendered as HTML
 *    with fonts, sizes, colours, bold/italic/underline/strike, super/subscript, alignment,
 *    indents, tables, hyperlinks, bullets and PNG/JPEG pictures.
 * Written from the published specifications and the RTF 1.9.1 specification.
 */
object Rtf {

    // ------------------------------------------------------------------ MS-OXRTFCP

    private const val PRESET = "{\\rtf1\\ansi\\mac\\deff0\\deftab720{\\fonttbl;}{\\f0\\fnil \\froman \\fswiss \\fmodern \\fscript \\fdecor MS Sans SerifSymbolArialTimes New RomanCourier{\\colortbl\\red0\\green0\\blue0\r\n\\par \\pard\\plain\\f0\\fs20\\b\\i\\u\\tab\\tx"

    /** The 207-byte initial dictionary of the compressed RTF format. */
    val PRESET_DICTIONARY: ByteArray = PRESET.toByteArray(Charsets.ISO_8859_1)

    const val COMPRESSED = 0x75465A4C   // "LZFu"
    const val UNCOMPRESSED = 0x414C454D // "MELA"

    /** Decompresses a PidTagRtfCompressed value; throws on a damaged header. */
    fun decompress(src: ByteArray): ByteArray {
        require(src.size >= 16) { "Compressed RTF is shorter than its header" }
        val compSize = Cfb.le32(src, 0).toLong() and 0xffffffffL
        val rawSize = Cfb.le32(src, 4).toLong() and 0xffffffffL
        val type = Cfb.le32(src, 8)
        val end = (compSize + 4).coerceAtMost(src.size.toLong()).toInt()
        if (type == UNCOMPRESSED) return src.copyOfRange(16, minOf(src.size, 16 + rawSize.toInt().coerceAtLeast(0)))
        require(type == COMPRESSED) { "Unknown compressed RTF type" }
        val dict = ByteArray(4096)
        System.arraycopy(PRESET_DICTIONARY, 0, dict, 0, PRESET_DICTIONARY.size)
        var write = PRESET_DICTIONARY.size
        val out = java.io.ByteArrayOutputStream(rawSize.coerceIn(0, 64L shl 20).toInt())
        var p = 16
        outer@ while (p < end) {
            val control = src[p++].toInt() and 0xff
            for (bit in 0 until 8) {
                if (p >= end) break@outer
                if (control and (1 shl bit) == 0) {
                    val b = src[p++]
                    out.write(b.toInt()); dict[write] = b; write = (write + 1) and 0xfff
                } else {
                    if (p + 1 >= end) break@outer
                    val ref = ((src[p].toInt() and 0xff) shl 8) or (src[p + 1].toInt() and 0xff)
                    p += 2
                    var offset = ref ushr 4
                    val len = (ref and 0xf) + 2
                    if (offset == write) break@outer
                    for (k in 0 until len) {
                        val b = dict[offset]
                        offset = (offset + 1) and 0xfff
                        out.write(b.toInt()); dict[write] = b; write = (write + 1) and 0xfff
                    }
                }
            }
        }
        return out.toByteArray()
    }

    /** The CRC-32 variant of [MS-OXRTFCP] (no initial or final inversion). */
    fun crc32(b: ByteArray, from: Int = 0, to: Int = b.size): Int {
        var crc = 0
        for (i in from until to) crc = CRC_TABLE[(crc xor b[i].toInt()) and 0xff] xor (crc ushr 8)
        return crc
    }

    private val CRC_TABLE = IntArray(256) { n -> var c = n; repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }; c }

    // ------------------------------------------------------------------ tokenizer

    internal sealed class Tok {
        object Open : Tok()
        object Close : Tok()
        class Word(val name: String, val param: Int?) : Tok()
        class Sym(val ch: Char) : Tok()
        class Hex(val b: Int) : Tok()
        class Text(val b: Int) : Tok()
        class Bin(val data: ByteArray) : Tok()
    }

    internal class Lexer(private val s: ByteArray) {
        var pos = 0
        fun next(): Tok? {
            while (pos < s.size) {
                val c = s[pos].toInt() and 0xff
                when (c) {
                    '{'.code -> { pos++; return Tok.Open }
                    '}'.code -> { pos++; return Tok.Close }
                    '\r'.code, '\n'.code -> { pos++; continue }
                    '\\'.code -> {
                        pos++
                        if (pos >= s.size) return null
                        val d = s[pos].toInt() and 0xff
                        if (d.toChar().isLetter() && d < 128) {
                            val start = pos
                            while (pos < s.size && (s[pos].toInt() and 0xff).let { it < 128 && it.toChar().isLetter() }) pos++
                            val name = String(s, start, pos - start, Charsets.ISO_8859_1)
                            var param: Int? = null
                            val ps = pos
                            if (pos < s.size && s[pos] == '-'.code.toByte()) pos++
                            while (pos < s.size && s[pos] >= '0'.code.toByte() && s[pos] <= '9'.code.toByte()) pos++
                            if (pos > ps && !(pos == ps + 1 && s[ps] == '-'.code.toByte())) param = String(s, ps, pos - ps, Charsets.ISO_8859_1).toLongOrNull()?.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())?.toInt()
                            else pos = ps
                            if (pos < s.size && s[pos] == ' '.code.toByte()) pos++
                            if (name == "bin" && param != null && param > 0) {
                                val n = minOf(param, s.size - pos)
                                val data = s.copyOfRange(pos, pos + n); pos += n
                                return Tok.Bin(data)
                            }
                            return Tok.Word(name, param)
                        }
                        if (d == '\''.code) {
                            pos++
                            val h = if (pos + 2 <= s.size) String(s, pos, 2, Charsets.ISO_8859_1).toIntOrNull(16) else null
                            if (h != null) { pos += 2; return Tok.Hex(h) }
                            continue
                        }
                        pos++
                        if (d == '\r'.code || d == '\n'.code) return Tok.Word("par", null)
                        return Tok.Sym(d.toChar())
                    }
                    else -> { pos++; return Tok.Text(c) }
                }
            }
            return null
        }
    }

    /** Collects bytes in a code page and characters, decoding multi-byte runs together. */
    internal class Sink {
        val sb = StringBuilder()
        private val pend = java.io.ByteArrayOutputStream()
        private var pendCs: Charset? = null
        fun byte(b: Int, cs: Charset) {
            if (pendCs != null && pendCs != cs) flush()
            pendCs = cs; pend.write(b)
        }
        fun char(c: CharSequence) { flush(); sb.append(c) }
        fun flush() { val cs = pendCs; if (cs != null) { sb.append(String(pend.toByteArray(), cs)); pend.reset(); pendCs = null } }
        fun take(): String { flush(); val s = sb.toString(); sb.setLength(0); return s }
        fun isEmpty(): Boolean = sb.isEmpty() && pend.size() == 0
    }

    private val SKIP_DESTINATIONS = setOf(
        "fonttbl", "colortbl", "stylesheet", "info", "listtable", "listoverridetable", "rsidtbl", "generator", "mmathPr", "themedata",
        "colorschememapping", "datastore", "latentstyles", "pgdsctbl", "revtbl", "filetbl", "protusertbl", "userprops", "docvar",
        "ftnsep", "ftnsepc", "aftnsep", "aftnsepc", "header", "headerl", "headerr", "headerf", "footer", "footerl", "footerr", "footerf",
        "footnote", "annotation", "atnid", "atnauthor", "atndate", "atnref", "bkmkstart", "bkmkend", "nonshppict", "objdata", "objclass",
        "objname", "pict", "xmlnstbl", "wgrffmtfilter", "pnseclvl", "pntxta", "pntxtb", "template", "comment", "title", "subject",
        "author", "operator", "keywords", "doccomm", "company", "falt", "panose", "fname", "fontemb", "fontfile", "listname",
        "ud", "upr", "sp", "sn", "sv", "shpinst", "passwordhash", "mhtmltag", "htmlbase", "background", "formfield", "datafield",
        "pgptbl", "ownerpassword", "password", "picprop", "blipuid", "bliptag", "defchp", "defpap", "fldtype"
    )

    private val SYMBOLS = mapOf(
        "emdash" to "—", "endash" to "–", "bullet" to "•", "lquote" to "‘", "rquote" to "’",
        "ldblquote" to "“", "rdblquote" to "”", "emspace" to " ", "enspace" to " ", "qmspace" to " ",
        "zwj" to "‍", "zwnj" to "‌", "ltrmark" to "‎", "rtlmark" to "‏"
    )

    private fun ansiCodepage(rtf: ByteArray): Int =
        Regex("\\\\ansicpg(\\d+)").find(String(rtf, 0, minOf(rtf.size, 4096), Charsets.ISO_8859_1))?.groupValues?.get(1)?.toIntOrNull()
            ?: if (String(rtf, 0, minOf(rtf.size, 200), Charsets.ISO_8859_1).contains("\\mac")) 10000 else 1252

    fun isRtf(b: ByteArray): Boolean = b.size >= 5 && String(b, 0, 5, Charsets.ISO_8859_1) == "{\\rtf"

    fun isEncapsulatedHtml(rtf: ByteArray): Boolean = String(rtf, 0, minOf(rtf.size, 2048), Charsets.ISO_8859_1).contains("\\fromhtml")
    fun isEncapsulatedText(rtf: ByteArray): Boolean = String(rtf, 0, minOf(rtf.size, 2048), Charsets.ISO_8859_1).contains("\\fromtext")

    // ------------------------------------------------------------------ MS-OXRTFEX

    /** The HTML encapsulated in an RTF body (\fromhtml1), or null when the RTF is not encapsulated HTML. */
    fun deEncapsulateHtml(rtf: ByteArray): String? {
        if (!isEncapsulatedHtml(rtf)) return null
        return deEncapsulate(rtf, html = true)
    }

    /** The plain text encapsulated in an RTF body (\fromtext). */
    fun deEncapsulateText(rtf: ByteArray): String? = if (!isEncapsulatedText(rtf)) null else deEncapsulate(rtf, html = false)

    private class EState(var skip: Boolean = false, var htmlrtf: Boolean = false, var inTag: Boolean = false, var uc: Int = 1, var font: Int = -1, var ignorable: Boolean = false) {
        fun copy() = EState(skip, htmlrtf, inTag, uc, font, false)
    }

    private fun deEncapsulate(rtf: ByteArray, html: Boolean): String {
        val lx = Lexer(rtf)
        val defaultCs = TextCodec.codepage(ansiCodepage(rtf))
        val fontCs = parseFontCharsets(rtf)
        val sink = Sink()
        val stack = ArrayList<EState>()
        var st = EState()
        var skipChars = 0
        var depth = 0
        fun cs(): Charset = fontCs[st.font] ?: defaultCs
        fun emit(): Boolean = !st.skip && (st.inTag || !st.htmlrtf)
        while (true) {
            val t = lx.next() ?: break
            if (skipChars > 0 && t !is Tok.Open && t !is Tok.Close) { skipChars--; continue }
            when (t) {
                Tok.Open -> { stack += st; st = st.copy(); depth++ }
                Tok.Close -> { st = if (stack.isNotEmpty()) stack.removeAt(stack.size - 1) else EState(); depth--; skipChars = 0 }
                is Tok.Word -> {
                    val w = t.name
                    when {
                        w == "htmltag" -> { st.inTag = true; st.skip = false; st.ignorable = false }
                        w == "mhtmltag" -> st.skip = true
                        w == "htmlrtf" -> st.htmlrtf = t.param != 0
                        w == "uc" -> st.uc = t.param ?: 1
                        w == "f" -> st.font = t.param ?: -1
                        w == "u" -> { if (emit()) sink.char(String(Character.toChars(((t.param ?: 0) + 65536) % 65536))); skipChars = st.uc }
                        w == "par" || w == "line" -> if (emit()) sink.char("\r\n")
                        w == "tab" -> if (emit()) sink.char("\t")
                        SYMBOLS.containsKey(w) -> if (emit()) sink.char(SYMBOLS.getValue(w))
                        w in SKIP_DESTINATIONS || st.ignorable -> st.skip = true
                        else -> {}
                    }
                    st.ignorable = false
                }
                is Tok.Sym -> when (t.ch) {
                    '*' -> st.ignorable = true
                    '\\', '{', '}' -> if (emit()) sink.char(t.ch.toString())
                    '~' -> if (emit()) sink.char(" ")
                    '_' -> if (emit()) sink.char("-")
                    else -> {}
                }
                is Tok.Hex -> if (emit()) sink.byte(t.b, cs())
                is Tok.Text -> if (emit()) sink.byte(t.b, cs())
                is Tok.Bin -> {}
            }
        }
        val s = sink.take()
        return if (html) s else s.replace("\r\n", "\n")
    }

    /** \fN → charset from the font table's \fcharset (or \cpg). */
    private fun parseFontCharsets(rtf: ByteArray): Map<Int, Charset> {
        val head = String(rtf, 0, minOf(rtf.size, 65536), Charsets.ISO_8859_1)
        val i = head.indexOf("\\fonttbl")
        if (i < 0) return emptyMap()
        val out = HashMap<Int, Charset>()
        // The font table ends where its group closes.
        val start = head.lastIndexOf('{', i).coerceAtLeast(0)
        var depth = 0
        var j = start
        while (j < head.length) { val c = head[j]; if (c == '\\') { j += 2; continue }; if (c == '{') depth++ else if (c == '}') { depth--; if (depth == 0) break }; j++ }
        val tbl = head.substring(start, j.coerceAtMost(head.length))
        for (m in Regex("\\\\f(\\d+)[^;]*?\\\\(?:fcharset(\\d+)|cpg(\\d+))").findAll(tbl)) {
            val f = m.groupValues[1].toInt()
            val cp = m.groupValues[3].toIntOrNull() ?: TextCodec.FCHARSET_CODEPAGE[m.groupValues[2].toIntOrNull() ?: 0] ?: continue
            if (m.groupValues[2] == "2") continue // symbol fonts keep the document code page
            out[f] = TextCodec.codepage(cp)
        }
        return out
    }

    // ------------------------------------------------------------------ RTF → HTML

    private class Font(var name: String = "", var charset: Int = 0, var family: String = "")
    private class Pict(var type: String = "", var w: Int = 0, var h: Int = 0, var goalW: Int = 0, var goalH: Int = 0, var scaleX: Int = 100, var scaleY: Int = 100, val hex: StringBuilder = StringBuilder(), var bin: ByteArray? = null)

    private enum class Dest { NORMAL, SKIP, FONTTBL, COLORTBL, PICT, FLDINST }

    private data class Chr(
        var b: Boolean = false, var i: Boolean = false, var ul: Boolean = false, var strike: Boolean = false,
        var sup: Boolean = false, var sub: Boolean = false, var fs: Int = 24, var f: Int = -1, var cf: Int = 0, var bg: Int = 0,
        var hidden: Boolean = false, var caps: Boolean = false
    )

    private data class Par(var align: String = "", var li: Int = 0, var ri: Int = 0, var fi: Int = 0, var sb: Int = 0, var sa: Int = 0, var intbl: Boolean = false)

    private class RState(var dest: Dest = Dest.NORMAL, var chr: Chr = Chr(), var par: Par = Par(), var uc: Int = 1, var ignorable: Boolean = false, var link: String? = null, var field: Field? = null) {
        fun copy() = RState(dest, chr.copy(), par.copy(), uc, false, link, field)
    }

    private class Field { val inst = StringBuilder(); var link: String? = null }

    /** Result of rendering: HTML body fragment and the same text without formatting. */
    data class Rendered(val html: String, val text: String)

    /** Renders [rtf] as a standalone HTML document (encapsulated HTML is returned as is). */
    fun toHtml(rtf: ByteArray): String = deEncapsulateHtml(rtf) ?: render(rtf).let { "<!DOCTYPE html><html><head><meta charset=\"utf-8\"></head><body>${it.html}</body></html>" }

    /** Plain text of [rtf]. */
    fun toText(rtf: ByteArray): String = deEncapsulateText(rtf) ?: deEncapsulateHtml(rtf)?.let { htmlToText(it) } ?: render(rtf).text

    fun render(rtf: ByteArray): Rendered {
        val lx = Lexer(rtf)
        val docCp = ansiCodepage(rtf)
        val fonts = HashMap<Int, Font>()
        var curFont: Font? = null
        var curFontId = -1
        val colors = ArrayList<String?>()
        var cr = -1; var cg = -1; var cbl = -1
        var deff = -1
        val html = StringBuilder()
        val text = StringBuilder()
        val stack = ArrayList<RState>()
        var st = RState()
        var skipChars = 0
        // paragraph content
        val para = StringBuilder()
        val paraText = StringBuilder()
        val runText = Sink()
        var runChr: Chr? = null
        var runLink: String? = null
        // tables
        val tableRows = StringBuilder()
        var inTable = false
        val cells = ArrayList<String>()
        val cellBuf = StringBuilder()
        val cellX = ArrayList<Int>()
        var rowBorders = false
        var pict: Pict? = null
        var parEmpty = true

        fun cpFor(f: Int): Charset {
            val cs = fonts[if (f >= 0) f else deff]?.charset
            return TextCodec.codepage(if (cs == null || cs == 0 || cs == 1 || cs == 2) docCp else TextCodec.FCHARSET_CODEPAGE[cs] ?: docCp)
        }
        fun isSymbolFont(f: Int): Boolean = fonts[if (f >= 0) f else deff]?.let { it.charset == 2 || it.name.equals("Symbol", true) || it.name.startsWith("Wingdings", true) } == true
        fun color(idx: Int): String? = colors.getOrNull(idx)
        fun styleOf(c: Chr): String {
            val s = StringBuilder()
            if (c.b) s.append("font-weight:bold;")
            if (c.i) s.append("font-style:italic;")
            if (c.ul || c.strike) s.append("text-decoration:${listOfNotNull(if (c.ul) "underline" else null, if (c.strike) "line-through" else null).joinToString(" ")};")
            if (c.fs != 24 && c.fs > 0) s.append("font-size:${c.fs / 2.0}pt;".replace(".0pt", "pt"))
            val font = if (c.f >= 0 && c.f != deff) fonts[c.f] else null
            if (font != null && font.name.isNotBlank() && !isSymbolFont(c.f)) s.append("font-family:'${font.name.replace("'", "")}'${familyFallback(font.family)};")
            color(c.cf)?.let { s.append("color:$it;") }
            color(c.bg)?.let { s.append("background-color:$it;") }
            if (c.sup) s.append("vertical-align:super;font-size:smaller;")
            if (c.sub) s.append("vertical-align:sub;font-size:smaller;")
            if (c.caps) s.append("text-transform:uppercase;")
            return s.toString()
        }
        fun flushRun() {
            val t = runText.take()
            val c = runChr
            if (t.isEmpty() || c == null) return
            val escaped = escapeHtml(t)
            val style = styleOf(c)
            var piece = if (style.isEmpty()) escaped else "<span style=\"$style\">$escaped</span>"
            runLink?.let { piece = "<a href=\"${escapeHtml(it)}\">$piece</a>" }
            para.append(piece); paraText.append(t); parEmpty = false
        }
        fun addText(s: String) {
            if (st.chr.hidden) return
            if (runChr != st.chr || runLink != st.link) { flushRun(); runChr = st.chr.copy(); runLink = st.link }
            runText.char(s)
        }
        fun addByte(b: Int) {
            if (st.chr.hidden) return
            if (isSymbolFont(st.chr.f)) { addText(symbolChar(b)); return }
            if (runChr != st.chr || runLink != st.link) { flushRun(); runChr = st.chr.copy(); runLink = st.link }
            runText.byte(b, cpFor(st.chr.f))
        }
        fun addHtml(h: String) { flushRun(); para.append(h); parEmpty = false }
        fun paraStyle(p: Par): String {
            val s = StringBuilder("margin:0;")
            if (p.align.isNotEmpty()) s.append("text-align:${p.align};")
            if (p.li != 0) s.append("margin-left:${p.li / 20}pt;")
            if (p.ri != 0) s.append("margin-right:${p.ri / 20}pt;")
            if (p.fi != 0) s.append("text-indent:${p.fi / 20}pt;")
            if (p.sb != 0) s.append("margin-top:${p.sb / 20}pt;")
            if (p.sa != 0) s.append("margin-bottom:${p.sa / 20}pt;")
            return s.toString()
        }
        fun closeTable() {
            if (!inTable) return
            html.append("<table style=\"border-collapse:collapse;margin:4px 0\">").append(tableRows).append("</table>")
            text.append('\n')
            tableRows.setLength(0); inTable = false
        }
        fun endParagraph(kind: String) { // "par", "cell"
            flushRun()
            val body = if (parEmpty && para.isEmpty()) "<br>" else para.toString()
            val p = "<p style=\"${paraStyle(st.par)}white-space:pre-wrap\">$body</p>"
            if (kind == "cell" || st.par.intbl) {
                cellBuf.append(p)
                text.append(paraText).append(if (kind == "cell") "\t" else " ")
                if (kind == "cell") { cells += cellBuf.toString(); cellBuf.setLength(0) }
            } else {
                closeTable()
                html.append(p); text.append(paraText).append('\n')
            }
            para.setLength(0); paraText.setLength(0); parEmpty = true
        }
        fun endRow() {
            if (para.isNotEmpty() || !runText.isEmpty()) endParagraph("cell")
            val tr = StringBuilder("<tr>")
            cells.forEachIndexed { k, c ->
                val w = if (k < cellX.size) (cellX[k] - (if (k > 0) cellX[k - 1] else 0)) / 15 else 0
                tr.append("<td style=\"vertical-align:top;padding:2px 6px;${if (rowBorders) "border:1px solid #999;" else ""}${if (w > 0) "width:${w}px;" else ""}\">").append(c).append("</td>")
            }
            tr.append("</tr>")
            tableRows.append(tr); inTable = true
            text.append('\n')
            cells.clear(); cellBuf.setLength(0)
        }
        fun finishPict(p: Pict) {
            val bytes = p.bin ?: hexToBytes(p.hex)
            val mime = when (p.type) { "pngblip" -> "image/png"; "jpegblip" -> "image/jpeg"; else -> null }
            val wPx = ((if (p.goalW > 0) p.goalW else 0) * p.scaleX / 100) / 15
            val hPx = ((if (p.goalH > 0) p.goalH else 0) * p.scaleY / 100) / 15
            if (mime != null && bytes.isNotEmpty()) addHtml("<img src=\"data:$mime;base64,${Base64.getEncoder().encodeToString(bytes)}\"${if (wPx > 0) " width=\"$wPx\"" else ""}${if (hPx > 0) " height=\"$hPx\"" else ""} alt=\"\">")
            else if (bytes.isNotEmpty()) addHtml("<span style=\"display:inline-block;border:1px dashed #999;padding:2px 6px;color:#666\">[picture]</span>")
        }

        while (true) {
            val t = lx.next() ?: break
            if (skipChars > 0 && t !is Tok.Open && t !is Tok.Close) { skipChars--; continue }
            when (t) {
                Tok.Open -> { stack += st; st = st.copy() }
                Tok.Close -> {
                    val prev = st
                    st = if (stack.isNotEmpty()) stack.removeAt(stack.size - 1) else RState()
                    if (prev.dest == Dest.PICT && st.dest != Dest.PICT) { pict?.let { finishPict(it) }; pict = null }
                    if (prev.dest == Dest.FONTTBL && st.dest != Dest.FONTTBL) curFont = null
                    if (prev.dest == Dest.FLDINST && st.dest != Dest.FLDINST) prev.field?.let { f -> f.link = hyperlinkOf(f.inst.toString()) }
                    skipChars = 0
                }
                is Tok.Word -> {
                    val w = t.name; val p = t.param
                    val ign = st.ignorable
                    st.ignorable = false
                    if (st.dest == Dest.SKIP) continue
                    if (st.dest == Dest.FONTTBL) {
                        when (w) {
                            "f" -> { curFontId = p ?: 0; curFont = fonts.getOrPut(curFontId) { Font() } }
                            "fcharset" -> curFont?.charset = p ?: 0
                            "froman", "fswiss", "fmodern", "fscript", "fdecor", "fnil", "ftech", "fbidi" -> curFont?.family = w
                            "panose", "falt", "fname" -> st.dest = Dest.SKIP
                            else -> if (ign) st.dest = Dest.SKIP
                        }
                        continue
                    }
                    if (st.dest == Dest.COLORTBL) {
                        when (w) { "red" -> cr = p ?: 0; "green" -> cg = p ?: 0; "blue" -> cbl = p ?: 0 }
                        continue
                    }
                    if (st.dest == Dest.PICT) {
                        val pc = pict ?: continue
                        when (w) {
                            "pngblip", "jpegblip", "emfblip", "wmetafile", "dibitmap", "wbitmap", "macpict", "pmmetafile" -> pc.type = w
                            "picw" -> pc.w = p ?: 0; "pich" -> pc.h = p ?: 0
                            "picwgoal" -> pc.goalW = p ?: 0; "pichgoal" -> pc.goalH = p ?: 0
                            "picscalex" -> pc.scaleX = p ?: 100; "picscaley" -> pc.scaleY = p ?: 100
                            else -> if (ign) st.dest = Dest.SKIP
                        }
                        continue
                    }
                    if (st.dest == Dest.FLDINST) { if (ign) st.dest = Dest.SKIP; continue }
                    when (w) {
                        "fonttbl" -> st.dest = Dest.FONTTBL
                        "colortbl" -> st.dest = Dest.COLORTBL
                        "pict" -> { st.dest = Dest.PICT; pict = Pict() }
                        "shppict" -> {}
                        "field" -> st.field = Field()
                        "fldinst" -> st.dest = Dest.FLDINST
                        "fldrslt" -> st.link = st.field?.link
                        "deff" -> deff = p ?: 0
                        "par" -> endParagraph("par")
                        "line" -> addHtml("<br>").also { paraText.append('\n') }
                        "page" -> { endParagraph("par"); html.append("<hr style=\"border:0;border-top:1px dashed #bbb\">") }
                        "sect" -> endParagraph("par")
                        "tab" -> addText("\t")
                        "cell", "nestcell" -> endParagraph("cell")
                        "row", "nestrow" -> endRow()
                        "trowd" -> { cellX.clear(); rowBorders = false }
                        "cellx" -> cellX += p ?: 0
                        "clbrdrt", "clbrdrb", "clbrdrl", "clbrdrr", "trbrdrt", "trbrdrl", "trbrdrb", "trbrdrr" -> rowBorders = true
                        "intbl" -> st.par.intbl = true
                        "pard" -> st.par = Par()
                        "plain" -> st.chr = Chr(f = -1)
                        "b" -> st.chr.b = p != 0
                        "i" -> st.chr.i = p != 0
                        "ul", "uld", "uldash", "uldb", "ulth", "ulw", "ulwave" -> st.chr.ul = p != 0
                        "ulnone" -> st.chr.ul = false
                        "strike", "striked" -> st.chr.strike = p != 0
                        "super" -> { st.chr.sup = true; st.chr.sub = false }
                        "sub" -> { st.chr.sub = true; st.chr.sup = false }
                        "nosupersub" -> { st.chr.sup = false; st.chr.sub = false }
                        "up" -> st.chr.sup = (p ?: 6) > 0
                        "dn" -> st.chr.sub = (p ?: 6) > 0
                        "caps", "scaps" -> st.chr.caps = p != 0
                        "v" -> st.chr.hidden = p != 0
                        "fs" -> st.chr.fs = p ?: 24
                        "f" -> st.chr.f = p ?: -1
                        "cf" -> st.chr.cf = p ?: 0
                        "highlight", "cb", "chcbpat" -> st.chr.bg = p ?: 0
                        "ql" -> st.par.align = ""
                        "qc" -> st.par.align = "center"
                        "qr" -> st.par.align = "right"
                        "qj" -> st.par.align = "justify"
                        "li" -> st.par.li = p ?: 0
                        "ri" -> st.par.ri = p ?: 0
                        "fi" -> st.par.fi = p ?: 0
                        "sb" -> st.par.sb = p ?: 0
                        "sa" -> st.par.sa = p ?: 0
                        "uc" -> st.uc = p ?: 1
                        "u" -> { addText(String(Character.toChars(((p ?: 0) + 65536) % 65536))); skipChars = st.uc }
                        else -> {
                            val sym = SYMBOLS[w]
                            when {
                                sym != null -> addText(sym)
                                w in SKIP_DESTINATIONS || ign -> st.dest = Dest.SKIP
                            }
                        }
                    }
                }
                is Tok.Sym -> {
                    if (t.ch == '*') { st.ignorable = true; continue }
                    when (st.dest) {
                        Dest.NORMAL -> when (t.ch) {
                            '\\', '{', '}' -> addText(t.ch.toString())
                            '~' -> addText(" ")
                            '_' -> addText("‑")
                            '-' -> {}
                            else -> {}
                        }
                        Dest.FLDINST -> if (t.ch == '\\' || t.ch == '{' || t.ch == '}') st.field?.inst?.append(t.ch)
                        else -> {}
                    }
                }
                is Tok.Hex -> when (st.dest) {
                    Dest.NORMAL -> addByte(t.b)
                    Dest.FONTTBL -> curFont?.let { it.name += t.b.toChar() }
                    Dest.FLDINST -> st.field?.inst?.append(t.b.toChar())
                    else -> {}
                }
                is Tok.Text -> when (st.dest) {
                    Dest.NORMAL -> addByte(t.b)
                    Dest.FONTTBL -> { val c = t.b.toChar(); if (c == ';') { curFont?.name = curFont?.name?.trim().orEmpty() } else curFont?.let { if (!it.name.endsWith(";")) it.name += c } }
                    Dest.COLORTBL -> if (t.b == ';'.code) { colors += if (cr < 0 && cg < 0 && cbl < 0) null else "#%02x%02x%02x".format(cr.coerceIn(0, 255), cg.coerceIn(0, 255), cbl.coerceIn(0, 255)); cr = -1; cg = -1; cbl = -1 }
                    Dest.PICT -> pict?.hex?.append(t.b.toChar())
                    Dest.FLDINST -> st.field?.inst?.append(t.b.toChar())
                    Dest.SKIP -> {}
                }
                is Tok.Bin -> if (st.dest == Dest.PICT) pict?.bin = t.data
            }
        }
        if (para.isNotEmpty() || !runText.isEmpty()) endParagraph("par")
        if (cells.isNotEmpty()) endRow()
        closeTable()
        val base = fonts[deff]?.takeIf { it.name.isNotBlank() && it.charset != 2 }
        val wrapped = if (base == null) html.toString() else "<div style=\"font-family:'${base.name.replace("'", "")}'${familyFallback(base.family)}\">$html</div>"
        return Rendered(wrapped, text.toString().trimEnd())
    }

    private fun familyFallback(f: String) = when (f) { "froman" -> ",serif"; "fswiss" -> ",sans-serif"; "fmodern" -> ",monospace"; "fscript" -> ",cursive"; else -> "" }

    private fun hyperlinkOf(inst: String): String? {
        val m = Regex("HYPERLINK\\s+(?:\\\\l\\s+)?\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(inst) ?: Regex("HYPERLINK\\s+(\\S+)", RegexOption.IGNORE_CASE).find(inst) ?: return null
        val url = m.groupValues[1].trim()
        return if (Regex("^(https?|mailto|ftp):", RegexOption.IGNORE_CASE).containsMatchIn(url)) url else null
    }

    private fun symbolChar(b: Int): String = when (b) {
        0xB7 -> "•"; 0xA7 -> "▪"; 0xD8 -> "➢"; 0xFC -> "✓"; 0x6F -> "◦"; 0x71 -> "▪"; 0x76 -> "❖"
        0xE0 -> "→"; 0xAE -> "→"; 0xA8 -> "○"
        else -> if (b in 0x41..0x5A || b in 0x61..0x7A) GREEK.getOrNull(if (b >= 0x61) b - 0x61 + 26 else b - 0x41)?.toString() ?: b.toChar().toString() else b.toChar().toString()
    }

    private const val GREEK = "ΑΒΧΔΕΦΓΗΙϑΚΛΜΝΟΠΘΡΣΤΥςΩΞΨΖαβχδεφγηιϕκλμνοπθρστυϖωξψζ"

    private fun hexToBytes(s: CharSequence): ByteArray {
        val out = java.io.ByteArrayOutputStream(s.length / 2)
        var hi = -1
        for (c in s) {
            val v = Character.digit(c, 16)
            if (v < 0) continue
            if (hi < 0) hi = v else { out.write((hi shl 4) or v); hi = -1 }
        }
        return out.toByteArray()
    }

    fun escapeHtml(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (c in s) when (c) { '<' -> sb.append("&lt;"); '>' -> sb.append("&gt;"); '&' -> sb.append("&amp;"); '"' -> sb.append("&quot;"); else -> sb.append(c) }
        return sb.toString()
    }

    /** Very small HTML-to-text conversion (block elements become line breaks, entities decoded). */
    fun htmlToText(html: String): String {
        var s = html.replace(Regex("(?is)<(script|style|head)[^>]*>.*?</\\1>"), "")
        s = s.replace(Regex("(?i)<br\\s*/?>"), "\n").replace(Regex("(?i)</(p|div|tr|li|h[1-6]|table|blockquote)>"), "\n").replace(Regex("(?i)</t[dh]>"), "\t")
        s = s.replace(Regex("<[^>]+>"), "")
        s = s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        s = Regex("&#(x?)([0-9a-fA-F]+);").replace(s) { m -> runCatching { String(Character.toChars(m.groupValues[2].toInt(if (m.groupValues[1].isNotEmpty()) 16 else 10))) }.getOrDefault("") }
        s = s.replace("&amp;", "&")
        return s.replace(Regex("[ \\t]+\\n"), "\n").replace(Regex("\\n{3,}"), "\n\n").trim()
    }
}
