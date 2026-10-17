package info.socrtwo.quillpost.web

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import jakarta.mail.Flags
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import java.io.ByteArrayInputStream
import java.util.Date
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure helpers behind the command set. */
class CommandHelpersTest {
    @Test fun resentHeadersAreAComplete_block() {
        val h = Resent.headers("me@example.org", "Paul Demo", listOf("a@x.com", "b@y.com"), Date(0), "<id@example.org>").toMap()
        assertEquals("\"Paul Demo\" <me@example.org>", h["Resent-From"])
        assertEquals("a@x.com, b@y.com", h["Resent-To"])
        assertEquals("<id@example.org>", h["Resent-Message-ID"])
        assertTrue(h["Resent-Date"]!!.contains("1970"))
    }

    @Test fun eudoraRedirectKeepsTheAuthorByWayOfMe() {
        assertEquals("\"Jane Smith (by way of Paul)\" <jane@example.com>", Resent.byWayOf("Jane Smith <jane@example.com>", "Paul"))
        assertTrue(Resent.byWayOf("José <jose@example.com>", "Ünal").startsWith("=?UTF-8?Q?"))
    }

    @Test fun keywordsBecomeImapAtoms() {
        assertEquals("To_Do", Keywords.atom("To Do"))
        assertEquals("\$label1", Keywords.atom("\$label1"))
        assertEquals("\\Seen", Keywords.atom("\\Seen"))
        assertNull(Keywords.atom("\\Recent"))
        assertNull(Keywords.atom("  "))
        val f = Keywords.toFlags(listOf("\\Answered", "Work", "\$Important"))
        assertTrue(f.contains(Flags.Flag.ANSWERED))
        assertTrue(f.contains("Work"))
        assertTrue(f.contains("\$Important"))
    }

    @Test fun listUnsubscribeRfc8058() {
        val p = ListUnsubscribe.parse("<mailto:leave@lists.example.org?subject=stop>, <https://news.example.com/u?id=1>", "List-Unsubscribe=One-Click")
        assertTrue(p.oneClick)
        assertEquals(listOf("https://news.example.com/u?id=1"), p.https)
        assertEquals(1, p.mailto.size)
        assertEquals(Triple("leave@lists.example.org", "stop", "unsubscribe"), ListUnsubscribe.mailto(p.mailto[0]))
        assertFalse(ListUnsubscribe.parse("<https://x.example/u>", null).oneClick)
    }

    @Test fun urlGuardRefusesPrivateNetworks() {
        val r = runCatching { UrlGuard.check(java.net.URL("http://127.0.0.1/unsub")) }
        if (qpEnv("QUILLPOST_ALLOW_PRIVATE_URLS") == null) assertTrue(r.isFailure)
        assertTrue(runCatching { UrlGuard.check(java.net.URL("file:///etc/passwd")) }.isFailure)
    }

    @Test fun headerInspectorReadsAuthenticationAndHops() {
        val raw = """
            Received: from mx2.example.net (mx2.example.net [203.0.113.9]) by mx.me.test; Thu, 24 Sep 2026 09:12:30 +0000
            Received: from mail.example.com (mail.example.com [203.0.113.10]) by mx2.example.net; Thu, 24 Sep 2026 09:12:00 +0000
            Authentication-Results: mx.me.test; spf=pass smtp.mailfrom=bounce.example.com; dkim=pass header.d=example.com; dmarc=pass header.from=example.com
            DKIM-Signature: v=1; a=rsa-sha256; d=example.com; s=sel; b=abc
            Return-Path: <bounce@bounce.example.com>
            From: Jane <jane@example.com>
            Reply-To: Jane <jane@elsewhere.example>
            Subject: Hi

            body
        """.trimIndent().replace("\n", "\r\n").toByteArray()
        val r = HeaderInspector.inspect(HeaderInspector.parseHeaders(raw))
        assertEquals("example.com", r.fromDomain)
        assertEquals("pass", r.dmarc)
        assertEquals(true, r.spfAligned)
        assertEquals(true, r.dkimAligned)
        assertEquals(listOf("example.com"), r.dkimDomains)
        assertEquals(2, r.hops.size)
        assertEquals("203.0.113.10", r.hops[0].ip)
        assertEquals(30L, r.hops[1].delaySeconds)
        assertEquals(30L, r.totalDelaySeconds)
        assertTrue(r.warnings.any { it.contains("elsewhere.example") })
    }

