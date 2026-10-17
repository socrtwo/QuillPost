package info.socrtwo.quillpost.web.spam

import java.net.URI
import java.net.URLDecoder

/**
 * Knowledge about *legitimate* reasons for a link domain to differ from the sender domain.
 *
 * "Link apex ≠ sender apex" on its own is not phishing evidence: genuine mail is routinely
 * sent from one domain of an organisation with links on another (facebookmail.com → facebook.com),
 * through an email service provider's click tracker (list-manage.com, sendgrid.net…), or with
 * links rewritten by a corporate link-protection gateway (Safe Links, Proofpoint URL Defense…).
 * The heuristics consult this object so that only *unexplained* disagreement scores.
 */
object LinkInfrastructure {

    /** Click-tracking, sending and link-protection domains operated by mail services, not by the brand. */
    val mailInfrastructureDomains: Set<String> = BrandKnowledgeBase.sharedSendingDomains + setOf(
        // Mailchimp / Mandrill
        "list-manage.com", "list-manage1.com", "list-manage2.com", "mailchi.mp", "campaign-archive.com", "mcusercontent.com", "mailchimpapp.com",
        // SendGrid / Twilio
        "sendgrid.net", "sendgrid.com", "sgizmo.com",
        // Salesforce Marketing Cloud / ExactTarget / Pardot / Eloqua / Responsys / Marketo
        "exacttarget.com", "exct.net", "exctarget.com", "pardot.com", "eloqua.com", "en25.com", "responsys.net", "rsys2.net", "rsys5.net",
        "mktoweb.com", "mktossl.com", "marketo.com", "mkt51.net",
        // Constant Contact / Campaign Monitor / Emma / Klaviyo / Braze / Iterable / HubSpot
        "rs6.net", "constantcontact.com", "ccsend.com", "cmail19.com", "cmail20.com", "cmail.eu", "createsend.com", "createsend1.com", "e2ma.net", "myemma.com",
        "klaviyomail.com", "klclick.com", "klclick1.com", "klclick2.com", "klclick3.com", "braze.com", "brazemail.com", "ablink.net",
        "iterable.com", "iterablemail.com", "hubspotlinks.com", "hubspotemail.net", "hs-sites.com", "hubspot.com",
        // Amazon SES / Postmark / SparkPost / Mailgun / Mailjet / Brevo / MailerLite / GetResponse / AWeber
        "awstrack.me", "amazonses.com", "postmarkapp.com", "pstmrk.it", "sparkpostmail.com", "sparkpost.com", "mailgun.org", "mailgun.net", "mailgun.info",
        "mailjet.com", "mjt.lu", "sendinblue.com", "brevo.com", "sendibm1.com", "sendibm2.com", "sendibm3.com", "mailerlite.com", "mlsend.com", "mlsend2.com",
        "getresponse.com", "aweber.com", "aweber-static.com",
        // Customer.io / Sailthru / Cordial / Listrak / Bluecore / Cheetah / Dotdigital / Bronto / ActiveCampaign / Drip / Sendlane / Moosend
        "customeriomail.com", "customer.io", "cio-messaging.com", "sailthru.com", "cordial.io", "cordialmail.com", "listrak.com", "listrakbi.com", "bluecore.com",
        "cheetahmail.com", "dotdigital.com", "dotmailer.com", "trackedlink.net", "bronto.com", "activehosted.com", "activecampaign.com", "getdrip.com",
        "sendlane.com", "moosend.com", "emltrk.com", "emsecure.net", "expertsender.com", "elasticemail.com", "smtp.com",
        // Newsletter platforms
        "substack.com", "substackcdn.com", "beehiiv.com", "convertkit.com", "convertkit-mail.com", "convertkit-mail2.com", "kit.com", "buttondown.email", "ghost.io",
        // Link-protection gateways (the wrapped URL is unwrapped first; opaque ones stay neutral)
        "safelinks.protection.outlook.com", "urldefense.com", "urldefense.proofpoint.com", "mimecast.com", "mimecastprotect.com", "emailprotection.link",
        "cudasvc.com", "barracudanetworks.com", "sophos.com", "secure-web.cisco.com", "trendmicro.com", "tmes.trendmicro.com", "ironport.com",
        "protection.office365.us", "linkprotect.cudasvc.com", "urlisolation.com", "menlosecurity.com", "fireeyecloud.com", "avanan.com", "checkpoint.com",
        // Email-open / click analytics pixels
        "mailtrack.io", "yesware.com", "mixmax.com", "streak.com", "hubspot-tracking.com", "litmus.com", "emailanalytics.com"
    )

