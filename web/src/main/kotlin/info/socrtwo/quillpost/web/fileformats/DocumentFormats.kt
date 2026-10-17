package info.socrtwo.quillpost.web.fileformats

/**
 * Recognises word-processor, spreadsheet and presentation files by their content (the file
 * extension only breaks ties or names formats that have no signature), so QuillPost can offer
 * the suite program that opens them: QuillPen, QuillGrid or QuillStage.
 */
object DocumentFormats {

    enum class Kind(val app: String, val appLabel: String, val androidPackage: String) {
        DOCUMENT("quillpen", "QuillPen", "io.github.socrtwo.quillpen"),
        SPREADSHEET("quillgrid", "QuillGrid", "io.github.socrtwo.quillgrid"),
        PRESENTATION("quillstage", "QuillStage", "io.github.socrtwo.quillstage")
    }

    data class Detected(val format: String, val label: String, val kind: Kind, val confidence: Double)

    private data class Ext(val label: String, val kind: Kind)

    /** Extension table: what each extension means when the content has no stronger signature. */
    private val EXT = mapOf(
        "doc" to Ext("Word 97-2003 document", Kind.DOCUMENT), "dot" to Ext("Word 97-2003 template", Kind.DOCUMENT),
        "docx" to Ext("Word document", Kind.DOCUMENT), "docm" to Ext("Word macro-enabled document", Kind.DOCUMENT), "dotx" to Ext("Word template", Kind.DOCUMENT),
        "dotm" to Ext("Word macro-enabled template", Kind.DOCUMENT), "odt" to Ext("OpenDocument text", Kind.DOCUMENT), "ott" to Ext("OpenDocument text template", Kind.DOCUMENT),
        "fodt" to Ext("Flat OpenDocument text", Kind.DOCUMENT), "rtf" to Ext("Rich Text Format document", Kind.DOCUMENT), "wpd" to Ext("WordPerfect document", Kind.DOCUMENT),
        "wp" to Ext("WordPerfect document", Kind.DOCUMENT), "wp5" to Ext("WordPerfect 5 document", Kind.DOCUMENT), "wp6" to Ext("WordPerfect 6 document", Kind.DOCUMENT),
        "pages" to Ext("Apple Pages document", Kind.DOCUMENT), "wps" to Ext("Microsoft Works document", Kind.DOCUMENT), "wri" to Ext("Windows Write document", Kind.DOCUMENT),
        "sxw" to Ext("StarOffice / OpenOffice.org 1 text", Kind.DOCUMENT), "sdw" to Ext("StarWriter document", Kind.DOCUMENT), "lwp" to Ext("Lotus Word Pro document", Kind.DOCUMENT),
        "sam" to Ext("Lotus Ami Pro document", Kind.DOCUMENT), "abw" to Ext("AbiWord document", Kind.DOCUMENT), "hwp" to Ext("Hangul (HWP) document", Kind.DOCUMENT),
        "cwk" to Ext("AppleWorks / ClarisWorks document", Kind.DOCUMENT), "mcw" to Ext("Word for Macintosh document", Kind.DOCUMENT), "txt" to Ext("Text document", Kind.DOCUMENT),
        "md" to Ext("Markdown document", Kind.DOCUMENT), "htm" to Ext("Web page", Kind.DOCUMENT), "html" to Ext("Web page", Kind.DOCUMENT), "xml" to Ext("XML document", Kind.DOCUMENT),
        "xls" to Ext("Excel 97-2003 workbook", Kind.SPREADSHEET), "xlt" to Ext("Excel 97-2003 template", Kind.SPREADSHEET), "xlsx" to Ext("Excel workbook", Kind.SPREADSHEET),
        "xlsm" to Ext("Excel macro-enabled workbook", Kind.SPREADSHEET), "xlsb" to Ext("Excel binary workbook", Kind.SPREADSHEET), "xltx" to Ext("Excel template", Kind.SPREADSHEET),
        "ods" to Ext("OpenDocument spreadsheet", Kind.SPREADSHEET), "ots" to Ext("OpenDocument spreadsheet template", Kind.SPREADSHEET), "fods" to Ext("Flat OpenDocument spreadsheet", Kind.SPREADSHEET),
        "numbers" to Ext("Apple Numbers spreadsheet", Kind.SPREADSHEET), "wk1" to Ext("Lotus 1-2-3 worksheet", Kind.SPREADSHEET), "wks" to Ext("Lotus 1-2-3 / Works worksheet", Kind.SPREADSHEET),
        "wk3" to Ext("Lotus 1-2-3 worksheet", Kind.SPREADSHEET), "wk4" to Ext("Lotus 1-2-3 worksheet", Kind.SPREADSHEET), "123" to Ext("Lotus 1-2-3 workbook", Kind.SPREADSHEET),
        "wq1" to Ext("Quattro Pro spreadsheet", Kind.SPREADSHEET), "wb1" to Ext("Quattro Pro notebook", Kind.SPREADSHEET), "wb2" to Ext("Quattro Pro notebook", Kind.SPREADSHEET),
        "wb3" to Ext("Quattro Pro notebook", Kind.SPREADSHEET), "qpw" to Ext("Quattro Pro notebook", Kind.SPREADSHEET), "xlr" to Ext("Microsoft Works spreadsheet", Kind.SPREADSHEET),
        "sxc" to Ext("StarOffice / OpenOffice.org 1 spreadsheet", Kind.SPREADSHEET), "sdc" to Ext("StarCalc spreadsheet", Kind.SPREADSHEET), "csv" to Ext("Comma-separated values", Kind.SPREADSHEET),
        "tsv" to Ext("Tab-separated values", Kind.SPREADSHEET), "slk" to Ext("SYLK spreadsheet", Kind.SPREADSHEET), "dif" to Ext("Data Interchange Format spreadsheet", Kind.SPREADSHEET),
        "gnumeric" to Ext("Gnumeric spreadsheet", Kind.SPREADSHEET), "et" to Ext("WPS Spreadsheets workbook", Kind.SPREADSHEET),
        "ppt" to Ext("PowerPoint 97-2003 presentation", Kind.PRESENTATION), "pps" to Ext("PowerPoint 97-2003 slide show", Kind.PRESENTATION), "pot" to Ext("PowerPoint 97-2003 template", Kind.PRESENTATION),
        "pptx" to Ext("PowerPoint presentation", Kind.PRESENTATION), "pptm" to Ext("PowerPoint macro-enabled presentation", Kind.PRESENTATION), "ppsx" to Ext("PowerPoint slide show", Kind.PRESENTATION),
        "potx" to Ext("PowerPoint template", Kind.PRESENTATION), "odp" to Ext("OpenDocument presentation", Kind.PRESENTATION), "otp" to Ext("OpenDocument presentation template", Kind.PRESENTATION),
        "fodp" to Ext("Flat OpenDocument presentation", Kind.PRESENTATION), "key" to Ext("Apple Keynote presentation", Kind.PRESENTATION), "sxi" to Ext("StarOffice / OpenOffice.org 1 presentation", Kind.PRESENTATION),
        "sdd" to Ext("StarImpress presentation", Kind.PRESENTATION), "shw" to Ext("Corel Presentations slide show", Kind.PRESENTATION), "prz" to Ext("Lotus Freelance presentation", Kind.PRESENTATION),
        "pre" to Ext("Lotus Freelance presentation", Kind.PRESENTATION), "dps" to Ext("WPS Presentation", Kind.PRESENTATION)
    )

