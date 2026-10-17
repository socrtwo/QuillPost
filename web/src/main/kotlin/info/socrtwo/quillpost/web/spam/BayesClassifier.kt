package info.socrtwo.quillpost.web.spam

/**
 * A small multinomial naive-Bayes text classifier (the same family of model behind
 * SpamBayes / SpamAssassin's Bayes module). It runs entirely on-device, needs no API, and
 * learns from the user's own "Junk" / "Not junk" decisions. A seed corpus gives it a sensible
 * starting point before any training has happened.
 */
class BayesClassifier {
    private val spamCounts = HashMap<String, Int>()
    private val hamCounts = HashMap<String, Int>()
    private var spamMessages = 0
    private var hamMessages = 0
    private var spamTokens = 0
    private var hamTokens = 0
    /** Number of built-in seed examples included in the counts above. */
    private var seeded = 0

    val trainedSpam: Int get() = spamMessages
    val trainedHam: Int get() = hamMessages
    /** Examples the user has personally labelled (excludes the built-in seed corpus). */
    val userExamples: Int get() = maxOf(0, spamMessages + hamMessages - seeded)
    val vocabulary: Int get() = (spamCounts.keys + hamCounts.keys).size

    companion object {
        private val tokenRe = Regex("""[\p{L}\p{N}][\p{L}\p{N}'’$€£%-]{1,30}""")
        private val stop = setOf(
            "the", "and", "for", "you", "your", "with", "this", "that", "are", "have", "from", "not", "but",
            "was", "were", "will", "can", "all", "any", "our", "out", "has", "had", "its", "they", "them",
            "then", "than", "into", "also", "been", "more", "about", "would", "could", "should", "there",
            "their", "which", "when", "what", "who", "how", "why", "just", "here", "over", "very", "some",
            "only", "other", "such", "these", "those", "does", "did", "him", "her", "his", "she", "yes"
        )

        /** Tokenises subject/body text plus a few structural header tokens. */
        fun tokenize(facts: MessageFacts): List<String> = tokenize(
            subject = facts.subject,
            body = facts.bodyText.ifBlank { facts.bodyHtml?.let { HeaderParser.htmlToText(it) } ?: "" },
            fromAddress = facts.fromAddress,
            fromName = facts.fromName,
            linkDomains = HeaderParser.extractLinks(facts.bodyHtml, facts.bodyText)
                .mapNotNull { HeaderParser.hostOf(it.href) }.map { HeaderParser.registrableDomain(it) }.distinct()
        )

        fun tokenize(subject: String, body: String, fromAddress: String = "", fromName: String = "", linkDomains: List<String> = emptyList()): List<String> {
            val out = ArrayList<String>()
            tokenRe.findAll(subject.lowercase()).forEach { m ->
                val t = m.value
                if (t.length >= 3 && t !in stop) out.add("subj:$t")
            }
            val seen = HashSet<String>()
            var n = 0
            for (m in tokenRe.findAll(body.lowercase())) {
                val t = m.value
                if (t.length < 3 || t in stop) continue
                if (seen.add(t)) out.add(t)          // binary presence per message
                if (++n > 4000) break
            }
            HeaderParser.addressDomain(fromAddress).takeIf { it.isNotBlank() }?.let { out.add("from:${HeaderParser.registrableDomain(it)}") }
            tokenRe.findAll(fromName.lowercase()).forEach { out.add("name:${it.value}") }
            linkDomains.take(15).forEach { out.add("url:$it") }
            return out
        }
    }

    @Synchronized
    fun train(tokens: Collection<String>, spam: Boolean) {
        val counts = if (spam) spamCounts else hamCounts
        for (t in tokens) counts[t] = (counts[t] ?: 0) + 1
        if (spam) { spamMessages++; spamTokens += tokens.size } else { hamMessages++; hamTokens += tokens.size }
    }

    /** Removes one earlier training example so a mis-click can be corrected. */
    @Synchronized
    fun untrain(tokens: Collection<String>, spam: Boolean) {
        val counts = if (spam) spamCounts else hamCounts
        for (t in tokens) {
            val c = (counts[t] ?: 0) - 1
            if (c <= 0) counts.remove(t) else counts[t] = c
        }
        if (spam) { spamMessages = maxOf(0, spamMessages - 1); spamTokens = maxOf(0, spamTokens - tokens.size) }
        else { hamMessages = maxOf(0, hamMessages - 1); hamTokens = maxOf(0, hamTokens - tokens.size) }
    }

    /** Whether there is enough training data to give a meaningful probability. */
    val ready: Boolean get() = spamMessages >= 3 && hamMessages >= 3

