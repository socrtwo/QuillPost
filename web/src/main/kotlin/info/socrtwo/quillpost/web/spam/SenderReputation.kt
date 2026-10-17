package info.socrtwo.quillpost.web.spam

/**
 * What the user's own mailbox says about a sender, derived from the per-account sender
 * history (messages received, opened, replied to, marked junk / not junk, and the link domains
 * the sender habitually uses). The engine uses it to stop second-guessing people and services
 * the user demonstrably deals with.
 */
data class SenderReputation(
    val messages: Int = 0,
    val opened: Int = 0,
    val replied: Int = 0,
    val junk: Int = 0,
    val ham: Int = 0,
    /** Link domains seen in at least three earlier messages from this sender that were not marked junk. */
    val familiarLinkApexes: Set<String> = emptySet()
) {
    /** A correspondent: the user replied, marked it "not junk", or opened several messages — and never marked it junk. */
    val knownCorrespondent: Boolean
        get() = junk == 0 && (replied >= 1 || ham >= 1 || opened >= 3)

    val summary: String
        get() = buildList {
            if (replied > 0) add("you replied $replied time${if (replied == 1) "" else "s"}")
            if (ham > 0) add("you marked ${if (ham == 1) "a message" else "$ham messages"} as not junk")
            if (opened > 0) add("you opened $opened message${if (opened == 1) "" else "s"}")
            if (messages > 0) add("$messages received")
        }.joinToString(", ")

    companion object {
        val NONE = SenderReputation()
    }
}
