package info.socrtwo.quillpost.web.fileformats

import java.io.File
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The sample files in src/test/resources/fixtures/samples (used by the browser test
 * src/test/e2e/openfile.mjs and for trying "Open file" by hand). They are assembled here from
 * the specifications; run with REGENERATE_SAMPLES=1 to rewrite them. The test checks that every
 * committed sample still opens as the expected kind.
 */
class SampleFilesTest {
    private val dir = File("src/test/resources/fixtures/samples")

    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAIAAAD8GO2jAAAAo0lEQVR42u2WQQ6FMAhE5wZe1Ct6Hdd//VdujbUwwJCYaNJdYR5NaQfsv3/rwiMAy7rdLgFgJs1jUJFmMJCoGwyQ6ukwuGnRS7IAiSZxE6eAaL/7gHT5djok5RsKkJRviEBV/kznA7wTQDLGYKtNE4cY4wMvmWGQ3lD67BjnqX7XMUC74dQt0z9BxfT9Sy6OLc47kAxe45Z+dLzEtAy/7YAz4wBHbRkYQDAOfAAAAABJRU5ErkJggg==")
    private val date = 1_790_061_300_000L

    fun samples(): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        out["Welcome.eml"] = ("From: \"Anne's Office Team\" <team@quillpost.test>\r\nTo: demo@quillpost.test\r\nSubject: =?UTF-8?Q?Welcome_to_QuillPost_=E2=9C=93?=\r\n" +
            "Date: Tue, 22 Sep 2026 09:15:00 +0200\r\nMessage-ID: <welcome@quillpost.test>\r\nMIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=\"m\"\r\n\r\n" +
            "--m\r\nContent-Type: multipart/related; boundary=\"r\"\r\n\r\n--r\r\nContent-Type: text/html; charset=utf-8\r\n\r\n" +
            "<h2 style=\"color:#0f6cbd\">Hello from a saved .eml file</h2><p>This message has an <b>inline image</b>:</p><img src=\"cid:logo@quill\" width=\"64\" height=\"64\"><p>and a remote tracking pixel that stays blocked:</p><img src=\"https://tracker.example/pixel.gif\" width=\"1\" height=\"1\">\r\n" +
            "--r\r\nContent-Type: image/png\r\nContent-ID: <logo@quill>\r\nContent-Transfer-Encoding: base64\r\n\r\n${Base64.getMimeEncoder().encodeToString(png)}\r\n--r--\r\n" +
            "--m\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Disposition: attachment; filename*=UTF-8''notes%20%E2%9C%93.txt\r\n\r\nAttached notes.\r\n--m--\r\n").toByteArray()

        val rtf = ("{\\rtf1\\ansi\\ansicpg1252\\deff0{\\fonttbl{\\f0\\fswiss Calibri;}}{\\colortbl;\\red192\\green0\\blue0;}" +
            "\\pard\\sa200 Hello team,\\par\\pard\\sa200 The {\\b quarterly figures} are attached. Totals are {\\cf1 up 12%}.\\par" +
            "\\trowd\\cellx2500\\cellx5000\\clbrdrt\\pard\\intbl Region\\cell Total\\cell\\row\\trowd\\cellx2500\\cellx5000\\pard\\intbl North\\cell 1,204\\cell\\row" +
            "\\pard Regards,\\par Alice\\par}").toByteArray()
        out["Quarterly figures.msg"] = MsgBuilder()
            .prop(Mapi.MESSAGE_CLASS, PV.Str("IPM.Note")).prop(Mapi.SUBJECT, PV.Str("Quarterly figures"))
            .prop(Mapi.SENT_REPRESENTING_NAME, PV.Str("Alice Example")).prop(Mapi.SENT_REPRESENTING_SMTP, PV.Str("alice@example.com"))
            .prop(Mapi.SENDER_NAME, PV.Str("Alice Example")).prop(Mapi.SENDER_SMTP, PV.Str("alice@example.com"))
            .prop(Mapi.CLIENT_SUBMIT_TIME, PV.Time(date)).prop(Mapi.IMPORTANCE, PV.I32(2))
            .prop(Mapi.INTERNET_MESSAGE_ID, PV.Str("<quarterly@example.com>"))
            .prop(Mapi.BODY, PV.Str("Hello team,\r\nThe quarterly figures are attached.\r\nRegards,\r\nAlice"))
            .prop(Mapi.RTF_COMPRESSED, PV.Bin(RtfCompressor.compress(rtf)))
            .recipient(1, "Demo User", "demo@quillpost.test").recipient(2, "Bob Builder", "bob@example.net")
            .attach(mapOf(Mapi.ATTACH_LONG_FILENAME to PV.Str("figures.csv"), Mapi.ATTACH_MIME_TAG to PV.Str("text/csv"), Mapi.ATTACH_DATA to PV.Bin("Region,Total\r\nNorth,1204\r\n".toByteArray()), Mapi.ATTACH_METHOD to PV.I32(1)))
            .attach(mapOf(Mapi.DISPLAY_NAME to PV.Str("Earlier thread")), MsgBuilder().prop(Mapi.SUBJECT, PV.Str("Earlier thread")).prop(Mapi.SENDER_NAME, PV.Str("Bob Builder"))
                .prop(Mapi.SENDER_SMTP, PV.Str("bob@example.net")).prop(Mapi.BODY, PV.Str("Can you send the figures?")).prop(Mapi.CLIENT_SUBMIT_TIME, PV.Time(date - 86_400_000)))
            .build()