    fun extension(name: String): String = name.substringAfterLast('/').substringAfterLast('\\').substringAfterLast('.', "").lowercase()

    private fun byExt(name: String, confidence: Double): Detected? = EXT[extension(name)]?.let { Detected(extension(name), "${it.label} (.${extension(name)})", it.kind, confidence) }

    private fun d(format: String, label: String, kind: Kind, c: Double) = Detected(format, label, kind, c)

    fun detect(b: ByteArray, name: String = ""): Detected? {
        val ext = extension(name)
        if (b.size >= 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() && (b[2].toInt() == 3 || b[2].toInt() == 5)) return zip(b, ext)
        Cfb.open(b)?.let { return cfb(it, ext) }
        val head = String(b, 0, minOf(b.size, 4096), Charsets.ISO_8859_1)
        when {
            head.startsWith("{\\rtf") -> return d("rtf", "Rich Text Format document (.rtf)", Kind.DOCUMENT, 0.97)
            b.size >= 10 && b[0] == 0xFF.toByte() && head.startsWith("ÿWPC") -> {
                val product = b[8].toInt() and 0xff; val type = b[9].toInt() and 0xff
                return if (ext == "shw" || ext == "prz" || (product == 0x0F)) d("shw", "Corel Presentations slide show (.shw)", Kind.PRESENTATION, 0.8)
                else if (product == 1 || type == 0x0A) d("wpd", "WordPerfect document (.wpd)", Kind.DOCUMENT, 0.95)
                else d("wpd", "WordPerfect-family file", Kind.DOCUMENT, 0.6)
            }
            head.startsWith("HWP Document File") -> return d("hwp", "Hangul (HWP) document (.hwp)", Kind.DOCUMENT, 0.95)
            b.size >= 8 && head.startsWith("1¾\u0000\u0000") -> return if (ext == "doc") d("doc", "Word for DOS document (.doc)", Kind.DOCUMENT, 0.85) else d("wri", "Windows Write document (.wri)", Kind.DOCUMENT, 0.85)
            b.size >= 4 && (b[0] == 0xFE.toByte() && b[1] == 0x37.toByte() && b[2].toInt() == 0) -> return d("mcw", "Word for Macintosh document", Kind.DOCUMENT, 0.8)
            b.size >= 8 && head.substring(4, 8) == "BOBO" -> return d("cwk", "AppleWorks / ClarisWorks document (.cwk)", Kind.DOCUMENT, 0.85)
            head.startsWith("[ver]") -> return d("sam", "Lotus Ami Pro document (.sam)", Kind.DOCUMENT, 0.8)
            head.startsWith("WordPro") -> return d("lwp", "Lotus Word Pro document (.lwp)", Kind.DOCUMENT, 0.9)
            b.size >= 8 && b[0].toInt() == 0 && b[1].toInt() == 0 && b[2].toInt() == 2 && b[3].toInt() == 0 -> {
                val v = ((b[5].toInt() and 0xff) shl 8) or (b[4].toInt() and 0xff)
                return when (v) {
                    0x0404, 0x0405, 0x0406 -> d(if (v == 0x0404) "wks" else "wk1", "Lotus 1-2-3 worksheet (.${if (v == 0x0404) "wks" else "wk1"})", Kind.SPREADSHEET, 0.9)
                    in 0x5120..0x512F -> d("wq1", "Quattro Pro spreadsheet", Kind.SPREADSHEET, 0.85)
                    else -> byExt(name, 0.5)
                }
            }
            b.size >= 8 && b[0].toInt() == 0 && b[1].toInt() == 0 && b[2].toInt() == 0x1A && b[3].toInt() == 0 && (b[5].toInt() and 0xff) == 0x10 -> {
                val v = b[4].toInt() and 0xff
                return d(when (v) { 0 -> "wk3"; 2 -> "wk4"; else -> "123" }, "Lotus 1-2-3 workbook (.${when (v) { 0 -> "wk3"; 2 -> "wk4"; else -> "123" }})", Kind.SPREADSHEET, 0.9)
            }
            b.size >= 4 && b[0].toInt() == 0x09 && (b[1].toInt() == 0x00 || b[1].toInt() == 0x02 || b[1].toInt() == 0x04) && (b[2].toInt() == 4 || b[2].toInt() == 6) && b[3].toInt() == 0 ->
                return d("xls", "Excel 2.x-4.0 worksheet (.xls)", Kind.SPREADSHEET, 0.85)
            head.startsWith("ID;P") || head.startsWith("ID;") && ext == "slk" -> return d("slk", "SYLK spreadsheet (.slk)", Kind.SPREADSHEET, 0.9)
            head.startsWith("TABLE\r\n0,1") || head.startsWith("TABLE\n0,1") -> return d("dif", "Data Interchange Format spreadsheet (.dif)", Kind.SPREADSHEET, 0.9)
            b.size >= 2 && b[0] == 0x1F.toByte() && b[1] == 0x8B.toByte() && ext == "gnumeric" -> return d("gnumeric", "Gnumeric spreadsheet (.gnumeric)", Kind.SPREADSHEET, 0.8)
        }
        xml(head, ext)?.let { return it }
        // Text formats recognised by extension only.
        if (ext in setOf("csv", "tsv", "txt", "md", "htm", "html", "xml") && !head.contains('\u0000')) return byExt(name, if (ext == "csv" || ext == "tsv") 0.7 else 0.5)
        return null
    }

