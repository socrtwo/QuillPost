package info.socrtwo.quillpost.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenedFilesTest {
    private val eml = "From: \"Ann\" <ann@example.com>\r\nTo: bob@example.com, \"Carol C\" <carol@example.com>\r\nSubject: Hi\r\nContent-Type: multipart/mixed; boundary=b\r\n\r\n--b\r\nContent-Type: text/plain\r\n\r\nHello\r\n--b\r\nContent-Type: text/vcard; name=card.vcf\r\n\r\nBEGIN:VCARD\r\nFN:Dee\r\nEMAIL:dee@example.com\r\nEND:VCARD\r\n--b--\r\n".toByteArray()

    @Test fun `opened files belong to the account that opened them`() {
        val e = OpenedFiles.open("a@example.com", "hi.eml", eml)
        assertNotNull(OpenedFiles.get(e.id, "a@example.com"))
        assertNull(OpenedFiles.get(e.id, "someone-else@example.com"))
    }

    @Test fun `a message response has addresses for replying and names for display, and attachment kinds`() {
        val r = OpenedFiles.response(OpenedFiles.open("a@example.com", "hi.eml", eml))
        assertEquals("message", r.kind)
        val m = r.message!!
        assertEquals(listOf("bob@example.com", "carol@example.com"), m.to)
        assertEquals(listOf("bob@example.com", "Carol C <carol@example.com>"), m.toDisplay)
        assertEquals("contacts", m.attachments.single().kind)
        assertEquals("message/rfc822", r.mimeType)
    }

    @Test fun `documents name their suite program, Windows Address Books explain themselves`() {
        val docx = java.io.ByteArrayOutputStream().also { java.util.zip.ZipOutputStream(it).use { z -> z.putNextEntry(java.util.zip.ZipEntry("word/document.xml")); z.write("<w/>".toByteArray()); z.closeEntry() } }.toByteArray()
        val r = OpenedFiles.response(OpenedFiles.open("a@example.com", "x.bin", docx))
        assertEquals("document", r.kind)
        assertEquals("Anne's Pen", r.app!!.label)
        assertEquals("io.github.socrtwo.quillpen", r.app!!.androidPackage)
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", r.mimeType)
        val wab = OpenedFiles.response(OpenedFiles.open("a@example.com", "Address.wab", ByteArray(64) { 1 }))
        assertEquals("wab", wab.format)
        assertTrue(wab.notes.any { it.contains("vCards") })
    }

    @Test fun `old files are dropped beyond the limit`() {
        val first = OpenedFiles.open("evict@example.com", "1.eml", eml)
        repeat(OpenedFiles.MAX_FILES + 2) { OpenedFiles.open("evict@example.com", "n.eml", eml) }
        assertNull(OpenedFiles.get(first.id, "evict@example.com"))
    }

    @Test fun `files handed over by the shell wait until the UI asks`() {
        OpenedFiles.enqueue("a.ics", "BEGIN:VCALENDAR\r\nEND:VCALENDAR".toByteArray())
        assertEquals(listOf("a.ics"), OpenedFiles.takePending().map { it.first })
        assertTrue(OpenedFiles.takePending().isEmpty())
    }
}
