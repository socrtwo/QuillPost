package info.socrtwo.quillpost.web.spam

enum class RuleField { SENDER, SENDER_NAME, SENDER_DOMAIN, SUBJECT, BODY, RECIPIENT, ATTACHMENT_NAME, CC, TO_OR_CC, HAS_ATTACHMENT, IMPORTANCE }
enum class RuleOperator { CONTAINS, NOT_CONTAINS, EQUALS, STARTS_WITH, ENDS_WITH, MATCHES_REGEX }
enum class RuleLogic { AND, OR }
enum class RuleAction { MOVE_TO_FOLDER, MARK_READ, DELETE, FLAG, MARK_SAFE, COPY_TO_FOLDER, CATEGORIZE, PIN, FORWARD_TO }

data class RuleCriterion(val field: RuleField, val operator: RuleOperator = RuleOperator.CONTAINS, val value: String)

/** A user (or AI-proposed, user-approved) filtering rule. First matching rule wins. */
data class Rule(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val logic: RuleLogic = RuleLogic.OR,
    val criteria: List<RuleCriterion>,
    val action: RuleAction = RuleAction.MOVE_TO_FOLDER,
    val targetFolder: String? = "Junk",
    /** Lower numbers evaluate first. */
    val priority: Int = 100,
    /** "user" or "ai". */
    val createdBy: String = "user",
    val createdAt: Long = System.currentTimeMillis(),
    val note: String = "",
    /** How many messages this rule has acted on. */
    val hitCount: Int = 0,
    /** Categories assigned by [RuleAction.CATEGORIZE]. */
    val categories: List<String> = emptyList(),
    /** Recipient for [RuleAction.FORWARD_TO]. */
    val forwardTo: String = ""
)

data class RuleOutcome(
    val rule: Rule? = null,
    val targetFolder: String? = null,
    val markRead: Boolean = false,
    val delete: Boolean = false,
    val flag: Boolean = false,
    val safe: Boolean = false
) {
    val matched: Boolean get() = rule != null
}

object RuleEngine {

    fun evaluate(facts: MessageFacts, rules: List<Rule>): RuleOutcome {
        val sorted = rules.filter { it.enabled }.sortedWith(compareBy({ it.priority }, { it.createdAt }))
        for (rule in sorted) {
            if (!matches(rule, facts)) continue
            return when (rule.action) {
                RuleAction.MOVE_TO_FOLDER -> RuleOutcome(rule, targetFolder = rule.targetFolder ?: "Junk")
                RuleAction.MARK_READ -> RuleOutcome(rule, markRead = true)
                RuleAction.DELETE -> RuleOutcome(rule, delete = true)
                RuleAction.FLAG -> RuleOutcome(rule, flag = true)
                RuleAction.MARK_SAFE -> RuleOutcome(rule, safe = true)
                RuleAction.COPY_TO_FOLDER, RuleAction.CATEGORIZE, RuleAction.PIN, RuleAction.FORWARD_TO -> RuleOutcome(rule)
            }
        }
        return RuleOutcome()
    }

    fun matches(rule: Rule, facts: MessageFacts): Boolean {
        if (rule.criteria.isEmpty()) return false
        val results = rule.criteria.map { matches(it, facts) }
        return if (rule.logic == RuleLogic.AND) results.all { it } else results.any { it }
    }

    fun matches(c: RuleCriterion, facts: MessageFacts): Boolean {
        val haystacks: List<String> = when (c.field) {
            RuleField.SENDER -> listOf(facts.fromAddress, "${facts.fromName} <${facts.fromAddress}>")
            RuleField.SENDER_NAME -> listOf(facts.fromName)
            RuleField.SENDER_DOMAIN -> listOf(HeaderParser.addressDomain(facts.fromAddress))
            RuleField.SUBJECT -> listOf(facts.subject)
            RuleField.BODY -> listOf(facts.bodyText.ifBlank { facts.bodyHtml?.let { HeaderParser.htmlToText(it) } ?: "" })
            RuleField.RECIPIENT -> facts.toAddresses.ifEmpty { listOf("") }
            RuleField.ATTACHMENT_NAME -> facts.attachmentNames.ifEmpty { listOf("") }
            RuleField.CC -> facts.ccAddresses.ifEmpty { listOf("") }
            RuleField.TO_OR_CC -> (facts.toAddresses + facts.ccAddresses).ifEmpty { listOf("") }
            // Flag-style conditions: the value says which state matches ("true"/"false", "high"/"low").
            RuleField.HAS_ATTACHMENT -> return facts.attachmentNames.isNotEmpty() == !c.value.trim().equals("false", true)
            RuleField.IMPORTANCE -> return facts.importance.equals(c.value.trim(), ignoreCase = true)
        }
        val value = c.value.trim()
        if (value.isEmpty()) return false
        val hit = haystacks.any { h -> test(h, c.operator, value) }
        return hit
    }