    /**
     * Domains that belong to one organisation but are not (all) in the brand table, so that
     * facebookmail.com ↔ facebook.com or aka.ms ↔ microsoft.com count as the same owner.
     */
    private val affiliatedGroups: List<Set<String>> = listOf(
        setOf("paypal.com", "paypal-communication.com", "paypalobjects.com", "paypal.me", "paypal-notice.com", "paypal.co.uk", "paypal.de", "paypal.fr", "paypal.ca", "paypal.com.au", "braintreepayments.com", "venmo.com"),
        setOf("facebook.com", "facebookmail.com", "fb.com", "fb.me", "fbcdn.net", "instagram.com", "messenger.com", "meta.com", "metamail.com", "whatsapp.com", "threads.net", "oculus.com"),
        setOf("amazon.com", "amazon.co.uk", "amazon.de", "amazon.fr", "amazon.ca", "amazon.com.au", "amazon.co.jp", "amazon.in", "amazon.it", "amazon.es", "amazon.nl", "amazonaws.com", "aboutamazon.com", "primevideo.com", "audible.com", "amazon.jobs", "media-amazon.com", "ssl-images-amazon.com", "amazonpay.com", "a2z.com"),
        setOf("microsoft.com", "microsoftonline.com", "live.com", "outlook.com", "hotmail.com", "office.com", "office365.com", "microsoft365.com", "aka.ms", "msft.it", "azure.com", "azure.net", "xbox.com", "skype.com", "windows.com", "bing.com", "msn.com", "microsoftstore.com", "onedrive.com", "sharepoint.com", "visualstudio.com", "github.com"),
        setOf("google.com", "goo.gle", "g.co", "youtube.com", "youtu.be", "gmail.com", "googleapis.com", "withgoogle.com", "googleusercontent.com", "gstatic.com", "google.co.uk", "google.de", "google.fr", "google.ca", "google.com.au", "android.com", "blogger.com", "googleblog.com", "fitbit.com", "nest.com", "waze.com", "youtube.com"),
        setOf("apple.com", "icloud.com", "me.com", "mac.com", "apple.co", "itunes.com", "itunes.apple.com", "apps.apple.com", "beatsbydre.com", "tv.apple.com"),
        setOf("linkedin.com", "lnkd.in", "licdn.com", "linkedin.cn"),
        setOf("twitter.com", "x.com", "t.co", "twimg.com"),
        setOf("netflix.com", "nflx.it", "nflxext.com", "nflximg.net"),
        setOf("ebay.com", "ebay.co.uk", "ebay.de", "ebay.com.au", "ebayimg.com", "ebaystatic.com", "ebay.ca"),
        setOf("chase.com", "jpmorgan.com", "jpmchase.com", "jpmorganchase.com"),
        setOf("bankofamerica.com", "bofa.com", "merrilledge.com", "ml.com"),
        setOf("wellsfargo.com", "wf.com", "wellsfargoadvisors.com"),
        setOf("citi.com", "citibank.com", "citigroup.com", "citicards.com"),
        setOf("capitalone.com", "capitalone360.com"),
        setOf("uber.com", "ubereats.com", "uber.co"),
        setOf("dropbox.com", "dropboxmail.com", "db.tt", "dropboxusercontent.com"),
        setOf("adobe.com", "adobesign.com", "echosign.com", "adobelogin.com", "acrobat.com", "typekit.com"),
        setOf("zoom.us", "zoom.com", "zoomgov.com"),
        setOf("slack.com", "slack-mail.com", "slack-edge.com"),
        setOf("spotify.com", "spotifymail.com", "spotifycdn.com", "scdn.co"),
        setOf("airbnb.com", "airbnbmail.com", "airbnb.co.uk", "muscache.com"),
        setOf("booking.com", "bstatic.com", "booking.com.cn"),
        setOf("expedia.com", "expediamail.com", "hotels.com", "vrbo.com", "travelocity.com", "orbitz.com"),
        setOf("intuit.com", "turbotax.com", "quickbooks.com", "mint.com", "creditkarma.com", "mailchimp.com"),
        setOf("fedex.com", "fedexmail.com"),
        setOf("ups.com", "upsemail.com", "ups-mail.com"),
        setOf("usps.com", "uspsinformeddelivery.com", "usps.gov"),
        setOf("ancestry.com", "ancestrycdn.com", "ancestry.co.uk", "ancestry.ca", "findagrave.com", "newspapers.com", "fold3.com", "rootsweb.com"),
        setOf("familysearch.org", "churchofjesuschrist.org"),
        setOf("myheritage.com", "myheritagemail.com"),
        setOf("comcast.net", "xfinity.com", "comcast.com"),
        setOf("verizon.com", "verizon.net", "verizonwireless.com", "vzw.com"),
        setOf("att.com", "att.net", "sbcglobal.net", "bellsouth.net"),
        setOf("t-mobile.com", "sprint.com"),
        setOf("costco.com", "costco.ca"),
        setOf("walmart.com", "walmart.ca", "samsclub.com", "wal-mart.com"),
        setOf("target.com", "targetmail.com"),
        setOf("bestbuy.com", "emailinfo.bestbuy.com"),
        setOf("homedepot.com", "homedepotemail.com"),
        setOf("lowes.com", "lowesemail.com"),
        setOf("kohls.com", "kohlsemail.com"),
        setOf("macys.com", "macysmail.com", "bloomingdales.com"),
        setOf("etsy.com", "etsystatic.com", "mail.etsy.com"),
        setOf("shopify.com", "shopifyemail.com", "myshopify.com", "shop.app"),
        setOf("squarespace.com", "squarespace-mail.com"),
        setOf("godaddy.com", "secureserver.net", "godaddymail.com"),
        setOf("namecheap.com", "namecheapmail.com"),
        setOf("wordpress.com", "wp.com", "automattic.com", "gravatar.com"),
        setOf("stripe.com", "stripemail.com"),
        setOf("square.com", "squareup.com", "cash.app", "cash.me"),
        setOf("docusign.com", "docusign.net"),
        setOf("irs.gov", "irs.treasury.gov", "treasury.gov"),
        setOf("ssa.gov", "socialsecurity.gov", "medicare.gov", "cms.gov"),
        setOf("nytimes.com", "nyt.com"),
        setOf("washingtonpost.com", "wapo.st"),
        setOf("wsj.com", "dowjones.com", "barrons.com")
    )
    private val affiliationOf: Map<String, Int> = buildMap {
        affiliatedGroups.forEachIndexed { i, g -> g.forEach { putIfAbsent(it, i) } }
    }

