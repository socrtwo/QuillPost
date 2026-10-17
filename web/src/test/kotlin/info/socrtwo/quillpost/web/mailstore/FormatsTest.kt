package info.socrtwo.quillpost.web.mailstore

import info.socrtwo.quillpost.web.MessageImport
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Content-based detection and the RTF helpers. */
class FormatsTest {
    private val tmp: File = Files.createTempDirectory("qp-formats").toFile().also { it.deleteOnExit() }
    private fun sniff(name: String, bytes: ByteArray) = Sniffer.sniffFile(File(tmp, name).also { it.writeBytes(bytes) }, name)
    private fun zip(name: String, entries: Map<String, String>): ByteArray {
        val f = File(tmp, name)
        ZipOutputStream(f.outputStream()).use { z ->
            for ((k, v) in entries) {
                val b = v.toByteArray()
                // OpenDocument stores "mimetype" first and uncompressed, as real files do.
                val e = ZipEntry(k).apply { if (k == "mimetype") { method = ZipEntry.STORED; size = b.size.toLong(); compressedSize = b.size.toLong(); crc = java.util.zip.CRC32().also { c -> c.update(b) }.value } }
                z.putNextEntry(e); z.write(b); z.closeEntry()
            }
        }
        return f.readBytes()
    }

    @Test
    fun detectsByContentNotExtension() {
        // Wrong or missing extensions do not matter.
        assertEquals("mbox", sniff("mail.dat", Fixtures.mboxrd().toByteArray()).format)
        assertEquals("eml", sniff("message.txt", Fixtures.msg("x").toByteArray()).format)
        assertEquals("oe4", sniff("x.bin", Fixtures.oe4()).format)
        assertEquals("dbx", sniff("x.bin", Fixtures.dbx(listOf(Fixtures.msg("a") to 0L))).format)
        assertEquals("tnef", sniff("winmail.dat", byteArrayOf(0x78, 0x9F.toByte(), 0x3E, 0x22, 0, 0)).format)
        assertEquals("emlx", sniff("1.bin", Fixtures.emlx(Fixtures.msg("x"), 1, 0)).format)
        val pst = File(tmp, "p.bin"); Fixtures.pst(pst, unicode = false, encode = false)
        assertEquals("ANSI", Sniffer.sniffFile(pst).variant)
        val ost = pst.readBytes().also { it[9] = 'O'.code.toByte() }
        assertEquals("ost", sniff("x.bin", ost).format)
        assertEquals("pegasus", sniff("folder.pmm", Fixtures.pegasus()).format)
        assertEquals("thebat", sniff("messages.tbb", Fixtures.theBat()).format)
    }

    @Test
    fun recognisesDocumentsSpreadsheetsAndPresentations() {
        assertEquals("document" to "doc", sniff("a.bin", Fixtures.cfb(listOf("WordDocument", "1Table"))).let { it.category to it.format })
        assertEquals("spreadsheet" to "xls", sniff("a.bin", Fixtures.cfb(listOf("Workbook"))).let { it.category to it.format })
        assertEquals("presentation" to "ppt", sniff("a.bin", Fixtures.cfb(listOf("PowerPoint Document"))).let { it.category to it.format })
        assertEquals("message" to "msg", sniff("a.bin", Fixtures.cfb(listOf("__substg1.0_0037001F", "__properties_version1.0"))).let { it.category to it.format })
        assertEquals("docx", sniff("a.zip", zip("a.zip", mapOf("[Content_Types].xml" to "<Types/>", "word/document.xml" to "<w/>"))).format)
        assertEquals("xlsx", sniff("a.zip", zip("b.zip", mapOf("[Content_Types].xml" to "<Types/>", "xl/workbook.xml" to "<w/>"))).format)
        assertEquals("pptx", sniff("a.zip", zip("c.zip", mapOf("[Content_Types].xml" to "<Types/>", "ppt/presentation.xml" to "<w/>"))).format)
        assertEquals("odt", sniff("a", zip("d.zip", mapOf("mimetype" to "application/vnd.oasis.opendocument.text", "content.xml" to "<x/>"))).format)
        assertEquals("ods", sniff("a", zip("e.zip", mapOf("mimetype" to "application/vnd.oasis.opendocument.spreadsheet"))).format)
        assertEquals("archive", sniff("a.zip", zip("f.zip", mapOf("Mail/cur/1:2,S" to Fixtures.msg("x")))).category)
        assertEquals("pdf", sniff("x", "%PDF-1.4\n".toByteArray()).format)
        assertEquals("rtf", sniff("x", "{\\rtf1\\ansi hi}".toByteArray()).format)
        assertEquals("wpd", sniff("x", byteArrayOf(0xFF.toByte(), 0x57, 0x50, 0x43, 16, 0, 0, 0, 1, 0x0A) + ByteArray(118)).format)
        assertEquals("wri", sniff("x", byteArrayOf(0x31, 0xBE.toByte(), 0, 0) + ByteArray(124)).format)
        assertEquals("wk1", sniff("x", byteArrayOf(0, 0, 2, 0, 6, 4, 6, 0) + ByteArray(120)).format)
        assertEquals("xml-excel", sniff("x.xml", "<?xml version=\"1.0\"?>\n<?mso-application progid=\"Excel.Sheet\"?><Workbook/>".toByteArray()).format)
        assertEquals("xml-word", sniff("x.xml", "<?xml version=\"1.0\"?>\n<?mso-application progid=\"Word.Document\"?><w:wordDocument/>".toByteArray()).format)
        assertEquals("contacts", sniff("x", "BEGIN:VCARD\r\nFN:A\r\nEND:VCARD\r\n".toByteArray()).category)
        assertEquals("calendar", sniff("x", "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n".toByteArray()).category)
        assertEquals("spreadsheet", sniff("data.csv", "a,b\n1,2\n".toByteArray()).category)
    }