    private fun test(h: String, op: RuleOperator, v: String): Boolean = when (op) {
        RuleOperator.CONTAINS -> h.contains(v, ignoreCase = true)
        RuleOperator.NOT_CONTAINS -> !h.contains(v, ignoreCase = true)
        RuleOperator.EQUALS -> h.trim().equals(v, ignoreCase = true)
        RuleOperator.STARTS_WITH -> h.trim().startsWith(v, ignoreCase = true)
        RuleOperator.ENDS_WITH -> h.trim().endsWith(v, ignoreCase = true)
        RuleOperator.MATCHES_REGEX -> runCatching { Regex(v, RegexOption.IGNORE_CASE).containsMatchIn(h) }.getOrDefault(false)
    }

    fun describe(c: RuleCriterion): String {
        val field = when (c.field) {
            RuleField.SENDER -> "sender address"
            RuleField.SENDER_NAME -> "sender name"
            RuleField.SENDER_DOMAIN -> "sender domain"
            RuleField.SUBJECT -> "subject"
            RuleField.BODY -> "body"
            RuleField.RECIPIENT -> "recipient"
            RuleField.ATTACHMENT_NAME -> "attachment name"
            RuleField.CC -> "Cc"
            RuleField.TO_OR_CC -> "To or Cc"
            RuleField.HAS_ATTACHMENT -> return if (c.value.equals("false", true)) "has no attachment" else "has an attachment"
            RuleField.IMPORTANCE -> return "importance is ${c.value.lowercase()}"
        }
        val op = when (c.operator) {
            RuleOperator.CONTAINS -> "contains"
            RuleOperator.NOT_CONTAINS -> "does not contain"
            RuleOperator.EQUALS -> "is"
            RuleOperator.STARTS_WITH -> "starts with"
            RuleOperator.ENDS_WITH -> "ends with"
            RuleOperator.MATCHES_REGEX -> "matches regex"
        }
        return "$field $op \"${c.value}\""
    }
}

data class ProposedCriterion(val criterion: RuleCriterion, val rationale: String, val confidence: Int)

data class RuleProposal(
    val rule: Rule,
    val criteria: List<ProposedCriterion>,
    val summary: String,
    val claimedOrganisation: String?,
    val verdictSummary: String
)

/**
 * Builds a rule that would catch this message and others like it. The rule keys on the most
 * stable, specific features (sender domain / address, spoofed display name, distinctive
 * subject phrase) so it generalises to "similar" mail without sweeping up legitimate mail.
 */
object RuleProposer {

    /** Words too generic to anchor a subject phrase on (kept deliberately small). */
    private val stop = setOf(
        "the", "of", "and", "for", "your", "our", "from", "via", "at", "in", "on", "by", "to", "a", "an", "is",
        "new", "my", "me", "you", "us", "re", "fw", "fwd", "hi", "hello", "dear", "please", "important",
        "regarding", "about", "with", "this", "that", "have", "has", "been", "will", "can", "now", "today",
        "tomorrow", "just", "here", "there", "get", "got", "one", "two", "all", "any", "are", "was", "were",
        "its", "it's", "you're", "we're", "let", "lets", "let's", "not", "but", "out", "off"
    )

