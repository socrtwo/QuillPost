package info.socrtwo.quillpost.web

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The portable DNS/HTTP helpers that replaced JNDI and java.net.http (so the backend runs on Android). */
class NetTest {
    @Test
    fun `resolver list always ends with public fallbacks`() {
        val servers = Dns.servers()
        assertTrue(servers.isNotEmpty())
        assertTrue("1.1.1.1" in servers && "8.8.8.8" in servers)
        assertEquals(servers.distinct(), servers)
    }

    @Test
    fun `mx lookup of a well-known domain returns exchangers (skipped offline)`() {
        val mx = Dns.mx("gmail.com")
        assumeTrue(mx != null, "no DNS available in this environment")
        assertNotNull(mx)
        assertTrue(mx.any { it.endsWith("google.com") || it.endsWith("googlemail.com") }, "unexpected MX set: $mx")
        assertTrue(mx.all { it == it.lowercase() && !it.endsWith(".") })
    }

    @Test
    fun `mx lookup of a non-existent domain is empty, not an error (skipped offline)`() {
        val probe = Dns.mx("gmail.com")
        assumeTrue(probe != null, "no DNS available in this environment")
        val mx = Dns.mx("this-domain-does-not-exist-quillpost-test.invalid")
        assertEquals(emptyList(), mx)
    }

    @Test
    fun `autodiscover still works from the built-in table without any network`() {
        val r = Autodiscover.discover("someone@gmail.com")
        assertTrue(r.found)
        assertEquals("imap.gmail.com", r.incomingHost)
        assertEquals("smtp.gmail.com", r.smtpHost)
    }
}
