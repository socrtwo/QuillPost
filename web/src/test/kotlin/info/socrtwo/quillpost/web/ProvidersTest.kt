package info.socrtwo.quillpost.web

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The setup wizard's provider catalogue covers the hundred most used mailbox domains plus Proton. */
class ProvidersTest {
    private val top100 = """
        gmail.com yahoo.com hotmail.com aol.com hotmail.co.uk hotmail.fr msn.com yahoo.fr wanadoo.fr orange.fr
        comcast.net yahoo.co.uk yahoo.com.br yahoo.co.in live.com rediffmail.com free.fr gmx.de web.de yandex.ru
        ymail.com libero.it outlook.com uol.com.br bol.com.br mail.ru cox.net hotmail.it sbcglobal.net sfr.fr
        live.fr verizon.net live.co.uk googlemail.com yahoo.es ig.com.br live.nl bigpond.com terra.com.br yahoo.it
        neuf.fr yahoo.de alice.it rocketmail.com att.net laposte.net facebook.com bellsouth.net yahoo.in hotmail.es
        charter.net yahoo.ca yahoo.com.au rambler.ru hotmail.de tiscali.it shaw.ca yahoo.co.jp sky.com earthlink.net
        optonline.net freenet.de t-online.de aliceadsl.fr virgilio.it home.nl qq.com telenet.be me.com yahoo.com.ar
        tiscali.co.uk yahoo.com.mx voila.fr gmx.net mail.com planet.nl tin.it live.it ntlworld.com arcor.de
        yahoo.co.id frontiernet.net hetnet.nl live.com.au yahoo.com.sg zonnet.nl club-internet.fr juno.com optusnet.com.au blueyonder.co.uk
        bluewin.ch skynet.be sympatico.ca windstream.net mac.com centurytel.net chello.nl live.ca aim.com bigpond.net.au
    """.trim().split(Regex("\\s+"))

    @Test
    fun `all top 100 domains and Proton are pre-configured`() {
        assertEquals(100, top100.size)
        val missing = (top100 + listOf("proton.me", "protonmail.com", "erols.com", "xfinity.com", "icloud.com")).filter { Providers.forDomain(it) == null }
        assertTrue(missing.isEmpty(), "not configured: $missing")
    }

    @Test
    fun `every supported provider has complete server settings`() {
        for (p in Providers.all) {
            if (p.unsupported) continue
            assertTrue(p.imapHost.isNotBlank() && p.smtpHost.isNotBlank(), "${p.id} lacks hosts")
            assertTrue(p.imapPort in 1..65535 && p.smtpPort in 1..65535, "${p.id} has bad ports")
            assertTrue(p.imapSecurity in setOf("SSL_TLS", "STARTTLS", "NONE") && p.smtpSecurity in setOf("SSL_TLS", "STARTTLS", "NONE"), "${p.id} security")
        }
        assertEquals(Providers.all.size, Providers.all.map { it.id }.distinct().size, "duplicate provider ids")
        val ranks = Providers.all.filter { it.popular > 0 }.map { it.popular }
        assertEquals(ranks.size, ranks.distinct().size, "duplicate popular ranks")
        for ((_, id) in Providers.byMx) assertNotNull(Providers.byId(id), "MX mapping to unknown provider $id")
    }

    @Test
    fun `regional providers requested for the wizard are present`() {
        val expectedIds = listOf("qq", "netease", "sina", "naver", "daum", "yahoojp", "docomo", "nifty", "orange", "free", "sfr", "laposte", "rediff", "yandex", "mailru", "rambler", "proton", "erols", "comcast", "verizon", "aol", "gmail", "yahoo", "outlook")
        val missing = expectedIds.filter { Providers.byId(it) == null }
        assertTrue(missing.isEmpty(), "missing providers: $missing")
        val cat = Providers.catalogue()
        assertTrue(cat.any { it.region == "cn" } && cat.any { it.region == "kr" } && cat.any { it.region == "jp" } && cat.any { it.region == "in" } && cat.any { it.region == "ru" } && cat.any { it.region == "eu" })
    }

    @Test
    fun `discovery answers from the catalogue without any network for a listed domain`() {
        val r = Autodiscover.discover("someone@verizon.net")
        assertTrue(r.found)
        assertEquals("imap.aol.com", r.incomingHost)
        assertEquals("verizon", r.providerId)
        assertTrue(r.notes.any { it.contains("AOL") })
        val fb = Autodiscover.discover("someone@facebook.com")
        assertTrue(!fb.found && fb.notes.any { it.contains("2014") })
        val local = Autodiscover.discover("hans@arcor.de")
        assertEquals("hans", local.username)
    }
}
