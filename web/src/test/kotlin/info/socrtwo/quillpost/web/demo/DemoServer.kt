package info.socrtwo.quillpost.web.demo

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import info.socrtwo.quillpost.web.module
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import java.io.ByteArrayInputStream
import java.util.Properties

/**
 * Starts an in-memory IMAP (3143) + SMTP (3025) server seeded with a realistic mix of mail —
 * genuine newsletters, personal mail, a PayPal phish from a look-alike domain, a "Norton"
 * invoice from Gmail, a blacklisted sender — then starts Anne's Post on port 8080.
 *
 * Sign in as demo@quillpost.test / demo. Use "Server settings" on the setup screen:
 * IMAP localhost:3143 (None), SMTP localhost:3025 (None).
 */
fun main() {
    val imapPort = System.getenv("DEMO_IMAP_PORT")?.toIntOrNull() ?: 3143
    val smtpPort = System.getenv("DEMO_SMTP_PORT")?.toIntOrNull() ?: 3025
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val green = GreenMail(arrayOf(ServerSetup(imapPort, "127.0.0.1", ServerSetup.PROTOCOL_IMAP), ServerSetup(smtpPort, "127.0.0.1", ServerSetup.PROTOCOL_SMTP)))
    green.start()
    val user = green.setUser("demo@quillpost.test", "demo@quillpost.test", "demo")
    val second = green.setUser("second@quillpost.test", "second@quillpost.test", "second")
    val session = Session.getInstance(Properties())
    for (raw in DemoMail.messages()) user.deliver(MimeMessage(session, ByteArrayInputStream(raw.toByteArray())))
    for (raw in DemoMail.secondMessages()) second.deliver(MimeMessage(session, ByteArrayInputStream(raw.toByteArray())))
    println("Demo IMAP on 127.0.0.1:$imapPort, SMTP on 127.0.0.1:$smtpPort — sign in as demo@quillpost.test / demo (second account: second@quillpost.test / second)")
    embeddedServer(Netty, port = port, host = "0.0.0.0") { module() }.start(wait = true)
}

object DemoMail {
    private fun msg(from: String, subject: String, body: String, extraHeaders: String = "", html: String? = null, date: String = "Thu, 24 Sep 2026 09:12:00 +0000", to: String = "demo@quillpost.test"): String {
        val boundary = "==qb-" + subject.hashCode().toString(16)
        val head = """
            |Return-Path: <${from.substringAfter('<').substringBefore('>')}>
            |Delivered-To: demo@quillpost.test
            |$extraHeaders
            |From: $from
            |To: $to
            |Subject: $subject
            |Date: $date
            |Message-ID: <${subject.hashCode().toString(16)}.${from.hashCode().toString(16)}@demo>
            |MIME-Version: 1.0
        """.trimMargin().lines().filter { it.isNotBlank() }.joinToString("\r\n")
        return if (html == null) {
            head + "\r\nContent-Type: text/plain; charset=utf-8\r\n\r\n" + body.replace("\n", "\r\n")
        } else {
            head + "\r\nContent-Type: multipart/alternative; boundary=\"$boundary\"\r\n\r\n" +
                "--$boundary\r\nContent-Type: text/plain; charset=utf-8\r\n\r\n" + body.replace("\n", "\r\n") + "\r\n" +
                "--$boundary\r\nContent-Type: text/html; charset=utf-8\r\n\r\n" + html.replace("\n", "\r\n") + "\r\n--$boundary--\r\n"
        }
    }

    private fun recv(ip: String, host: String, auth: String) = """
        |Received: from $host ($host [$ip]) by mx.quillpost.test with ESMTPS id abc123; Thu, 24 Sep 2026 09:12:01 +0000
        |Authentication-Results: mx.quillpost.test; $auth
    """.trimMargin()

    private val pngBase64 = "iVBORw0KGgoAAAANSUhEUgAAAAgAAAAICAYAAADED76LAAAAHklEQVQYV2P8z8Dwn4EIwDiqEAtgIF4hOaqQCAUAj0MJ/Tw3/0kAAAAASUVORK5CYII="