    private fun xml(head: String, ext: String): Detected? {
        if (!head.trimStart('ï', '»', '¿', ' ', '\r', '\n', '\t').startsWith("<")) return null
        return when {
            head.contains("progid=\"Word.Document\"") || head.contains("<w:wordDocument") -> d("xml-word", "Word 2003 XML document", Kind.DOCUMENT, 0.9)
            head.contains("progid=\"Excel.Sheet\"") || head.contains("urn:schemas-microsoft-com:office:spreadsheet") -> d("xml-excel", "Excel 2003 XML spreadsheet", Kind.SPREADSHEET, 0.9)
            head.contains("progid=\"PowerPoint.Show\"") -> d("xml-ppt", "PowerPoint XML presentation", Kind.PRESENTATION, 0.85)
            head.contains("<office:document") -> when {
                head.contains("opendocument.text") -> d("fodt", "Flat OpenDocument text (.fodt)", Kind.DOCUMENT, 0.95)
                head.contains("opendocument.spreadsheet") -> d("fods", "Flat OpenDocument spreadsheet (.fods)", Kind.SPREADSHEET, 0.95)
                head.contains("opendocument.presentation") -> d("fodp", "Flat OpenDocument presentation (.fodp)", Kind.PRESENTATION, 0.95)
                else -> null
            }
            head.contains("<abiword") -> d("abw", "AbiWord document (.abw)", Kind.DOCUMENT, 0.9)
            head.contains("<gnm:Workbook") -> d("gnumeric", "Gnumeric spreadsheet", Kind.SPREADSHEET, 0.9)
            else -> null
        }
    }