    /** Wrapper hosts whose query string carries the real destination. */
    private val wrapperParams: Map<String, List<String>> = mapOf(
        "safelinks.protection.outlook.com" to listOf("url"),
        "protection.office365.us" to listOf("url"),
        "google.com" to listOf("q", "url"),                     // https://www.google.com/url?q=…
        "l.facebook.com" to listOf("u"),
        "lm.facebook.com" to listOf("u"),
        "l.instagram.com" to listOf("u"),
        "l.messenger.com" to listOf("u"),
        "linkprotect.cudasvc.com" to listOf("a"),
        "click.linksynergy.com" to listOf("murl"),
        "go.redirectingat.com" to listOf("url"),
        "t.umblr.com" to listOf("z"),
        "out.reddit.com" to listOf("url"),
        "away.vk.com" to listOf("to"),
        "exit.sc" to listOf("url"),
        "secure-web.cisco.com" to listOf("u")
    )

    /**
     * Strips link-protection and redirect wrappers so the *destination* domain is judged:
     * Microsoft Safe Links, Proofpoint URL Defense v2/v3, Google redirects, Facebook `l.php`,
     * Barracuda Link Protect and similar. Unknown wrappers are returned unchanged.
     */
    fun unwrap(url: String): String {
        var current = url.trim()
        repeat(3) {
            val next = unwrapOnce(current) ?: return current
            if (next == current) return current
            current = next
        }
        return current
    }