    private fun withAttachment(from: String, subject: String, text: String, html: String, date: String, extraHeaders: String): String {
        val outer = "==qb-mixed-" + subject.hashCode().toString(16)
        val inner = "==qb-alt-" + subject.hashCode().toString(16)
        val rel = "==qb-rel-" + subject.hashCode().toString(16)
        val head = listOf(
            "Return-Path: <${from.substringAfter('<').substringBefore('>')}>", extraHeaders,
            "From: $from", "To: demo@quillpost.test", "Subject: $subject", "Date: $date",
            "Message-ID: <att.${subject.hashCode().toString(16)}@demo>", "MIME-Version: 1.0",
            "Content-Type: multipart/mixed; boundary=\"$outer\""
        ).filter { it.isNotBlank() }.joinToString("\r\n") { it.trimEnd() }.replace("\n", "\r\n")
        return head + "\r\n\r\n" +
            "--$outer\r\nContent-Type: multipart/related; boundary=\"$rel\"\r\n\r\n" +
            "--$rel\r\nContent-Type: multipart/alternative; boundary=\"$inner\"\r\n\r\n" +
            "--$inner\r\nContent-Type: text/plain; charset=utf-8\r\n\r\n$text\r\n" +
            "--$inner\r\nContent-Type: text/html; charset=utf-8\r\n\r\n$html\r\n--$inner--\r\n" +
            "--$rel\r\nContent-Type: image/png\r\nContent-ID: <logo@demo>\r\nContent-Disposition: inline; filename=\"logo.png\"\r\nContent-Transfer-Encoding: base64\r\n\r\n$pngBase64\r\n--$rel--\r\n" +
            "--$outer\r\nContent-Type: application/pdf; name=\"minutes.pdf\"\r\nContent-Disposition: attachment; filename=\"minutes.pdf\"\r\nContent-Transfer-Encoding: base64\r\n\r\nJVBERi0xLjQKJcOkw7zDtsOfCjEgMCBvYmoKPDwvVHlwZS9DYXRhbG9nPj4KZW5kb2JqCnRyYWlsZXIKPDwvUm9vdCAxIDAgUj4+CiUlRU9G\r\n--$outer--\r\n"
    }

    private fun messageId(from: String, subject: String) = "<${subject.hashCode().toString(16)}.${from.hashCode().toString(16)}@demo>"

    private const val JANE = "Jane Smith <jane.smith@example.com>"
    private const val TOM = "Tom Rivera <tom@riveraconsulting.example>"

    /** A meeting request as Outlook and Google Calendar send it: text/plain + text/calendar; method=REQUEST. */
    private fun invitation(): String {
        val boundary = "==qb-invite"
        val ics = listOf(
            "BEGIN:VCALENDAR", "PRODID:-//Example Corp//Calendar 1.0//EN", "VERSION:2.0", "METHOD:REQUEST",
            "BEGIN:VTIMEZONE", "TZID:Eastern Standard Time",
            "BEGIN:STANDARD", "DTSTART:16011104T020000", "RRULE:FREQ=YEARLY;BYDAY=1SU;BYMONTH=11", "TZOFFSETFROM:-0400", "TZOFFSETTO:-0500", "END:STANDARD",
            "BEGIN:DAYLIGHT", "DTSTART:16010311T020000", "RRULE:FREQ=YEARLY;BYDAY=2SU;BYMONTH=3", "TZOFFSETFROM:-0500", "TZOFFSETTO:-0400", "END:DAYLIGHT",
            "END:VTIMEZONE",
            "BEGIN:VEVENT", "UID:board-meeting-2026@example.com", "SEQUENCE:0", "DTSTAMP:20260928T130000Z",
            "DTSTART;TZID=Eastern Standard Time:20261110T100000", "DTEND;TZID=Eastern Standard Time:20261110T113000",
            "RRULE:FREQ=MONTHLY;BYDAY=2TU;COUNT=12",
            "SUMMARY:Historical Society board meeting", "LOCATION:Library annex, room 2",
            "DESCRIPTION:Monthly board meeting. Agenda to follow.",
            "ORGANIZER;CN=Jane Smith:mailto:jane.smith@example.com",
            "ATTENDEE;CN=Paul;ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:demo@quillpost.test",
            "ATTENDEE;CN=Tom Rivera;ROLE=OPT-PARTICIPANT;PARTSTAT=ACCEPTED:mailto:tom@riveraconsulting.example",
            "END:VEVENT", "END:VCALENDAR"
        ).joinToString("\r\n")
        val head = listOf(
            "Return-Path: <jane.smith@example.com>", recv("203.0.113.10", "mail.example.com", "spf=pass; dkim=pass header.d=example.com; dmarc=pass"),
            "From: $JANE", "To: demo@quillpost.test", "Subject: Invitation: Historical Society board meeting",
            "Date: Mon, 28 Sep 2026 13:00:00 +0000", "Message-ID: <invite.board-meeting@demo>", "MIME-Version: 1.0",
            "Content-Type: multipart/alternative; boundary=\"$boundary\""
        ).joinToString("\r\n").replace("\r\n", "\n").replace("\n", "\r\n")
        return head + "\r\n\r\n" +
            "--$boundary\r\nContent-Type: text/plain; charset=utf-8\r\n\r\nYou are invited to the Historical Society board meeting, the second Tuesday of each month at 10:00 Eastern, starting 10 November 2026.\r\n" +
            "--$boundary\r\nContent-Type: text/calendar; charset=utf-8; method=REQUEST\r\n\r\n$ics\r\n--$boundary--\r\n"
    }

