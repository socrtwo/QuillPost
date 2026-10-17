package info.socrtwo.quillpost.web.spam

/**
 * Works out which organisation a message *claims* to be from and whether the sending domain
 * actually belongs to that organisation. Entirely offline: dictionary NER over the display
 * name, subject and body, plus look-alike (typo-squat / homoglyph) detection on the domain.
 */
object BrandDetector {

    data class Claim(val brand: Brand?, val text: String, val source: String, val confidence: Int)

    fun analyze(facts: MessageFacts): BrandAnalysis {
        val senderDomain = HeaderParser.addressDomain(facts.fromAddress)
        val apex = HeaderParser.registrableDomain(senderDomain)
        val freemail = BrandKnowledgeBase.isFreemail(senderDomain)

        val claims = mutableListOf<Claim>()
        claimFromText(facts.fromName, "display name", 90)?.let { claims.add(it) }
        // The local part of the address is a common place to smuggle a brand name in.
        claimFromText(facts.fromAddress.substringBefore('@').replace(Regex("[._-]"), " "), "sender address", 60)?.let { claims.add(it) }
        claimFromText(facts.subject, "subject", 55)?.let { claims.add(it) }
        val text = facts.bodyText.ifBlank { facts.bodyHtml?.let { HeaderParser.htmlToText(it) } ?: "" }
        bodyClaim(text)?.let { claims.add(it) }
        lookalike(senderDomain)?.let { (brand, label) ->
            claims.add(Claim(brand, label, "look-alike domain", 95))
        }

        // Generic (not in the knowledge base) organisation claims from the display name.
        val genericOrg = genericOrganisationClaim(facts.fromName)

        val best = claims.filter { it.brand != null }.maxByOrNull { it.confidence }
        if (best?.brand != null) {
            val brand = best.brand
            val verified = BrandKnowledgeBase.domainBelongsTo(senderDomain, brand) ||
                BrandKnowledgeBase.domainBelongsTo(apex, brand)
            val isLookalike = best.source == "look-alike domain" || lookalike(senderDomain)?.first == brand
            // Merely mentioning a brand in the body is normal ("I bought it on Amazon"). Only
            // treat body/subject mentions as an impersonation claim when they read like a
            // sender identity (signature, copyright line, "the X team") — bodyClaim() already
            // restricts to those patterns — or when combined with an org-like display name.
            val strongClaim = best.source == "display name" || best.source == "look-alike domain" ||
                best.source == "sender address" || best.source == "body signature" ||
                (best.source == "subject" && (genericOrg != null || freemail))
            val mismatch = !verified && strongClaim
            val explanation = when {
                verified -> "Sender domain $senderDomain is a legitimate ${brand.name} domain."
                mismatch && isLookalike -> "The sender domain \"$senderDomain\" imitates ${brand.name} but is not one of its real domains (${brand.domains.take(3).joinToString(", ")}…)."
                mismatch && freemail -> "Claims to be ${brand.name} (${best.source}: \"${best.text}\") but was sent from a free webmail address at $senderDomain."
                mismatch -> "Claims to be ${brand.name} (${best.source}: \"${best.text}\") but was sent from $senderDomain, which does not belong to ${brand.name}."
                else -> "${brand.name} is mentioned (${best.source}) but the message does not present itself as coming from ${brand.name}."
            }
            return BrandAnalysis(
                claimedBrand = brand.name,
                claimSource = best.source,
                claimedText = best.text,
                legitimateDomains = brand.domains,
                senderDomain = senderDomain,
                mismatch = mismatch,
                lookalike = isLookalike && !verified,
                freemailSender = freemail,
                knownBrand = true,
                verified = verified,
                explanation = explanation
            )
        }

        if (genericOrg != null) {
            val orgTokens = significantTokens(genericOrg)
            val domainBlob = apex.replace(Regex("[^a-z0-9]"), "")
            val tokenInDomain = orgTokens.any { t -> t.length >= 3 && domainBlob.contains(t) }
            val mismatch = orgTokens.isNotEmpty() && !tokenInDomain
            val explanation = when {
                !mismatch -> "Display name \"$genericOrg\" is consistent with sender domain $senderDomain."
                freemail -> "Presents itself as an organisation (\"$genericOrg\") but was sent from a free webmail address at $senderDomain."
                else -> "Presents itself as \"$genericOrg\" but nothing in that name appears in the sender domain $senderDomain."
            }
            return BrandAnalysis(
                claimedBrand = genericOrg,
                claimSource = "display name",
                claimedText = facts.fromName,
                senderDomain = senderDomain,
                mismatch = mismatch,
                freemailSender = freemail,
                knownBrand = false,
                verified = !mismatch,
                explanation = explanation
            )
        }

        val owner = BrandKnowledgeBase.brandsForDomain(senderDomain).firstOrNull()
        return BrandAnalysis(
            claimedBrand = owner?.name,
            senderDomain = senderDomain,
            freemailSender = freemail,
            knownBrand = owner != null,
            verified = owner != null,
            legitimateDomains = owner?.domains ?: emptyList(),
            explanation = if (owner != null) "Sent from a verified ${owner.name} domain ($senderDomain)."
            else if (senderDomain.isBlank()) "No sender address." else "No organisation claim detected; sender domain is $senderDomain."
        )
    }

