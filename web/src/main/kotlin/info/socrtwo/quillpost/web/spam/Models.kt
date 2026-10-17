package info.socrtwo.quillpost.web.spam

/**
 * Everything the spam engine needs to know about one message. This is deliberately a plain
 * Kotlin model (no framework or serialization annotations) so the same engine source can be
 * dropped into the Android client unchanged.
 */
data class MessageFacts(
    val messageId: String,
    val fromName: String,
    val fromAddress: String,
    val replyTo: String = "",
    val toAddresses: List<String> = emptyList(),
    val subject: String,
    val bodyText: String,
    val bodyHtml: String? = null,
    val receivedHeaders: List<String> = emptyList(),
    val authenticationResults: List<String> = emptyList(),
    val originatingIpHeader: String? = null,
    val listUnsubscribe: String? = null,
    val precedence: String? = null,
    val mailer: String? = null,
    val attachmentNames: List<String> = emptyList(),
    val date: Long = 0L,
    /** The account owner's own address(es); used to spot mail not addressed to the user. */
    val userAddresses: List<String> = emptyList(),
    val ccAddresses: List<String> = emptyList(),
    /** "high", "normal" or "low" (from Importance / X-Priority). */
    val importance: String = "normal"
)

enum class SpamLevel { CLEAN, SUSPICIOUS, SPAM }

/** One human-readable contribution to the verdict. Positive weight = more spammy. */
data class SpamReason(
    val code: String,
    val title: String,
    val detail: String,
    val weight: Int
)

data class BlacklistHit(
    val list: String,
    val zone: String,
    /** The IP address or domain that was listed. */
    val subject: String,
    /** "ip" or "domain". */
    val kind: String,
    val response: String,
    val note: String
)

/** Per-blacklist outcome of a check so the UI can show which lists actually answered. */
data class BlacklistStatus(
    val list: String,
    val zone: String,
    val queried: Int,
    val hits: Int,
    /** True when the list refused the query (e.g. Spamhaus behind a public DNS resolver). */
    val refused: Boolean,
    val error: String? = null
)

data class AuthResults(
    val spf: String? = null,
    val dkim: String? = null,
    val dmarc: String? = null,
    val dkimDomain: String? = null,
    val raw: String? = null
) {
    val anyFailed: Boolean
        get() = listOf(spf, dkim, dmarc).any { it == "fail" || it == "softfail" || it == "permerror" }
}

data class BrandAnalysis(
    /** Canonical brand/organisation name the message appears to claim, or null. */
    val claimedBrand: String? = null,
    /** Where the claim was found: display name, subject, body, lookalike domain… */
    val claimSource: String? = null,
    /** The literal text that produced the claim. */
    val claimedText: String? = null,
    /** Domains the claimed brand legitimately sends from (empty for unknown organisations). */
    val legitimateDomains: List<String> = emptyList(),
    val senderDomain: String = "",
    /** True when the sender's domain does not belong to the organisation it claims to be. */
    val mismatch: Boolean = false,
    /** True when the sender domain is a typo-squat / homoglyph of a known brand domain. */
    val lookalike: Boolean = false,
    /** True when the sender uses a consumer webmail domain (gmail.com, outlook.com…). */
    val freemailSender: Boolean = false,
    /** True when the claimed brand is in the knowledge base (vs. a generic organisation). */
    val knownBrand: Boolean = false,
    /** True when the sender domain is a verified legitimate domain of the claimed brand. */
    val verified: Boolean = false,
    val explanation: String = ""
)

data class SpamVerdict(
    val score: Int,
    val level: SpamLevel,
    val reasons: List<SpamReason>,
    val brand: BrandAnalysis,
    val auth: AuthResults,
    val blacklistHits: List<BlacklistHit>,
    val blacklistStatus: List<BlacklistStatus>,
    val bayesProbability: Double?,
    val linkDomains: List<String>,
    val originatingIps: List<String>,
    val senderDomain: String,
    val matchedRule: Rule? = null,
    val safeSender: Boolean = false,
    val blockedSender: Boolean = false
) {
    val isSpam: Boolean get() = level == SpamLevel.SPAM
}

/** A DNS blacklist definition. [kind] is "ip" (DNSBL) or "domain" (RHSBL / URIBL). */
data class BlacklistDef(
    val id: String,
    val label: String,
    val zone: String,
    val kind: String,
    val weight: Int,
    val enabled: Boolean = true,
    val builtIn: Boolean = true,
    val homepage: String = ""
)

data class SpamConfig(
    val enabled: Boolean = true,
    /** Move messages the engine classifies as spam into the Junk folder automatically. */
    val autoMoveToJunk: Boolean = true,
    val spamThreshold: Int = 60,
    val suspiciousThreshold: Int = 30,
    val blacklists: List<BlacklistDef> = Blacklists.defaults(),
    val checkLinkDomains: Boolean = true,
    val useBayes: Boolean = true,
    val brandDetection: Boolean = true,
    /** A confirmed hit on a strong IP blacklist is enough on its own to classify as spam. */
    val blacklistHitIsSpam: Boolean = true,
    /** A known brand claimed from an unrelated domain is enough on its own to classify as spam. */
    val brandMismatchIsSpam: Boolean = true,
    val safeSenders: List<String> = emptyList(),
    val blockedSenders: List<String> = emptyList(),
    val dnsTimeoutMs: Int = 3000,
    val maxIpsToCheck: Int = 4,
    val maxDomainsToCheck: Int = 8
)

/** Built-in catalogue of free, open DNS blocklists (no API key required). */
object Blacklists {
    const val SPAMHAUS_ZEN = "spamhaus-zen"
    const val SPAMCOP = "spamcop"
    const val BARRACUDA = "barracuda"
    const val PSBL = "psbl"
    const val UCEPROTECT1 = "uceprotect-1"
    const val SPAMHAUS_DBL = "spamhaus-dbl"
    const val SURBL = "surbl"
    const val URIBL = "uribl"

    val catalogue: List<BlacklistDef> = listOf(
        BlacklistDef(SPAMHAUS_ZEN, "Spamhaus ZEN", "zen.spamhaus.org", "ip", 45, homepage = "https://www.spamhaus.org/blocklists/"),
        BlacklistDef(SPAMCOP, "SpamCop", "bl.spamcop.net", "ip", 40, homepage = "https://www.spamcop.net/bl.shtml"),
        BlacklistDef(BARRACUDA, "Barracuda Reputation", "b.barracudacentral.org", "ip", 40, homepage = "https://www.barracudacentral.org/rbl"),
        BlacklistDef(PSBL, "Passive Spam Block List", "psbl.surriel.com", "ip", 30, homepage = "https://psbl.org/"),
        BlacklistDef(UCEPROTECT1, "UCEPROTECT Level 1", "dnsbl-1.uceprotect.net", "ip", 25, enabled = false, homepage = "https://www.uceprotect.net/"),
        BlacklistDef(SPAMHAUS_DBL, "Spamhaus DBL (domains)", "dbl.spamhaus.org", "domain", 45, homepage = "https://www.spamhaus.org/blocklists/domain-block-list/"),
        BlacklistDef(SURBL, "SURBL (domains/links)", "multi.surbl.org", "domain", 40, homepage = "https://surbl.org/"),
        BlacklistDef(URIBL, "URIBL (domains/links)", "multi.uribl.com", "domain", 35, homepage = "https://uribl.com/")
    )

    fun defaults(): List<BlacklistDef> = catalogue
}
