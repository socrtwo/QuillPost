package info.socrtwo.quillpost.web.mailstore

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.Base64

/**
 * Rich Text Format as Outlook stores message bodies:
 *
 *  - [decompress]: compressed RTF (MS-OXRTFCP) — the LZ77 variant "LZFu" with a 4 KB dictionary
 *    pre-loaded with a fixed RTF prologue, or the uncompressed "MELA" form.
 *  - [deencapsulate]: HTML or plain text wrapped in RTF (MS-OXRTFEX, `\fromhtml1` / `\fromtext`) —
 *    the original HTML is rebuilt from the `{\*\htmltag…}` groups and the text outside `\htmlrtf`.
 *  - [toHtml] / [toText]: genuine RTF bodies converted to HTML with their character and paragraph
 *    formatting (bold, italic, underline, strike-through, size, colour, font, alignment, indents),
 *    tables, hyperlinks, bullets and PNG/JPEG pictures, or to plain text.
 */
object Rtf {
    private const val PREBUF = "{\\rtf1\\ansi\\mac\\deff0\\deftab720{\\fonttbl;}{\\f0\\fnil \\froman \\fswiss \\fmodern \\fscript \\fdecor MS Sans SerifSymbolArialTimes New RomanCourier{\\colortbl\\red0\\green0\\blue0\r\n\\par \\pard\\plain\\f0\\fs20\\b\\i\\u\\tab\\tx"

    /** Decompresses PidTagRtfCompressed. Returns the RTF bytes. */
    fun decompress(data: ByteArray): ByteArray {
        if (data.size < 16) throw MailStoreException("Compressed RTF is too short")
        val compSize = data.u32(0)
        val rawSize = data.u32(4).toInt()
        when (data.i32(8)) {
            0x414C454D -> return data.copyOfRange(16, minOf(data.size, 16 + rawSize)) // "MELA": stored uncompressed
            0x75465A4C -> {} // "LZFu"
            else -> throw MailStoreException("Unknown compressed RTF type")
        }
        val dict = ByteArray(4096)
        val pre = PREBUF.toByteArray(Charsets.ISO_8859_1)
        System.arraycopy(pre, 0, dict, 0, pre.size)
        var wp = pre.size
        val out = ByteArrayOutputStream(maxOf(rawSize, 16))
        var i = 16
        val end = minOf(data.size.toLong(), compSize + 4).toInt()
        while (i < end) {
            val control = data.u8(i++)
            for (bit in 0 until 8) {
                if (i >= end) break
                if (control and (1 shl bit) == 0) {
                    val b = data[i++]
                    out.write(b.toInt()); dict[wp] = b; wp = (wp + 1) and 0xFFF
                } else {
                    if (i + 1 >= end) { i = end; break }
                    val word = (data.u8(i) shl 8) or data.u8(i + 1)
                    i += 2
                    val offset = word ushr 4
                    val len = (word and 0xF) + 2
                    if (offset == wp) return out.toByteArray().let { if (it.size > rawSize && rawSize > 0) it.copyOf(rawSize) else it }
                    for (k in 0 until len) {
                        val b = dict[(offset + k) and 0xFFF]
                        out.write(b.toInt()); dict[wp] = b; wp = (wp + 1) and 0xFFF
                    }
                }
            }
        }
        val r = out.toByteArray()
        return if (r.size > rawSize && rawSize > 0) r.copyOf(rawSize) else r
    }

    // ---------------------------------------------------------------------------------- tokens

    internal sealed class Tok {
        object Open : Tok()
        object Close : Tok()
        class Word(val word: String, val param: Int?) : Tok()
        class Symbol(val ch: Char) : Tok()
        class Hex(val byte: Int) : Tok()
        class Text(val text: String) : Tok()
        class Bin(val bytes: ByteArray) : Tok()
    }

