package info.socrtwo.quillpost.web.spam

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Legitimate mail whose links live on domains other than the sender's must not be flagged:
 * authenticated senders, organisation-owned secondary domains, mail-service click trackers and
 * link-protection wrappers. Real lures (a brand hostname shown, another domain opened, no
 * authentication) must still score.
 */
class LinkFalsePositiveTest {
    private val engine = SpamEngine(BlacklistChecker(FakeResolver(emptyMap())), BayesClassifier().apply { seed() })
    private val cfg = SpamConfig()

    private fun base() = MessageFacts(
        messageId = "<m>", fromName = "", fromAddress = "", subject = "", bodyText = "", toAddresses = listOf("me@example.org"), userAddresses = listOf("me@example.org"),
        receivedHeaders = listOf("from mail.sender.example (mail.sender.example [203.0.113.10]) by mx.example.org")
    )

    private fun codes(v: SpamVerdict) = v.reasons.filter { it.weight != 0 }.associate { it.code to it.weight }

    @Test
    fun `authenticated newsletter with tracked links and sign-in wording is clean`() {
        val f = base().copy(
            fromName = "Typography Weekly", fromAddress = "hello@typographyweekly.example", subject = "Issue 215",
            bodyHtml = """<p>Sign in to read the archive: <a href="https://click.list-manage.com/track/click?u=abc&id=1">typographyweekly.example/archive</a></p>
                          <p><a href="https://ct.sendgrid.net/ls/click?upn=xyz">Read this week's issue</a> · <a href="https://bit.ly/3abc">Share</a></p>
                          <p><a href="https://typographyweekly.example/unsubscribe">Unsubscribe</a></p>""",
            bodyText = "Sign in to read the archive: typographyweekly.example/archive https://click.list-manage.com/track/click?u=abc&id=1",
            authenticationResults = listOf("mx.example.org; spf=pass smtp.mailfrom=typographyweekly.example; dkim=pass header.d=typographyweekly.example; dmarc=pass header.from=typographyweekly.example"),
            listUnsubscribe = "<https://typographyweekly.example/unsubscribe>", precedence = "bulk"
        )
        val v = engine.analyze(f, cfg, emptyList())
        assertEquals(SpamLevel.CLEAN, v.level, "reasons=${v.reasons}")
        assertFalse("LINK_TEXT_MISMATCH" in codes(v), "tracked link must not count as a lure: ${codes(v)}")
        assertFalse("LOGIN_LINK_OFFSITE" in codes(v), "links on mail infrastructure are neutral: ${codes(v)}")
    }

    @Test
    fun `sender and links from different domains of the same organisation are not a mismatch`() {
        val f = base().copy(
            fromName = "Facebook", fromAddress = "notification@facebookmail.com", subject = "You have a new friend request",
            bodyHtml = """<p>Log in to see it: <a href="https://www.facebook.com/n/?friends">facebook.com/friends</a></p>""",
            bodyText = "Log in to see it: facebook.com/friends https://www.facebook.com/n/?friends",
            authenticationResults = listOf("mx; spf=pass; dkim=pass header.d=facebookmail.com; dmarc=pass header.from=facebookmail.com")
        )
        val v = engine.analyze(f, cfg, emptyList())
        assertEquals(SpamLevel.CLEAN, v.level, "reasons=${v.reasons}")
        assertFalse("LOGIN_LINK_OFFSITE" in codes(v))
        assertFalse("LINK_TEXT_MISMATCH" in codes(v))
        assertTrue(LinkInfrastructure.sameOrganisation("facebookmail.com", "facebook.com"))
        assertTrue(LinkInfrastructure.sameOrganisation("e.paypal.com", "paypal-communication.com"))
        assertTrue(LinkInfrastructure.sameOrganisation("accounts.google.com", "youtube.com"))
        assertFalse(LinkInfrastructure.sameOrganisation("paypal.com", "evil-links.example"))
    }