    /** Spam probability of an individual token (Robinson's degree-of-belief smoothing). */
    @Synchronized
    fun tokenProbability(token: String): Double {
        val s = spamCounts[token] ?: 0
        val h = hamCounts[token] ?: 0
        if (s + h == 0) return 0.5
        val spamFreq = if (spamMessages == 0) 0.0 else s.toDouble() / spamMessages
        val hamFreq = if (hamMessages == 0) 0.0 else h.toDouble() / hamMessages
        val p = if (spamFreq + hamFreq == 0.0) 0.5 else spamFreq / (spamFreq + hamFreq)
        val n = s + h
        // Pull rare tokens toward 0.5 (strength 1, prior 0.5).
        return (0.5 + n * p) / (1 + n)
    }

    /** Probability (0..1) that a message with these tokens is spam. Null if untrained. */
    @Synchronized
    fun classify(tokens: Collection<String>): Double? {
        if (!ready) return null
        // Use the 25 most decisive tokens (Graham's approach) to avoid dilution by neutral words.
        val decisive = tokens.distinct().map { it to tokenProbability(it) }
            .sortedByDescending { kotlin.math.abs(it.second - 0.5) }
            .take(25)
        if (decisive.isEmpty()) return null
        var logSpam = 0.0
        var logHam = 0.0
        for ((_, p) in decisive) {
            val pc = p.coerceIn(0.01, 0.99)
            logSpam += kotlin.math.ln(pc)
            logHam += kotlin.math.ln(1 - pc)
        }
        // Convert back to a probability without overflow.
        val diff = logSpam - logHam
        return 1.0 / (1.0 + kotlin.math.exp(-diff))
    }

    /** Tokens that pushed the classification toward spam the most; used for rule proposals. */
    @Synchronized
    fun spammiestTokens(tokens: Collection<String>, limit: Int = 8): List<Pair<String, Double>> =
        tokens.distinct().map { it to tokenProbability(it) }.filter { it.second > 0.75 }
            .sortedByDescending { it.second }.take(limit)

    // --- persistence -------------------------------------------------------------------

    @Synchronized
    fun serialize(): String {
        val sb = StringBuilder()
        sb.append("# quillpost-bayes v1\n")
        sb.append("meta\t$spamMessages\t$hamMessages\t$spamTokens\t$hamTokens\t$seeded\n")
        val keys = (spamCounts.keys + hamCounts.keys).toSortedSet()
        for (k in keys) sb.append(k.replace('\t', ' ').replace('\n', ' ')).append('\t')
            .append(spamCounts[k] ?: 0).append('\t').append(hamCounts[k] ?: 0).append('\n')
        return sb.toString()
    }

    @Synchronized
    fun load(text: String) {
        spamCounts.clear(); hamCounts.clear()
        spamMessages = 0; hamMessages = 0; spamTokens = 0; hamTokens = 0; seeded = 0
        for (line in text.lineSequence()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val parts = line.split('\t')
            if (parts[0] == "meta" && parts.size >= 5) {
                spamMessages = parts[1].toIntOrNull() ?: 0
                hamMessages = parts[2].toIntOrNull() ?: 0
                spamTokens = parts[3].toIntOrNull() ?: 0
                hamTokens = parts[4].toIntOrNull() ?: 0
                seeded = parts.getOrNull(5)?.toIntOrNull() ?: 0
            } else if (parts.size >= 3) {
                val s = parts[1].toIntOrNull() ?: 0
                val h = parts[2].toIntOrNull() ?: 0
                if (s > 0) spamCounts[parts[0]] = s
                if (h > 0) hamCounts[parts[0]] = h
            }
        }
    }

