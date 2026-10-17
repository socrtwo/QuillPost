package info.socrtwo.quillpost.web.mailstore

import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.internet.MimeMessage
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MailStoreTest {
    private val tmp: File = Files.createTempDirectory("qp-mailstore").toFile().also { it.deleteOnExit() }

    private fun file(name: String, bytes: ByteArray) = File(tmp, name).also { it.parentFile.mkdirs(); it.writeBytes(bytes) }
    private fun file(name: String, text: String) = file(name, text.toByteArray(Charsets.ISO_8859_1))
    private fun folder(s: MailStore, path: String): StoreFolder = s.root.walk().firstOrNull { it.first.joinToString("/") == path }?.second
        ?: error("No folder $path in ${s.root.walk().map { it.first.joinToString("/") }}")
    private fun mime(m: StoreMessage): MimeMessage = MimeMessage(localSession, m.raw().inputStream())
    private fun subjects(f: StoreFolder) = f.messages().map { mime(it).subject }.toList()
    private fun texts(p: Part, out: MutableList<Pair<String, String>> = ArrayList()): List<Pair<String, String>> {
        val c = p.content
        if (c is Multipart) for (i in 0 until c.count) texts(c.getBodyPart(i), out)
        else if (c is MimeMessage) { out += "message/rfc822" to (c.subject ?: ""); texts(c, out) }
        else out += p.contentType.substringBefore(';').lowercase() to (p.fileName ?: (c as? String) ?: "binary")
        return out
    }

    // ------------------------------------------------------------------------------ mbox

    @Test
    fun mboxrdUnquotesAndReadsStatus() {
        val f = file("rd.mbox", Fixtures.mboxrd())
        assertEquals("mbox", Sniffer.sniffFile(f).format)
        MailStores.open(f).use { s ->
            val inbox = s.root.children.single()
            assertEquals("rd", inbox.name)
            val msgs = inbox.messages().toList()
            assertEquals(2, msgs.size)
            val body = mime(msgs[0]).content as String
            assertContains(body, "\nFrom here on it was quoted")
            assertContains(body, "\n>From the archive")
            assertEquals(MsgFlags(), msgs[0].flags)
            assertEquals(MsgFlags(seen = true, answered = true, flagged = true), msgs[1].flags)
            assertEquals(Mbox.fromLineDate("From x Fri Sep 25 10:00:00 2026"), msgs[1].internalDate)
            assertEquals("mboxrd", s.detection.variant)
        }
    }

    @Test
    fun mboxcl2TrustsContentLength() {
        val f = file("cl2.mbox", Fixtures.mboxcl2())
        MailStores.open(f).use { s ->
            val msgs = s.root.children.single().messages().toList()
            assertEquals(listOf("CL one", "CL two"), msgs.map { mime(it).subject })
            assertContains(mime(msgs[0]).content as String, "From me to you")
            assertEquals("mboxcl2", s.detection.variant)
        }
    }

    @Test
    fun thunderbirdStatusAndTags() {
        val f = file("Inbox", Fixtures.thunderbird())
        MailStores.open(f).use { s ->
            val msgs = s.root.children.single().messages().toList()
            assertEquals(MsgFlags(seen = true, flagged = true), msgs[0].flags)
            assertEquals(listOf("\$label1", "projectx"), msgs[0].keywords)
            assertEquals(MsgFlags(seen = true, forwarded = true), msgs[1].flags)
            assertContains(s.detection.program, "Thunderbird")
        }
    }

    @Test
    fun gmailTakeoutBecomesLabelFolders() {
        val f = file("All mail Including Spam and Trash.mbox", Fixtures.takeout())
        MailStores.open(f).use { s ->
            assertContains(s.detection.label, "Gmail")
            assertEquals(listOf("Inbox", "Sent", "Work, 2026"), s.root.children.map { it.name })
            val inbox = folder(s, "Inbox").messages().single()
            assertFalse(inbox.flags.seen)
            assertEquals(listOf("\$Important"), inbox.keywords)
            assertTrue(folder(s, "Work, 2026").messages().single().flags.flagged)
        }
    }

    // ------------------------------------------------------------------------------ Eudora

    @Test
    fun eudoraTocStatusDeletedAndAttachments() {
        val mbx = Fixtures.eudora(File(tmp, "Eudora"))
        MailStores.open(mbx).use { s ->
            assertEquals("eudora", s.detection.format)
            val msgs = s.root.children.single().messages().toList()
            assertEquals(2, msgs.size, "the compacted-away third message is skipped")
            assertEquals(MsgFlags(seen = true), msgs[0].flags)
            assertEquals(MsgFlags(seen = true, answered = true), msgs[1].flags)
            val parts = texts(mime(msgs[1]))
            assertTrue(parts.any { it.second == "report.txt" }, "resolved attachment: $parts")
            assertTrue(msgs[1].notes.any { it.contains("missing.doc") })
            assertTrue(s.warnings.any { it.contains("deleted") })
        }
        // The whole Eudora folder opens too.
        MailStores.open(File(tmp, "Eudora")).use { s -> assertEquals(2, s.root.walk().sumOf { it.second.count }) }
    }

    // ------------------------------------------------------------------------------ Maildir / MH / Apple

    @Test
    fun maildirFlagsKeywordsAndSubfolders() {
        val dir = Fixtures.maildir(File(tmp, "Mail"))
        assertEquals("maildir", Sniffer.sniffDir(dir).format)
        MailStores.open(dir).use { s ->
            val top = s.root.children.single()
            assertEquals(3, top.count)
            val byS = top.messages().associateBy { mime(it).subject }
            assertEquals(MsgFlags(seen = true, answered = true), byS["Maildir seen replied"]!!.flags)
            assertTrue(byS["Maildir flagged keyword"]!!.flags.flagged)
            assertEquals(listOf("\$Work"), byS["Maildir flagged keyword"]!!.keywords)
            assertFalse(byS["Maildir new"]!!.flags.seen)
            assertEquals(listOf("Archive", "Entwürfe", "Sent"), top.children.map { it.name }.sorted())
            assertEquals(listOf("2020"), top.children.first { it.name == "Archive" }.children.map { it.name })
        }
        // A zip of the Maildir is unpacked by the importer; here the reader opens the folder inside.
        val zip = File(tmp, "mail.zip"); Fixtures.zipDir(dir, zip)
        assertEquals("archive", Sniffer.sniffFile(zip).category)
    }

    @Test
    fun mhSequences() {
        val dir = Fixtures.mh(File(tmp, "inbox"))
        MailStores.open(dir).use { s ->
            val msgs = s.root.children.single().messages().toList()
            assertEquals(listOf("MH one", "MH two", "MH three"), msgs.map { mime(it).subject })
            assertEquals(listOf(true, false, false), msgs.map { it.flags.seen })
            assertTrue(msgs[2].flags.flagged)
        }
    }

    @Test
    fun appleMailBundlesFlagsAndPartialAttachments() {
        val root = Fixtures.appleMail(File(tmp, "Mailboxes"))
        assertEquals("apple-mail", Sniffer.sniffDir(root).format)
        MailStores.open(root).use { s ->
            val inbox = folder(s, "Inbox")
            val msgs = inbox.messages().toList()
            assertEquals(3, msgs.size)
            assertEquals(MsgFlags(seen = true, flagged = true), msgs[0].flags)
            assertEquals(listOf("\$MailFlagBit1"), msgs[0].keywords)
            assertEquals(1790241120000L, msgs[0].internalDate)
            val partial = texts(mime(msgs[2]))
            assertTrue(partial.any { it.first == "image/png" && it.second == "pic.png" })
            assertEquals(4, (mime(msgs[2]).content as Multipart).getBodyPart(1).inputStream.readAllBytes().size)
            assertEquals(listOf("Apple nested"), subjects(folder(s, "Inbox/Projects")))
        }
        // A single .emlx is a message.
        assertEquals("message", Sniffer.sniffFile(File(root, "Inbox.mbox/1A2B/Data/Messages/1.emlx")).category)
    }

    // ------------------------------------------------------------------------------ Outlook Express / Pegasus / The Bat!

    @Test
    fun outlookExpressStoreWithFolderTree() {
        val dir = Fixtures.oeStore(File(tmp, "OE"))
        assertEquals("dbx", Sniffer.sniffFile(File(dir, "Inbox.dbx")).format)
        assertEquals("dbx-folders", Sniffer.sniffFile(File(dir, "Folders.dbx")).format)
        MailStores.open(dir).use { s ->
            val paths = s.root.walk().map { it.first.joinToString("/") }
            assertEquals(listOf("Local Folders", "Local Folders/Inbox", "Local Folders/Inbox/Clients", "Local Folders/Sent Items"), paths)
            val inbox = folder(s, "Local Folders/Inbox").messages().toList()
            assertEquals(listOf("OE inbox read", "OE inbox replied"), inbox.map { mime(it).subject })
            assertEquals(MsgFlags(seen = true), inbox[0].flags)
            assertEquals(MsgFlags(seen = true, answered = true), inbox[1].flags)
            assertEquals(1500, (mime(inbox[1]).content as String).length, "message spanning several 512-byte blocks")
            assertEquals(1790241120000L, inbox[0].internalDate)
        }
        // A single .dbx with a damaged tree is recovered by scanning.
        val bytes = File(dir, "Inbox.dbx").readBytes().also { PstWriter.le32(0).copyInto(it, 0xE4) }
        MailStores.open(file("broken.dbx", bytes)).use { s ->
            assertEquals(2, s.root.children.single().count)
            assertTrue(s.warnings.any { it.contains("scanning") })
        }
    }

    @Test
    fun oe4PegasusAndTheBat() {
        MailStores.open(file("Inbox.mbx", Fixtures.oe4())).use { s -> assertEquals(listOf("OE4 one", "OE4 two"), subjects(s.root.children.single())) }
        MailStores.open(file("folder1.pmm", Fixtures.pegasus())).use { s ->
            assertEquals("Project mail", s.root.children.single().name)
            assertEquals(listOf("Pegasus one", "Pegasus two"), subjects(s.root.children.single()))
        }
        MailStores.open(file("messages.tbb", Fixtures.theBat())).use { s -> assertEquals(listOf("Bat one", "Bat two"), subjects(s.root.children.single())) }
    }

    // ------------------------------------------------------------------------------ PST

    private fun checkPst(unicode: Boolean, encode: Boolean) {
        val f = File(tmp, "fixture-$unicode-$encode.pst")
        Fixtures.pst(f, unicode, encode)
        val det = Sniffer.sniffFile(f)
        assertEquals("pst", det.format)
        assertEquals(if (unicode) "Unicode" else "ANSI", det.variant)
        MailStores.open(f).use { s ->
            assertEquals("Fixture Data File", s.root.name)
            assertEquals(listOf("Inbox", "Inbox/Projects", "Sent Items", "Contacts", "Calendar", "Tasks"), s.root.walk().map { it.first.joinToString("/") })
            assertEquals(FolderKind.CONTACTS, folder(s, "Contacts").kind)

            val inbox = folder(s, "Inbox").messages().toList()
            assertEquals(2, inbox.size)
            // 1: transport headers kept, HTML body from a subnode, attachments, inline image and attached item.
            val q = mime(inbox[0])
            assertEquals("Quarterly figures", q.subject)
            assertEquals("<q3@example.net>", q.messageID)
            assertNotNull(q.getHeader("Received"))
            assertEquals(1790241120000L, inbox[0].internalDate)
            assertTrue(inbox[0].flags.seen)
            val parts = texts(q)
            assertTrue(parts.any { it.first == "text/html" && it.second.contains("<b>attached</b>") }, "$parts")
            assertTrue(parts.any { it.second == "figures.csv" })
            assertTrue(parts.any { it.first == "message/rfc822" && it.second == "Original request" })
            assertTrue(parts.any { it.second.contains("Please send the figures.") })
            assertContains(String(inbox[0].raw()), "multipart/related")
            // 2: encapsulated HTML recovered from compressed RTF; status and categories.
            val lunch = mime(inbox[1])
            val lunchParts = texts(lunch)
            val lunchHtml = lunchParts.first { it.first == "text/html" }.second
            assertContains(lunchHtml, "<html><body>Café menu<br>€ price</body></html>")
            assertEquals(MsgFlags(seen = true, answered = true, flagged = true), inbox[1].flags)
            assertEquals(listOf("Food", "Team lunch"), inbox[1].keywords)
            assertEquals("Dave <dave@example.com>", lunch.from.single().toString())
            assertEquals("Food, Team lunch", lunch.getHeader("Keywords", null))

            // Genuine RTF converted to HTML with its formatting.
            val plan = texts(mime(folder(s, "Inbox/Projects").messages().single()))
            val planHtml = plan.first { it.first == "text/html" }.second
            assertContains(planHtml, "text-align:center")
            assertTrue(Regex("font-weight:bold;?\">Plan<").containsMatchIn(planHtml), planHtml)
            assertContains(planHtml, "color:#ff0000")
            assertTrue(Regex("font-style:italic;?\">italic<").containsMatchIn(planHtml), planHtml)

            // Sent item: headers rebuilt from the recipient table, body from a data tree.
            val sent = mime(folder(s, "Sent Items").messages().single())
            assertEquals("Status report", sent.subject)
            assertEquals("Carol Sender <carol@example.net>", sent.getHeader("To", null))
            assertEquals("Dave <dave@example.com>", sent.getHeader("Cc", null))
            assertEquals("Hidden Helper <helper@example.com>", sent.getHeader("Bcc", null))
            assertEquals("<status@example.org>", sent.messageID)
            assertEquals("High", sent.getHeader("Importance", null))
            assertEquals(900, (sent.content as String).split("Report line.").size - 1)

            val card = assertNotNull(folder(s, "Contacts").pimItems().single().contact)
            assertEquals("Frank Contact", card.displayName); assertEquals("frank@example.com", card.emails.single().value); assertEquals("Acme", card.company)
            val event = folder(s, "Calendar").pimItems().single().text
            assertContains(event, "SUMMARY:Team sync"); assertContains(event, "LOCATION:Room 2"); assertContains(event, "RRULE:FREQ=WEEKLY;BYDAY=TU;COUNT=10")
            assertContains(event, "DTSTART:20260924T091200Z")
            val task = folder(s, "Tasks").pimItems().single().text
            assertContains(task, "SUMMARY:File expenses"); assertContains(task, "DUE;VALUE=DATE:20260927"); assertContains(task, "PRIORITY:1")
        }
    }

    @Test fun pstUnicode() = checkPst(unicode = true, encode = false)
    @Test fun pstUnicodePermuteEncoded() = checkPst(unicode = true, encode = true)
    @Test fun pstAnsi() = checkPst(unicode = false, encode = false)
    @Test fun pstAnsiPermuteEncoded() = checkPst(unicode = false, encode = true)

    @Test
    fun pstInsideAFolder() {
        val dir = File(tmp, "backup").apply { mkdirs() }
        Fixtures.pst(File(dir, "archive.pst"), unicode = true, encode = true)
        File(dir, "notes.txt").writeText("not mail")
        MailStores.open(dir).use { s -> assertTrue(s.root.walk().any { it.first.last() == "Projects" }) }
    }
}
