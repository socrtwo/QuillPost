package info.socrtwo.quillpost.web.fileformats

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OutlookFormatsTest {

    private fun hex(s: String) = s.split(' ').filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()

    // ------------------------------------------------------------------ MS-CFB

    @Test fun `compound file round trip with mini and regular streams and nested storages`() {
        val big = ByteArray(10_000) { (it % 251).toByte() }
        val root = CfbWriter.Node("Root Entry")
        root.stream("Small", "hello".toByteArray())
        root.stream("Big", big)
        root.storage("Inner").stream("Deep", ByteArray(700) { 7 })
        val cfb = Cfb.open(CfbWriter.write(root))!!
        assertEquals("hello", String(cfb.read(cfb.find("Small")!!)))
        assertTrue(cfb.read(cfb.find("Big")!!).contentEquals(big))
        assertEquals(700, cfb.read(cfb.find("Inner/Deep")!!).size)
        assertTrue(cfb.paths().containsAll(listOf("Small", "Big", "Inner", "Inner/Deep")))
    }

    @Test fun `non compound files and damaged headers are rejected quietly`() {
        assertNull(Cfb.open("not a compound file at all".toByteArray()))
        val broken = CfbWriter.write(CfbWriter.Node("Root Entry").also { it.stream("A", ByteArray(10)) }).copyOf(600)
        assertTrue(Cfb.open(broken) == null || Cfb.open(broken)!!.root.name == "Root Entry")
    }

    // ------------------------------------------------------------------ MS-OXRTFCP

    @Test fun `the preset dictionary is 207 bytes`() = assertEquals(207, Rtf.PRESET_DICTIONARY.size)

    @Test fun `decompresses the specification example`() {
        val comp = hex("2d 00 00 00 2b 00 00 00 4c 5a 46 75 f1 c5 c7 a7 03 00 0a 00 72 63 70 67 31 32 35 42 32 0a f3 20 68 65 6c 09 00 20 62 77 05 b0 6c 64 7d 0a 80 0f a0")
        assertEquals("{\\rtf1\\ansi\\ansicpg1252\\pard hello world}\r\n", String(Rtf.decompress(comp), Charsets.ISO_8859_1))
        assertEquals(Cfb.le32(comp, 12), Rtf.crc32(comp, 16, comp.size))
    }

    @Test fun `compressed RTF round trip and the uncompressed MELA form`() {
        val raw = "{\\rtf1\\ansi\\ansicpg1252\\deff0{\\fonttbl{\\f0 Arial;}}\\pard\\plain\\f0\\fs20 repeated repeated repeated text\\par}".toByteArray()
        assertTrue(Rtf.decompress(RtfCompressor.compress(raw)).contentEquals(raw))
        val mela = ByteArray(16 + raw.size); CfbWriter.put32(mela, 0, raw.size + 12); CfbWriter.put32(mela, 4, raw.size); CfbWriter.put32(mela, 8, Rtf.UNCOMPRESSED); raw.copyInto(mela, 16)
        assertTrue(Rtf.decompress(mela).contentEquals(raw))
    }

    // ------------------------------------------------------------------ MS-OXRTFEX and RTF rendering

    @Test fun `de-encapsulates HTML from RTF`() {
        val rtf = ("{\\rtf1\\ansi\\ansicpg1252\\fromhtml1 \\deff0{\\fonttbl{\\f0\\fswiss Arial;}}" +
            "{\\*\\htmltag19 <html>}{\\*\\htmltag34 <head>}{\\*\\htmltag41 </head>}{\\*\\htmltag50 <body>}\\htmlrtf {\\htmlrtf0" +
            "{\\*\\htmltag64 <p>}Caf\\'e9 \\{ok\\} \\u8364?{\\*\\htmltag72 </p>}\\htmlrtf\\par\\htmlrtf0}" +
            "{\\*\\mhtmltag84 <img src=\"x\">}{\\*\\htmltag84 <img src=\"cid:a@b\">}{\\*\\htmltag58 </body>}{\\*\\htmltag27 </html>}}").toByteArray()
        val html = Rtf.deEncapsulateHtml(rtf)!!
        assertEquals("<html><head></head><body><p>Café {ok} €</p><img src=\"cid:a@b\"></body></html>", html)
        assertNull(Rtf.deEncapsulateHtml("{\\rtf1 plain}".toByteArray()))
    }

    @Test fun `de-encapsulates plain text from RTF`() {
        val rtf = "{\\rtf1\\ansi\\fromtext \\deff0{\\fonttbl{\\f0 Courier;}}\\pard Line one\\par Line two\\par}".toByteArray()
        assertEquals("Line one\nLine two\n", Rtf.deEncapsulateText(rtf))
    }

    @Test fun `renders rich text with fonts colours tables links pictures and code pages`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val rtf = ("{\\rtf1\\ansi\\ansicpg1252\\deff0{\\fonttbl{\\f0\\froman Times New Roman;}{\\f1\\fswiss\\fcharset204 Arial;}{\\f2\\fcharset2 Symbol;}}" +
            "{\\colortbl;\\red255\\green0\\blue0;}" +
            "\\pard\\qc{\\b Bold} {\\i it}\\cf1 red\\cf0  {\\ul under} {\\strike gone} x{\\super 2}\\par" +
            "\\pard{\\f1 \\'cf\\'f0\\'e8\\'e2\\'e5\\'f2}\\par" +
            "\\pard{\\f2 \\'b7}\\tab item\\par" +
            "{\\field{\\*\\fldinst HYPERLINK \"https://example.org/\"}{\\fldrslt link text}}\\par" +
            "\\trowd\\cellx1500\\cellx3000\\pard\\intbl A1\\cell B1\\cell\\row" +
            "\\pard{\\*\\shppict{\\pict\\pngblip\\picwgoal1500\\pichgoal750 89504e47}}{\\nonshppict{\\pict\\wmetafile8 0102}}\\par" +
            "\\pard{\\*\\comment hidden}\\u8212?\\emdash\\par}").toByteArray()
        val r = Rtf.render(rtf)
        assertContains(r.html, "text-align:center")
        assertContains(r.html, "font-weight:bold;")
        assertContains(r.html, "color:#ff0000")
        assertContains(r.html, "text-decoration:underline")
        assertContains(r.html, "line-through")
        assertContains(r.html, "vertical-align:super")
        assertContains(r.html, "Привет")
        assertContains(r.html, "•")
        assertContains(r.html, "<a href=\"https://example.org/\">")
        assertContains(r.html, "<table")
        assertContains(r.html, ">A1<")
        assertContains(r.html, "width:100px")
        assertContains(r.html, "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(png))
        assertEquals(1, Regex("<img").findAll(r.html).count())
        assertFalse(r.html.contains("hidden"))
        assertContains(r.text, "——")
        assertContains(r.text, "A1\tB1")
    }

    // ------------------------------------------------------------------ MS-OXMSG

    private val sent = 1_790_000_000_000L // 2026-09-21

    private fun sampleMsg(): MsgBuilder {
        val inner = MsgBuilder()
            .prop(Mapi.MESSAGE_CLASS, PV.Str("IPM.Note")).prop(Mapi.SUBJECT, PV.Str("Inner note"))
            .prop(Mapi.SENDER_NAME, PV.Str("Bob")).prop(Mapi.SENDER_SMTP, PV.Str("bob@example.net"))
            .prop(Mapi.BODY, PV.Str("Inner body"))
        return MsgBuilder()
            .prop(Mapi.MESSAGE_CLASS, PV.Str("IPM.Note"))
            .prop(Mapi.SUBJECT, PV.Str("Quarterly figures ✓"))
            .prop(Mapi.SENT_REPRESENTING_NAME, PV.Str("Alice Example"))
            .prop(Mapi.SENT_REPRESENTING_EMAIL, PV.Str("/O=EXAMPLE/OU=FIRST/CN=RECIPIENTS/CN=ALICE"))
            .prop(Mapi.SENT_REPRESENTING_ADDRTYPE, PV.Str("EX"))
            .prop(Mapi.SENT_REPRESENTING_SMTP, PV.Str("alice@example.com"))
            .prop(Mapi.CLIENT_SUBMIT_TIME, PV.Time(sent))
            .prop(Mapi.IMPORTANCE, PV.I32(2))
            .prop(Mapi.INTERNET_MESSAGE_ID, PV.Str("<q3@example.com>"))
            .prop(Mapi.BODY, PV.Str("Hello team"))
            .prop(Mapi.BODY_HTML, PV.Bin("<html><body><p>Hello <b>team</b></p><img src=\"cid:chart@x\"></body></html>".toByteArray()))
            .prop(Mapi.INTERNET_CPID, PV.I32(65001))
            .recipient(1, "Carol", "carol@example.com").recipient(2, "Dan", "dan@example.com").recipient(3, "Eve", "eve@example.com")
            .attach(mapOf(Mapi.ATTACH_LONG_FILENAME to PV.Str("chart.png"), Mapi.ATTACH_MIME_TAG to PV.Str("image/png"), Mapi.ATTACH_CONTENT_ID to PV.Str("chart@x"), Mapi.ATTACH_DATA to PV.Bin(byteArrayOf(1, 2, 3)), Mapi.ATTACH_METHOD to PV.I32(1)))
            .attach(mapOf(Mapi.ATTACH_LONG_FILENAME to PV.Str("figures.xlsx"), Mapi.ATTACH_DATA to PV.Bin(ByteArray(5000) { 9 }), Mapi.ATTACH_METHOD to PV.I32(1)))
            .attach(mapOf(Mapi.DISPLAY_NAME to PV.Str("Inner note")), inner)
    }

    @Test fun `reads an Outlook message with recipients HTML body inline image file and embedded message`() {
        val bytes = sampleMsg().build()
        assertEquals("msg" to 0.99, MessageFormats.detect(bytes, "q.msg"))
        val m = MessageFormats.parse(bytes, "q.msg")
        assertEquals("Quarterly figures ✓", m.subject)
        assertEquals(MailAddress("Alice Example", "alice@example.com"), m.from)
        assertEquals(listOf("carol@example.com"), m.to.map { it.address })
        assertEquals(listOf("dan@example.com"), m.cc.map { it.address })
        assertEquals(listOf("eve@example.com"), m.bcc.map { it.address })
        assertEquals(sent, m.date)
        assertEquals("high", m.importance)
        assertEquals("<q3@example.com>", m.messageId)
        assertContains(m.bodyHtml!!, "Hello <b>team</b>")
        assertEquals("Hello team", m.bodyText)
        val chart = m.attachments.first { it.fileName == "chart.png" }
        assertTrue(chart.inline); assertEquals("chart@x", chart.contentId); assertEquals("image/png", chart.mimeType)
        val xlsx = m.attachments.first { it.fileName == "figures.xlsx" }
        assertFalse(xlsx.inline); assertEquals(5000, xlsx.size)
        val emb = m.attachments.first { it.embedded != null }
        assertEquals("Inner note", emb.embedded!!.subject)
        assertEquals("bob@example.net", emb.embedded!!.from?.address)
        assertEquals("message/rfc822", emb.mimeType)
        // The embedded message travels as a real RFC 822 message.
        assertEquals("Inner note", MimeReader.parse(emb.bytes).subject)
    }

    @Test fun `converts an Outlook message to RFC 822 for saving to a folder`() {
        val mime = MessageFormats.toRfc822(sampleMsg().build(), "q.msg")
        val back = MimeReader.parse(mime)
        assertEquals("Quarterly figures ✓", back.subject)
        assertEquals("alice@example.com", back.from?.address)
        assertEquals("<q3@example.com>", back.messageId)
        assertEquals(listOf("chart.png", "figures.xlsx", "Inner note.eml").sorted(), back.attachments.map { it.fileName }.sorted())
        assertTrue(back.attachments.first { it.fileName == "chart.png" }.inline)
        assertEquals("high", back.importance)
    }

    @Test fun `8-bit strings use the message code page and RTF-only bodies are rendered`() {
        val rtf = "{\\rtf1\\ansi\\ansicpg1252\\deff0{\\fonttbl{\\f0 Arial;}}\\pard Plain {\\b bold} body\\par}".toByteArray()
        val bytes = MsgBuilder()
            .prop(Mapi.MESSAGE_CLASS, PV.Ansi("IPM.Note".toByteArray()))
            .prop(Mapi.MESSAGE_CODEPAGE, PV.I32(1251))
            .prop(Mapi.SUBJECT, PV.Ansi("Привет".toByteArray(charset("windows-1251"))))
            .prop(Mapi.SENDER_NAME, PV.Ansi("Иван".toByteArray(charset("windows-1251"))))
            .prop(Mapi.SENDER_EMAIL, PV.Ansi("ivan@example.ru".toByteArray()))
            .prop(Mapi.DISPLAY_TO, PV.Ansi("Olga; petr@example.ru".toByteArray()))
            .attach(mapOf(Mapi.ATTACH_LONG_FILENAME to PV.Ansi("Отчёт.txt".toByteArray(charset("windows-1251"))), Mapi.ATTACH_DATA to PV.Bin("x".toByteArray())))
            .prop(Mapi.RTF_COMPRESSED, PV.Bin(RtfCompressor.compress(rtf)))
            .build()
        val m = MessageFormats.parse(bytes, "x.msg")
        assertEquals("Привет", m.subject)
        assertEquals(MailAddress("Иван", "ivan@example.ru"), m.from)
        assertEquals(listOf("Olga", "petr@example.ru"), m.to.map { it.name.ifEmpty { it.address } })
        assertEquals("Отчёт.txt", m.attachments.single().fileName, "attachments use the message code page")
        assertContains(m.bodyHtml!!, "font-weight:bold;\">bold</span>")
        assertContains(m.bodyText, "Plain bold body")
    }

    @Test fun `encapsulated HTML in compressed RTF becomes the HTML body`() {
        val rtf = "{\\rtf1\\ansi\\fromhtml1 {\\*\\htmltag <p>}\\htmlrtf {\\htmlrtf0 Hi there\\htmlrtf\\par}\\htmlrtf0{\\*\\htmltag </p>}}".toByteArray()
        val m = MessageFormats.parse(MsgBuilder().prop(Mapi.SUBJECT, PV.Str("x")).prop(Mapi.RTF_COMPRESSED, PV.Bin(RtfCompressor.compress(rtf))).build())
        assertEquals("<p>Hi there</p>", m.bodyHtml)
    }

    @Test fun `templates open as drafts`() {
        val b = MsgBuilder().prop(Mapi.MESSAGE_CLASS, PV.Str("IPM.Note")).prop(Mapi.SUBJECT, PV.Str("Weekly status")).prop(Mapi.BODY, PV.Str("Status:")).build()
        assertEquals("oft", MessageFormats.detect(b, "Weekly.oft")!!.first)
        assertTrue(MessageFormats.parse(b, "Weekly.oft").template)
        assertFalse(MessageFormats.parse(b, "Weekly.msg").template)
    }

    @Test fun `contact items become People entries`() {
        val b = MsgBuilder().prop(Mapi.MESSAGE_CLASS, PV.Str("IPM.Contact")).prop(Mapi.SUBJECT, PV.Str("Grace Hopper"))
            .prop(0x3A06, PV.Str("Grace")).prop(0x3A11, PV.Str("Hopper")).prop(0x3A16, PV.Str("Navy")).prop(0x3A17, PV.Str("Rear Admiral"))
            .prop(0x3A1C, PV.Str("+1 555 0100")).prop(0x3A42, PV.Time(-1_985_000_000_000L))
            .named(Mapi.PSETID_ADDRESS, 0x8083, PV.Str("grace@example.mil")).named(Mapi.PSETID_ADDRESS, 0x8046, PV.Str("Arlington"))
            .build()
        val m = MessageFormats.parse(b)
        val c = m.contacts.single()
        assertEquals("Grace", c.firstName); assertEquals("Hopper", c.lastName); assertEquals("Navy", c.company)
        assertEquals("grace@example.mil", c.emails.single().value)
        assertEquals("mobile", c.phones.single().label)
        assertEquals("Arlington", c.addresses.single().city)
        assertTrue(c.birthday.startsWith("19"))
    }

    @Test fun `appointment items become calendar events`() {
        val start = 1_793_000_000_000L
        val b = MsgBuilder().prop(Mapi.MESSAGE_CLASS, PV.Str("IPM.Appointment")).prop(Mapi.SUBJECT, PV.Str("Design review"))
            .named(Mapi.PSETID_APPOINTMENT, 0x820D, PV.Time(start)).named(Mapi.PSETID_APPOINTMENT, 0x820E, PV.Time(start + 5_400_000))
            .named(Mapi.PSETID_APPOINTMENT, 0x8208, PV.Str("Room 4")).named(Mapi.PSETID_APPOINTMENT, 0x8223, PV.Bool(true))
            .build()
        val m = MessageFormats.parse(b)
        val e = m.events.single()
        assertEquals("Design review", e.title); assertEquals("Room 4", e.location); assertFalse(e.allDay)
        assertTrue(m.notes.any { it.contains("recurring") })
    }

    @Test fun `S-MIME Outlook items show the signed message inside smime p7m`() {
        val signed = ("Content-Type: multipart/signed; protocol=\"application/pkcs7-signature\"; boundary=s\r\n\r\n--s\r\nContent-Type: text/html; charset=utf-8\r\n\r\n<p>Signed <b>body</b></p>\r\n" +
            "--s\r\nContent-Type: application/pkcs7-signature; name=smime.p7s\r\nContent-Transfer-Encoding: base64\r\n\r\nMIAGCSqGSIb3DQEHAqCAMIACAQEx\r\n--s--\r\n").toByteArray()
        val b = MsgBuilder().prop(Mapi.MESSAGE_CLASS, PV.Str("IPM.Note.SMIME.MultipartSigned")).prop(Mapi.SUBJECT, PV.Str("Signed"))
            .attach(mapOf(Mapi.ATTACH_LONG_FILENAME to PV.Str("smime.p7m"), Mapi.ATTACH_MIME_TAG to PV.Str("multipart/signed"), Mapi.ATTACH_DATA to PV.Bin(signed))).build()
        val m = MessageFormats.parse(b)
        assertContains(m.bodyHtml!!, "Signed <b>body</b>")
        assertTrue(m.attachments.isEmpty())
        assertEquals("smime-signed", m.security)
        assertTrue(m.notes.any { it.contains("does not verify") })
    }

    // ------------------------------------------------------------------ MS-OXTNEF

    private fun sampleTnef(): ByteArray {
        val rtf = "{\\rtf1\\ansi\\ansicpg1252\\deff0{\\fonttbl{\\f0 Calibri;}}\\pard Please see {\\b attached}.\\par}".toByteArray()
        val inner = TnefBuilder()
            .attr(1, 0x00069003, TnefBuilder.propList(listOf(Triple(Mapi.SUBJECT, PV.Str("Forwarded note"), null), Triple(Mapi.BODY, PV.Str("Inner text"), null))))
            .bytes()
        return TnefBuilder()
            .attr(1, 0x00089006, byteArrayOf(0, 0, 1, 0))
            .attr(1, 0x00069007, byteArrayOf(0xE4.toByte(), 0x04, 0, 0, 0, 0, 0, 0))
            .attr(1, 0x00078008, "IPM.Microsoft Mail.Note\u0000".toByteArray())
            .attr(1, 0x00018004, "Old subject\u0000".toByteArray())
            .attr(1, 0x00038005, TnefBuilder.date(2026, 9, 21, 8, 30, 0))
            .attr(1, 0x00069003, TnefBuilder.propList(listOf(
                Triple(Mapi.SUBJECT, PV.Str("Budget ✓"), null),
                Triple(Mapi.RTF_COMPRESSED, PV.Bin(RtfCompressor.compress(rtf)), null),
                Triple(0x8501, PV.I32(15), Mapi.PSETID_COMMON to 0x8501)
            )))
            .attr(2, 0x00069002, ByteArray(14))
            .attr(2, 0x00018010, "BUDGET~1.XLS\u0000".toByteArray())
            .attr(2, 0x0006800F, ByteArray(300) { 3 })
            .attr(2, 0x00069005, TnefBuilder.propList(listOf(Triple(Mapi.ATTACH_LONG_FILENAME, PV.Str("Budget 2027.xls"), null), Triple(Mapi.ATTACH_MIME_TAG, PV.Str("application/vnd.ms-excel"), null))))
            .attr(2, 0x00069002, ByteArray(14))
            .attr(2, 0x00069005, TnefBuilder.propList(listOf(Triple(Mapi.DISPLAY_NAME, PV.Str("Forwarded note"), null), Triple(Mapi.ATTACH_METHOD, PV.I32(5), null),
                Triple(Mapi.ATTACH_DATA, PV.Obj(TnefBuilder.embeddedMessage(inner)), null))))
            .bytes()
    }

    @Test fun `reads winmail dat with RTF body named properties attachments and an attached message`() {
        val t = sampleTnef()
        assertEquals("tnef" to 0.99, MessageFormats.detect(t, "winmail.dat"))
        val m = MessageFormats.parse(t, "winmail.dat")
        assertEquals("Budget ✓", m.subject)
        assertContains(m.bodyHtml!!, "attached")
        assertContains(m.bodyText, "Please see attached.")
        val xls = m.attachments.first { it.fileName == "Budget 2027.xls" }
        assertEquals(300, xls.size); assertEquals("application/vnd.ms-excel", xls.mimeType)
        val emb = m.attachments.first { it.embedded != null }
        assertEquals("Forwarded note", emb.embedded!!.subject)
        assertNotNull(m.date)
    }

    @Test fun `a message carrying winmail dat shows its attachments and rich body`() {
        val b64 = java.util.Base64.getMimeEncoder().encodeToString(sampleTnef())
        val eml = ("From: a@example.com\r\nTo: b@example.com\r\nSubject: With TNEF\r\nMIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=\"b1\"\r\n\r\n" +
            "--b1\r\nContent-Type: text/plain; charset=us-ascii\r\n\r\nPlease see attached.\r\n" +
            "--b1\r\nContent-Type: application/ms-tnef; name=\"winmail.dat\"\r\nContent-Transfer-Encoding: base64\r\nContent-Disposition: attachment; filename=\"winmail.dat\"\r\n\r\n$b64\r\n--b1--\r\n").toByteArray()
        val m = MessageFormats.parse(eml, "x.eml")
        assertTrue(m.attachments.any { it.fileName == "Budget 2027.xls" })
        assertFalse(m.attachments.any { it.fileName.equals("winmail.dat", true) })
        assertContains(m.bodyHtml!!, "attached")
    }

    @Test fun `truncated TNEF is read as far as possible`() {
        val t = sampleTnef()
        val m = Tnef.parse(t.copyOf(t.size - 40))
        assertEquals("Budget ✓", m.subject)
        assertTrue(m.notes.any { it.contains("truncated") })
    }
}
