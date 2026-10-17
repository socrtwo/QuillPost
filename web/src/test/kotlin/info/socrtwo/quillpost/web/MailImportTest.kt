package info.socrtwo.quillpost.web

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import info.socrtwo.quillpost.web.mailstore.Fixtures
import info.socrtwo.quillpost.web.spam.BlacklistChecker
import jakarta.mail.Flags
import jakarta.mail.Folder
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The import pipeline end to end: upload → analyse → import into IMAP (GreenMail) or Local Archives. */
class MailImportTest {
    private lateinit var green: GreenMail
    private lateinit var ctx: AccountContext
    private val tmp: File = Files.createTempDirectory("qp-import").toFile()

    @BeforeTest fun setUp() {
        // Fixed ports in the range reserved for this work.
        green = GreenMail(arrayOf(ServerSetup(13166, "127.0.0.1", ServerSetup.PROTOCOL_IMAP), ServerSetup(13167, "127.0.0.1", ServerSetup.PROTOCOL_SMTP)))
        green.start()
        green.setUser("me@test.example", "me@test.example", "pw")
        val account = AccountDto("Me", "me@test.example", "127.0.0.1", 13166, "IMAP", "NONE", "127.0.0.1", 13167, "NONE", "me@test.example", "pw")
        ctx = AccountContext(account, DataStore(File(tmp, "data")), BlacklistChecker())
    }

    @AfterTest fun tearDown() { ctx.close(); green.stop(); tmp.deleteRecursively() }

    private fun upload(name: String, bytes: ByteArray): String = ctx.imports.receive(null, name, bytes.inputStream())

    private fun waitJob(id: String): ImportJobDto {
        repeat(300) {
            val j = ctx.imports.job(id)
            if (j.state != "running") return j
            Thread.sleep(50)
        }
        error("import did not finish")
    }

    private fun pstBytes(): ByteArray { val f = File(tmp, "f.pst"); Fixtures.pst(f, unicode = true, encode = true); return f.readBytes() }

    @Test
    fun pstIntoImapKeepsFlagsDatesAndTree() {
        val id = upload("outlook.pst", pstBytes())
        val a = ctx.imports.analyze(id)
        assertEquals("pst", a.detection.format)
        assertEquals(listOf("Inbox", "Inbox/Projects", "Sent Items", "Contacts", "Calendar", "Tasks"), a.folders.map { it.path })
        assertEquals(4, a.totalMessages)
        assertEquals(3, a.totalItems)
        val job = waitJob(ctx.imports.start(ImportRequest(id, destination = "imap", imapParent = "Imported")).id)
        assertEquals("done", job.state, job.toString())
        assertEquals(4 + 3, job.imported)
        assertEquals(1, job.contacts); assertEquals(1, job.events); assertEquals(1, job.tasks)

        val store = ctx.mail.connect()
        val d = store.defaultFolder.separator
        val inbox = store.getFolder("Imported${d}Inbox").also { it.open(Folder.READ_ONLY) }
        val msgs = inbox.messages.sortedBy { it.subject }
        assertEquals(listOf("Lunch menu", "Quarterly figures"), msgs.map { it.subject })
        val lunch = msgs[0]
        assertTrue(lunch.isSet(Flags.Flag.SEEN)); assertTrue(lunch.isSet(Flags.Flag.FLAGGED)); assertTrue(lunch.isSet(Flags.Flag.ANSWERED))
        assertTrue(lunch.flags.userFlags.toSet().containsAll(listOf("Food", "Team_lunch")), lunch.flags.userFlags.toList().toString())
        assertEquals(1790241180000L, lunch.receivedDate.time, "INTERNALDATE is the original delivery time")
        inbox.close(false)
        val projects = store.getFolder("Imported${d}Inbox${d}Projects").also { it.open(Folder.READ_ONLY) }
        assertEquals(1, projects.messageCount)
        projects.close(false)
        assertEquals(1, store.getFolder("Imported${d}Sent Items").also { it.open(Folder.READ_ONLY) }.messageCount)

        val pim = ctx.pim.data()
        assertTrue(pim.contacts.any { it.emails.any { e -> e.value == "frank@example.com" } })
        assertTrue(pim.events.any { it.title == "Team sync" })
        assertTrue(pim.tasks.any { it.title == "File expenses" && it.important })
    }