    @Test fun dmarcIsComputedWhenNotReported() {
        val raw = "Authentication-Results: mx; spf=pass smtp.mailfrom=other.example; dkim=fail header.d=example.com\r\nFrom: a@example.com\r\n\r\n".toByteArray()
        val r = HeaderInspector.inspect(HeaderInspector.parseHeaders(raw))
        assertEquals(false, r.spfAligned)
        assertEquals(false, r.dkimAligned)
        assertTrue(r.dmarc.startsWith("fail"))
    }

    @Test fun stripAttachmentsLeavesANote() {
        val raw = ("From: a@x.com\r\nTo: b@y.com\r\nSubject: s\r\nMessage-ID: <keep@x>\r\nMIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=\"B\"\r\n\r\n" +
            "--B\r\nContent-Type: text/plain\r\n\r\nHello\r\n--B\r\nContent-Type: application/pdf; name=\"a.pdf\"\r\nContent-Disposition: attachment; filename=\"a.pdf\"\r\nContent-Transfer-Encoding: base64\r\n\r\nJVBERi0x\r\n--B--\r\n").toByteArray()
        val m = MimeMessage(Session.getInstance(Properties()), ByteArrayInputStream(raw))
        val removed = ArrayList<String>()
        assertTrue(stripAttachments(m, removed))
        assertEquals(listOf("a.pdf"), removed)
        m.saveChanges()
        val out = java.io.ByteArrayOutputStream().also { m.writeTo(it) }.toString()
        assertFalse(out.contains("JVBERi0x"))
        assertTrue(out.contains("a.pdf") && out.contains("was removed"))
    }
}

/** The mail operations against an in-memory IMAP/SMTP server. */
class CommandMailTest {
    private lateinit var green: GreenMail
    private lateinit var mail: MailSession
    private val session = Session.getInstance(Properties())

    private fun deliver(raw: String) = green.setUser("me@test.example", "me@test.example", "pw").deliver(MimeMessage(session, ByteArrayInputStream(raw.replace("\n", "\r\n").toByteArray())))