    // --- claims -----------------------------------------------------------------------

    private fun normalise(s: String): String =
        s.lowercase().replace(Regex("[\"'“”‘’®™©]"), " ").replace(Regex("\\s+"), " ").trim()

    /**
     * Finds the longest knowledge-base alias appearing as whole words in [text]. A one-word
     * alias ("Dave", "Chase", "Next") only counts when it is the whole text or sits next to an
     * organisation word ("Chase Alerts"), so a person called Dave is not mistaken for the bank.
     */
    fun claimFromText(text: String, source: String, confidence: Int): Claim? {
        if (text.isBlank()) return null
        val norm = " " + normalise(text).replace(Regex("[^a-z0-9&+. ]"), " ").replace(Regex("\\s+"), " ") + " "
        val words = norm.trim().split(' ').filter { it.isNotBlank() }
        val hasOrgWord = words.any { it in BrandKnowledgeBase.organisationWords }
        for ((alias, brand) in BrandKnowledgeBase.aliasesLongestFirst) {
            if (alias.length < 3) continue
            val needle = " $alias "
            val hit = norm.contains(needle) || (alias.contains('.') && norm.contains(" $alias"))
            if (!hit) continue
            val singleWord = !alias.contains(' ')
            val ambiguous = alias in BrandKnowledgeBase.ambiguousAliases || (alias.length <= 4 && brand.name !in BrandKnowledgeBase.coreBrandNames)
            if (singleWord && ambiguous && norm.trim() != alias && !hasOrgWord) continue
            return Claim(brand, alias, source, confidence)
        }
        return null
    }