    @Test
    fun mboxIntoLocalArchivesBrowseSearchFlagsAndExport() = runBlocking {
        val id = upload("Inbox", Fixtures.thunderbird().toByteArray())
        val a = ctx.imports.analyze(id)
        assertEquals("mbox", a.detection.format)
        val job = waitJob(ctx.imports.start(ImportRequest(id, destination = "archive", archiveName = "Old Thunderbird")).id)
        assertEquals("done", job.state)
        assertEquals(2, job.imported)
        val folders = ctx.archives.folders()
        assertEquals(1, folders.size)
        val path = folders[0].path
        assertEquals(job.firstFolder, path)
        assertEquals("Old Thunderbird", ctx.archives.list().single().name)

        val list = ctx.listMessages(path, 50, 0)
        assertEquals(2, list.total)
        assertEquals(setOf("Mozilla read flagged", "Mozilla forwarded"), list.messages.map { it.subject }.toSet())
        val flagged = list.messages.first { it.subject == "Mozilla read flagged" }
        assertTrue(flagged.flagged && flagged.read)
        assertTrue(list.messages.first { it.subject == "Mozilla forwarded" }.forwarded)

        val detail = assertNotNull(ctx.message(path, flagged.uid))
        assertContains(detail.bodyText, "Hello from Mozilla read flagged")
        assertEquals("alice@example.com", detail.fromAddress)

        assertEquals(1, ctx.search(path, "forwarded", 10).messages.size)
        assertEquals(2, ctx.search(path, "hello from", 10).messages.size, "body search")

        ctx.flags(FlagRequest(listOf(MessageRef(path, flagged.uid)), read = false))
        assertTrue(!ctx.listMessages(path, 50, 0).messages.first { it.uid == flagged.uid }.read)

        val moved = ctx.move(MoveRequest(listOf(MessageRef(path, flagged.uid)), "INBOX"))
        assertEquals(0, moved.ok)
        assertContains(moved.errors.single(), "read-only")

        val out = ByteArrayOutputStream()
        assertEquals(2, ctx.exportMbox(path, out))
        assertTrue(String(out.toByteArray()).startsWith("From "))
        assertNotNull(ctx.rawMessage(path, flagged.uid))

        // Removing the archive removes its folders.
        assertTrue(ctx.archives.delete(ctx.archives.list().single().id))
        assertTrue(ctx.archives.folders().isEmpty())
    }

    @Test
    fun pstIntoArchiveKeepsHierarchyAndAttachments() = runBlocking {
        val id = upload("outlook.pst", pstBytes())
        ctx.imports.analyze(id)
        val job = waitJob(ctx.imports.start(ImportRequest(id, destination = "archive", includePim = false)).id)
        assertEquals("done", job.state)
        assertTrue(job.report.any { it.contains("not imported") })
        val folders = ctx.archives.folders()
        assertEquals(listOf("Inbox", "Projects", "Sent Items"), folders.map { it.name })
        assertEquals(listOf(1, 2, 1), folders.map { it.depth })
        val inbox = folders.first { it.name == "Inbox" }.path
        val q = ctx.listMessages(inbox, 50, 0).messages.first { it.subject == "Quarterly figures" }
        assertTrue(q.hasAttachments)
        val d = assertNotNull(ctx.message(inbox, q.uid))
        assertContains(d.bodyHtml.orEmpty(), "<b>attached</b>")
        val csv = d.attachments.first { it.fileName == "figures.csv" }
        val att = assertNotNull(ctx.attachment(inbox, q.uid, csv.index, null))
        assertEquals("q,amount\n3,100\n", String(att.bytes))
        assertNotNull(ctx.attachment(inbox, q.uid, null, "logo@x"))
    }

    @Test
    fun singleMessageOpensInOpenedFiles() = runBlocking {
        val id = upload("note.eml", Fixtures.msg("Opened message").toByteArray())
        val a = ctx.imports.analyze(id)
        assertEquals("message", a.detection.category)
        val ref = assertNotNull(a.opened)
        assertEquals("@archive/opened/files", ref.folder)
        assertEquals("Opened message", ctx.message(ref.folder, ref.uid)?.subject)
        assertNull(a.folders.firstOrNull())
    }

    @Test
    fun archivesAndDocumentsAreRecognised() {
        // A zip of a Maildir is unpacked; a .tar.gz of an mbox too.
        val md = Fixtures.maildir(File(tmp, "Maildir"))
        val zip = File(tmp, "maildir.zip"); Fixtures.zipDir(md, zip)
        val a = ctx.imports.analyze(upload("maildir.zip", zip.readBytes()))
        assertEquals("maildir", a.detection.format)
        assertEquals(6, a.totalMessages)
        val tgz = File(tmp, "takeout.tgz"); Fixtures.tgz("Takeout/Mail/All mail.mbox", Fixtures.takeout().toByteArray(), tgz)
        val b = ctx.imports.analyze(upload("takeout.tgz", tgz.readBytes()))
        assertContains(b.detection.label, "Gmail")
        // Office documents are described for hand-off, not imported.
        val doc = ctx.imports.analyze(upload("letter.bin", Fixtures.cfb(listOf("WordDocument", "1Table"))))
        assertEquals("document", doc.detection.category)
        // Lotus Notes is recognised and explained.
        val nsf = ctx.imports.analyze(upload("mail.nsf", byteArrayOf(0x1A, 0, 0, 4, 0, 0) + ByteArray(600)))
        assertEquals("nsf", nsf.detection.format)
        assertTrue(!nsf.detection.supported && nsf.detection.note.contains("undocumented"))
        assertFailsWith<IllegalArgumentException> { ctx.imports.start(ImportRequest(nsf.upload)) }
    }
}