    @BeforeTest fun setUp() {
        green = GreenMail(arrayOf(ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_IMAP), ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTP)))
        green.start()
        green.setUser("me@test.example", "me@test.example", "pw")
        green.setUser("friend@test.example", "friend@test.example", "pw2")
        mail = MailSession(AccountDto("Me", "me@test.example", "127.0.0.1", green.imap.port, "IMAP", "NONE", "127.0.0.1", green.smtp.port, "NONE", "me@test.example", "pw"))
        deliver("From: Jane <jane@example.com>\nTo: me@test.example\nSubject: Hello\nMessage-ID: <m1@example.com>\nList-Id: Book club <club.lists.example.org>\nDate: Mon, 21 Sep 2026 10:00:00 +0000\n\nHi there")
        deliver("From: Bob <bob@example.com>\nTo: me@test.example\nSubject: Files\nMessage-ID: <m2@example.com>\nDate: Tue, 22 Sep 2026 10:00:00 +0000\nMIME-Version: 1.0\nContent-Type: multipart/mixed; boundary=\"B\"\n\n--B\nContent-Type: text/plain\n\nSee file\n--B\nContent-Type: application/octet-stream; name=\"big.bin\"\nContent-Disposition: attachment; filename=\"big.bin\"\nContent-Transfer-Encoding: base64\n\n" + "QUFB".repeat(3000) + "\n--B--\n")
    }

    @AfterTest fun tearDown() { mail.close(); green.stop() }

    private fun uidOf(subject: String) = mail.listMessages("INBOX", 50).second.first { it.subject == subject }.uid

    @Test fun bounceDeliversUnchangedWithResentHeaders() {
        mail.resend("INBOX", uidOf("Hello"), listOf("friend@test.example"), "bounce")
        assertTrue(green.waitForIncomingEmail(5000, 3))
        val got = green.getReceivedMessagesForDomain("test.example").first { it.getHeader("Resent-To") != null }
        assertEquals("<m1@example.com>", got.messageID)
        assertEquals("Jane <jane@example.com>", got.getHeader("From", null))
        assertTrue(got.getHeader("Resent-From", null).contains("me@test.example"))
        assertTrue(mail.listMessages("INBOX", 50).second.first { it.subject == "Hello" }.keywords.contains("\$Bounced"))
    }

    @Test fun redirectAddsByWayOf() {
        mail.resend("INBOX", uidOf("Hello"), listOf("friend@test.example"), "redirect")
        assertTrue(green.waitForIncomingEmail(5000, 3))
        val got = green.getReceivedMessagesForDomain("test.example").first { it.getHeader("Resent-To") != null }
        assertTrue(got.getHeader("From", null).contains("by way of Me"))
    }

    @Test fun tagsAndSystemFlags() {
        val uid = uidOf("Hello")
        mail.editFlags("INBOX", listOf(uid), listOf("\$label1", "To Do", "\\Answered"), emptyList())
        var s = mail.listMessages("INBOX", 50).second.first { it.uid == uid }
        assertTrue(s.keywords.containsAll(listOf("\$label1", "To_Do")))
        assertTrue(s.answered)
        mail.editFlags("INBOX", listOf(uid), emptyList(), listOf("\$label1", "\\Answered"))
        s = mail.listMessages("INBOX", 50).second.first { it.uid == uid }
        assertFalse(s.keywords.contains("\$label1"))
        assertFalse(s.answered)
        assertTrue(mail.folderKeywords("INBOX").second.contains("To_Do"))
    }

    @Test fun markForDeletionThenCompact() {
        val uid = uidOf("Hello")
        mail.editFlags("INBOX", listOf(uid), listOf("\\Deleted"), emptyList())
        assertTrue(mail.listMessages("INBOX", 50).second.first { it.uid == uid }.keywords.contains("\\Deleted"))
        assertEquals(1, mail.deletedSummaries("INBOX", 10).size)
        assertEquals(1, mail.compact("INBOX"))
        assertEquals(1, mail.listMessages("INBOX", 50).first)
    }

    @Test fun markReadByDateRange() {
        val since = java.time.ZonedDateTime.now().minusDays(1).toInstant().toEpochMilli()
        assertEquals(2, mail.markReadRange("INBOX", since, null))
        assertEquals(0, mail.folderStats("INBOX").unread)
    }

    @Test fun statisticsLargeMessagesAndLists() {
        val st = mail.folderStats("INBOX")
        assertEquals(2, st.total)
        assertTrue(st.sizeBytes > 9000)
        assertEquals(listOf("Files"), mail.largeMessages("INBOX", 5000, 10).map { it.subject })
        val lists = mail.mailingLists("INBOX", 50)
        assertEquals("club.lists.example.org", lists.single().listId)
        assertEquals("Book club", lists.single().name)
    }

    @Test fun stripAttachmentsAndEditSubjectKeepTheMessageId() {
        val r = mail.rewrite("INBOX", uidOf("Files"), "Files (renamed)", strip = true)
        assertEquals(listOf("big.bin"), r.removed)
        val s = mail.listMessages("INBOX", 50).second.first { it.subject == "Files (renamed)" }
        assertEquals("<m2@example.com>", s.messageId)
        assertTrue(s.size < 3000)
        assertEquals(2, mail.listMessages("INBOX", 50).first)
    }

    @Test fun copyFolderCopiesItsMessages() {
        val target = mail.createFolder("Projects", null)
        mail.copy("INBOX", listOf(uidOf("Hello")), target)
        val (path, n) = mail.copyFolder(target, "INBOX")
        assertEquals(1, n)
        assertEquals(1, mail.listMessages(path, 10).first)
    }

    @Test fun sendAgainFindsTheOriginalInABounce() {
        deliver("From: MAILER-DAEMON@test.example\nTo: me@test.example\nSubject: Undelivered Mail Returned to Sender\nMIME-Version: 1.0\nContent-Type: multipart/report; report-type=delivery-status; boundary=\"R\"\n\n--R\nContent-Type: text/plain\n\nCould not deliver.\n--R\nContent-Type: message/delivery-status\n\nStatus: 5.1.1\n--R\nContent-Type: message/rfc822\n\nFrom: me@test.example\nTo: nobody@nowhere.example\nSubject: Lunch?\n\nAre you free on Friday?\n--R--\n")
        val o = mail.bouncedOriginal("INBOX", uidOf("Undelivered Mail Returned to Sender"))
        assertTrue(o.found)
        assertEquals(listOf("nobody@nowhere.example"), o.to)
        assertEquals("Lunch?", o.subject)
        assertTrue(o.text.contains("Friday"))
        assertFalse(mail.bouncedOriginal("INBOX", uidOf("Hello")).found)
    }

    @Test fun headerListParsesTheStoredMessage() {
        val hs = mail.headerList("INBOX", uidOf("Hello"))
        assertNotNull(hs.firstOrNull { it.first == "List-Id" })
    }
}
