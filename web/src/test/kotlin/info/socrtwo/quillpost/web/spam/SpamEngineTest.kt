package info.socrtwo.quillpost.web.spam

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DNS stub: any query name containing a listed key answers with the given code. */
class FakeResolver(private val listed: Map<String, String>) : DnsResolver {
    val queries = mutableListOf<String>()
    override fun lookupA(name: String, timeoutMs: Int): DnsResult {
        queries += name
        for ((k, v) in listed) if (name.startsWith(k)) return DnsResult.Found(listOf(v))
        return DnsResult.NotFound
    }
}

class HeaderParserTest {
    @Test
    fun `extracts public ips from received headers nearest first`() {
        val received = listOf(
            "from mx.example.net (mx.example.net [203.0.113.10]) by mail.mine.org with ESMTPS id x; Mon, 1 Jan 2024 10:00:00 +0000",
            "from [10.0.0.5] (helo=laptop) by mx.example.net with esmtpa",
            "from spammer.biz (unknown [198.51.100.77]) by relay.example.net"
        )
        assertEquals(listOf("203.0.113.10", "198.51.100.77"), HeaderParser.extractReceivedIps(received))
    }

    @Test
    fun `ignores the receiving host in the by clause`() {
        val r = listOf("from a.b.c (a.b.c [198.51.100.1]) by inbound.provider.com ([203.0.113.5]) with ESMTP")
        assertEquals(listOf("198.51.100.1"), HeaderParser.extractReceivedIps(r))
    }

    @Test
    fun `parses authentication results`() {
        val a = HeaderParser.parseAuthenticationResults(listOf(
            "mx.google.com; dkim=pass header.i=@paypal.com header.s=pp-dkim1; spf=pass (google.com: domain of x@paypal.com designates 1.2.3.4 as permitted sender) smtp.mailfrom=x@paypal.com; dmarc=pass (p=REJECT sp=REJECT dis=NONE) header.from=paypal.com"
        ))
        assertEquals("pass", a.spf); assertEquals("pass", a.dkim); assertEquals("pass", a.dmarc); assertEquals("paypal.com", a.dkimDomain)
        val f = HeaderParser.parseAuthenticationResults(listOf("mx; spf=fail smtp.mailfrom=evil.com; dmarc=fail header.from=paypal.com"))
        assertEquals("fail", f.dmarc)
        assertTrue(f.anyFailed)
    }

    @Test
    fun `registrable domains`() {
        assertEquals("paypal.com", HeaderParser.registrableDomain("mail.paypal.com"))
        assertEquals("bbc.co.uk", HeaderParser.registrableDomain("news.bbc.co.uk"))
        assertEquals("example.com", HeaderParser.registrableDomain("example.com"))
    }

    @Test
    fun `extracts links and hosts`() {
        val links = HeaderParser.extractLinks("""<a href="http://evil.example/login">https://www.paypal.com/</a> <img src=x>""", "see https://bit.ly/abc.")
        assertEquals(2, links.size)
        assertEquals("evil.example", HeaderParser.hostOf(links[0].href))
        assertEquals("https://www.paypal.com/", links[0].text)
        assertEquals("https://bit.ly/abc", links[1].href)
    }

    @Test
    fun `ipv6 reversal`() {
        assertEquals("2001:0db8:0000:0000:0000:0000:0000:0001", HeaderParser.expandIpv6("2001:db8::1"))
        assertEquals("1.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.8.b.d.0.1.0.0.2", HeaderParser.reverseIpv6("2001:db8::1"))
    }

    @Test
    fun `html to text strips tags and decodes entities`() {
        assertEquals("Hello & welcome\n\nBye", HeaderParser.htmlToText("<style>p{}</style><p>Hello &amp; welcome</p><script>x()</script><p>Bye</p>"))
    }
}

class BrandDetectorTest {
    private fun facts(fromName: String, fromAddress: String, subject: String = "", body: String = "") =
        MessageFacts(messageId = "<x>", fromName = fromName, fromAddress = fromAddress, subject = subject, bodyText = body)

    @Test
    fun `genuine brand domain is verified`() {
        val b = BrandDetector.analyze(facts("PayPal", "service@paypal.com", "Your receipt"))
        assertEquals("PayPal", b.claimedBrand); assertTrue(b.verified); assertFalse(b.mismatch)
    }