    fun secondMessages(): List<String> = listOf(
        msg("Genealogy Forum <digest@genforum.example>", "Weekly digest: Pruitt family queries", "Three new posts this week mention the Pruitt surname in Tennessee and Kentucky.\n\nRead them at https://genforum.example/pruitt",
            recv("203.0.113.70", "mail.genforum.example", "spf=pass; dkim=pass header.d=genforum.example; dmarc=pass"), date = "Tue, 29 Sep 2026 07:00:00 +0000", to = "second@quillpost.test"),
        msg(JANE, "Second-account test message", "This message lives only in the second demo account.\n\nJane",
            recv("203.0.113.10", "mail.example.com", "spf=pass; dkim=pass header.d=example.com; dmarc=pass"), date = "Mon, 28 Sep 2026 16:00:00 +0000", to = "second@quillpost.test")
    )

    fun messages(): List<String> = listOf(
        invitation(),
        msg(TOM, "Re: VBA macro for the quarterly report", "Paul,\n\nOne more fix: the macro now skips hidden sheets. Totals reconcile on my copy of the ledger.\n\nTom\n\n> Thanks Tom, I'll test it tonight.",
            recv("203.0.113.90", "mail.riveraconsulting.example", "spf=pass; dkim=pass header.d=riveraconsulting.example; dmarc=pass") +
                "\r\nIn-Reply-To: ${messageId(TOM, "VBA macro for the quarterly report")}\r\nReferences: ${messageId(TOM, "VBA macro for the quarterly report")}",
            date = "Sun, 27 Sep 2026 19:40:00 +0000"),
        msg("Margaret Ellis <margaret.ellis@example.org>", "Please confirm you received the bylaws draft", "Paul,\n\nCould you confirm you received the bylaws draft? I've asked for a read receipt so I know it arrived.\n\nMargaret",
            recv("203.0.113.15", "mail.example.org", "spf=pass; dkim=pass header.d=example.org; dmarc=pass") + "\r\nDisposition-Notification-To: margaret.ellis@example.org",
            date = "Sun, 27 Sep 2026 10:05:00 +0000"),
        msg("Dan Hughes <dan.hughes@example.net>", "Server room access changes tomorrow", "Paul,\n\nHeads up: badge access to the server room changes at 8am tomorrow. Pick up your new badge from the front desk before 10.\n\nDan",
            recv("203.0.113.33", "mail.example.net", "spf=pass; dkim=pass header.d=example.net; dmarc=pass") + "\r\nImportance: High\r\nX-Priority: 1 (Highest)",
            date = "Sat, 26 Sep 2026 17:30:00 +0000"),
        withAttachment("Historical Society <secretary@localhistory.example>", "Minutes and photos from the September meeting",
            "Dear members,\n\nThe minutes from September are attached. The photo from the archive visit is below.\n\nRegards,\nAnne",
            "<html><body style='font-family:Segoe UI,Arial'><p>Dear members,</p><p>The minutes from September are attached. The photo from the archive visit is below.</p><p><img src='cid:logo@demo' width='64' height='64' alt='society logo'> <img src='https://tracker.example.net/pixel.gif?id=42' width='1' height='1'> <img src='https://images.example.net/archive.jpg' width='320' alt='archive visit'></p><p>Regards,<br>Anne</p></body></html>",
            "Sat, 26 Sep 2026 08:05:00 +0000", recv("203.0.113.44", "mail.localhistory.example", "spf=pass; dkim=pass header.d=localhistory.example; dmarc=pass")),
        msg(JANE, "Re: Census record for great-grandfather", "Hi Paul,\n\nI found the 1910 census page you asked about — the surname is spelled \"Pruit\" there. Scan attached next time; the archive's scanner was down today.\n\nJane\n\n> Could you look for the 1910 census entry?",
            recv("203.0.113.10", "mail.example.com", "spf=pass smtp.mailfrom=example.com; dkim=pass header.d=example.com; dmarc=pass header.from=example.com"), date = "Fri, 25 Sep 2026 14:20:00 +0000"),
        msg("PayPal <service@paypal.com>", "Receipt for your payment to Ancestry.com", "Hello Paul,\n\nYou sent a payment of $24.99 USD to Ancestry.com. Transaction ID 7HK21.\n\nThanks for using PayPal.\nThe PayPal Team",
            recv("173.0.84.226", "mx1.phx.paypal.com", "spf=pass smtp.mailfrom=paypal.com; dkim=pass header.d=paypal.com; dmarc=pass (p=REJECT) header.from=paypal.com"), date = "Fri, 25 Sep 2026 11:02:00 +0000"),
        msg("PayPal Security <alerts@paypal-secure-center.com>", "URGENT: Your account has been limited", "Dear customer,\n\nWe detected unusual activity on your PayPal account. Your account has been limited until you verify your identity.\n\nVerify now: http://paypal-secure-center.com/verify?id=88213\n\nFailure to verify within 24 hours will result in permanent closure.\n\nPayPal Security Team",
            recv("198.51.100.77", "vps-77.cheaphost.example", "spf=fail smtp.mailfrom=paypal-secure-center.com; dkim=none; dmarc=fail header.from=paypal-secure-center.com") + "\r\nReply-To: paypal.resolution.desk@gmail.com",
            html = "<html><body style='font-family:Arial'><p>Dear customer,</p><p>We detected unusual activity on your PayPal account. Your account has been <b>limited</b> until you verify your identity.</p><p><a href='http://paypal-secure-center.com/verify?id=88213'>https://www.paypal.com/signin</a></p><p>Failure to verify within 24 hours will result in permanent closure.</p><p>PayPal Security Team</p></body></html>",
            date = "Fri, 25 Sep 2026 10:40:00 +0000"),
        msg("Norton LifeLock Billing <norton.billing.desk8842@gmail.com>", "Invoice #NL-33812: Your subscription has been renewed for $399.99", "Dear Customer,\n\nYour Norton 360 subscription has been renewed for 1 year. \$399.99 has been charged to your card.\n\nIf you did not authorize this transaction, call our helpline immediately at +1 (888) 555-0142 to get a refund.\n\nThank you,\nNorton Billing Department",
            recv("198.51.100.77", "vps-77.cheaphost.example", "spf=pass smtp.mailfrom=gmail.com; dkim=pass header.d=gmail.com; dmarc=pass header.from=gmail.com"), date = "Fri, 25 Sep 2026 08:15:00 +0000"),
        msg("Amazon.com <ship-confirm@amazon.com>", "Your Amazon.com order #112-4471203-8812 has shipped", "Hello Paul,\n\nYour package with \"Fountain pen ink, Diamine Oxblood\" is on its way and is expected Monday.\n\nTrack your package: https://www.amazon.com/progress-tracker/package\n\nAmazon.com",
            recv("54.240.9.12", "a9-12.smtp-out.amazonses.com", "spf=pass smtp.mailfrom=amazonses.com; dkim=pass header.d=amazon.com; dmarc=pass header.from=amazon.com"), date = "Thu, 24 Sep 2026 18:30:00 +0000"),
        msg("Typography Weekly <newsletter@mail.typographyweekly.example>", "Issue 214: Optical sizes, again", "This week: why optical sizes matter for small text, an interview with a punchcutter, and a roundup of new open-source serifs.\n\nRead online: https://typographyweekly.example/issues/214\n\nUnsubscribe: https://typographyweekly.example/unsub?u=42",
            recv("203.0.113.55", "mail.typographyweekly.example", "spf=pass; dkim=pass header.d=typographyweekly.example; dmarc=pass") + "\r\nList-Unsubscribe: <https://typographyweekly.example/unsub?u=42>\r\nPrecedence: bulk", date = "Thu, 24 Sep 2026 12:00:00 +0000"),
        msg("USPS <usps-delivery@track-parcel-status.info>", "USPS: Your package could not be delivered", "We attempted to deliver your package but the address was incomplete. Please confirm your address and pay the \$1.95 redelivery fee within 48 hours: http://198.51.100.23/usps/redeliver\n\nUnited States Postal Service",
            recv("198.51.100.23", "unknown", "spf=none; dkim=none; dmarc=none"), date = "Thu, 24 Sep 2026 07:45:00 +0000"),
        msg(TOM, "VBA macro for the quarterly report", "Paul,\n\nAttached is the revised macro — it now handles the merged cells in the summary sheet and no longer throws on empty rows. Let me know if the totals reconcile with the ledger.\n\nTom",
            recv("203.0.113.90", "mail.riveraconsulting.example", "spf=pass; dkim=pass header.d=riveraconsulting.example; dmarc=pass"), date = "Wed, 23 Sep 2026 16:05:00 +0000"),
        msg("Lucky Winner Dept <claims@intl-lottery-payout.biz>", "CONGRATULATIONS!!! You have won \$2,500,000.00", "Dear Beneficiary,\n\nYou have been selected as the lucky winner of the International Email Lottery. To claim your prize money, kindly reply with your full name, address, date of birth and bank details.\n\nMr. Ade Williams\nClaims Agent",
            recv("198.51.100.77", "vps-77.cheaphost.example", "spf=softfail; dkim=none; dmarc=none"), date = "Wed, 23 Sep 2026 03:12:00 +0000"),
        msg("Microsoft account team <account-security-noreply@accountprotection.microsoft.com>", "Microsoft account security code", "Your Microsoft account security code is 482913. If you didn't request this code, you can safely ignore this email.\n\nThanks,\nThe Microsoft account team",
            recv("40.107.8.100", "mail-eopbgr80100.outbound.protection.outlook.com", "spf=pass smtp.mailfrom=accountprotection.microsoft.com; dkim=pass header.d=microsoft.com; dmarc=pass header.from=microsoft.com"), date = "Tue, 22 Sep 2026 20:10:00 +0000"),
        msg("Chase <alerts@chase-verification-portal.com>", "Action required: verify your Chase account", "Dear Chase customer,\n\nYour online access has been suspended due to a failed login attempt. Sign in to restore access: http://chase-verification-portal.com/login\n\nChase Online Banking",
            recv("198.51.100.201", "srv1.chase-verification-portal.com", "spf=pass smtp.mailfrom=chase-verification-portal.com; dkim=pass header.d=chase-verification-portal.com; dmarc=none"), date = "Tue, 22 Sep 2026 09:33:00 +0000"),
        msg("Book club <bookclub@lists.example.org>", "October pick: The Name of the Rose", "Hi all,\n\nOctober's pick is The Name of the Rose. Margaret is hosting on the 14th at 7pm — bring a snack if you like.\n\nSee you there,\nDan",
            recv("203.0.113.12", "lists.example.org", "spf=pass; dkim=pass header.d=lists.example.org; dmarc=pass") + "\r\nList-Unsubscribe: <mailto:bookclub-leave@lists.example.org>\r\nPrecedence: list", date = "Mon, 21 Sep 2026 15:00:00 +0000")
    )
}