    private fun cfb(c: Cfb, ext: String): Detected? {
        val names = c.root.children.map { it.name }
        fun has(n: String) = names.any { it.equals(n, true) }
        return when {
            has("WordDocument") -> if (ext == "dot") d("dot", "Word 97-2003 template (.dot)", Kind.DOCUMENT, 0.97) else d("doc", "Word 97-2003 document (.doc)", Kind.DOCUMENT, 0.97)
            has("Workbook") -> if (ext == "et") d("et", "WPS Spreadsheets workbook (.et)", Kind.SPREADSHEET, 0.9) else d("xls", "Excel 97-2003 workbook (.xls)", Kind.SPREADSHEET, 0.97)
            has("Book") -> d("xls", "Excel 5.0/95 workbook (.xls)", Kind.SPREADSHEET, 0.95)
            has("PowerPoint Document") -> when (ext) { "pps" -> d("pps", "PowerPoint 97-2003 slide show (.pps)", Kind.PRESENTATION, 0.97); "dps" -> d("dps", "WPS Presentation (.dps)", Kind.PRESENTATION, 0.9); else -> d("ppt", "PowerPoint 97-2003 presentation (.ppt)", Kind.PRESENTATION, 0.97) }
            has("MatOST") || (has("CONTENTS") && ext == "wps") -> d("wps", "Microsoft Works document (.wps)", Kind.DOCUMENT, 0.9)
            has("WksSSWorkBook") -> d("xlr", "Microsoft Works spreadsheet (.xlr)", Kind.SPREADSHEET, 0.9)
            has("NativeContent_MAIN") -> d("qpw", "Quattro Pro notebook (.qpw)", Kind.SPREADSHEET, 0.9)
            has("StarWriterDocument") -> d("sdw", "StarWriter document (.sdw)", Kind.DOCUMENT, 0.9)
            has("StarCalcDocument") -> d("sdc", "StarCalc spreadsheet (.sdc)", Kind.SPREADSHEET, 0.9)
            has("StarImpressDocument") -> d("sdd", "StarImpress presentation (.sdd)", Kind.PRESENTATION, 0.9)
            has("FileHeader") && has("BodyText") -> d("hwp", "Hangul (HWP) document (.hwp)", Kind.DOCUMENT, 0.9)
            has("EncryptedPackage") -> byExt("x.$ext", 0.6) ?: d("office-encrypted", "Password-protected Office file", Kind.DOCUMENT, 0.5)
            else -> null
        }
    }

    /** Names of the entries in a ZIP archive (central directory, else local headers), and the stored "mimetype". */
    fun zipEntries(b: ByteArray): Pair<List<String>, String?> {
        val names = ArrayList<String>()
        var eocd = -1
        for (i in b.size - 22 downTo maxOf(0, b.size - 65557)) if (b[i] == 0x50.toByte() && b[i + 1] == 0x4B.toByte() && b[i + 2].toInt() == 5 && b[i + 3].toInt() == 6) { eocd = i; break }
        if (eocd >= 0) {
            val count = u16(b, eocd + 10)
            var p = Cfb.le32(b, eocd + 16)
            for (k in 0 until count) {
                if (p < 0 || p + 46 > b.size || Cfb.le32(b, p) != 0x02014b50) break
                val n = u16(b, p + 28); val x = u16(b, p + 30); val c = u16(b, p + 32)
                if (p + 46 + n > b.size) break
                names += String(b, p + 46, n, Charsets.UTF_8)
                p += 46 + n + x + c
            }
        }
        if (names.isEmpty()) {
            var p = 0
            while (p + 30 <= b.size && Cfb.le32(b, p) == 0x04034b50) {
                val n = u16(b, p + 26); val x = u16(b, p + 28); val size = Cfb.le32(b, p + 18)
                if (p + 30 + n > b.size) break
                names += String(b, p + 30, n, Charsets.UTF_8)
                if (size <= 0) break
                p += 30 + n + x + size
            }
        }
        var mimetype: String? = null
        if (b.size > 38 && Cfb.le32(b, 0) == 0x04034b50 && u16(b, 8) == 0) {
            val n = u16(b, 26); val x = u16(b, 28); val size = Cfb.le32(b, 18)
            if (n == 8 && String(b, 30, 8, Charsets.ISO_8859_1) == "mimetype" && size in 1..200 && 30 + n + x + size <= b.size) mimetype = String(b, 30 + n + x, size, Charsets.ISO_8859_1)
        }
        return names to mimetype
    }