    @Test
    fun `unauthenticated sender with a brand lure still scores fully`() {
        val f = base().copy(
            fromName = "PayPal", fromAddress = "service@paypal-secure-center.com", subject = "Your account has been limited",
            bodyHtml = """<p>Sign in to restore access: <a href="http://evil-links.example/login">https://www.paypal.com/signin</a></p>""",
            bodyText = "Sign in to restore access: https://www.paypal.com/signin http://evil-links.example/login",
            authenticationResults = listOf("mx; spf=fail; dkim=none; dmarc=fail header.from=paypal-secure-center.com")
        )
        val v = engine.analyze(f, cfg, emptyList())
        val c = codes(v)
        assertEquals(25, c["LINK_TEXT_MISMATCH"], "a shown brand hostname opening elsewhere is the classic lure: $c")
        assertEquals(10, c["LOGIN_LINK_OFFSITE"], c.toString())
        assertEquals(SpamLevel.SPAM, v.level)
    }

    @Test
    fun `authenticated sender reduces link disagreement to a small residual`() {
        val f = base().copy(
            fromName = "Contoso Billing", fromAddress = "billing@contoso.example", subject = "Sign in to view your statement",
            bodyHtml = """<p>Sign in at <a href="https://portal.partnerbilling.example/login">https://www.chase.com/statements</a></p>""",
            bodyText = "Sign in at https://www.chase.com/statements https://portal.partnerbilling.example/login",
            authenticationResults = listOf("mx; spf=pass; dkim=pass header.d=contoso.example; dmarc=pass header.from=contoso.example")
        )
        val v = engine.analyze(f, cfg, emptyList())
        val c = codes(v)
        assertEquals(8, c["LINK_TEXT_MISMATCH"], c.toString())
        assertEquals(3, c["LOGIN_LINK_OFFSITE"], c.toString())
        assertEquals(SpamLevel.CLEAN, v.level, "reasons=${v.reasons}")
    }

    @Test
    fun `aligned dkim with spf counts as proven even without dmarc`() {
        val f = base().copy(
            fromName = "Club", fromAddress = "news@club.example", subject = "Log in for the minutes",
            bodyHtml = """<p><a href="https://cmail19.com/t/abc">club.example/minutes</a> — log in to read.</p>""",
            bodyText = "club.example/minutes https://cmail19.com/t/abc log in to read",
            authenticationResults = listOf("mx; spf=pass smtp.mailfrom=club.example; dkim=pass header.d=club.example")
        )
        val v = engine.analyze(f, cfg, emptyList())
        assertEquals(SpamLevel.CLEAN, v.level, "reasons=${v.reasons}")
        assertFalse("LINK_TEXT_MISMATCH" in codes(v))
    }

    @Test
    fun `weak bulk-mail traits alone are not suspicious but a second family of evidence is`() {
        val f = base().copy(
            fromName = "Deals", fromAddress = "deals@shop.example", subject = "YOUR REWARD IS READY!!!",
            toAddresses = listOf("undisclosed-recipients:;"),
            bodyHtml = """<p>The refund of your membership fee is ready. Click here: <a href="https://bit.ly/3zz">Get it</a></p>""",
            bodyText = "The refund of your membership fee is ready. Click here: https://bit.ly/3zz",
            authenticationResults = listOf("mx; spf=pass; dkim=pass header.d=shop.example; dmarc=pass header.from=shop.example")
        )
        val v = engine.analyze(f, cfg, emptyList())
        assertTrue(v.score >= cfg.suspiciousThreshold, "this pile of traits used to be suspicious: score=${v.score} ${codes(v)}")
        assertEquals(SpamLevel.CLEAN, v.level, "reasons=${v.reasons}")
        assertTrue(v.reasons.any { it.code == "WEAK_SIGNALS_ONLY" })
        // Same message, but SPF fails: the authentication family joins the content family.
        val failing = f.copy(authenticationResults = listOf("mx; spf=fail smtp.mailfrom=shop.example; dkim=none; dmarc=none"))
        val fv = engine.analyze(failing, cfg, emptyList())
        assertEquals(SpamLevel.SUSPICIOUS, fv.level, "reasons=${fv.reasons}")
    }