        out["Weekly status.oft"] = MsgBuilder().prop(Mapi.MESSAGE_CLASS, PV.Str("IPM.Note")).prop(Mapi.SUBJECT, PV.Str("Weekly status"))
            .prop(Mapi.BODY_HTML, PV.Bin("<html><body><p><b>Done:</b></p><ul><li></li></ul><p><b>Next:</b></p><ul><li></li></ul></body></html>".toByteArray())).build()

        val tnefRtf = "{\\rtf1\\ansi\\ansicpg1252\\deff0{\\fonttbl{\\f0 Calibri;}}\\pard Please find the {\\b budget} attached.\\par}".toByteArray()
        out["winmail.dat"] = TnefBuilder()
            .attr(1, 0x00089006, byteArrayOf(0, 0, 1, 0)).attr(1, 0x00069007, byteArrayOf(0xE4.toByte(), 0x04, 0, 0, 0, 0, 0, 0))
            .attr(1, 0x00078008, "IPM.Microsoft Mail.Note\u0000".toByteArray())
            .attr(1, 0x00038005, TnefBuilder.date(2026, 9, 21, 8, 30, 0))
            .attr(1, 0x00069003, TnefBuilder.propList(listOf(Triple(Mapi.SUBJECT, PV.Str("Budget 2027"), null), Triple(Mapi.RTF_COMPRESSED, PV.Bin(RtfCompressor.compress(tnefRtf)), null))))
            .attr(2, 0x00069002, ByteArray(14)).attr(2, 0x00018010, "BUDGET~1.CSV\u0000".toByteArray()).attr(2, 0x0006800F, "Item,Amount\r\nPaper,120\r\n".toByteArray())
            .attr(2, 0x00069005, TnefBuilder.propList(listOf(Triple(Mapi.ATTACH_LONG_FILENAME, PV.Str("Budget 2027.csv"), null))))
            .bytes()

        out["Ada Lovelace.vcf"] = ("BEGIN:VCARD\r\nVERSION:2.1\r\nN:Lovelace;Ada\r\nFN:Ada Lovelace\r\nORG:Analytical Engines Ltd\r\nTITLE:Programmer\r\nTEL;WORK;VOICE:+44 20 7946 0000\r\n" +
            "TEL;CELL:+44 7700 900000\r\nEMAIL;PREF;INTERNET:ada@example.org\r\nADR;WORK;ENCODING=QUOTED-PRINTABLE:;;12 St James=E2=80=99s Square;London;;SW1Y 4JH;United Kingdom\r\nEND:VCARD\r\n" +
            "BEGIN:VCARD\r\nVERSION:3.0\r\nN:Babbage;Charles;;;\r\nFN:Charles Babbage\r\nitem1.EMAIL;type=INTERNET;type=pref:charles@example.org\r\nEND:VCARD\r\n").toByteArray()

        out["Design review.ics"] = ("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//QuillPost//Samples//EN\r\nBEGIN:VEVENT\r\nUID:design-review@quillpost.test\r\nDTSTAMP:20260920T120000Z\r\n" +
            "DTSTART;TZID=Europe/London:20261103T100000\r\nDTEND;TZID=Europe/London:20261103T113000\r\nSUMMARY:Design review\r\nLOCATION:Room 4\r\nRRULE:FREQ=WEEKLY;COUNT=4\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n").toByteArray()

        out["Old phone.vcs"] = ("BEGIN:VCALENDAR\r\nVERSION:1.0\r\nBEGIN:VEVENT\r\nDTSTART:20261110T090000Z\r\nDTEND:20261110T093000Z\r\nSUMMARY;ENCODING=QUOTED-PRINTABLE:Stand=2Dup\r\nRRULE:W1 MO TU WE TH FR #10\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n").toByteArray()

        // A small Word document (Office Open XML), enough for the format to be recognised and opened.
        out["Report.docx"] = java.io.ByteArrayOutputStream().also { bo ->
            ZipOutputStream(bo).use { z ->
                fun put(n: String, s: String) { z.putNextEntry(ZipEntry(n)); z.write(s.toByteArray()); z.closeEntry() }
                put("[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>")
                put("_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>")
                put("word/document.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>Quarterly report</w:t></w:r></w:p></w:body></w:document>")
            }
        }.toByteArray()

        // An Outlook item saved without an extension: recognised by content alone.
        out["no-extension-message"] = out.getValue("Quarterly figures.msg")
        return out
    }

    @Test fun `committed samples open as the expected kind`() {
        val s = samples()
        if (System.getenv("REGENERATE_SAMPLES") == "1" || !dir.isDirectory) { dir.mkdirs(); for ((n, b) in s) File(dir, n).writeBytes(b) }
        val expect = mapOf("Welcome.eml" to "message", "Quarterly figures.msg" to "message", "Weekly status.oft" to "message", "winmail.dat" to "message",
            "Ada Lovelace.vcf" to "contacts", "Design review.ics" to "calendar", "Old phone.vcs" to "calendar", "Report.docx" to "document", "no-extension-message" to "message")
        for ((n, kind) in expect) {
            val f = File(dir, n)
            assertTrue(f.isFile, "missing sample $n (run with REGENERATE_SAMPLES=1)")
            assertEquals(kind, MessageFormats.classify(f.readBytes(), n).kind, n)
        }
        assertEquals(2, PimFormats.contacts(File(dir, "Ada Lovelace.vcf").readBytes(), "vcf").size)
        assertTrue(MessageFormats.parse(File(dir, "Weekly status.oft").readBytes(), "Weekly status.oft").template)
        assertEquals("Budget 2027.csv", MessageFormats.parse(File(dir, "winmail.dat").readBytes()).attachments.single().fileName)
    }
}