    @Test
    fun `brand in display name from unrelated domain is a mismatch`() {
        val b = BrandDetector.analyze(facts("PayPal Service", "alerts@secure-mail-update.info", "Account limited"))
        assertEquals("PayPal", b.claimedBrand); assertTrue(b.mismatch); assertTrue(b.knownBrand); assertFalse(b.verified)
    }

    @Test
    fun `brand from a freemail address`() {
        val b = BrandDetector.analyze(facts("Amazon Customer Service", "amzn.help.desk@gmail.com", "Order problem"))
        assertEquals("Amazon", b.claimedBrand); assertTrue(b.mismatch); assertTrue(b.freemailSender)
    }

    @Test
    fun `lookalike domains are caught`() {
        assertEquals("PayPal", BrandDetector.lookalike("paypa1.com")?.first?.name)
        assertEquals("Amazon", BrandDetector.lookalike("arnazon.com")?.first?.name)
        assertEquals("PayPal", BrandDetector.lookalike("secure-paypal-login.net")?.first?.name)
        assertEquals("PayPal", BrandDetector.lookalike("paypal.com.verify-account.ru")?.first?.name)
        assertEquals("Wells Fargo", BrandDetector.lookalike("wellsfargo-alerts.com")?.first?.name)
        assertEquals("Chase", BrandDetector.lookalike("chase-verification-portal.com")?.first?.name)
        assertEquals("Netflix", BrandDetector.lookalike("netflixbilling.com")?.first?.name)
        assertEquals("Amazon", BrandDetector.lookalike("amazon.xyz")?.first?.name)
        // Unrelated domains that merely contain or resemble a brand label must be left alone.
        assertNull(BrandDetector.lookalike("mail.typographyweekly.example"))
        assertNull(BrandDetector.lookalike("purchase-orders.com"))
        assertNull(BrandDetector.lookalike("chasm.org"))
        assertNull(BrandDetector.lookalike("united-way.org"))
        assertNull(BrandDetector.lookalike("start-ups.com"))
        assertNull(BrandDetector.lookalike("lists.example.org"))
        assertNull(BrandDetector.lookalike("paypal.com"))
        assertNull(BrandDetector.lookalike("mail.paypal.com"))
        assertNull(BrandDetector.lookalike("example.org"))
        val b = BrandDetector.analyze(facts("Billing", "no-reply@paypa1.com", "Invoice"))
        assertTrue(b.mismatch); assertTrue(b.lookalike); assertEquals("PayPal", b.claimedBrand)
    }

    @Test
    fun `generic organisation name not in domain`() {
        val b = BrandDetector.analyze(facts("Acme Billing Department", "x@randomhost.xyz"))
        assertEquals("Acme Billing Department", b.claimedBrand); assertTrue(b.mismatch); assertFalse(b.knownBrand)
        val ok = BrandDetector.analyze(facts("Acme Billing Department", "billing@acme.com"))
        assertFalse(ok.mismatch)
    }

    @Test
    fun `personal names and mere mentions are not claims`() {
        val p = BrandDetector.analyze(facts("Jane Smith", "jane@example.org", "Lunch?", "I ordered it on Amazon yesterday"))
        assertFalse(p.mismatch)
        assertNull(p.claimedBrand)
    }

    @Test
    fun `body signature claims count`() {
        val b = BrandDetector.analyze(facts("Notifications", "noreply@random-mailer.net", "Action required",
            "Your Netflix account is on hold. Please update your payment details.\n\nThanks,\nThe Netflix Team"))
        assertEquals("Netflix", b.claimedBrand); assertTrue(b.mismatch)
    }
}

class RuleEngineTest {
    private val facts = MessageFacts(messageId = "<a>", fromName = "Deals", fromAddress = "promo@mail.dealsite.biz",
        toAddresses = listOf("me@example.org"), subject = "50% off today only", bodyText = "Click here to claim your reward", attachmentNames = listOf("offer.pdf.exe"))

    @Test
    fun `operators and logic`() {
        val r = Rule("1", "t", criteria = listOf(
            RuleCriterion(RuleField.SENDER_DOMAIN, RuleOperator.ENDS_WITH, "dealsite.biz"),
            RuleCriterion(RuleField.SUBJECT, RuleOperator.CONTAINS, "nothing here")
        ), logic = RuleLogic.OR)
        assertTrue(RuleEngine.matches(r, facts))
        assertFalse(RuleEngine.matches(r.copy(logic = RuleLogic.AND), facts))
        assertTrue(RuleEngine.matches(Rule("2", "x", criteria = listOf(RuleCriterion(RuleField.ATTACHMENT_NAME, RuleOperator.MATCHES_REGEX, "\\.exe$"))), facts))
        assertTrue(RuleEngine.matches(Rule("3", "x", criteria = listOf(RuleCriterion(RuleField.RECIPIENT, RuleOperator.EQUALS, "me@example.org"))), facts))
        assertTrue(RuleEngine.matches(Rule("4", "x", criteria = listOf(RuleCriterion(RuleField.BODY, RuleOperator.NOT_CONTAINS, "unsubscribe"))), facts))
    }