    @Test
    fun `authenticated mail from a listed shared sending address is not forced to junk`() {
        val listed = SpamEngine(BlacklistChecker(FakeResolver(mapOf("12.9.240.54.bl.spamcop.net" to "127.0.0.2"))), BayesClassifier().apply { seed() })
        val amazon = base().copy(
            fromName = "Amazon.com", fromAddress = "ship-confirm@amazon.com", subject = "Your Amazon.com order has shipped",
            bodyText = "Your package is on its way. Track your package: https://www.amazon.com/progress-tracker/package",
            receivedHeaders = listOf("from a9-12.smtp-out.amazonses.com (a9-12.smtp-out.amazonses.com [54.240.9.12]) by mx.example.org with ESMTPS"),
            authenticationResults = listOf("mx.example.org; spf=pass smtp.mailfrom=amazonses.com; dkim=pass header.d=amazon.com; dmarc=pass header.from=amazon.com")
        )
        val v = listed.analyze(amazon, cfg, emptyList())
        assertTrue(v.blacklistHits.any { it.list == "SpamCop" && it.subject == "54.240.9.12" }, "the listing is still reported")
        assertEquals(SpamLevel.CLEAN, v.level, "reasons=${v.reasons}")
        assertTrue(v.reasons.first { it.code == "BLACKLIST" }.weight <= 15)
        // The same listing without authentication still decides.
        val unproven = amazon.copy(fromAddress = "deals@unknown-shop.example", fromName = "Deals", authenticationResults = emptyList())
        assertEquals(SpamLevel.SPAM, listed.analyze(unproven, cfg, emptyList()).level)
    }

    @Test
    fun `link protection wrappers are unwrapped to the destination`() {
        assertEquals("https://www.example.com/path?a=1",
            LinkInfrastructure.unwrap("https://nam02.safelinks.protection.outlook.com/?url=https%3A%2F%2Fwww.example.com%2Fpath%3Fa%3D1&data=05%7C01&reserved=0"))
        assertEquals("https://www.example.com/path?a=1",
            LinkInfrastructure.unwrap("https://urldefense.proofpoint.com/v2/url?u=https-3A__www.example.com_path-3Fa-3D1&d=DwMFaQ&c=x"))
        assertEquals("https://www.example.com/path",
            LinkInfrastructure.unwrap("https://urldefense.com/v3/__https://www.example.com/path__;!!AbCd$"))
        assertEquals("https://www.example.com/",
            LinkInfrastructure.unwrap("https://www.google.com/url?q=https://www.example.com/&sa=D"))
        assertEquals("https://www.example.com/x",
            LinkInfrastructure.unwrap("https://l.facebook.com/l.php?u=https%3A%2F%2Fwww.example.com%2Fx&h=AT"))
        assertEquals("https://protect-us.mimecast.com/s/abc", LinkInfrastructure.unwrap("https://protect-us.mimecast.com/s/abc"))
        assertTrue(LinkInfrastructure.isMailInfrastructure("protect-us.mimecast.com"))
        assertTrue(LinkInfrastructure.isMailInfrastructure("click.e2ma.net"))
        assertFalse(LinkInfrastructure.isMailInfrastructure("www.paypal.com"))
        // A wrapped lure is still a lure once unwrapped.
        val f = base().copy(
            fromName = "PayPal", fromAddress = "service@paypal-secure-center.com", subject = "Limited",
            bodyHtml = """<p><a href="https://nam02.safelinks.protection.outlook.com/?url=http%3A%2F%2Fevil-links.example%2Flogin">https://www.paypal.com/signin</a></p>""",
            bodyText = "https://www.paypal.com/signin",
            authenticationResults = listOf("mx; spf=fail; dmarc=fail")
        )
        val v = engine.analyze(f, cfg, emptyList())
        assertEquals(25, codes(v)["LINK_TEXT_MISMATCH"], codes(v).toString())
        assertTrue("evil-links.example" in v.linkDomains, v.linkDomains.toString())
    }
}
