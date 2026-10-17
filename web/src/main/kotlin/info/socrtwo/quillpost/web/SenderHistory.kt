package info.socrtwo.quillpost.web

import info.socrtwo.quillpost.web.spam.BrandKnowledgeBase
import info.socrtwo.quillpost.web.spam.HeaderParser
import info.socrtwo.quillpost.web.spam.SenderReputation
import kotlinx.serialization.Serializable

/** Per-sender counters persisted in `sender-history.json`. */
@Serializable
data class SenderStats(
    val messages: Int = 0,
    val opened: Int = 0,
    val replied: Int = 0,
    val junk: Int = 0,
    val ham: Int = 0,
    val lastSeen: Long = 0L,
    /** Registrable link domains seen in this sender's (non-junk) messages, with counts. */
    val links: Map<String, Int> = emptyMap()
)

@Serializable
data class SenderHistoryFile(val version: Int = 1, val senders: Map<String, SenderStats> = emptyMap())

/**
 * The account's own experience with each sender: how many messages arrived, how many the user
 * opened or replied to, junk / not-junk marks, and which link domains the sender habitually
 * uses. Keyed by the sender's organisation domain, or by the full address for free-mail and
 * shared sending domains where the domain says nothing about the sender.
 *
 * Thread-safe; callers persist through [DataStore.saveHistory].
 */
class SenderHistory(initial: Map<String, SenderStats> = emptyMap()) {
    private val senders = HashMap(initial)
    @Volatile var dirty = false
        private set

    companion object {
        private const val MAX_SENDERS = 3000
        private const val MAX_LINKS_PER_SENDER = 24
        const val FAMILIAR_LINK_MIN = 3

        /** The key a sender is tracked under. */
        fun keyFor(address: String): String {
            val raw = address.trim().lowercase()
            // "Name <user@host>" → user@host
            val a = Regex("<([^<>]+)>").find(raw)?.groupValues?.get(1)?.trim() ?: raw
            val domain = HeaderParser.addressDomain(a)
            if (domain.isBlank()) return a
            val apex = HeaderParser.registrableDomain(domain)
            return if (BrandKnowledgeBase.isFreemail(apex) || BrandKnowledgeBase.isSharedSender(apex)) a else apex
        }
    }

    @Synchronized
    fun snapshot(): Map<String, SenderStats> = HashMap(senders)

    @Synchronized
    fun stats(address: String): SenderStats? = senders[keyFor(address)]

    /** The reputation the engine should use for mail from [address]. */
    fun reputation(address: String): SenderReputation {
        val s = stats(address) ?: return SenderReputation.NONE
        return SenderReputation(
            messages = s.messages, opened = s.opened, replied = s.replied, junk = s.junk, ham = s.ham,
            familiarLinkApexes = if (s.junk == 0) s.links.filterValues { it >= FAMILIAR_LINK_MIN }.keys else emptySet()
        )
    }

    private fun update(address: String, f: (SenderStats) -> SenderStats) {
        val k = keyFor(address)
        if (k.isBlank()) return
        synchronized(this) {
            senders[k] = f(senders[k] ?: SenderStats()).copy(lastSeen = System.currentTimeMillis())
            dirty = true
            if (senders.size > MAX_SENDERS) {
                val drop = senders.entries.sortedBy { it.value.lastSeen }.take(senders.size - MAX_SENDERS + 200).map { it.key }
                drop.forEach { senders.remove(it) }
            }
        }
    }

    /** A message from [address] was analysed; [spam] says whether the engine filed it as junk. */
    fun seen(address: String, linkApexes: Collection<String>, spam: Boolean) = update(address) { s ->
        if (spam) s.copy(messages = s.messages + 1)
        else {
            val links = HashMap(s.links)
            for (l in linkApexes.distinct()) links[l] = (links[l] ?: 0) + 1
            val trimmed = if (links.size > MAX_LINKS_PER_SENDER) links.entries.sortedByDescending { it.value }.take(MAX_LINKS_PER_SENDER).associate { it.key to it.value } else links
            s.copy(messages = s.messages + 1, links = trimmed)
        }
    }

    fun opened(address: String) = update(address) { it.copy(opened = it.opened + 1) }
    fun replied(address: String) = update(address) { it.copy(replied = it.replied + 1) }
    fun markedJunk(address: String) = update(address) { it.copy(junk = it.junk + 1) }
    fun markedNotJunk(address: String) = update(address) { it.copy(ham = it.ham + 1, junk = 0) }

    @Synchronized
    fun markClean() { dirty = false }
}
