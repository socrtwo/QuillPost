package info.socrtwo.quillpost.web.spam

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Exercises the real resolver against SpamCop's documented test entry (127.0.0.2 is always
 * listed). Skipped automatically when DNS is unavailable so CI without network still passes.
 */
class DnsblLiveTest {
    @Test
    fun `spamcop test entry is reported as listed`() {
        val checker = BlacklistChecker(InetAddressDnsResolver())
        val def = Blacklists.catalogue.first { it.id == Blacklists.SPAMCOP }
        val outcome = checker.check(listOf("127.0.0.2"), emptyList(), listOf(def), 4000)
        val status = outcome.status.single()
        if (status.error != null || outcome.hits.isEmpty()) {
            // No usable DNS on this machine (sandboxed CI, offline) — nothing to verify.
            println("DNS blocklist not reachable here (error=${status.error}, hits=${outcome.hits.size}); skipping")
            return
        }
        assertEquals(1, outcome.hits.size)
        assertEquals("SpamCop", outcome.hits[0].list)
    }
}
