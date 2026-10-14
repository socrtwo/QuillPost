package info.socrtwo.quillbox.web.spam

/**
 * Orchestrates all detectors into one verdict:
 *
 *  1. Safe / blocked sender lists and the user's rules (deterministic, always win).
 *  2. DNS blocklists for the originating IPs and for the sender / link domains.
 *  3. Authentication-Results (SPF / DKIM / DMARC as evaluated by the receiving server).
 *  4. Brand impersonation: claimed organisation vs. real sender domain (+ look-alike domains).
 *  5. On-device Bayesian classifier trained by the user's own Junk / Not-junk actions.
 *  6. Content heuristics.
 *
 * No network service other than plain DNS is contacted, and no API key is needed.
 */
class SpamEngine(
    private val blacklists: BlacklistChecker = BlacklistChecker(),
    val bayes: BayesClassifier = BayesClassifier().apply { seed() }
) {

    fun analyze(facts: MessageFacts, config: SpamConfig, rules: List<Rule>, reputation: SenderReputation = SenderReputation.NONE): SpamVerdict {
        val reasons = ArrayList<SpamReason>()
        val senderDomain = HeaderParser.addressDomain(facts.fromAddress)
        val senderApex = HeaderParser.registrableDomain(senderDomain)
        val text = facts.bodyText.ifBlank { facts.bodyHtml?.let { HeaderParser.htmlToText(it) } ?: "" }
        // Link-protection and redirect wrappers (Safe Links, URL Defense, google.com/url…) are
        // stripped so blocklists and heuristics judge the real destination.
        val links = HeaderParser.extractLinks(facts.bodyHtml, facts.bodyText).map { l -> l.copy(href = LinkInfrastructure.unwrap(l.href)) }
        val linkDomains = links.mapNotNull { HeaderParser.hostOf(it.href) }
            .filter { !HeaderParser.isIpLiteral(it) }
            .map { HeaderParser.registrableDomain(it) }
            .distinct()
        val ips = HeaderParser.extractReceivedIps(facts.receivedHeaders, facts.originatingIpHeader)
        val auth = HeaderParser.parseAuthenticationResults(facts.authenticationResults)
        // The sender's identity is proven when DMARC passed, or when SPF passed and a DKIM
        // signature from the From domain (or its registrable domain) verified.
        val dkimAligned = auth.dkim == "pass" && auth.dkimDomain?.let { d ->
            val da = HeaderParser.registrableDomain(d); da.isNotBlank() && (da == senderApex || d.equals(senderDomain, true))
        } == true
        val senderProven = auth.dmarc == "pass" || (auth.spf == "pass" && dkimAligned)

        // --- 1. lists & rules ---------------------------------------------------------
        val safe = matchesList(facts.fromAddress, config.safeSenders)
        val blocked = matchesList(facts.fromAddress, config.blockedSenders)
        val ruleOutcome = RuleEngine.evaluate(facts, rules)
        val brand = if (config.brandDetection) BrandDetector.analyze(facts) else BrandAnalysis(senderDomain = senderDomain)

        if (safe) reasons += SpamReason("SAFE_SENDER", "Safe sender", "${facts.fromAddress} is on your safe senders list", -100)
        if (blocked) reasons += SpamReason("BLOCKED_SENDER", "Blocked sender", "${facts.fromAddress} is on your blocked senders list", 100)
        ruleOutcome.rule?.let { r ->
            val what = when (r.action) {
                RuleAction.MOVE_TO_FOLDER -> "move to ${r.targetFolder}"
                RuleAction.MARK_READ -> "mark as read"
                RuleAction.DELETE -> "delete"
                RuleAction.FLAG -> "flag"
                RuleAction.MARK_SAFE -> "never treat as junk"
                RuleAction.COPY_TO_FOLDER -> "copy to ${r.targetFolder}"
                RuleAction.CATEGORIZE -> "categorize as ${r.categories.joinToString(", ")}"
                RuleAction.PIN -> "pin"
                RuleAction.FORWARD_TO -> "forward to ${r.forwardTo}"
            }
            val w = when {
                r.action == RuleAction.MARK_SAFE -> -100
                r.action == RuleAction.MOVE_TO_FOLDER && isJunkFolder(r.targetFolder) -> 100
                r.action == RuleAction.DELETE -> 100
                else -> 0
            }
            reasons += SpamReason("RULE", "Matched rule \"${r.name}\"", "Action: $what", w)
        }
        val effectivelySafe = safe || ruleOutcome.safe
        if (!config.enabled) {
            return SpamVerdict(0, SpamLevel.CLEAN, reasons, brand, auth, emptyList(), emptyList(), null,
                linkDomains, ips, senderDomain, ruleOutcome.rule, effectivelySafe, blocked)
        }

        // --- 2. blocklists --------------------------------------------------------------
        var blHits: List<BlacklistHit> = emptyList()
        var blStatus: List<BlacklistStatus> = emptyList()
        if (!effectivelySafe) {
            val domains = LinkedHashSet<String>()
            if (senderApex.isNotBlank() && !BrandKnowledgeBase.isFreemail(senderApex)) domains += senderApex
            HeaderParser.addressDomain(facts.replyTo).takeIf { it.isNotBlank() }?.let { domains += HeaderParser.registrableDomain(it) }
            if (config.checkLinkDomains) linkDomains.filter { it !in BrandKnowledgeBase.freemailDomains }.take(config.maxDomainsToCheck).forEach { domains += it }
            val outcome = blacklists.check(ips.take(config.maxIpsToCheck), domains.toList().take(config.maxDomainsToCheck + 2), config.blacklists, config.dnsTimeoutMs)
            blHits = outcome.hits
            blStatus = outcome.status
            var blScore = 0
            for (hit in blHits) {
                val def = config.blacklists.firstOrNull { it.zone == hit.zone }
                val base = def?.weight ?: 30
                // An IP listing describes the sending server, which large senders share (Amazon
                // SES, SendGrid, Google…) and which gets listed because of other tenants. When
                // DMARC/DKIM prove the From domain, the listing is kept as a strong signal but no
                // longer decides on its own; a listing of the sender's own domain still does.
                val sharedIp = hit.kind == "ip" && senderProven
                val w = when {
                    sharedIp -> minOf(base, 15)
                    hit.kind == "ip" -> base
                    hit.subject == senderApex -> base
                    else -> maxOf(10, base - 10)          // a listed link domain
                }
                blScore += w
                reasons += SpamReason(
                    "BLACKLIST", "Listed on ${hit.list}",
                    (if (hit.kind == "ip") "Sending server ${hit.subject}: " else "Domain ${hit.subject}: ") + hit.note +
                        (if (sharedIp) " (the sender is authenticated, so this is probably shared sending infrastructure listed because of other senders)" else ""), w
                )
            }
            blStatus.filter { it.refused }.forEach {
                reasons += SpamReason("BLACKLIST_REFUSED", "${it.list} could not be queried",
                    "The list refused the query (this happens when the DNS resolver is a public one such as 8.8.8.8 or 1.1.1.1).", 0)
            }
        }

        // --- 3. authentication ----------------------------------------------------------
        if (!effectivelySafe) {
            when (auth.dmarc) {
                "fail" -> reasons += SpamReason("DMARC_FAIL", "Failed DMARC", "The domain owner's policy says this message is not authorised", 25)
            }
            when (auth.spf) {
                "fail" -> reasons += SpamReason("SPF_FAIL", "Failed SPF", "The sending server is not permitted to send for $senderDomain", 12)
                "softfail" -> reasons += SpamReason("SPF_SOFTFAIL", "SPF soft-fail", "The sending server is probably not permitted to send for $senderDomain", 6)
                "permerror" -> reasons += SpamReason("SPF_ERROR", "SPF error", "The sender's SPF record is broken", 3)
            }
            if (auth.dkim == "fail") reasons += SpamReason("DKIM_FAIL", "Failed DKIM", "The signature does not verify; the message may have been altered or forged", 8)
            if (auth.dmarc == "pass" && brand.verified && brand.knownBrand) {
                reasons += SpamReason("DMARC_PASS_BRAND", "Authenticated ${brand.claimedBrand} mail", "DMARC passed for a genuine ${brand.claimedBrand} domain", -10)
            } else if (auth.dmarc == "pass" && auth.dkim == "pass") {
                reasons += SpamReason("AUTH_PASS", "Sender authenticated", "SPF/DKIM/DMARC passed for $senderDomain", -4)
            }
        }

        // --- 4. brand impersonation -------------------------------------------------------
        if (config.brandDetection && !effectivelySafe) {
            when {
                brand.mismatch && brand.knownBrand -> {
                    val w = 45 + (if (brand.lookalike) 10 else 0) + (if (brand.freemailSender) 10 else 0)
                    reasons += SpamReason("BRAND_MISMATCH", "Impersonates ${brand.claimedBrand}", brand.explanation, w)
                }
                brand.mismatch -> {
                    val w = 18 + (if (brand.freemailSender) 12 else 0)
                    reasons += SpamReason("ORG_MISMATCH", "Organisation name does not match sender domain", brand.explanation, w)
                }
                brand.verified && brand.knownBrand -> {
                    reasons += SpamReason("BRAND_VERIFIED", "Genuine ${brand.claimedBrand} domain", brand.explanation, -8)
                }
            }
        }

        // --- 5. Bayes --------------------------------------------------------------------
        var bayesP: Double? = null
        if (config.useBayes && !effectivelySafe) {
            bayesP = bayes.classify(BayesClassifier.tokenize(facts))
            bayesP?.let { p ->
                val raw = when {
                    p >= 0.95 -> 30; p >= 0.8 -> 18; p >= 0.65 -> 8
                    p <= 0.05 -> -20; p <= 0.2 -> -10; else -> 0
                }
                // Until the user has labelled a fair number of messages the model only knows the
                // small built-in seed corpus, so its opinion is kept deliberately weak.
                val cap = when { bayes.userExamples >= 40 -> 30; bayes.userExamples >= 15 -> 20; else -> 10 }
                val w = raw.coerceIn(-cap, cap)
                val basis = if (bayes.userExamples == 0) "Based only on the built-in examples so far — teach it with Junk / Not junk"
                    else "Based on ${bayes.userExamples} message${if (bayes.userExamples == 1) "" else "s"} you have marked as junk or not junk"
                if (w != 0) reasons += SpamReason("BAYES", "Learned classifier: ${(p * 100).toInt()}% spam-like", basis, w)
            }
        }

        // --- 6. heuristics -----------------------------------------------------------------
        if (!effectivelySafe) {
            if (reputation.knownCorrespondent) {
                reasons += SpamReason("KNOWN_SENDER", "A sender you deal with", reputation.summary.replaceFirstChar { it.uppercase() } + "; link and wording checks are relaxed for them", -10)
            }
            val h = ContentHeuristics.evaluate(facts, links, text, ContentHeuristics.SenderContext(
                senderProven = senderProven, knownSender = reputation.knownCorrespondent, familiarLinkApexes = reputation.familiarLinkApexes))
            var total = 0
            for (r in h.sortedByDescending { it.weight }) {
                val allowed = minOf(r.weight, 40 - total)
                if (allowed <= 0) { reasons += r.copy(weight = 0); continue }
                total += allowed
                reasons += if (allowed == r.weight) r else r.copy(weight = allowed)
            }
        }

        // --- score -------------------------------------------------------------------------
        var score = reasons.sumOf { it.weight }
        var level: SpamLevel
        if (effectivelySafe) {
            score = 0
            level = SpamLevel.CLEAN
        } else if (blocked || (ruleOutcome.rule != null && ((ruleOutcome.targetFolder != null && isJunkFolder(ruleOutcome.targetFolder)) || ruleOutcome.delete))) {
            score = 100
            level = SpamLevel.SPAM
        } else {
            score = score.coerceIn(0, 100)
            val strongIpHit = !senderProven && blHits.any { h -> h.kind == "ip" && (config.blacklists.firstOrNull { it.zone == h.zone }?.weight ?: 0) >= 40 }
            val senderDomainHit = blHits.any { it.kind == "domain" && it.subject == senderApex }
            val forcedSpam = (config.blacklistHitIsSpam && (strongIpHit || senderDomainHit)) ||
                (config.brandMismatchIsSpam && brand.mismatch && brand.knownBrand)
            if (forcedSpam) score = maxOf(score, config.spamThreshold)
            level = when {
                score >= config.spamThreshold -> SpamLevel.SPAM
                score >= config.suspiciousThreshold -> SpamLevel.SUSPICIOUS
                else -> SpamLevel.CLEAN
            }
            // "Suspicious" needs either one strong signal or agreement between two independent
            // families of evidence (authentication, blocklists, brand impersonation, the learned
            // classifier, content). A pile of weak bulk-mail traits — undisclosed recipients, a
            // shortener, a tracked link, a "sign in" mention — from an authenticated sender is
            // ordinary mail, not a warning.
            if (level == SpamLevel.SUSPICIOUS && !forcedSpam) {
                val positive = reasons.filter { it.weight > 0 }
                val strong = positive.any { it.weight >= 15 }
                val families = positive.map { family(it.code) }.toMutableSet()
                if (!senderProven && "content" in families) families += "unauthenticated"
                if (!strong && families.size < 2) {
                    level = SpamLevel.CLEAN
                    reasons += SpamReason("WEAK_SIGNALS_ONLY", "Only weak, common bulk-mail traits",
                        "No single strong signal and only one kind of evidence from an authenticated sender, so this is not treated as suspicious", 0)
                }
            }
        }

        return SpamVerdict(
            score = score,
            level = level,
            reasons = reasons.sortedByDescending { kotlin.math.abs(it.weight) },
            brand = brand,
            auth = auth,
            blacklistHits = blHits,
            blacklistStatus = blStatus,
            bayesProbability = bayesP,
            linkDomains = linkDomains,
            originatingIps = ips,
            senderDomain = senderDomain,
            matchedRule = ruleOutcome.rule,
            safeSender = effectivelySafe,
            blockedSender = blocked
        )
    }

    /** Entries may be full addresses, bare domains, or "@domain". Sub-domains match. */
    /** Groups reason codes into independent evidence families for the "suspicious" rule. */
    private fun family(code: String): String = when {
        code.startsWith("BLACKLIST") -> "blocklist"
        code.startsWith("DMARC") || code.startsWith("SPF") || code.startsWith("DKIM") -> "auth"
        code == "BRAND_MISMATCH" || code == "ORG_MISMATCH" -> "brand"
        // The classifier is an independent witness only once the user has taught it; before
        // that it merely echoes the built-in seed corpus, which is content evidence again.
        code == "BAYES" -> if (bayes.userExamples >= 15) "bayes" else "content"
        code == "BLOCKED_SENDER" || code == "RULE" -> "rule"
        code == "KNOWN_SENDER" -> "history"
        else -> "content"
    }

    fun matchesList(address: String, entries: List<String>): Boolean {
        val addr = address.trim().lowercase()
        if (addr.isBlank()) return false
        val domain = HeaderParser.addressDomain(addr)
        for (raw in entries) {
            val e = raw.trim().lowercase().removePrefix("@").removePrefix("*.")
            if (e.isBlank()) continue
            if (e.contains('@')) { if (e == addr) return true }
            else if (domain == e || domain.endsWith(".$e")) return true
        }
        return false
    }

    fun isJunkFolder(name: String?): Boolean {
        val n = name?.lowercase()?.trim() ?: return false
        return n == "junk" || n == "spam" || n == "junk e-mail" || n == "junk email" || n.endsWith("/junk") || n.endsWith("/spam") || n == "bulk mail" || n == "bulk"
    }
}
