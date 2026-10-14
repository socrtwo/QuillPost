package info.socrtwo.quillbox.web

import info.socrtwo.quillbox.web.spam.BlacklistDef
import info.socrtwo.quillbox.web.spam.MessageFacts
import info.socrtwo.quillbox.web.spam.ProposedCriterion
import info.socrtwo.quillbox.web.spam.Rule
import info.socrtwo.quillbox.web.spam.RuleAction
import info.socrtwo.quillbox.web.spam.RuleCriterion
import info.socrtwo.quillbox.web.spam.RuleField
import info.socrtwo.quillbox.web.spam.RuleLogic
import info.socrtwo.quillbox.web.spam.RuleOperator
import info.socrtwo.quillbox.web.spam.RuleProposal
import info.socrtwo.quillbox.web.spam.SpamConfig
import info.socrtwo.quillbox.web.spam.SpamVerdict

/** Conversions between the wire DTOs and the plain-Kotlin engine model. */
object Mapping {

    fun RuleDto.toRule(): Rule = Rule(
        id = id, name = name, enabled = enabled,
        logic = runCatching { RuleLogic.valueOf(logic.uppercase()) }.getOrDefault(RuleLogic.OR),
        criteria = criteria.mapNotNull { c ->
            val field = runCatching { RuleField.valueOf(c.field.uppercase()) }.getOrNull() ?: return@mapNotNull null
            val op = runCatching { RuleOperator.valueOf(c.operator.uppercase()) }.getOrDefault(RuleOperator.CONTAINS)
            RuleCriterion(field, op, c.value)
        },
        action = runCatching { RuleAction.valueOf(action.uppercase()) }.getOrDefault(RuleAction.MOVE_TO_FOLDER),
        targetFolder = targetFolder, priority = priority, createdBy = createdBy, createdAt = createdAt, note = note, hitCount = hitCount,
        categories = categories, forwardTo = forwardTo
    )

    fun Rule.toDto(): RuleDto = RuleDto(
        id = id, name = name, enabled = enabled, logic = logic.name,
        criteria = criteria.map { RuleCriterionDto(it.field.name, it.operator.name, it.value) },
        action = action.name, targetFolder = targetFolder, priority = priority, createdBy = createdBy,
        createdAt = createdAt, note = note, hitCount = hitCount, categories = categories, forwardTo = forwardTo
    )

    fun BlacklistDefDto.toDef() = BlacklistDef(id, label, zone, kind, weight, enabled, builtIn, homepage)

    fun SpamConfigDto.toConfig(): SpamConfig = SpamConfig(
        enabled = enabled, autoMoveToJunk = autoMoveToJunk, spamThreshold = spamThreshold,
        suspiciousThreshold = suspiciousThreshold, blacklists = blacklists.map { it.toDef() },
        checkLinkDomains = checkLinkDomains, useBayes = useBayes, brandDetection = brandDetection,
        blacklistHitIsSpam = blacklistHitIsSpam, brandMismatchIsSpam = brandMismatchIsSpam,
        safeSenders = safeSenders, blockedSenders = blockedSenders, dnsTimeoutMs = dnsTimeoutMs
    )

    fun SpamVerdict.toDto(now: Long = System.currentTimeMillis()): VerdictDto = VerdictDto(
        score = score, level = level.name,
        reasons = reasons.map { SpamReasonDto(it.code, it.title, it.detail, it.weight) },
        brand = BrandDto(brand.claimedBrand, brand.claimSource, brand.claimedText, brand.legitimateDomains, brand.senderDomain,
            brand.mismatch, brand.lookalike, brand.freemailSender, brand.knownBrand, brand.verified, brand.explanation),
        auth = AuthDto(auth.spf, auth.dkim, auth.dmarc, auth.dkimDomain),
        blacklistHits = blacklistHits.map { BlacklistHitDto(it.list, it.zone, it.subject, it.kind, it.response, it.note) },
        blacklistStatus = blacklistStatus.map { BlacklistStatusDto(it.list, it.zone, it.queried, it.hits, it.refused, it.error) },
        bayesProbability = bayesProbability, linkDomains = linkDomains, originatingIps = originatingIps,
        senderDomain = senderDomain, matchedRule = matchedRule?.name, safeSender = safeSender, blockedSender = blockedSender,
        analyzedAt = now
    )