    fun propose(facts: MessageFacts, verdict: SpamVerdict, bayes: BayesClassifier?, targetFolder: String = "Junk"): RuleProposal {
        val proposed = ArrayList<ProposedCriterion>()
        val senderDomain = HeaderParser.addressDomain(facts.fromAddress)
        val apex = HeaderParser.registrableDomain(senderDomain)
        val brand = verdict.brand

        // 1. Sender
        if (facts.fromAddress.isNotBlank()) {
            if (BrandKnowledgeBase.isSharedSender(senderDomain) || apex.isBlank()) {
                proposed += ProposedCriterion(
                    RuleCriterion(RuleField.SENDER, RuleOperator.EQUALS, facts.fromAddress.lowercase()),
                    "$apex is a shared mail service used by many unrelated people, so the rule keys on this exact address rather than the whole domain.",
                    85
                )
            } else if (brand.verified && brand.knownBrand) {
                proposed += ProposedCriterion(
                    RuleCriterion(RuleField.SENDER, RuleOperator.EQUALS, facts.fromAddress.lowercase()),
                    "The sender domain belongs to ${brand.claimedBrand}, so only this exact address is blocked, not the whole organisation.",
                    70
                )
            } else {
                proposed += ProposedCriterion(
                    RuleCriterion(RuleField.SENDER_DOMAIN, RuleOperator.ENDS_WITH, apex),
                    "Blocks everything from $apex and its sub-domains. Spammers rotate addresses within a domain far more often than they change domains.",
                    90
                )
            }
        }

        // 2. Spoofed display name
        val nameClaim = BrandDetector.claimFromText(facts.fromName, "display name", 90)
        if (brand.mismatch && nameClaim?.brand != null && facts.fromName.isNotBlank()) {
            proposed += ProposedCriterion(
                RuleCriterion(RuleField.SENDER_NAME, RuleOperator.CONTAINS, facts.fromName.trim()),
                "The display name \"${facts.fromName}\" impersonates ${nameClaim.brand.name}. Future messages reusing this name from any other address will match too.",
                80
            )
        } else if (brand.mismatch && !brand.knownBrand && facts.fromName.isNotBlank() && facts.fromName.length >= 4) {
            proposed += ProposedCriterion(
                RuleCriterion(RuleField.SENDER_NAME, RuleOperator.CONTAINS, facts.fromName.trim()),
                "The organisation name \"${facts.fromName}\" does not match the sender's domain; other messages using it will match too.",
                60
            )
        }

        // 3. Distinctive subject phrase
        subjectPhrase(facts.subject, bayes)?.let { (phrase, why) ->
            proposed += ProposedCriterion(RuleCriterion(RuleField.SUBJECT, RuleOperator.CONTAINS, phrase), why, 55)
        }

        // 4. Distinctive body phrase (only strong, multi-word scam phrases)
        val text = facts.bodyText.ifBlank { facts.bodyHtml?.let { HeaderParser.htmlToText(it) } ?: "" }
        bodyPhrase(text, verdict)?.let { (phrase, why) ->
            proposed += ProposedCriterion(RuleCriterion(RuleField.BODY, RuleOperator.CONTAINS, phrase), why, 45)
        }

        // 5. Reply-To address used to harvest replies
        if (facts.replyTo.isNotBlank() && !facts.replyTo.equals(facts.fromAddress, ignoreCase = true)) {
            val rApex = HeaderParser.registrableDomain(HeaderParser.addressDomain(facts.replyTo))
            if (rApex.isNotBlank() && rApex != apex) {
                proposed += ProposedCriterion(
                    RuleCriterion(RuleField.BODY, RuleOperator.CONTAINS, facts.replyTo.lowercase()),
                    "Replies are routed to ${facts.replyTo}; scammers reuse the same reply address across many sender identities.",
                    40
                )
            }
        }

        val nameTarget = when {
            brand.mismatch && brand.knownBrand -> "fake ${brand.claimedBrand}"
            apex.isNotBlank() && !BrandKnowledgeBase.isSharedSender(senderDomain) -> apex
            facts.fromAddress.isNotBlank() -> facts.fromAddress.lowercase()
            else -> facts.subject.take(30)
        }
        val rule = Rule(
            id = "ai-" + java.util.UUID.randomUUID().toString().take(8),
            name = "Junk: $nameTarget",
            logic = RuleLogic.OR,
            criteria = proposed.map { it.criterion },
            action = RuleAction.MOVE_TO_FOLDER,
            targetFolder = targetFolder,
            priority = 50,
            createdBy = "ai",
            note = "Proposed from \"${facts.subject.take(60)}\" (${facts.fromAddress})"
        )
        val summary = buildString {
            append("Any message matching ")
            append(if (proposed.size > 1) "any one of ${proposed.size} conditions" else "this condition")
            append(" will be moved to $targetFolder. ")
            append("The conditions were chosen from the features of this message that spammers reuse most.")
        }
        val verdictSummary = when (verdict.level) {
            SpamLevel.SPAM -> "Classified as spam (score ${verdict.score}/100)."
            SpamLevel.SUSPICIOUS -> "Suspicious (score ${verdict.score}/100)."
            SpamLevel.CLEAN -> "Looks legitimate (score ${verdict.score}/100), but you can still filter it."
        } + " " + brand.explanation
        return RuleProposal(rule, proposed, summary, brand.claimedBrand, verdictSummary)
    }