    private fun unwrapOnce(url: String): String? {
        val host = HeaderParser.hostOf(url)?.lowercase() ?: return null
        // Proofpoint URL Defense v3: https://urldefense.com/v3/__https://example.com/path__;!!token$
        if (host == "urldefense.com" || host == "urldefense.proofpoint.com") {
            val v3 = Regex("""/v3/__(https?://.+?)__;""").find(url)?.groupValues?.get(1)
            if (v3 != null) return v3.replace("*", "")
            // v2: https://urldefense.proofpoint.com/v2/url?u=https-3A__www.example.com_path-3Fa-3D1&d=…
            val u = queryParam(url, "u") ?: return null
            if (!u.startsWith("http")) return null
            return u.replace('_', '/').replace(Regex("-([0-9A-Fa-f]{2})")) { m -> m.groupValues[1].toInt(16).toChar().toString() }
        }
        val params = wrapperParams.entries.firstOrNull { (h, _) -> host == h || host.endsWith(".$h") }?.value ?: return null
        for (p in params) {
            val v = queryParam(url, p) ?: continue
            if (v.startsWith("http://", true) || v.startsWith("https://", true)) return v
        }
        return null
    }

    private fun queryParam(url: String, name: String): String? {
        val q = url.substringAfter('?', "").substringBefore('#')
        if (q.isEmpty()) return null
        for (pair in q.split('&')) {
            val k = pair.substringBefore('=')
            if (k.equals(name, ignoreCase = true)) return runCatching { URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8") }.getOrNull()
        }
        return null
    }

    /** True when [host] is run by a mail service or link-protection gateway rather than the brand. */
    fun isMailInfrastructure(host: String): Boolean {
        val h = host.lowercase()
        val apex = HeaderParser.registrableDomain(h)
        return apex in mailInfrastructureDomains || h in mailInfrastructureDomains ||
            mailInfrastructureDomains.any { it.contains('.') && h.endsWith(".$it") }
    }

    /** The organisation identifier for [domain]: an affiliation group, else the owning brand names, else the apex itself. */
    fun organisation(domain: String): Set<String> {
        val apex = HeaderParser.registrableDomain(domain.lowercase())
        val out = LinkedHashSet<String>()
        affiliationOf[apex]?.let { out += "group:$it" }
        BrandKnowledgeBase.brandsForDomain(apex).forEach { out += "brand:" + it.name }
        if (out.isEmpty()) out += "apex:$apex"
        return out
    }

    /** True when both domains belong to the same organisation (same apex, same brand entry, or affiliated). */
    fun sameOrganisation(a: String, b: String): Boolean {
        val aa = HeaderParser.registrableDomain(a.lowercase()); val bb = HeaderParser.registrableDomain(b.lowercase())
        if (aa.isBlank() || bb.isBlank()) return false
        if (aa == bb) return true
        return organisation(aa).any { it in organisation(bb) }
    }

    /** True when [host] is a domain the brand table knows an organisation by (so a fake link showing it is a real lure). */
    fun isKnownBrandDomain(host: String): Boolean {
        val apex = HeaderParser.registrableDomain(host.lowercase())
        return affiliationOf.containsKey(apex) || BrandKnowledgeBase.brandsForDomain(apex).isNotEmpty()
    }

    /** Parses [url] leniently; null when it is not a URL at all. */
    fun parse(url: String): URI? = runCatching { URI(url) }.getOrNull()
}