    /**
     * Seeds the model with a handful of stereotypical spam and legitimate messages so it gives
     * useful answers before the user has trained it. Each seed counts as one message.
     */
    fun seed() {
        val spam = listOf(
            "congratulations you have won a prize claim your reward now lottery winner selected" to "you have been selected as the lucky winner of our lottery draw claim your prize money now by replying with your bank details",
            "urgent your account has been suspended verify now" to "we detected unusual activity on your account and it has been limited click here to verify your identity within 24 hours or your account will be permanently closed",
            "final notice invoice payment overdue" to "this is your final notice your invoice is overdue pay immediately to avoid legal action attached invoice wire transfer",
            "your package could not be delivered" to "we attempted to deliver your parcel but no one was available to schedule redelivery please confirm your address and pay the small redelivery fee",
            "cheap viagra cialis pharmacy discount" to "buy cheap meds online no prescription needed viagra cialis discount pharmacy lowest prices guaranteed order now",
            "make money fast work from home" to "earn thousands per week working from home no experience required limited spots act now click the link below to start earning today",
            "your password expires today" to "your mailbox password expires today keep your current password by clicking here otherwise you will lose access to your email account",
            "security alert unusual sign in attempt" to "someone signed in to your account from a new device if this was not you secure your account immediately by confirming your password",
            "you have a new voicemail" to "you have received a new encrypted voice message listen to it by opening the attached html file and signing in with your email credentials",
            "invoice attached please review" to "please find attached the invoice for your recent purchase of antivirus subscription renewal 399.99 has been charged to your card to cancel call our helpline",
            "crypto investment opportunity guaranteed returns" to "invest in bitcoin today guaranteed 300 percent returns within 30 days our trading bot never loses join thousands of happy investors",
            "hot singles in your area" to "meet lonely singles near you tonight free registration adult dating no strings attached click here",
            "re payment confirmation" to "dear customer your payment of 499 usd to geek squad has been processed if you did not authorize this transaction call us immediately at the number below to get a refund",
            "we tried to reach you" to "this is our third attempt to reach you regarding your car's extended warranty respond now before coverage expires",
            "important document shared with you" to "a confidential document has been shared with you via docusign review document by signing in with your microsoft office 365 credentials",
            "act now limited time offer" to "exclusive deal only for you 90 percent off today only hurry offer expires at midnight unsubscribe here",
            "your tax refund is ready" to "hmrc irs has calculated that you are due a tax refund submit your refund request with your card details to receive it",
            "claim your gift card" to "you have been chosen to receive a 500 dollar walmart amazon gift card complete the short survey to claim it now",
            "account verification required" to "your paypal account has been temporarily restricted because we could not verify your information log in through the link below to restore full access",
            "loan approved" to "you are pre approved for a personal loan of up to 50000 no credit check bad credit ok apply today instant approval"
        )
        val ham = listOf(
            "meeting tomorrow at 10" to "hi just confirming our meeting tomorrow at 10 in the small conference room let me know if that still works for you thanks",
            "re project update" to "thanks for the update i have reviewed the draft and left a few comments in the document happy to discuss on our call this afternoon",
            "photos from the weekend" to "here are the photos from saturday it was great to see everyone let's do it again soon love mum",
            "your order has shipped" to "good news your order 112-3344 has shipped and is expected to arrive on thursday track your package in your account",
            "dinner on friday" to "are you free for dinner on friday we could try the new italian place on main street let me know what time suits",
            "minutes from today's committee meeting" to "please find attached the minutes from today's meeting the next meeting is scheduled for the first tuesday of next month",
            "question about the report" to "when you get a chance could you look at section 3 of the quarterly report i think the totals in table 2 do not match the appendix",
            "receipt for your payment" to "thank you for your payment of 12.99 this is your receipt no further action is required you can view your billing history in settings",
            "welcome to the newsletter" to "thanks for subscribing each month we share genealogy research tips archive news and reader questions you can change your preferences any time",
            "re family tree" to "i found great grandfather's naturalisation record in the county archive scan attached the surname is spelled differently on the 1910 census",
            "library book due" to "the item you borrowed is due back on monday you can renew it online or at the front desk",
            "invitation to the retirement party" to "we are throwing a small party for john on the 14th at the office please rsvp by wednesday so we can order enough cake",
            "typo in the spreadsheet" to "the vba macro throws an error on the summary sheet because column f is text not a number i fixed it in the attached copy",
            "your appointment is confirmed" to "your appointment with dr smith is confirmed for tuesday at 2 30 pm please arrive ten minutes early and bring your insurance card",
            "book club next week" to "we are reading chapter 5 to 9 for next week jane is hosting bring a snack if you like",
            "git pull request review" to "i pushed the fix for the null pointer in the parser can you review the pull request when you have a minute the tests pass locally",
            "thanks for your help" to "just wanted to say thanks for helping with the move on saturday we could not have done it without you",
            "utility bill statement" to "your statement for march is now available to view online your direct debit of 84.20 will be collected on the 15th",
            "school newsletter" to "the spring term newsletter includes dates for parents evening the science fair and the summer concert",
            "recipe you asked for" to "here is grandma's apple pie recipe you asked about the trick is to chill the pastry for an hour before rolling"
        )
        for ((s, b) in spam) train(tokenize(s, b), spam = true)
        for ((s, b) in ham) train(tokenize(s, b), spam = false)
        seeded += spam.size + ham.size
    }
}