    /** Picks a 2–3 word phrase from the subject that best characterises it. */
    fun subjectPhrase(subject: String, bayes: BayesClassifier?): Pair<String, String>? {
        val cleaned = subject.replace(Regex("^\\s*((re|fw|fwd)\\s*:\\s*)+", RegexOption.IGNORE_CASE), "").trim()
        val words = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}'’-]*").findAll(cleaned).map { it.value }.toList()
        val content = words.filter { w -> w.length >= 3 && w.lowercase() !in stop && !w.all { it.isDigit() } }
        if (content.size < 2) return null
        // Prefer windows of consecutive content words as they appear in the subject.
        val positions = words.withIndex().filter { (_, w) -> w.length >= 3 && w.lowercase() !in stop && !w.all { it.isDigit() } }
        var best: List<String>? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (size in listOf(3, 2)) {
            for (i in 0..positions.size - size) {
                val window = positions.subList(i, i + size)
                if (window.last().index - window.first().index != size - 1) continue // must be adjacent
                val phrase = window.map { it.value }
                val score = if (bayes != null && bayes.ready)
                    phrase.sumOf { bayes.tokenProbability("subj:" + it.lowercase()) - 0.5 } + size * 0.05
                else phrase.sumOf { it.length } / 10.0 + size * 0.5
                if (score > bestScore) { bestScore = score; best = phrase }
            }
        }
        val phrase = best ?: return null
        val text = phrase.joinToString(" ")
        // Recover original spacing/punctuation-free form as it appears in the subject.
        val re = Regex(phrase.joinToString("\\W+") { Regex.escape(it) }, RegexOption.IGNORE_CASE)
        val literal = re.find(cleaned)?.value ?: text
        return literal to "Subject phrase \"$literal\" is distinctive for this kind of message; the same wording tends to be reused across a campaign."
    }

    private val strongBodyPhrases = listOf(
        "verify your account", "confirm your account", "verify your identity", "update your payment",
        "your account has been suspended", "your account has been limited", "unusual sign-in", "unusual activity",
        "you have been selected", "you have won", "claim your prize", "claim your reward", "gift card",
        "extended warranty", "no credit check", "guaranteed returns", "work from home", "renew your subscription",
        "has been charged", "will be charged", "call us immediately", "kindly reply", "wire transfer",
        "password will expire", "password expires", "mailbox is full", "storage is full", "upgrade your mailbox",
        "redelivery fee", "could not be delivered", "delivery attempt", "customs fee", "shipping fee",
        "tech support", "remote access", "geek squad", "norton", "mcafee"
    )

    fun bodyPhrase(text: String, verdict: SpamVerdict): Pair<String, String>? {
        if (text.isBlank()) return null
        val lower = text.lowercase()
        val hit = strongBodyPhrases.filter { lower.contains(it) }.maxByOrNull { it.length } ?: return null
        val idx = lower.indexOf(hit)
        val literal = text.substring(idx, idx + hit.length)
        return literal to "The body contains \"$literal\", a phrase typical of this scam family."
    }
}