    private fun zip(b: ByteArray, ext: String): Detected? {
        val (names, mimetype) = zipEntries(b)
        fun has(n: String) = names.any { it.equals(n, true) }
        fun prefix(p: String) = names.any { it.startsWith(p, true) }
        mimetype?.let { m ->
            return when {
                m.startsWith("application/vnd.oasis.opendocument.text") -> d(if (m.endsWith("template")) "ott" else "odt", "OpenDocument text (.${if (m.endsWith("template")) "ott" else "odt"})", Kind.DOCUMENT, 0.98)
                m.startsWith("application/vnd.oasis.opendocument.spreadsheet") -> d(if (m.endsWith("template")) "ots" else "ods", "OpenDocument spreadsheet (.${if (m.endsWith("template")) "ots" else "ods"})", Kind.SPREADSHEET, 0.98)
                m.startsWith("application/vnd.oasis.opendocument.presentation") -> d(if (m.endsWith("template")) "otp" else "odp", "OpenDocument presentation (.${if (m.endsWith("template")) "otp" else "odp"})", Kind.PRESENTATION, 0.98)
                m == "application/vnd.sun.xml.writer" -> d("sxw", "OpenOffice.org 1 text (.sxw)", Kind.DOCUMENT, 0.95)
                m == "application/vnd.sun.xml.calc" -> d("sxc", "OpenOffice.org 1 spreadsheet (.sxc)", Kind.SPREADSHEET, 0.95)
                m == "application/vnd.sun.xml.impress" -> d("sxi", "OpenOffice.org 1 presentation (.sxi)", Kind.PRESENTATION, 0.95)
                else -> null
            }
        }
        return when {
            has("word/document.xml") || prefix("word/") && has("[Content_Types].xml") -> when (ext) {
                "docm", "dotx", "dotm" -> byExt("x.$ext", 0.97)
                else -> d("docx", "Word document (.docx)", Kind.DOCUMENT, 0.98)
            }
            has("xl/workbook.xml") || has("xl/workbook.bin") -> when { has("xl/workbook.bin") -> d("xlsb", "Excel binary workbook (.xlsb)", Kind.SPREADSHEET, 0.97); ext in setOf("xlsm", "xltx", "xltm") -> byExt("x.$ext", 0.97); else -> d("xlsx", "Excel workbook (.xlsx)", Kind.SPREADSHEET, 0.98) }
            has("ppt/presentation.xml") -> when (ext) { "ppsx", "pptm", "potx" -> byExt("x.$ext", 0.97); else -> d("pptx", "PowerPoint presentation (.pptx)", Kind.PRESENTATION, 0.98) }
            has("Index/Document.iwa") || prefix("Index/") && names.any { it.endsWith(".iwa") } -> when {
                prefix("Index/Slide") || prefix("Index/MasterSlide") || ext == "key" -> d("key", "Apple Keynote presentation (.key)", Kind.PRESENTATION, 0.9)
                has("Index/CalculationEngine.iwa") || prefix("Index/Tables/") || ext == "numbers" -> d("numbers", "Apple Numbers spreadsheet (.numbers)", Kind.SPREADSHEET, 0.9)
                else -> d("pages", "Apple Pages document (.pages)", Kind.DOCUMENT, 0.85)
            }
            has("index.apxl") || has("index.apxl.gz") -> d("key", "Apple Keynote '09 presentation (.key)", Kind.PRESENTATION, 0.9)
            has("index.xml") || has("index.xml.gz") -> when (ext) { "numbers" -> d("numbers", "Apple Numbers '09 spreadsheet (.numbers)", Kind.SPREADSHEET, 0.85); else -> d("pages", "Apple Pages '09 document (.pages)", Kind.DOCUMENT, 0.75) }
            else -> null
        }
    }

    private fun u16(b: ByteArray, o: Int): Int = if (o + 2 > b.size) 0 else (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
}
