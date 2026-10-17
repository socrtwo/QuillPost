package info.socrtwo.quillpost.web.spam

import info.socrtwo.quillpost.web.SenderHistory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Link roles (footer plumbing is not judged, a sign-in link is judged first), the account's
 * own sender history, and Public Suffix List registrable domains.
 */
class SenderContextTest {
    private val engine = SpamEngine(BlacklistChecker(FakeResolver(emptyMap())), BayesClassifier().apply { seed() })
    private val cfg = SpamConfig()

    private fun base() = MessageFacts(
        messageId = "<m>", fromName = "", fromAddress = "", subject = "", bodyText = "", toAddresses = listOf("me@example.org"), userAddresses = listOf("me@example.org"),
        receivedHeaders = listOf("from mail.sender.example (mail.sender.example [203.0.113.10]) by mx.example.org")
    )

    private fun codes(v: SpamVerdict) = v.reasons.filter { it.weight != 0 }.associate { it.code to it.weight }

    // ---- item 4: link roles -------------------------------------------------------------------

    @Test
    fun `footer and social links are auxiliary and never judged`() {
        fun link(text: String, href: String) = HeaderParser.Link(href, text)
        assertTrue(ContentHeuristics.isAuxiliaryLink(link("Unsubscribe", "https://mail.example/u/1")))
        assertTrue(ContentHeuristics.isAuxiliaryLink(link("View in browser", "https://mail.example/x")))
        assertTrue(ContentHeuristics.isAuxiliaryLink(link("Privacy policy", "https://brand.example/legal/privacy")))
        assertTrue(ContentHeuristics.isAuxiliaryLink(link("", "https://sender.example/email/preferences?id=4")))
        assertTrue(ContentHeuristics.isAuxiliaryLink(link("", "https://www.facebook.com/brand")))
        assertTrue(ContentHeuristics.isAuxiliaryLink(link("Get it on Google Play", "https://play.google.com/store/apps/details?id=x")))
        assertFalse(ContentHeuristics.isAuxiliaryLink(link("Read the article", "https://brand.example/articles/1")))
        assertTrue(ContentHeuristics.isLoginLink(link("Sign in to your account", "https://x.example")))
        assertTrue(ContentHeuristics.isLoginLink(link("Verify your email", "https://x.example")))
        assertFalse(ContentHeuristics.isLoginLink(link("Read the article", "https://x.example")))
    }

    @Test
    fun `unauthenticated newsletter whose only foreign links are footer plumbing is not a mismatch`() {
        val f = base().copy(
            fromName = "Gazette", fromAddress = "editor@gazette.example", subject = "This week",
            bodyHtml = """<p><a href="https://gazette.example/issue/12">gazette.example/issue/12</a></p>
                          <p><a href="https://mailhost.example/privacy">gazette.example/privacy</a> · <a href="https://www.facebook.com/gazette">facebook.com/gazette</a></p>""",
            bodyText = "gazette.example/issue/12 https://gazette.example/issue/12",
            authenticationResults = listOf("mx; spf=none; dkim=none; dmarc=none")
        )
        val v = engine.analyze(f, cfg, emptyList())
        assertFalse("LINK_TEXT_MISMATCH" in codes(v), codes(v).toString())
        assertEquals(SpamLevel.CLEAN, v.level, "reasons=${v.reasons}")
    }

    @Test
    fun `an explicit sign-in link is judged on its own even when other links are fine`() {
        val f = base().copy(
            fromName = "Bank", fromAddress = "alerts@bank.example", subject = "Action required",
            bodyHtml = """<p><a href="https://bank.example/help">Help centre</a> <a href="https://bank.example/news">News</a>
                          <p><a href="http://bank-verify.example/login">Sign in to verify your account</a></p>""",
            bodyText = "Help centre News Sign in to verify your account http://bank-verify.example/login",
            authenticationResults = listOf("mx; spf=fail; dkim=none; dmarc=fail header.from=bank.example")
        )
        val v = engine.analyze(f, cfg, emptyList())
        assertEquals(10, codes(v)["LOGIN_LINK_OFFSITE"], codes(v).toString())
    }

    // ---- item 6: sender history ---------------------------------------------------------------

    @Test
    fun `history keys by organisation domain but by full address for free-mail and shared senders`() {
        assertEquals("contoso.example", SenderHistory.keyFor("Billing <billing@mail.contoso.example>"))
        assertEquals("paul@gmail.com", SenderHistory.keyFor("Paul <paul@gmail.com>"))
        assertEquals("list@bounce.sendgrid.net", SenderHistory.keyFor("<list@bounce.sendgrid.net>"))
    }