    @Test
    fun `first matching rule by priority wins`() {
        val rules = listOf(
            Rule("b", "later", criteria = listOf(RuleCriterion(RuleField.SUBJECT, value = "off")), action = RuleAction.DELETE, priority = 100),
            Rule("a", "first", criteria = listOf(RuleCriterion(RuleField.SUBJECT, value = "off")), action = RuleAction.MOVE_TO_FOLDER, targetFolder = "Promotions", priority = 10)
        )
        val o = RuleEngine.evaluate(facts, rules)
        assertEquals("first", o.rule?.name); assertEquals("Promotions", o.targetFolder); assertFalse(o.delete)
    }
}

class BayesClassifierTest {
    @Test
    fun `seeded model separates obvious spam from ham`() {
        val b = BayesClassifier().apply { seed() }
        assertTrue(b.ready)
        val spam = b.classify(BayesClassifier.tokenize("You have won the lottery", "Claim your prize now, reply with your bank details to receive the money"))!!
        val ham = b.classify(BayesClassifier.tokenize("Meeting notes", "Attached are the minutes from today's meeting, see you tomorrow at 10"))!!
        assertTrue(spam > 0.7, "spam=$spam"); assertTrue(ham < 0.3, "ham=$ham")
    }

    @Test
    fun `seed examples do not count as user training`() {
        val b = BayesClassifier().apply { seed() }
        assertEquals(0, b.userExamples)
        b.train(BayesClassifier.tokenize("x", "y z w"), true)
        assertEquals(1, b.userExamples)
        val copy = BayesClassifier().apply { load(b.serialize()) }
        assertEquals(1, copy.userExamples)
    }

    @Test
    fun `serialization round trip and untrain`() {
        val b = BayesClassifier()
        val t = BayesClassifier.tokenize("Free money", "free money now")
        b.train(t, true); b.train(BayesClassifier.tokenize("Hello", "see you soon"), false)
        val copy = BayesClassifier().apply { load(b.serialize()) }
        assertEquals(b.trainedSpam, copy.trainedSpam); assertEquals(b.vocabulary, copy.vocabulary)
        b.untrain(t, true)
        assertEquals(0, b.trainedSpam)
    }
}

class SpamEngineTest {
    private val resolver = FakeResolver(mapOf(
        "77.100.51.198.zen.spamhaus.org" to "127.0.0.2",
        "77.100.51.198.bl.spamcop.net" to "127.0.0.2",
        "evil-links.example.multi.surbl.org" to "127.0.0.8",
        "10.113.0.203.zen.spamhaus.org" to "127.255.255.254"    // "public resolver" refusal code
    ))
    private val engine = SpamEngine(BlacklistChecker(resolver), BayesClassifier().apply { seed() })
    private val cfg = SpamConfig()

    private fun phish() = MessageFacts(
        messageId = "<p1>", fromName = "PayPal", fromAddress = "service@paypal-secure-center.com", replyTo = "collect@gmail.com",
        toAddresses = listOf("victim@example.org"), subject = "URGENT: Your account has been limited",
        bodyText = "Dear customer, unusual activity was detected. Verify your account within 24 hours: http://evil-links.example/login",
        bodyHtml = "<p>Dear customer, unusual activity was detected. <a href=\"http://evil-links.example/login\">https://www.paypal.com/signin</a></p>",
        receivedHeaders = listOf("from spammer.biz (unknown [198.51.100.77]) by mx.example.org with ESMTP"),
        authenticationResults = listOf("mx.example.org; spf=fail smtp.mailfrom=paypal-secure-center.com; dkim=none; dmarc=fail header.from=paypal-secure-center.com"),
        userAddresses = listOf("victim@example.org")
    )