    fun RuleProposal.toDto(): RuleProposalDto = RuleProposalDto(
        rule = rule.toDto(),
        criteria = criteria.map { it.toDto() },
        summary = summary, claimedOrganisation = claimedOrganisation, verdictSummary = verdictSummary
    )

    fun ProposedCriterion.toDto() = ProposedCriterionDto(RuleCriterionDto(criterion.field.name, criterion.operator.name, criterion.value), rationale, confidence)

    fun RawSummary.toFacts(userAddresses: List<String>, bodyText: String = "", bodyHtml: String? = null, attachmentNames: List<String> = emptyList()): MessageFacts = MessageFacts(
        messageId = messageId, fromName = fromName, fromAddress = fromAddress, replyTo = replyTo, toAddresses = to,
        subject = subject, bodyText = bodyText, bodyHtml = bodyHtml,
        receivedHeaders = headers["Received"] ?: emptyList(),
        authenticationResults = (headers["Authentication-Results"] ?: emptyList()) + (headers["ARC-Authentication-Results"] ?: emptyList()),
        originatingIpHeader = headers["X-Originating-IP"]?.firstOrNull(),
        listUnsubscribe = headers["List-Unsubscribe"]?.firstOrNull(),
        precedence = headers["Precedence"]?.firstOrNull(),
        mailer = headers["X-Mailer"]?.firstOrNull(),
        attachmentNames = attachmentNames, date = date, userAddresses = userAddresses,
        ccAddresses = cc, importance = importanceOf(headers)
    )

    /** "high", "low" or "normal" from the Importance, Priority or X-Priority header. */
    fun importanceOf(headers: Map<String, List<String>>): String {
        val imp = (headers["Importance"]?.firstOrNull() ?: headers["Priority"]?.firstOrNull())?.trim()?.lowercase()
        when {
            imp == null -> {}
            imp.startsWith("high") || imp.startsWith("urgent") -> return "high"
            imp.startsWith("low") || imp.startsWith("non-urgent") -> return "low"
        }
        val xp = headers["X-Priority"]?.firstOrNull()?.trim()?.take(1)?.toIntOrNull() ?: return "normal"
        return when { xp <= 2 -> "high"; xp >= 4 -> "low"; else -> "normal" }
    }

    fun RawMessage.toFacts(userAddresses: List<String>): MessageFacts =
        summary.toFacts(userAddresses, bodyText, bodyHtml, attachments.filter { !it.inline }.map { it.fileName })

    fun RawSummary.toSummaryDto(folder: String, cached: CachedAnalysis?): MessageSummaryDto = MessageSummaryDto(
        uid = uid, folder = folder, messageId = messageId, fromName = fromName, fromAddress = fromAddress, to = to,
        subject = subject, preview = cached?.preview ?: "", date = date, read = seen, flagged = flagged, answered = answered,
        hasAttachments = hasAttachments, size = size, verdict = cached?.verdict,
        cc = cc, inReplyTo = headers["In-Reply-To"]?.firstOrNull()?.let { messageIds(it).lastOrNull() },
        references = headers["References"]?.joinToString(" ")?.let { messageIds(it) } ?: emptyList(),
        importance = importanceOf(headers), forwarded = forwarded
    )

    /** All <message-id> tokens in a References / In-Reply-To header, in order. */
    fun messageIds(header: String): List<String> = Regex("<[^<>\\s]+>").findAll(header).map { it.value }.toList()

    fun preview(text: String, html: String?): String {
        val t = text.ifBlank { html?.let { info.socrtwo.quillbox.web.spam.HeaderParser.htmlToText(it) } ?: "" }
        return t.replace(Regex("\\s+"), " ").trim().take(200)
    }
}
