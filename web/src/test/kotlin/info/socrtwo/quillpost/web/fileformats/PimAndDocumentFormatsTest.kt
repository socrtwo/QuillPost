package info.socrtwo.quillpost.web.fileformats

import java.time.ZoneId
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PimAndDocumentFormatsTest {

    private fun crlf(s: String) = s.trimIndent().replace("\n", "\r\n")

    // ------------------------------------------------------------------ vCard

    @Test fun `Outlook vCard 2-1 with quoted-printable and windows-1252`() {
        val v = crlf("""
            BEGIN:VCARD
            VERSION:2.1
            N;LANGUAGE=de;CHARSET=Windows-1252:Müller;Jörg
            FN:Jörg Müller
            ORG:Beispiel GmbH;Vertrieb
            TEL;WORK;VOICE:+49 30 1234
            TEL;CELL;VOICE:+49 170 5678
            ADR;WORK;PREF;ENCODING=QUOTED-PRINTABLE:;;Hauptstra=DFe 1=0D=0AHinterhaus;Berlin;;10115;Deutschland
            EMAIL;PREF;INTERNET:joerg@example.de
            X-MS-OL-DEFAULT-POSTAL-ADDRESS:2
            END:VCARD
        """).toByteArray(charset("windows-1252"))
        assertEquals("vcf" to 0.98, MessageFormats.detect(v, "joerg.vcf"))
        val c = PimFormats.contacts(v, "vcf").single()
        assertEquals("Jörg", c.firstName); assertEquals("Müller", c.lastName)
        assertEquals("Beispiel GmbH", c.company); assertEquals("Vertrieb", c.department)
        assertEquals(listOf("work", "mobile"), c.phones.map { it.label })
        assertEquals("Hauptstraße 1\nHinterhaus", c.addresses.single().street.replace("\r\n", "\n"))
        assertEquals("joerg@example.de", c.emails.single().value)
    }

    @Test fun `Apple vCard 3-0 with grouped properties and a UTF-16 vCard 4-0`() {
        val apple = crlf("""
            BEGIN:VCARD
            VERSION:3.0
            N:Appleseed;Johnny;;;
            FN:Johnny Appleseed
            item1.EMAIL;type=INTERNET;type=pref:johnny@example.com
            item1.X-ABLabel:_${'$'}!<Other>!${'$'}_
            item2.TEL;type=pref:+1 408 555 0100
            BDAY;value=date:1980-02-15
            END:VCARD
        """).toByteArray()
        val a = PimFormats.contacts(apple, "vcf").single()
        assertEquals("johnny@example.com", a.emails.single().value)
        assertEquals("+1 408 555 0100", a.phones.single().value)
        assertEquals("1980-02-15", a.birthday)
        val v4 = (crlf("""
            BEGIN:VCARD
            VERSION:4.0
            FN:Zoë Ångström
            N:Ångström;Zoë;;;
            TEL;VALUE=uri;TYPE=cell:tel:+46-8-555-0100
            EMAIL:zoe@example.se
            END:VCARD
        """)).let { byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + it.toByteArray(Charsets.UTF_16LE) }
        assertEquals("vcf", MessageFormats.detect(v4, "z.vcf")!!.first)
        val z = PimFormats.contacts(v4, "vcf").single()
        assertEquals("Zoë Ångström", z.displayName); assertEquals("+46-8-555-0100", z.phones.single().value)
    }

    @Test fun `Windows Contacts XML`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <c:contact c:Version="1" xmlns:c="http://schemas.microsoft.com/Contact" xmlns:MSWABMAPI="http://schemas.microsoft.com/Contact/Extended/MSWABMAPI">
              <c:NameCollection><c:Name><c:FormattedName>Ada Lovelace</c:FormattedName><c:GivenName>Ada</c:GivenName><c:FamilyName>Lovelace</c:FamilyName></c:Name></c:NameCollection>
              <c:EmailAddressCollection><c:EmailAddress><c:Type>SMTP</c:Type><c:Address>ada@example.org</c:Address><c:LabelCollection><c:Label>Preferred</c:Label></c:LabelCollection></c:EmailAddress></c:EmailAddressCollection>
              <c:PhoneNumberCollection><c:PhoneNumber><c:Number>+44 20 7946 0000</c:Number><c:LabelCollection><c:Label>Cellular</c:Label></c:LabelCollection></c:PhoneNumber></c:PhoneNumberCollection>
              <c:PositionCollection><c:Position><c:Company>Analytical Engines</c:Company><c:JobTitle>Programmer</c:JobTitle></c:Position></c:PositionCollection>
            </c:contact>
        """.trimIndent().toByteArray()
        assertEquals("contact", MessageFormats.detect(xml, "Ada.contact")!!.first)
        val c = PimFormats.contacts(xml, "contact").single()
        assertEquals("Ada", c.firstName); assertEquals("ada@example.org", c.emails.single().value)
        assertEquals("mobile", c.phones.single().label); assertEquals("Analytical Engines", c.company)
    }

    // ------------------------------------------------------------------ iCalendar / vCalendar

    @Test fun `vCalendar 1-0 with TZ offset, quoted-printable and the old RRULE grammar`() {
        val vcs = crlf("""
            BEGIN:VCALENDAR
            VERSION:1.0
            TZ:+01:00
            BEGIN:VEVENT
            DTSTART:20261103T090000
            DTEND:20261103T100000
            SUMMARY;ENCODING=QUOTED-PRINTABLE;CHARSET=UTF-8:Caf=C3=A9 meeting
            DESCRIPTION;ENCODING=QUOTED-PRINTABLE:Line one=0D=0ALine two
            RRULE:W1 TU TH #10
            AALARM:20261103T084500
            END:VEVENT
            END:VCALENDAR
        """).toByteArray()
        assertEquals("vcs", MessageFormats.detect(vcs, "m.vcs")!!.first)
        val ics = PimFormats.vcalToIcal(String(vcs))
        assertTrue(ics.contains("DTSTART:20261103T080000Z"), ics)
        assertTrue(ics.contains("RRULE:FREQ=WEEKLY;BYDAY=TU,TH;COUNT=10"), ics)
        val e = PimFormats.events(vcs, ZoneId.of("UTC")).events.single()
        assertEquals("Café meeting", e.title)
        assertEquals("2026-11-03T08:00", e.start)
        assertEquals(listOf("TU", "TH"), e.recurrence!!.byDay)
        assertEquals(10, e.recurrence!!.count)
        assertTrue(e.notes.contains("Line two"))
    }

    @Test fun `vCalendar 1-0 recurrence variants`() {
        assertEquals("FREQ=DAILY;INTERVAL=2;COUNT=5", PimFormats.rrule("D2 #5"))
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=15", PimFormats.rrule("MD1 15 #0"))
        assertEquals("FREQ=MONTHLY;BYDAY=1MO", PimFormats.rrule("MP1 1+ MO #0"))
        assertEquals("FREQ=MONTHLY;BYDAY=-1FR", PimFormats.rrule("MP1 1- FR"))
        assertEquals("FREQ=YEARLY;BYMONTH=6", PimFormats.rrule("YM1 6 #0"))
        assertEquals("FREQ=WEEKLY;BYDAY=MO;UNTIL=20261231T000000Z", PimFormats.rrule("W1 MO 20261231T000000Z"))
        assertNull(PimFormats.rrule("nonsense"))
    }

    @Test fun `iCalendar files`() {
        val ics = crlf("""
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example//EN
            BEGIN:VEVENT
            UID:1@example
            DTSTART;TZID=Europe/Paris:20261110T140000
            DTEND;TZID=Europe/Paris:20261110T150000
            SUMMARY:Review
            END:VEVENT
            END:VCALENDAR
        """).toByteArray()
        assertEquals("ics", MessageFormats.detect(ics, "invite.ics")!!.first)
        val e = PimFormats.events(ics).events.single()
        assertEquals("Review", e.title); assertEquals("Europe/Paris", e.tz)
    }

    // ------------------------------------------------------------------ documents

    private fun zip(vararg entries: Pair<String, String>, storedMimetype: String? = null): ByteArray {
        val bo = java.io.ByteArrayOutputStream()
        ZipOutputStream(bo).use { z ->
            if (storedMimetype != null) {
                val b = storedMimetype.toByteArray()
                val e = ZipEntry("mimetype").apply { method = ZipEntry.STORED; size = b.size.toLong(); compressedSize = b.size.toLong(); crc = CRC32().also { it.update(b) }.value }
                z.putNextEntry(e); z.write(b); z.closeEntry()
            }
            for ((n, c) in entries) { z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() }
        }
        return bo.toByteArray()
    }

    private fun cfbWith(vararg streams: String) = CfbWriter.write(CfbWriter.Node("Root Entry").also { r -> streams.forEach { r.stream(it, ByteArray(64)) } })

    @Test fun `office open XML, OpenDocument and iWork packages`() {
        fun det(b: ByteArray, n: String) = DocumentFormats.detect(b, n)!!
        assertEquals("docx", det(zip("[Content_Types].xml" to "", "word/document.xml" to "<w:document/>"), "letter.bin").format)
        assertEquals(DocumentFormats.Kind.SPREADSHEET, det(zip("xl/workbook.xml" to ""), "x").kind)
        assertEquals("xlsb", det(zip("xl/workbook.bin" to ""), "x").format)
        assertEquals("pptx", det(zip("ppt/presentation.xml" to ""), "deck").format)
        assertEquals("ppsx", det(zip("ppt/presentation.xml" to ""), "deck.ppsx").format)
        assertEquals("odt", det(zip("content.xml" to "", storedMimetype = "application/vnd.oasis.opendocument.text"), "a.zip").format)
        assertEquals("ods", det(zip("content.xml" to "", storedMimetype = "application/vnd.oasis.opendocument.spreadsheet"), "a").format)
        assertEquals("odp", det(zip("content.xml" to "", storedMimetype = "application/vnd.oasis.opendocument.presentation"), "a").format)
        assertEquals("sxw", det(zip("content.xml" to "", storedMimetype = "application/vnd.sun.xml.writer"), "a").format)
        assertEquals("key", det(zip("Index/Document.iwa" to "", "Index/Slide-1.iwa" to ""), "talk").format)
        assertEquals("numbers", det(zip("Index/Document.iwa" to "", "Index/CalculationEngine.iwa" to ""), "sheet").format)
        assertEquals("pages", det(zip("Index/Document.iwa" to "", "Index/DocumentStylesheet.iwa" to ""), "essay").format)
        assertNull(DocumentFormats.detect(zip("random.txt" to "x"), "a.zip"))
    }

    @Test fun `binary Office and other compound documents`() {
        assertEquals("doc", DocumentFormats.detect(cfbWith("WordDocument", "1Table"), "x")!!.format)
        assertEquals("xls", DocumentFormats.detect(cfbWith("Workbook"), "x")!!.format)
        assertEquals("Excel 5.0/95 workbook (.xls)", DocumentFormats.detect(cfbWith("Book"), "x")!!.label)
        assertEquals("ppt", DocumentFormats.detect(cfbWith("PowerPoint Document", "Current User"), "x")!!.format)
        assertEquals("wps", DocumentFormats.detect(cfbWith("MatOST", "CONTENTS"), "x")!!.format)
        assertEquals("qpw", DocumentFormats.detect(cfbWith("NativeContent_MAIN"), "x")!!.format)
        assertEquals("sdw", DocumentFormats.detect(cfbWith("StarWriterDocument"), "x")!!.format)
        // An Outlook item is a compound file too, but a message, not a document.
        val msg = MsgBuilder().prop(Mapi.SUBJECT, PV.Str("hi")).build()
        assertNull(DocumentFormats.detect(msg, "x.msg"))
        assertEquals("message", MessageFormats.classify(msg, "x.msg").kind)
    }

    @Test fun `historical word processor and spreadsheet signatures`() {
        fun b(vararg v: Int) = v.map { it.toByte() }.toByteArray() + ByteArray(32)
        assertEquals("rtf", DocumentFormats.detect("{\\rtf1\\ansi hi}".toByteArray(), "x")!!.format)
        assertEquals("wpd", DocumentFormats.detect(b(0xFF, 0x57, 0x50, 0x43, 0x10, 0, 0, 0, 0x01, 0x0A), "x")!!.format)
        assertEquals("wk1", DocumentFormats.detect(b(0, 0, 2, 0, 6, 4, 6, 0), "x")!!.format)
        assertEquals("wks", DocumentFormats.detect(b(0, 0, 2, 0, 4, 4, 6, 0), "x")!!.format)
        assertEquals("wk3", DocumentFormats.detect(b(0, 0, 0x1A, 0, 0, 0x10, 4, 0), "x")!!.format)
        assertEquals("wk4", DocumentFormats.detect(b(0, 0, 0x1A, 0, 2, 0x10, 4, 0), "x")!!.format)
        assertEquals("xls", DocumentFormats.detect(b(0x09, 0x04, 0x06, 0x00, 0, 0, 0x10, 0), "x")!!.format)
        assertEquals("wri", DocumentFormats.detect(b(0x31, 0xBE, 0, 0, 0, 0xAB), "x.wri")!!.format)
        assertEquals("slk", DocumentFormats.detect("ID;PWXL;N;E\r\nC;Y1;X1;K1\r\nE\r\n".toByteArray(), "x")!!.format)
        assertEquals("hwp", DocumentFormats.detect("HWP Document File V3.00 \u001a\u0001\u0002\u0003\u0004\u0005".toByteArray(), "x")!!.format)
        assertEquals("fods", DocumentFormats.detect("<?xml version=\"1.0\"?><office:document office:mimetype=\"application/vnd.oasis.opendocument.spreadsheet\">".toByteArray(), "x")!!.format)
        assertEquals("xml-word", DocumentFormats.detect("<?xml version=\"1.0\"?><?mso-application progid=\"Word.Document\"?><w:wordDocument>".toByteArray(), "x")!!.format)
        assertEquals("xml-excel", DocumentFormats.detect("<?xml version=\"1.0\"?><Workbook xmlns=\"urn:schemas-microsoft-com:office:spreadsheet\">".toByteArray(), "x")!!.format)
        assertEquals(DocumentFormats.Kind.SPREADSHEET, DocumentFormats.detect("a,b\n1,2\n".toByteArray(), "data.csv")!!.kind)
        assertNull(DocumentFormats.detect(ByteArray(100) { 7 }, "mystery.bin"))
    }

    @Test fun `whole-file classification routes every kind`() {
        assertEquals("document", MessageFormats.classify(zip("word/document.xml" to ""), "a.docx").kind)
        assertEquals("presentation", MessageFormats.classify(cfbWith("PowerPoint Document"), "a.ppt").kind)
        assertEquals("contacts", MessageFormats.classify("BEGIN:VCARD\r\nFN:x\r\nEND:VCARD".toByteArray(), "x.vcf").kind)
        assertEquals("calendar", MessageFormats.classify("BEGIN:VCALENDAR\r\nEND:VCALENDAR".toByteArray(), "x.ics").kind)
        assertEquals("mailbox", MessageFormats.classify("!BDN....".toByteArray() + ByteArray(100), "a.pst").kind)
        assertEquals("other", MessageFormats.classify(ByteArray(100) { 7 }, "x.bin").kind)
        // A uuencoded Word document is unwrapped and recognised.
        val doc = zip("word/document.xml" to "")
        val uu = StringBuilder("begin 644 report.docx\n")
        doc.toList().chunked(45).forEach { chunk -> uu.append(String(binasciiUu(chunk.toByteArray()), Charsets.ISO_8859_1)) }
        uu.append("`\nend\n")
        val c = MessageFormats.classify(uu.toString().toByteArray(), "report.uue")
        assertEquals("document", c.kind); assertEquals("report.docx", c.name); assertEquals("uuencoded file", c.wrappedIn)
    }

    private fun binasciiUu(b: ByteArray): ByteArray {
        val o = java.io.ByteArrayOutputStream()
        o.write(32 + b.size)
        var i = 0
        while (i < b.size) {
            val x = (b[i].toInt() and 0xff); val y = if (i + 1 < b.size) b[i + 1].toInt() and 0xff else 0; val z = if (i + 2 < b.size) b[i + 2].toInt() and 0xff else 0
            for (v in intArrayOf(x shr 2, ((x and 3) shl 4) or (y shr 4), ((y and 15) shl 2) or (z shr 6), z and 63)) o.write(if (v == 0) '`'.code else 32 + v)
            i += 3
        }
        o.write('\n'.code)
        return o.toByteArray()
    }
}