    private val signatureRe = Regex(
        """(?:^|\n)\s*(?:thanks?,?|thank you,?|regards,?|sincerely,?|best regards,?|cheers,?|©\s*\d{4}|\(c\)\s*\d{4}|copyright\s*(?:©)?\s*\d{4})?\s*(?:the\s+)?([A-Z][A-Za-z0-9&.'’ -]{2,40}?)\s+(?:team|support|customer service|security team|billing team|customer care|service team|account team)\b""",
        RegexOption.IGNORE_CASE
    )
    private val copyrightRe = Regex("""(?:©|\(c\)|copyright)\s*(?:\d{4}(?:\s*[-–]\s*\d{4})?)?\s*,?\s*([A-Z][A-Za-z0-9&.'’ -]{2,40}?)(?:[,.]|\s+(?:inc|llc|ltd|all rights)|\s*$)""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))

    /** Looks for identity-like brand claims in the body: signatures, "The X Team", © lines. */
    private fun bodyClaim(text: String): Claim? {
        if (text.isBlank()) return null
        val head = text.take(1500)
        val tail = text.takeLast(1200)
        for (m in signatureRe.findAll(head + "\n" + tail)) {
            claimFromText(m.groupValues[1], "body signature", 75)?.let { return it }
        }
        for (m in copyrightRe.findAll(tail)) {
            claimFromText(m.groupValues[1], "body signature", 70)?.let { return it }
        }
        // Phrases like "Your PayPal account", "your Netflix membership".
        val possessive = Regex("""\byour\s+([A-Za-z][A-Za-z0-9&.' -]{2,30}?)\s+(?:account|membership|subscription|order|package|parcel|delivery|payment|invoice|wallet|password|mailbox|storage|license|licence|plan)\b""", RegexOption.IGNORE_CASE)
        for (m in possessive.findAll(head)) {
            claimFromText(m.groupValues[1], "body signature", 65)?.let { return it }
        }
        return null
    }

    /** An organisation-looking display name that is not a known brand, e.g. "Acme Billing Dept". */
    fun genericOrganisationClaim(displayName: String): String? {
        val name = normalise(displayName)
        if (name.isBlank() || name.contains('@')) return null
        val words = name.split(Regex("[^a-z0-9&]+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return null
        val orgWords = words.count { it in BrandKnowledgeBase.organisationWords }
        val looksLikePerson = words.size in 1..3 && orgWords == 0
        if (looksLikePerson) return null
        return if (orgWords > 0 || words.size >= 4) displayName.trim() else null
    }

    fun significantTokens(name: String): List<String> =
        normalise(name).split(Regex("[^a-z0-9]+")).filter { it.length >= 3 && it !in BrandKnowledgeBase.nameStopwords }

    // --- look-alike domains -------------------------------------------------------------

    private val homoglyphs = mapOf(
        '0' to 'o', '1' to 'l', '3' to 'e', '4' to 'a', '5' to 's', '7' to 't', '8' to 'b', '9' to 'g',
        '@' to 'a', '$' to 's', '|' to 'l', '!' to 'i', 'ı' to 'i', 'í' to 'i', 'ï' to 'i', 'ì' to 'i',
        'а' to 'a', 'е' to 'e', 'о' to 'o', 'р' to 'p', 'с' to 'c', 'х' to 'x', 'у' to 'y', 'і' to 'i',
        'ο' to 'o', 'α' to 'a', 'ν' to 'v', 'ѕ' to 's', 'ԁ' to 'd', 'ɡ' to 'g', 'ł' to 'l'
    )

    fun normaliseHomoglyphs(s: String): String {
        val sb = StringBuilder()
        for (c in s.lowercase()) sb.append(homoglyphs[c] ?: c)
        return sb.toString().replace("rn", "m").replace("vv", "w").replace("cl", "d")
    }

    /**
     * Returns the brand a sender domain imitates, if any. Catches "paypa1.com", "arnazon.com",
     * "secure-paypal-login.net", "paypal.com.verify-account.ru" and the like, while leaving the
     * genuine domains — and unrelated domains that merely contain a brand-like substring
     * ("purchase-orders.com", "chasm.org") — alone.
     */
    fun lookalike(senderDomain: String): Pair<Brand, String>? {
        val domain = senderDomain.lowercase().trimEnd('.')
        if (domain.isBlank() || HeaderParser.isIpLiteral(domain)) return null
        if (BrandKnowledgeBase.brandsForDomain(domain).isNotEmpty()) return null
        val apex = HeaderParser.registrableDomain(domain)
        val apexLabel = apex.substringBefore('.')
        val normApex = normaliseHomoglyphs(apexLabel)
        val tokens = normApex.split(Regex("[-_0-9]+")).filter { it.isNotBlank() }
        val subLabels = domain.removeSuffix(apex).trimEnd('.').split('.').filter { it.isNotBlank() }.map { normaliseHomoglyphs(it) }
        val decorations = BrandKnowledgeBase.decorationWords

        var best: Pair<Brand, String>? = null
        var bestScore = 0
        for ((label, brand) in BrandKnowledgeBase.brandLabels) {
            var score = 0
            when {
                // 1) the whole apex label is the brand, possibly disguised: paypa1.com, arnazon.net, paypal.xyz
                normApex == label -> score = 90
                // 2) brand + decoration words joined by hyphens/digits: secure-paypal-login, paypal-billing2
                label in tokens && tokens.all { it == label || it in decorations } -> score = 88
                // 3) brand glued to a decoration word: securepaypal, paypalsecure, netflixbilling
                normApex.startsWith(label) && normApex.removePrefix(label).let { it in decorations } -> score = 85
                normApex.endsWith(label) && normApex.removeSuffix(label).let { it in decorations } -> score = 85
                // 4) the brand is a sub-domain label of an unrelated domain: paypal.com.verify-account.ru
                label in subLabels -> score = 80
                // 5) one typo away, only for reasonably long, hand-curated brand names
                //    (paypal, wellsfargo, americanexpress) — never for the 2,000-entry extended table
                brand.name in BrandKnowledgeBase.coreBrandNames && label.length >= 6 && normApex.length >= label.length - 1 && levenshtein(normApex, label) == 1 -> score = 75
                brand.name in BrandKnowledgeBase.coreBrandNames && label.length >= 9 && normApex.length >= label.length - 2 && levenshtein(normApex, label) == 2 -> score = 70
            }
            if (score > bestScore) { bestScore = score; best = brand to apex }
        }
        return best
    }

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }
}