    private fun legit() = MessageFacts(
        messageId = "<l1>", fromName = "Jane Smith", fromAddress = "jane@example.com", toAddresses = listOf("victim@example.org"),
        subject = "Re: Family tree question", bodyText = "Hi, I found the 1910 census record you asked about. It is attached. Thanks, Jane\n\n> You wrote: could you look for the census?",
        receivedHeaders = listOf("from mail.example.com (mail.example.com [203.0.113.10]) by mx.example.org"),
        authenticationResults = listOf("mx.example.org; spf=pass; dkim=pass header.d=example.com; dmarc=pass header.from=example.com"),
        userAddresses = listOf("victim@example.org")
    )

    @Test
    fun `phishing message is spam with the right reasons`() {
        val v = engine.analyze(phish(), cfg, emptyList())
        assertEquals(SpamLevel.SPAM, v.level)
        assertTrue(v.score >= 60)
        assertTrue(v.brand.mismatch); assertEquals("PayPal", v.brand.claimedBrand)
        assertTrue(v.blacklistHits.any { it.list == "Spamhaus ZEN" && it.subject == "198.51.100.77" })
        assertTrue(v.blacklistHits.any { it.kind == "domain" && it.subject == "evil-links.example" })
        val codes = v.reasons.map { it.code }
        assertTrue("DMARC_FAIL" in codes); assertTrue("LINK_TEXT_MISMATCH" in codes); assertTrue("REPLY_TO_MISMATCH" in codes)
        assertTrue("BRAND_MISMATCH" in codes)
    }

    @Test
    fun `untrained bayes opinion is capped so genuine transactional mail stays clean`() {
        val f = legit().copy(fromName = "Microsoft account team", fromAddress = "account-security-noreply@accountprotection.microsoft.com",
            subject = "Microsoft account security code", bodyText = "Your Microsoft account security code is 482913. If you didn't request this code, you can safely ignore this email.",
            authenticationResults = listOf("mx; spf=pass; dkim=pass header.d=microsoft.com; dmarc=pass header.from=microsoft.com"))
        val v = engine.analyze(f, cfg, emptyList())
        assertEquals(SpamLevel.CLEAN, v.level, "reasons=${v.reasons}")
        assertTrue(v.reasons.none { it.code == "BAYES" && it.weight > 10 })
        val newsletter = legit().copy(fromName = "Typography Weekly", fromAddress = "newsletter@mail.typographyweekly.example",
            subject = "Issue 214: Optical sizes, again", bodyText = "This week: why optical sizes matter. Unsubscribe: https://typographyweekly.example/unsub",
            authenticationResults = listOf("mx; spf=pass; dkim=pass header.d=typographyweekly.example; dmarc=pass"), listUnsubscribe = "<https://typographyweekly.example/unsub>", precedence = "bulk")
        val nv = engine.analyze(newsletter, cfg, emptyList())
        assertEquals(SpamLevel.CLEAN, nv.level, "reasons=${nv.reasons}")
        assertFalse(nv.brand.mismatch)
    }

    @Test
    fun `legitimate mail is clean`() {
        val v = engine.analyze(legit(), cfg, emptyList())
        assertEquals(SpamLevel.CLEAN, v.level, "reasons=${v.reasons}")
        assertTrue(v.blacklistHits.isEmpty())
        assertFalse(v.brand.mismatch)
    }

    @Test
    fun `refused blacklist answers are reported not counted`() {
        val f = legit().copy(receivedHeaders = listOf("from x (x [203.0.113.10]) by y"))
        val v = engine.analyze(f, cfg, emptyList())
        assertTrue(v.blacklistHits.none { it.list == "Spamhaus ZEN" })
        assertTrue(v.blacklistStatus.any { it.list == "Spamhaus ZEN" && it.refused })
    }

    @Test
    fun `safe sender overrides everything and blocked sender is spam`() {
        val safe = engine.analyze(phish(), cfg.copy(safeSenders = listOf("paypal-secure-center.com")), emptyList())
        assertEquals(SpamLevel.CLEAN, safe.level); assertTrue(safe.safeSender)
        val blocked = engine.analyze(legit(), cfg.copy(blockedSenders = listOf("jane@example.com")), emptyList())
        assertEquals(SpamLevel.SPAM, blocked.level); assertTrue(blocked.blockedSender)
    }

    @Test
    fun `rules move to junk make it spam`() {
        val rule = Rule("r", "Block example", criteria = listOf(RuleCriterion(RuleField.SENDER_DOMAIN, RuleOperator.EQUALS, "example.com")), targetFolder = "Junk")
        val v = engine.analyze(legit(), cfg, listOf(rule))
        assertEquals(SpamLevel.SPAM, v.level); assertEquals("Block example", v.matchedRule?.name)
    }