    /** Splits RTF (read as ISO-8859-1 so every byte survives) into tokens. */
    internal fun tokens(rtf: String): Sequence<Tok> = sequence {
        var i = 0
        val n = rtf.length
        val text = StringBuilder()
        suspend fun SequenceScope<Tok>.flush() { if (text.isNotEmpty()) { yield(Tok.Text(text.toString())); text.setLength(0) } }
        while (i < n) {
            val c = rtf[i]
            when (c) {
                '{' -> { flush(); yield(Tok.Open); i++ }
                '}' -> { flush(); yield(Tok.Close); i++ }
                '\\' -> {
                    flush()
                    if (i + 1 >= n) { i++; continue }
                    val d = rtf[i + 1]
                    if (d.isLetter()) {
                        var j = i + 1
                        while (j < n && rtf[j].isLetter() && j - i < 34) j++
                        val word = rtf.substring(i + 1, j)
                        var k = j
                        if (k < n && (rtf[k] == '-' || rtf[k].isDigit())) {
                            k++
                            while (k < n && rtf[k].isDigit() && k - j < 11) k++
                        }
                        val param = if (k > j) rtf.substring(j, k).toLongOrNull()?.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())?.toInt() else null
                        if (k < n && rtf[k] == ' ') k++
                        if (word == "bin" && param != null && param > 0) {
                            val e = minOf(n, k + param)
                            yield(Tok.Bin(rtf.substring(k, e).toByteArray(Charsets.ISO_8859_1)))
                            i = e
                        } else {
                            yield(Tok.Word(word, param)); i = k
                        }
                    } else if (d == '\'') {
                        val h = if (i + 4 <= n) rtf.substring(i + 2, i + 4).toIntOrNull(16) else null
                        if (h != null) yield(Tok.Hex(h))
                        i += 4
                    } else if (d == '\r' || d == '\n') {
                        yield(Tok.Word("par", null)); i += 2
                    } else {
                        yield(Tok.Symbol(d)); i += 2
                    }
                }
                '\r', '\n' -> i++
                else -> { text.append(c); i++ }
            }
        }
        flush()
    }

    /** Collects \'hh bytes and decodes them together, so double-byte code pages work. */
    private class ByteRun(var charset: Charset) {
        val bytes = ByteArrayOutputStream()
        fun add(b: Int) = bytes.write(b)
        fun drain(): String { if (bytes.size() == 0) return ""; val s = String(bytes.toByteArray(), charset); bytes.reset(); return s }
    }

    fun charsetForCodepage(cp: Int?): Charset {
        val name = when (cp) {
            null, 0 -> "windows-1252"
            65001 -> "UTF-8"
            932 -> "windows-31j"
            936 -> "GBK"
            949 -> "x-windows-949"
            950 -> "Big5"
            10000 -> "x-MacRoman"
            20127 -> "US-ASCII"
            28591 -> "ISO-8859-1"
            1200 -> "UTF-16LE"
            else -> "windows-$cp"
        }
        return runCatching { Charset.forName(name) }.recoverCatching { Charset.forName("Cp$cp") }.getOrDefault(Charsets.ISO_8859_1)
    }

    private fun codepageForFontCharset(cs: Int): Int? = when (cs) {
        0 -> 1252; 77 -> 10000; 128 -> 932; 129 -> 949; 130 -> 1361; 134 -> 936; 136 -> 950; 161 -> 1253; 162 -> 1254
        163 -> 1258; 177 -> 1255; 178 -> 1256; 186 -> 1257; 204 -> 1251; 222 -> 874; 238 -> 1250; else -> null
    }

    private val SPECIALS = mapOf(
        "emdash" to "\u2014", "endash" to "\u2013", "lquote" to "\u2018", "rquote" to "\u2019", "ldblquote" to "\u201C",
        "rdblquote" to "\u201D", "bullet" to "\u2022", "emspace" to "\u2003", "enspace" to "\u2002", "qmspace" to "\u2005", "zwj" to "\u200D", "zwnj" to "\u200C"
    )

    /** Destinations whose text is never part of the body. */
    private val SKIP = setOf(
        "fonttbl", "colortbl", "stylesheet", "info", "listtable", "listoverridetable", "rsidtbl", "generator", "themedata",
        "colorschememapping", "latentstyles", "datastore", "xmlnstbl", "header", "footer", "headerl", "headerr", "headerf",
        "footerl", "footerr", "footerf", "object", "fldinst", "filetbl", "revtbl", "pgdsctbl", "mmathPr", "xe", "tc", "bkmkstart", "bkmkend", "nonshppict", "falt"
    )

    // ------------------------------------------------------------------------------ encapsulation

    data class Encapsulated(val html: Boolean, val content: String)

    /** True when the RTF wraps HTML (`\fromhtml1`) or plain text (`\fromtext`). */
    fun isEncapsulated(rtf: String): Boolean {
        val head = rtf.take(1024)
        return head.contains("\\fromhtml") || head.contains("\\fromtext")
    }

    /** Rebuilds the HTML (or text) an RTF body encapsulates, per MS-OXRTFEX; null when it is genuine RTF. */
    fun deencapsulate(rtf: String): Encapsulated? {
        if (!isEncapsulated(rtf)) return null
        val html = rtf.take(1024).contains("\\fromhtml")
        class St(var skip: Boolean = false, var tag: Boolean = false, var htmlrtf: Boolean = false, var uc: Int = 1)
        val stack = ArrayList<St>()
        var st = St()
        val out = StringBuilder()
        var charset = charsetForCodepage(1252)
        val run = ByteRun(charset)
        var pendingSkip = 0
        var groupStart = false
        var skipNextTag: Int? = null
        fun emitting() = !st.skip && (st.tag || !st.htmlrtf)
        fun put(s: String) { out.append(run.drain()); out.append(s) }
        for (t in tokens(rtf)) {
            if (t !is Tok.Hex) { val d = run.drain(); if (d.isNotEmpty() && emitting()) out.append(d) }
            when (t) {
                is Tok.Open -> { stack += st; st = St(st.skip, st.tag, st.htmlrtf, st.uc); groupStart = true; continue }
                is Tok.Close -> { st = stack.removeLastOrNull() ?: St(); groupStart = false; pendingSkip = 0; continue }
                is Tok.Symbol -> {
                    if (t.ch == '*' && groupStart) { st.skip = true; continue } // unknown \* destination unless htmltag follows
                    if (pendingSkip > 0) { pendingSkip--; continue }
                    if (emitting()) when (t.ch) { '{', '}', '\\' -> put(t.ch.toString()); '~' -> put("\u00A0"); '_' -> put("-"); else -> {} }
                }
                is Tok.Word -> {
                    val w = t.word
                    if (groupStart && st.skip && (w == "htmltag" || w == "mhtmltag")) {
                        // \*\htmltagN: the original HTML tag. \mhtmltagN carries the MIME (cid:) form of the same tag
                        // and is used instead of the \htmltag with the same number that follows it.
                        if (w == "htmltag" && skipNextTag != null && skipNextTag == t.param) { skipNextTag = null; groupStart = false; continue }
                        if (w == "mhtmltag") skipNextTag = t.param
                        st.skip = false; st.tag = true
                    } else if (groupStart && w in SKIP) st.skip = true
                    else when (w) {
                        "ansicpg" -> { charset = charsetForCodepage(t.param); run.charset = charset }
                        "htmlrtf" -> st.htmlrtf = t.param != 0
                        "uc" -> st.uc = t.param ?: 1
                        "u" -> if (emitting()) { val v = t.param ?: 0; put((if (v < 0) v + 65536 else v).toChar().toString()); pendingSkip = st.uc } else pendingSkip = st.uc
                        "par", "line" -> if (emitting()) put("\r\n")
                        "tab" -> if (emitting()) put("\t")
                        else -> SPECIALS[w]?.let { if (emitting() && !st.tag) put(it) }
                    }
                    groupStart = false
                    continue
                }
                is Tok.Hex -> {
                    if (pendingSkip > 0) { pendingSkip--; continue }
                    if (emitting()) run.add(t.byte)
                }
                is Tok.Text -> {
                    var s = t.text
                    if (pendingSkip > 0) { val k = minOf(pendingSkip, s.length); s = s.substring(k); pendingSkip -= k }
                    if (emitting() && s.isNotEmpty()) put(s)
                }
                is Tok.Bin -> {}
            }
            groupStart = false
        }
        out.append(run.drain())
        return Encapsulated(html, out.toString())
    }

    // --------------------------------------------------------------------------------- RTF → HTML

    private data class Chr(
        val b: Boolean = false, val i: Boolean = false, val u: Boolean = false, val s: Boolean = false,
        val sup: Boolean = false, val sub: Boolean = false, val fs: Int = 0, val cf: Int = 0, val hl: Int = 0, val f: Int = -1, val hidden: Boolean = false
    )

    private data class Para(val align: String = "", val li: Int = 0, val ri: Int = 0, val fi: Int = 0, val sb: Int = 0, val sa: Int = 0, val intbl: Boolean = false)

    private class Font(var name: String = "", var charset: Int = -1)

    /** Converts a genuine RTF body to an HTML fragment that keeps its formatting. */
    fun toHtml(rtf: String): String = Converter(rtf, html = true).run()

    /** Plain text of an RTF body (or of the HTML/text it encapsulates). */
    fun toText(rtf: String): String {
        deencapsulate(rtf)?.let { return if (it.html) htmlToText(it.content) else it.content }
        return Converter(rtf, html = false).run()
    }

    private class Converter(val rtf: String, val html: Boolean) {
        val out = StringBuilder()
        val fonts = HashMap<Int, Font>()
        val colors = ArrayList<String?>()
        var defaultCp = 1252
        var defaultFont = 0

        // paragraph being built
        val para = StringBuilder()
        var paraFmt = Para()
        // table being built
        var rowCells = ArrayList<String>()
        val rows = ArrayList<List<String>>()
        var cellBorders = false

        fun esc(s: String) = if (!html) s else s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

        fun span(text: String, c: Chr, link: String?): String {
            if (!html) return text
            if (c.hidden || text.isEmpty()) return ""
            var t = esc(text).replace("  ", " &nbsp;")
            val styles = ArrayList<String>()
            if (c.fs > 0 && c.fs != 24) styles += "font-size:${c.fs / 2.0}pt".replace(".0pt", "pt")
            colors.getOrNull(c.cf)?.takeIf { c.cf > 0 }?.let { styles += "color:$it" }
            colors.getOrNull(c.hl)?.takeIf { c.hl > 0 }?.let { styles += "background:$it" }
            fonts[c.f]?.name?.takeIf { it.isNotBlank() && c.f != defaultFont }?.let { styles += "font-family:'${it.replace("'", "")}'" }
            if (c.b) t = "<b>$t</b>"
            if (c.i) t = "<i>$t</i>"
            if (c.u) t = "<u>$t</u>"
            if (c.s) t = "<s>$t</s>"
            if (c.sup) t = "<sup>$t</sup>"
            if (c.sub) t = "<sub>$t</sub>"
            if (styles.isNotEmpty()) t = "<span style=\"${styles.joinToString(";")}\">$t</span>"
            if (link != null) t = "<a href=\"${esc(link)}\">$t</a>"
            return t
        }

        fun paraHtml(): String {
            val content = para.toString()
            para.setLength(0)
            if (!html) return content
            val st = ArrayList<String>()
            if (paraFmt.align.isNotEmpty()) st += "text-align:${paraFmt.align}"
            if (paraFmt.li != 0) st += "margin-left:${paraFmt.li / 20}pt"
            if (paraFmt.ri != 0) st += "margin-right:${paraFmt.ri / 20}pt"
            if (paraFmt.fi != 0) st += "text-indent:${paraFmt.fi / 20}pt"
            st += "margin-top:${paraFmt.sb / 20}pt"
            st += "margin-bottom:${paraFmt.sa / 20}pt"
            return "<p style=\"${st.joinToString(";")}\">${content.ifEmpty { "&nbsp;" }}</p>"
        }

        fun endParagraph() {
            if (paraFmt.intbl) { para.append(if (html) "<br>" else "\n"); return }
            flushTable()
            out.append(paraHtml())
            if (!html) out.append("\n")
        }

        fun endCell() {
            rowCells += para.toString(); para.setLength(0)
        }

        fun endRow() {
            if (para.isNotEmpty()) endCell()
            rows += rowCells; rowCells = ArrayList()
        }

        fun flushTable() {
            if (rows.isEmpty()) return
            if (html) {
                out.append("<table style=\"border-collapse:collapse\"${if (cellBorders) " border=\"1\"" else ""} cellpadding=\"4\">")
                for (r in rows) { out.append("<tr>"); for (c in r) out.append("<td valign=\"top\">").append(c).append("</td>"); out.append("</tr>") }
                out.append("</table>")
            } else for (r in rows) out.append(r.joinToString("\t")).append("\n")
            rows.clear()
        }

        fun run(): String {
            class G(var chr: Chr, var skip: Boolean, var uc: Int, var dest: String = "", var link: String? = null)
            val stack = ArrayList<G>()
            var g = G(Chr(), false, 1)
            var groupStart = false
            var pendingSkip = 0
            var cp = defaultCp
            val run = ByteRun(charsetForCodepage(cp))
            var fontCur: Font? = null
            var fontNum = -1
            var colorR = 0; var colorG = 0; var colorB = 0; var colorSet = false
            val pict = StringBuilder(); var pictType = ""; var pictW = 0; var pictH = 0
            val fldinst = StringBuilder()
            var pendingLink: String? = null
            fun text(s: String) {
                if (s.isEmpty()) return
                when (g.dest) {
                    "fonttbl" -> { fontCur?.let { it.name += s.trimEnd(';') } }
                    "colortbl" -> {}
                    "pict" -> pict.append(s.filter { it.isLetterOrDigit() })
                    "fldinst" -> fldinst.append(s)
                    else -> if (!g.skip) para.append(span(s, g.chr, g.link))
                }
            }
            fun drain() { val d = run.drain(); if (d.isNotEmpty()) text(d) }
            fun charsetFor(c: Chr) = fonts[c.f]?.charset?.let { codepageForFontCharset(it) } ?: cp
            for (t in tokens(rtf)) {
                if (t !is Tok.Hex) drain()
                when (t) {
                    is Tok.Open -> { stack += g; g = G(g.chr, g.skip, g.uc, if (g.dest == "fonttbl" || g.dest == "colortbl") g.dest else "", g.link); groupStart = true; continue }
                    is Tok.Close -> {
                        when (g.dest) {
                            "pict" -> if (html && pict.isNotEmpty() && (pictType == "png" || pictType == "jpeg")) {
                                val bytes = runCatching { pict.toString().chunked(2).map { it.toInt(16).toByte() }.toByteArray() }.getOrNull()
                                if (bytes != null) {
                                    val size = (if (pictW > 0) " width=\"${pictW / 15}\"" else "") + (if (pictH > 0) " height=\"${pictH / 15}\"" else "")
                                    para.append("<img src=\"data:image/$pictType;base64,${Base64.getEncoder().encodeToString(bytes)}\"$size>")
                                }
                            }
                            "fldinst" -> Regex("HYPERLINK\\s+\"([^\"]+)\"").find(fldinst)?.let { pendingLink = it.groupValues[1] }
                            "fontent" -> {}
                        }
                        if (g.dest == "pict") { pict.setLength(0); pictType = ""; pictW = 0; pictH = 0 }
                        if (g.dest == "fldinst") fldinst.setLength(0)
                        val inFont = g.dest == "fonttbl" && fontCur != null
                        if (inFont && stack.lastOrNull()?.dest == "fonttbl") { fontCur = null }
                        g = stack.removeLastOrNull() ?: G(Chr(), false, 1)
                        groupStart = false; pendingSkip = 0
                        continue
                    }
                    is Tok.Symbol -> {
                        if (t.ch == '*' && groupStart) { g.skip = true; g.dest = "*"; continue }
                        if (pendingSkip > 0) { pendingSkip--; continue }
                        when (t.ch) { '{', '}', '\\' -> text(t.ch.toString()); '~' -> text("\u00A0"); '_' -> text("\u2011"); else -> {} }
                    }
                    is Tok.Hex -> {
                        if (pendingSkip > 0) { pendingSkip--; continue }
                        val wanted = charsetForCodepage(charsetFor(g.chr))
                        if (wanted != run.charset) { drain(); run.charset = wanted }
                        run.add(t.byte)
                    }
                    is Tok.Text -> {
                        var s = t.text
                        if (pendingSkip > 0) { val k = minOf(pendingSkip, s.length); s = s.substring(k); pendingSkip -= k }
                        if (g.dest == "colortbl") {
                            if (s.contains(';')) { repeat(s.count { it == ';' }) { colors += if (colorSet) "#%02x%02x%02x".format(colorR, colorG, colorB) else null; colorSet = false; colorR = 0; colorG = 0; colorB = 0 } }
                        } else text(s)
                    }
                    is Tok.Bin -> if (g.dest == "pict") t.bytes.forEach { pict.append("%02x".format(it)) }
                    is Tok.Word -> {
                        val w = t.word; val p = t.param
                        if (groupStart) {
                            groupStart = false
                            when {
                                w == "fonttbl" -> { g.dest = "fonttbl"; continue }
                                w == "colortbl" -> { g.dest = "colortbl"; continue }
                                w == "pict" -> { g.dest = "pict"; g.skip = false; continue }
                                w == "fldinst" -> { g.dest = "fldinst"; continue }
                                w == "fldrslt" -> { g.link = pendingLink; pendingLink = null; g.skip = false; continue }
                                w in SKIP -> { g.skip = true; g.dest = w; continue }
                                g.dest == "*" && w != "pict" -> { g.skip = true; continue }
                            }
                        }
                        if (g.dest == "fonttbl") {
                            when (w) {
                                "f" -> { fontNum = p ?: 0; fontCur = Font().also { fonts[fontNum] = it } }
                                "fcharset" -> fontCur?.charset = p ?: -1
                            }
                            continue
                        }
                        if (g.dest == "colortbl") {
                            when (w) { "red" -> { colorR = p ?: 0; colorSet = true }; "green" -> { colorG = p ?: 0; colorSet = true }; "blue" -> { colorB = p ?: 0; colorSet = true } }
                            continue
                        }
                        if (g.dest == "pict") {
                            when (w) { "pngblip" -> pictType = "png"; "jpegblip" -> pictType = "jpeg"; "emfblip", "wmetafile", "macpict", "dibitmap", "wbitmap" -> pictType = w; "picwgoal" -> pictW = p ?: 0; "pichgoal" -> pictH = p ?: 0 }
                            continue
                        }
                        val on = p != 0
                        when (w) {
                            "ansicpg" -> { cp = p ?: 1252; run.charset = charsetForCodepage(cp) }
                            "deff" -> { defaultFont = p ?: 0 }
                            "uc" -> g.uc = p ?: 1
                            "u" -> { val v = p ?: 0; text((if (v < 0) v + 65536 else v).toChar().toString()); pendingSkip = g.uc }
                            "par", "sect" -> endParagraph()
                            "line" -> para.append(if (html) "<br>" else "\n")
                            "page" -> { endParagraph(); if (html) out.append("<hr>") }
                            "tab" -> text("\t")
                            "cell", "nestcell" -> endCell()
                            "row", "nestrow" -> endRow()
                            "trowd" -> {}
                            "clbrdrt", "clbrdrb", "clbrdrl", "clbrdrr" -> cellBorders = true
                            "intbl" -> paraFmt = paraFmt.copy(intbl = true)
                            "pard" -> paraFmt = Para()
                            "ql" -> paraFmt = paraFmt.copy(align = "")
                            "qc" -> paraFmt = paraFmt.copy(align = "center")
                            "qr" -> paraFmt = paraFmt.copy(align = "right")
                            "qj" -> paraFmt = paraFmt.copy(align = "justify")
                            "li" -> paraFmt = paraFmt.copy(li = p ?: 0)
                            "ri" -> paraFmt = paraFmt.copy(ri = p ?: 0)
                            "fi" -> paraFmt = paraFmt.copy(fi = p ?: 0)
                            "sb" -> paraFmt = paraFmt.copy(sb = p ?: 0)
                            "sa" -> paraFmt = paraFmt.copy(sa = p ?: 0)
                            "plain" -> g.chr = Chr(f = g.chr.f)
                            "b" -> g.chr = g.chr.copy(b = on)
                            "i" -> g.chr = g.chr.copy(i = on)
                            "ul", "uld", "uldash", "uldb", "ulth", "ulw", "ulwave" -> g.chr = g.chr.copy(u = on)
                            "ulnone" -> g.chr = g.chr.copy(u = false)
                            "strike", "striked" -> g.chr = g.chr.copy(s = on)
                            "super" -> g.chr = g.chr.copy(sup = on, sub = false)
                            "sub" -> g.chr = g.chr.copy(sub = on, sup = false)
                            "nosupersub" -> g.chr = g.chr.copy(sub = false, sup = false)
                            "fs" -> g.chr = g.chr.copy(fs = p ?: 24)
                            "cf" -> g.chr = g.chr.copy(cf = p ?: 0)
                            "highlight", "cb", "chcbpat" -> g.chr = g.chr.copy(hl = p ?: 0)
                            "f" -> g.chr = g.chr.copy(f = p ?: 0)
                            "v" -> g.chr = g.chr.copy(hidden = on)
                            else -> SPECIALS[w]?.let { text(it) }
                        }
                    }
                }
                groupStart = false
            }
            drain()
            if (para.isNotEmpty() || paraFmt.intbl) { paraFmt = paraFmt.copy(intbl = false); flushTable(); out.append(paraHtml()) }
            flushTable()
            return if (html) out.toString() else out.toString().trimEnd()
        }
    }

    /** Rough plain text of an HTML body (for the text/plain alternative). */
    fun htmlToText(html: String): String = html
        .replace(Regex("(?is)<(script|style|head)[^>]*>.*?</\\1>"), "")
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("(?i)</(p|div|tr|h[1-6]|li)>"), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
        .replace(Regex("[ \\t]+\n"), "\n").replace(Regex("\n{3,}"), "\n\n").trim()
}