    @Test
    fun `replied-to or not-junk senders are known correspondents and familiar links need repetition`() {
        val h = SenderHistory()
        val addr = "news@club.example"
        assertFalse(h.reputation(addr).knownCorrespondent)
        h.seen(addr, listOf("cmail19.com", "club.example"), spam = false)
        h.seen(addr, listOf("cmail19.com"), spam = false)
        assertEquals(emptySet(), h.reputation(addr).familiarLinkApexes, "two sightings are not yet familiar")
        h.seen(addr, listOf("cmail19.com"), spam = false)
        assertEquals(setOf("cmail19.com"), h.reputation(addr).familiarLinkApexes)
        assertFalse(h.reputation(addr).knownCorrespondent, "three unopened messages do not make a correspondent")
        h.replied(addr)
        assertTrue(h.reputation(addr).knownCorrespondent)
        h.markedJunk(addr)
        assertFalse(h.reputation(addr).knownCorrespondent, "a junk mark revokes trust")
        assertEquals(emptySet(), h.reputation(addr).familiarLinkApexes)
        h.markedNotJunk(addr)
        assertTrue(h.reputation(addr).knownCorrespondent)
        assertTrue(h.dirty)
        h.markClean(); assertFalse(h.dirty)
        val other = SenderHistory()
        repeat(3) { other.opened("friend@contoso.example") }
        assertTrue(other.reputation("Friend <friend@contoso.example>").knownCorrespondent)
    }

    @Test
    fun `a known correspondent gets a history credit and relaxed link checks`() {
        val f = base().copy(
            fromName = "Contoso Billing", fromAddress = "billing@contoso.example", subject = "Sign in to view your statement",
            bodyHtml = """<p>Sign in at <a href="https://portal.partnerbilling.example/login">https://www.chase.com/statements</a></p>""",
            bodyText = "Sign in at https://www.chase.com/statements https://portal.partnerbilling.example/login",
            authenticationResults = listOf("mx; spf=fail; dkim=none; dmarc=none")
        )
        val stranger = engine.analyze(f, cfg, emptyList())
        assertTrue("LINK_TEXT_MISMATCH" in codes(stranger) && "LOGIN_LINK_OFFSITE" in codes(stranger), codes(stranger).toString())
        val known = engine.analyze(f, cfg, emptyList(), SenderReputation(messages = 12, opened = 9, replied = 2))
        val c = codes(known)
        assertEquals(-10, c["KNOWN_SENDER"], c.toString())
        assertFalse("LINK_TEXT_MISMATCH" in c, c.toString())
        assertFalse("LOGIN_LINK_OFFSITE" in c, c.toString())
        assertTrue(known.score < stranger.score)
    }

    @Test
    fun `familiar link domains count as the sender's own`() {
        val f = base().copy(
            fromName = "Club", fromAddress = "news@club.example", subject = "Minutes",
            bodyHtml = """<p><a href="https://clubhosting.example/minutes/9">club.example/minutes</a></p>""",
            bodyText = "club.example/minutes https://clubhosting.example/minutes/9",
            authenticationResults = listOf("mx; spf=none; dkim=none; dmarc=none")
        )
        assertTrue("LINK_TEXT_MISMATCH" in codes(engine.analyze(f, cfg, emptyList())))
        val familiar = engine.analyze(f, cfg, emptyList(), SenderReputation(messages = 4, familiarLinkApexes = setOf("clubhosting.example")))
        assertFalse("LINK_TEXT_MISMATCH" in codes(familiar), codes(familiar).toString())
    }

    // ---- item 8: Public Suffix List -----------------------------------------------------------

    @Test
    fun `public suffix list resolves registrable domains`() {
        assertTrue(PublicSuffixList.loadedFromResource, "psl/icann.dat must be on the class path")
        assertEquals("example.co.uk", PublicSuffixList.registrableDomain("mail.example.co.uk"))
        assertEquals("example.com", PublicSuffixList.registrableDomain("a.b.example.com"))
        assertEquals("example.com", PublicSuffixList.registrableDomain("Example.COM."))
        assertEquals("example.pvt.k12.ma.us", PublicSuffixList.registrableDomain("www.example.pvt.k12.ma.us"))
        assertEquals("a.b.ck", PublicSuffixList.registrableDomain("x.a.b.ck"), "wildcard rule *.ck makes b.ck public")
        assertEquals("www.ck", PublicSuffixList.registrableDomain("www.ck"), "exception rule !www.ck")
        assertEquals("example.co.jp", PublicSuffixList.registrableDomain("shop.example.co.jp"))
        assertEquals("co.uk", PublicSuffixList.registrableDomain("co.uk"), "a public suffix stands alone")
        assertEquals("example.unknowntld", PublicSuffixList.registrableDomain("mail.example.unknowntld"))
        assertEquals("203.0.113.10", HeaderParser.registrableDomain("203.0.113.10"))
        assertEquals("bbc.co.uk", HeaderParser.registrableDomain("news.bbc.co.uk"))
    }
}
