package info.socrtwo.quillpost.web.spam

/**
 * SpamAssassin-style content heuristics. Each check contributes a weighted [SpamReason];
 * the engine caps the total so no single heuristic family dominates.
 */
object ContentHeuristics {

    private val urgency = listOf(
        "act now", "immediately", "urgent", "within 24 hours", "within 48 hours", "final notice", "last chance",
        "account will be", "will be suspended", "has been suspended", "has been limited", "will be closed",
        "verify your account", "confirm your account", "verify your identity", "update your payment",
        "unusual activity", "suspicious activity", "unauthorized", "unauthorised", "expires today", "respond now",
        "limited time", "don't miss", "do not ignore", "immediate action", "action required", "failure to"
    )
    private val money = listOf(
        "you have won", "winner", "lottery", "prize", "claim your", "gift card", "cash prize", "million dollars",
        "inheritance", "beneficiary", "wire transfer", "western union", "bitcoin", "crypto", "guaranteed return",
        "risk free", "no credit check", "pre-approved", "pre approved", "refund", "compensation", "reward"
    )
    private val scamPhrases = listOf(
        "dear customer", "dear user", "dear valued", "dear account holder", "dear friend", "dear beneficiary",
        "kindly", "click here", "click the link", "open the attached", "confirm your password", "your password",
        "log in to your account", "sign in to your account", "call us at", "toll free", "renewal", "auto-renew",
        "has been charged", "charged to your", "geek squad", "tech support", "remote access", "anydesk", "teamviewer",
        "one time password", "otp", "social security number", "ssn", "date of birth", "mother's maiden"
    )
    private val adult = listOf("viagra", "cialis", "enlargement", "singles in your area", "hot singles", "xxx", "adult dating", "sexy")
    private val shorteners = setOf("bit.ly", "tinyurl.com", "t.co", "goo.gl", "ow.ly", "is.gd", "buff.ly", "cutt.ly", "rb.gy", "shorturl.at", "tiny.cc", "lnkd.in", "rebrand.ly", "t.ly", "bl.ink")
    private val dangerousExt = setOf("exe", "scr", "js", "jse", "vbs", "vbe", "bat", "cmd", "com", "pif", "hta", "msi", "jar", "lnk", "iso", "img", "wsf", "ps1", "html", "htm", "shtml", "svg", "one")

    /**
     * What the engine already knows about the sender, used to weigh link evidence:
     * [senderProven] is true when DMARC passed (or SPF and an aligned DKIM signature both
     * passed), i.e. the From domain really sent this. Link/sender disagreement then cannot mean
     * impersonation, so those checks are reduced to a fraction of their weight.
     */
    data class SenderContext(
        val senderProven: Boolean = false,
        /** The user has corresponded with this sender (replied, opened several, marked not junk) and never marked it junk. */
        val knownSender: Boolean = false,
        /** Link domains this sender has used in several earlier messages the user did not mark as junk. */
        val familiarLinkApexes: Set<String> = emptySet()
    )

    private val auxiliaryText = Regex("""\b(unsubscribe|opt[ -]?out|manage (your |my )?(preferences|subscriptions?|emails?)|email preferences|view (this|it|email|in|online)|view in (a )?browser|privacy( policy| notice)?|terms( of (service|use))?|legal|help cent(er|re)|contact us|faq|support|forward to a friend|add (us )?to (your )?(address book|contacts)|app store|google play|download (the|our) app)\b""", RegexOption.IGNORE_CASE)
    private val auxiliaryPath = Regex("""(unsub|optout|opt-out|preferences|privacy|terms|legal|/help|/support|/faq|view-?in-?browser|webversion|mirror)""", RegexOption.IGNORE_CASE)
    private val socialHosts = setOf("facebook.com", "twitter.com", "x.com", "instagram.com", "linkedin.com", "youtube.com", "youtu.be", "pinterest.com", "tiktok.com", "threads.net", "reddit.com", "whatsapp.com", "t.me", "apple.com", "google.com", "play.google.com", "apps.apple.com")
    private val loginText = Regex("""\b(sign in|log ?in|verify|confirm|update (your|my) (account|password|payment|details)|my account|secure (your|my) account|reset (your|my) password|review (your|my) account|validate)\b""", RegexOption.IGNORE_CASE)