    @Test
    fun `brand mismatch alone forces spam when configured`() {
        val f = legit().copy(fromName = "Netflix", fromAddress = "billing@some-random-host.net", subject = "Payment failed", bodyText = "Update your payment details.",
            authenticationResults = emptyList(), receivedHeaders = emptyList())
        val on = engine.analyze(f, cfg, emptyList())
        assertEquals(SpamLevel.SPAM, on.level)
        val off = engine.analyze(f, cfg.copy(brandMismatchIsSpam = false), emptyList())
        assertTrue(off.level != SpamLevel.CLEAN || off.score >= 30)
    }

    @Test
    fun `rule proposal keys on domain and spoofed name`() {
        val v = engine.analyze(phish(), cfg, emptyList())
        val p = RuleProposer.propose(phish(), v, engine.bayes)
        val fields = p.rule.criteria.map { it.field }
        assertTrue(RuleField.SENDER_DOMAIN in fields, "criteria=${p.rule.criteria}")
        assertTrue(RuleField.SENDER_NAME in fields)
        assertTrue(p.rule.criteria.any { it.field == RuleField.SENDER_DOMAIN && it.value == "paypal-secure-center.com" })
        assertTrue(RuleEngine.matches(p.rule, phish()))
        assertEquals("ai", p.rule.createdBy)
        // A freemail sender is keyed on the exact address instead.
        val g = phish().copy(fromAddress = "someone123@gmail.com")
        val pg = RuleProposer.propose(g, engine.analyze(g, cfg, emptyList()), engine.bayes)
        assertNotNull(pg.rule.criteria.firstOrNull { it.field == RuleField.SENDER && it.value == "someone123@gmail.com" })
    }

    @Test
    fun `subject phrase extraction`() {
        val (phrase, _) = RuleProposer.subjectPhrase("Re: Final Notice: Your Norton subscription renewal", null)!!
        assertTrue(phrase.split(" ").size in 2..3)
        assertNull(RuleProposer.subjectPhrase("Hi", null))
    }
}

class ExtendedBrandTableTest {
    @Test
    fun `extended table loads and merges`() {
        assertTrue(BrandKnowledgeBaseExtra.brands.size > 2000, "extra=${BrandKnowledgeBaseExtra.brands.size}")
        assertTrue(BrandKnowledgeBase.brands.size > 2000)
        assertTrue(BrandKnowledgeBase.brands.map { it.name.lowercase() }.toSet().size == BrandKnowledgeBase.brands.size, "duplicate names")
        assertTrue(BrandKnowledgeBase.brandsForDomain("boeing.com").any { it.name == "Boeing" })
        assertTrue(BrandKnowledgeBase.brandsForDomain("mail.chase.com").isNotEmpty())
    }

    @Test
    fun `one-word extended names do not turn people into brands`() {
        val dave = BrandDetector.analyze(MessageFacts(messageId = "<d>", fromName = "Dave Miller", fromAddress = "dave.miller@gmail.com", subject = "Golf on Sunday?", bodyText = "Are we still on?"))
        assertFalse(dave.mismatch, dave.explanation)
        val next = BrandDetector.analyze(MessageFacts(messageId = "<n>", fromName = "Alice", fromAddress = "alice@example.org", subject = "Next steps for the project", bodyText = "Here are the next steps."))
        assertFalse(next.mismatch, next.explanation)
        val boeing = BrandDetector.analyze(MessageFacts(messageId = "<b>", fromName = "Boeing Careers", fromAddress = "hr-team@careers-portal.info", subject = "Job offer", bodyText = "Congratulations"))
        assertTrue(boeing.mismatch); assertEquals("Boeing", boeing.claimedBrand)
        val genuine = BrandDetector.analyze(MessageFacts(messageId = "<g>", fromName = "Delta Air Lines", fromAddress = "deltaairlines@e.delta.com", subject = "Your trip", bodyText = "Thanks for flying"))
        assertTrue(genuine.verified, genuine.explanation)
    }

    @Test
    fun `typo squats only apply to core brands`() {
        assertNull(BrandDetector.lookalike("sender.net"))      // "render" is only in the extended table
        assertEquals("PayPal", BrandDetector.lookalike("paypa1.com")?.first?.name)
    }
}