    @Test
    fun explainsWhatCannotBeRead() {
        val nsf = sniff("x.bin", byteArrayOf(0x1A, 0, 0, 4, 0, 0) + ByteArray(100))
        assertEquals("nsf", nsf.format); assertFalse(nsf.supported); assertContains(nsf.note, "no public specification")
        val msf = sniff("Inbox.msf", "// <!-- <mdb:mork:z v=\"1.4\"/> -->\n".toByteArray())
        assertFalse(msf.supported); assertContains(msf.note, "same name")
        assertFalse(sniff("x.7z", byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)).supported)
        val cyclic = File(tmp, "c.pst"); Fixtures.pst(cyclic, unicode = true, encode = false)
        val b = cyclic.readBytes().also { it[513] = 2 }
        val ex = runCatching { MailStores.open(File(tmp, "cyc.pst").also { it.writeBytes(b) }) }.exceptionOrNull()
        assertContains(ex?.message.orEmpty(), "high")
    }

    @Test
    fun compressedRtfFixturesMatchTheSharedDecompressor() {
        // The PST fixtures compress RTF with Fixtures.lzfu; check it against the shared reader (fileformats/Rtf.kt).
        val rtf = "{\\rtf1\\ansi Hello {\\b world}\\par}"
        assertEquals(rtf, String(info.socrtwo.quillpost.web.fileformats.Rtf.decompress(Fixtures.lzfu(rtf)), Charsets.ISO_8859_1))
    }

    @Test
    fun messageImportReadsEmlEmlxAndOneMessageMbox() {
        val eml = MessageImport.parse(Fixtures.msg("Plain").toByteArray(), "a.eml")
        assertEquals("eml", eml.format); assertEquals("Plain", eml.subject)
        val emlx = MessageImport.parse(Fixtures.emlx(Fixtures.msg("Apple"), 1L or (1L shl 2), 1790241120), "1.emlx")
        assertEquals("emlx", emlx.format); assertEquals(MsgFlags(seen = true, answered = true), emlx.flags); assertEquals(1790241120000L, emlx.internalDate)
        val mbox = MessageImport.parse(("From a@b Thu Sep 24 09:12:00 2026\n" + Fixtures.msg("One", body = ">From x\n")).toByteArray())
        assertEquals("One", mbox.subject); assertContains(String(mbox.mime), "\nFrom x")
    }

    @Test
    fun imapUtf7FolderNames() {
        assertEquals("Entwürfe", MailStores.decodeImapUtf7("Entw&APw-rfe"))
        assertEquals("A&B", MailStores.decodeImapUtf7("A&-B"))
    }
}

/** Writes fixture files for the browser test (src/test/e2e/mailimport.mjs) when QUILLPOST_FIXTURE_OUT is set. */
class FixtureExport {
    @Test
    fun writeFixtures() {
        val out = System.getenv("QUILLPOST_FIXTURE_OUT")?.let { File(it) } ?: return
        out.mkdirs()
        Fixtures.pst(File(out, "fixture.pst"), unicode = true, encode = true)
        File(out, "Thunderbird Inbox").writeText(Fixtures.thunderbird())
        File(out, "takeout.mbox").writeText(Fixtures.takeout())
        File(out, "note.eml").writeText(Fixtures.msg("Opened from a file", body = "This message was opened from an .eml file.\n"))
        Fixtures.oeStore(File(out, "OE Store"))
        File(out, "letter.doc").writeBytes(Fixtures.cfb(listOf("WordDocument", "1Table")))
    }
}