    /** Footer plumbing, social icons and app-store buttons: never the call to action, so not judged. */
    fun isAuxiliaryLink(link: HeaderParser.Link): Boolean {
        val host = HeaderParser.hostOf(link.href)?.lowercase() ?: return true
        val apex = HeaderParser.registrableDomain(host)
        if (apex in socialHosts || host in socialHosts) return true
        if (auxiliaryText.containsMatchIn(link.text.trim())) return true
        val path = link.href.substringAfter("://", link.href).substringAfter('/', "")
        return auxiliaryPath.containsMatchIn(path)
    }

    /** True when the anchor text itself asks the reader to sign in, verify or update an account. */
    fun isLoginLink(link: HeaderParser.Link): Boolean = loginText.containsMatchIn(link.text)

    fun evaluate(facts: MessageFacts, links: List<HeaderParser.Link>, text: String, ctx: SenderContext = SenderContext()): List<SpamReason> {
        val out = ArrayList<SpamReason>()
        val subject = facts.subject
        val lower = (subject + "\n" + text).lowercase()

        // Subject shape
        val letters = subject.filter { it.isLetter() }
        if (letters.length >= 8 && letters.count { it.isUpperCase() } >= letters.length * 0.8) {
            out += SpamReason("SUBJ_CAPS", "Subject is mostly capitals", "\"${subject.take(60)}\"", 6)
        }
        if (Regex("[!?]{2,}|[$]{3,}|!\\s*!").containsMatchIn(subject)) {
            out += SpamReason("SUBJ_PUNCT", "Excessive punctuation in subject", "\"${subject.take(60)}\"", 5)
        }
        if (Regex("^\\s*(re|fw|fwd)\\s*:", RegexOption.IGNORE_CASE).containsMatchIn(subject) && facts.receivedHeaders.size <= 2 &&
            !lower.contains("wrote:") && !lower.contains("original message") && !lower.contains("from:")) {
            out += SpamReason("FAKE_REPLY", "Looks like a reply but contains no quoted message", "Subject starts with Re:/Fwd: yet nothing is quoted", 8)
        }
        if (Regex("[\\x{1F300}-\\x{1FAFF}\\u2600-\\u27BF]").containsMatchIn(subject)) {
            out += SpamReason("SUBJ_EMOJI", "Emoji in subject", "", 3)
        }

        // Phrase families
        phraseHits(lower, urgency, "URGENCY", "Pressure / urgency language", 4, 16)?.let { out += it }
        phraseHits(lower, money, "MONEY", "Prize, money or refund bait", 5, 18)?.let { out += it }
        phraseHits(lower, scamPhrases, "SCAM_PHRASE", "Phrases common in phishing and scams", 4, 16)?.let { out += it }
        phraseHits(lower, adult, "ADULT", "Adult / pharmacy spam vocabulary", 8, 16)?.let { out += it }

        // Links
        val hosts = links.mapNotNull { HeaderParser.hostOf(it.href) }
        if (hosts.any { HeaderParser.isIpLiteral(it) }) {
            out += SpamReason("IP_LINK", "Link points to a bare IP address", hosts.first { HeaderParser.isIpLiteral(it) }, 15)
        }
        val shortened = hosts.filter { HeaderParser.registrableDomain(it) in shorteners }
        if (shortened.isNotEmpty()) {
            out += SpamReason("SHORT_URL", "Uses a link shortener", shortened.distinct().joinToString(", "), 6)
        }
        // Link text vs. destination. A visible *brand* hostname whose link really goes somewhere
        // unrelated is the classic phishing lure and scores fully; a tracked newsletter link
        // ("example.com" → click.list-manage.com) or a link to another domain of the same
        // organisation is normal. When the sender is authenticated the residual weight is small.
        val senderApex = HeaderParser.registrableDomain(HeaderParser.addressDomain(facts.fromAddress))
        fun ownedBySender(host: String): Boolean =
            LinkInfrastructure.sameOrganisation(host, senderApex) || HeaderParser.registrableDomain(host) in ctx.familiarLinkApexes
        // Only the calls to action are judged: footer plumbing (unsubscribe, preferences, privacy,
        // view in browser), social icons and app-store buttons are skipped.
        val actionLinks = links.filter { !isAuxiliaryLink(it) }
        if (!ctx.knownSender) for (link in actionLinks) {
            val textHost = Regex("""(?:https?://)?(?:www\.)?([a-z0-9-]+(?:\.[a-z0-9-]+)+)""", RegexOption.IGNORE_CASE)
                .find(link.text)?.groupValues?.get(1)?.lowercase() ?: continue
            val hrefHost = HeaderParser.hostOf(link.href) ?: continue
            if (HeaderParser.isIpLiteral(textHost)) continue
            if (HeaderParser.registrableDomain(textHost) == HeaderParser.registrableDomain(hrefHost)) continue
            if (LinkInfrastructure.sameOrganisation(textHost, hrefHost)) continue
            if (HeaderParser.registrableDomain(hrefHost) in ctx.familiarLinkApexes && ownedBySender(textHost)) continue
            if (LinkInfrastructure.isMailInfrastructure(hrefHost) && (ctx.senderProven || ownedBySender(textHost))) continue
            val lure = LinkInfrastructure.isKnownBrandDomain(textHost)
            val weight = when {
                lure && !ctx.senderProven -> 25
                lure -> 8
                ctx.senderProven -> 0
                else -> 6
            }
            if (weight == 0) continue
            out += SpamReason("LINK_TEXT_MISMATCH", "Link text shows one address but goes to another",
                "Shows \"$textHost\" but opens $hrefHost" + (if (ctx.senderProven) " (sender is authenticated, so this weighs little)" else ""), weight)
            break
        }
        // A sign-in request whose link leads to a domain the sender's organisation does not own.
        // Preferred evidence is a link whose own text says "sign in" / "verify"; failing that, the
        // body wording plus the calls to action. Mail-service infrastructure links are neutral
        // (their real target is unknown without following the redirect), familiar and
        // organisation-owned domains are fine, and an authenticated sender weighs little.
        if (!ctx.knownSender && senderApex.isNotBlank()) {
            val loginLinks = actionLinks.filter { isLoginLink(it) }
            // With no explicit sign-in link every link counts, footer ones included: a sender-owned
            // unsubscribe link still shows the mail links to the sender's own domain.
            val judged = (if (loginLinks.isNotEmpty()) loginLinks else links)
                .mapNotNull { HeaderParser.hostOf(it.href) }
                .filter { !LinkInfrastructure.isMailInfrastructure(it) && !HeaderParser.isIpLiteral(it) }
                .map { HeaderParser.registrableDomain(it) }.distinct()
            val foreign = judged.filter { !ownedBySender(it) }
            val mentionsLogin = loginLinks.isNotEmpty() || lower.contains("log in") || lower.contains("login") || lower.contains("sign in")
            if (mentionsLogin && foreign.isNotEmpty() && foreign.size == judged.size) {
                out += SpamReason("LOGIN_LINK_OFFSITE", "Asks you to sign in on a site unrelated to the sender",
                    "Sender $senderApex, " + (if (loginLinks.isNotEmpty()) "sign-in link goes to " else "links to ") + foreign.take(3).joinToString(", ") +
                        (if (ctx.senderProven) " (sender is authenticated)" else ""),
                    if (ctx.senderProven) 3 else 10)
            }
        }

        // Structure
        val html = facts.bodyHtml
        if (html != null) {
            val imgs = Regex("<img\\b", RegexOption.IGNORE_CASE).findAll(html).count()
            val words = text.split(Regex("\\s+")).count { it.length > 1 }
            if (imgs >= 1 && words < 25) {
                out += SpamReason("IMAGE_ONLY", "Almost no text, mostly images", "$imgs image(s), $words words", 10)
            }
            if (Regex("""(font-size\s*:\s*0|display\s*:\s*none|visibility\s*:\s*hidden|color\s*:\s*#?fff(?:fff)?\b)""", RegexOption.IGNORE_CASE).containsMatchIn(html) &&
                Regex("""<(div|span|p)[^>]*(font-size\s*:\s*0|display\s*:\s*none)""", RegexOption.IGNORE_CASE).containsMatchIn(html)) {
                out += SpamReason("HIDDEN_TEXT", "Contains hidden text (a filter-evasion trick)", "", 12)
            }
            if (Regex("<form\\b", RegexOption.IGNORE_CASE).containsMatchIn(html)) {
                out += SpamReason("HTML_FORM", "Contains an HTML form asking for input", "", 12)
            }
        }
        if (facts.bodyHtml == null && facts.bodyText.isBlank() && facts.attachmentNames.isNotEmpty()) {
            out += SpamReason("EMPTY_WITH_ATTACHMENT", "Empty body with only an attachment", facts.attachmentNames.joinToString(), 10)
        }
        val badAtt = facts.attachmentNames.filter { n -> n.substringAfterLast('.', "").lowercase() in dangerousExt }
        if (badAtt.isNotEmpty()) {
            out += SpamReason("DANGEROUS_ATTACHMENT", "Attachment type often used to deliver malware", badAtt.joinToString(", "), 20)
        }
        if (facts.attachmentNames.any { Regex("\\.(pdf|doc|docx|xls|xlsx|zip|rar|7z)\\.(exe|js|scr|vbs|bat)$", RegexOption.IGNORE_CASE).containsMatchIn(it) }) {
            out += SpamReason("DOUBLE_EXTENSION", "Attachment with a disguised double extension", "", 25)
        }

        // Addressing
        val user = facts.userAddresses.map { it.lowercase() }
        if (user.isNotEmpty() && facts.toAddresses.isNotEmpty() && facts.toAddresses.none { it.lowercase() in user }) {
            out += SpamReason("NOT_ADDRESSED_TO_YOU", "Your address is not in the To: line", "To: ${facts.toAddresses.take(2).joinToString()}", 6)
        }
        if (facts.toAddresses.isEmpty() && facts.precedence.isNullOrBlank()) {
            out += SpamReason("UNDISCLOSED_RECIPIENTS", "Sent to undisclosed recipients", "", 5)
        }
        if (facts.replyTo.isNotBlank() && facts.fromAddress.isNotBlank()) {
            val rApex = HeaderParser.registrableDomain(HeaderParser.addressDomain(facts.replyTo))
            if (rApex.isNotBlank() && rApex != senderApex) {
                val weight = if (BrandKnowledgeBase.isFreemail(rApex)) 14 else 8
                out += SpamReason("REPLY_TO_MISMATCH", "Replies go to a different domain than the sender", "From $senderApex, Reply-To ${facts.replyTo}", weight)
            }
        }
        if (facts.fromName.isNotBlank() && facts.fromName.contains('@')) {
            val nameAddr = Regex("[\\w.+-]+@[\\w.-]+").find(facts.fromName)?.value?.lowercase()
            if (nameAddr != null && nameAddr != facts.fromAddress.lowercase()) {
                out += SpamReason("NAME_IS_OTHER_ADDRESS", "Display name is a different email address", "\"${facts.fromName}\" vs ${facts.fromAddress}", 20)
            }
        }
        val nameNorm = BrandDetector.normaliseHomoglyphs(facts.fromName)
        if (facts.fromName.any { it.code > 0x2FF } && nameNorm != facts.fromName.lowercase() && facts.fromName.any { it.isLetter() && it.code < 0x80 }) {
            out += SpamReason("MIXED_SCRIPT_NAME", "Display name mixes alphabets (possible homoglyph spoofing)", "\"${facts.fromName}\"", 12)
        }
        if (facts.date > 0) {
            val now = System.currentTimeMillis()
            if (facts.date > now + 2 * 24 * 3600_000L) out += SpamReason("FUTURE_DATE", "Date header is in the future", "", 6)
            if (facts.date < now - 3 * 365 * 24 * 3600_000L) out += SpamReason("ANCIENT_DATE", "Date header is years in the past", "", 4)
        }
        return out
    }

    private fun phraseHits(lower: String, phrases: List<String>, code: String, title: String, each: Int, cap: Int): SpamReason? {
        val hits = phrases.filter { lower.contains(it) }
        if (hits.isEmpty()) return null
        return SpamReason(code, title, hits.take(4).joinToString(", ") { "\"$it\"" }, minOf(cap, each * hits.size))
    }
}
