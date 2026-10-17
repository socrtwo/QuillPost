package info.socrtwo.quillpost.web

import java.net.InetSocketAddress
import java.net.Socket

/**
 * Finds mail server settings from just an email address — the way Outlook's "add account"
 * does — using, in order: the built-in provider table ([Providers]), Mozilla's public ISPDB,
 * the domain's own autoconfig file, the MX record (to spot hosted Google / Microsoft 365 /
 * Yahoo domains), and finally a quick port probe of the usual imap./mail./smtp. host names.
 */
object Autodiscover {

    fun discover(email: String): AutodiscoverResponse {
        val addr = email.trim().lowercase()
        val domain = addr.substringAfterLast('@', "")
        if (domain.isBlank() || !addr.contains('@')) {
            return AutodiscoverResponse(addr, domain, false, "none", notes = listOf("Enter a full email address."))
        }
        Providers.forDomain(domain)?.let { return fromProvider(addr, domain, it, "built-in provider list") }

        ispdb(addr, domain)?.let { return it }
        domainAutoconfig(addr, domain)?.let { return it }
        mxLookup(domain)?.let { mx ->
            Providers.byMx.firstOrNull { (suffixes, _) -> mx.any { host -> suffixes.any { s -> host == s || host.endsWith(".$s") } } }
                ?.let { (_, id) -> Providers.byId(id)?.let { p -> return fromProvider(addr, domain, p, "MX record (${p.label})") } }
        }
        probe(addr, domain)?.let { return it }
        return AutodiscoverResponse(
            addr, domain, false, "none", username = addr,
            incomingHost = "imap.$domain", smtpHost = "smtp.$domain",
            notes = listOf("No settings could be found automatically for $domain. Enter the IMAP and SMTP host names from your provider's help pages.")
        )
    }

    /** Settings for a known provider, as shown on the wizard's review step. */
    fun fromProvider(email: String, domain: String, p: Providers.ProviderDef, source: String) = AutodiscoverResponse(
        email = email, domain = domain, found = p.imapHost.isNotBlank() && !p.unsupported, source = source, provider = p.label, providerId = p.id,
        incomingHost = p.imapHost, incomingPort = p.imapPort, protocol = "IMAP", incomingSecurity = p.imapSecurity,
        smtpHost = p.smtpHost, smtpPort = p.smtpPort, smtpSecurity = p.smtpSecurity,
        username = if (p.usernameLocalPart) email.substringBefore('@') else email,
        notes = (listOfNotNull(p.status) + p.notes), appPasswordUrl = p.appPasswordUrl, oauthOnly = p.oauthOnly
    )

    // --- Mozilla ISPDB & domain autoconfig ----------------------------------------------

    private fun ispdb(email: String, domain: String): AutodiscoverResponse? =
        fetchAutoconfig("https://autoconfig.thunderbird.net/v1.1/$domain", email, domain, "Mozilla ISPDB")

    private fun domainAutoconfig(email: String, domain: String): AutodiscoverResponse? =
        fetchAutoconfig("https://autoconfig.$domain/mail/config-v1.1.xml?emailaddress=$email", email, domain, "domain autoconfig")
            ?: fetchAutoconfig("https://$domain/.well-known/autoconfig/mail/config-v1.1.xml", email, domain, "domain autoconfig")

    private fun fetchAutoconfig(url: String, email: String, domain: String, source: String): AutodiscoverResponse? = runCatching {
        val res = Http.get(url, timeoutMs = 5000)
        if (res.status != 200) return null
        parseAutoconfig(res.body, email, domain, source)
    }.getOrNull()

    internal fun parseAutoconfig(xml: String, email: String, domain: String, source: String): AutodiscoverResponse? {
        fun servers(type: String): List<String> =
            Regex("""<$type[^>]*>(.*?)</$type>""", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { it.groupValues[1] }.toList()
        fun tag(block: String, name: String) = Regex("""<$name>\s*([^<]+?)\s*</$name>""").find(block)?.groupValues?.get(1)
        fun typeOf(block: String, ctx: String) = Regex("""<$ctx[^>]*type="([^"]+)"""").find(xml.substring(0, (xml.indexOf(block).takeIf { it > 0 } ?: 0)) + "<$ctx type=\"?\">")?.groupValues?.get(1)

        val incomingBlocks = Regex("""<incomingServer\s+type="(imap|pop3)"[^>]*>(.*?)</incomingServer>""", RegexOption.DOT_MATCHES_ALL).findAll(xml).toList()
        val incoming = incomingBlocks.firstOrNull { it.groupValues[1] == "imap" } ?: incomingBlocks.firstOrNull() ?: return null
        val outgoing = servers("outgoingServer").firstOrNull()
        val inBlock = incoming.groupValues[2]
        val user = (tag(inBlock, "username") ?: "%EMAILADDRESS%")
            .replace("%EMAILADDRESS%", email).replace("%EMAILLOCALPART%", email.substringBefore('@')).replace("%EMAILDOMAIN%", domain)
        fun sec(s: String?) = when (s?.uppercase()) { "SSL" -> "SSL_TLS"; "STARTTLS" -> "STARTTLS"; else -> "NONE" }
        val notes = mutableListOf<String>()
        Regex("""<documentation\s+url="([^"]+)"""").find(xml)?.let { notes += "Provider help: ${it.groupValues[1]}" }
        val displayName = Regex("""<displayName>([^<]+)</displayName>""").find(xml)?.groupValues?.get(1)?.trim()
        val oauth = tag(inBlock, "authentication")?.contains("OAuth2", ignoreCase = true) == true &&
            !Regex("""<authentication>\s*password""", RegexOption.IGNORE_CASE).containsMatchIn(inBlock)
        return AutodiscoverResponse(
            email = email, domain = domain, found = true, source = source, provider = displayName,
            incomingHost = tag(inBlock, "hostname") ?: return null,
            incomingPort = tag(inBlock, "port")?.toIntOrNull() ?: 993,
            protocol = incoming.groupValues[1].uppercase(),
            incomingSecurity = sec(tag(inBlock, "socketType")),
            smtpHost = outgoing?.let { tag(it, "hostname") } ?: "",
            smtpPort = outgoing?.let { tag(it, "port")?.toIntOrNull() } ?: 587,
            smtpSecurity = sec(outgoing?.let { tag(it, "socketType") }),
            username = user,
            notes = notes,
            oauthOnly = oauth
        )
    }

    // --- MX & probing ------------------------------------------------------------------

    /** MX hosts for [domain] (lowest preference first) via the built-in DNS client; null on failure. */
    fun mxLookup(domain: String): List<String>? = runCatching { Dns.mx(domain) }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun probe(email: String, domain: String): AutodiscoverResponse? {
        val imapCandidates = listOf(
            Triple("imap.$domain", 993, "SSL_TLS"), Triple("mail.$domain", 993, "SSL_TLS"), Triple(domain, 993, "SSL_TLS"),
            Triple("imap.$domain", 143, "STARTTLS"), Triple("mail.$domain", 143, "STARTTLS")
        )
        val smtpCandidates = listOf(
            Triple("smtp.$domain", 587, "STARTTLS"), Triple("mail.$domain", 587, "STARTTLS"), Triple("smtp.$domain", 465, "SSL_TLS"),
            Triple("mail.$domain", 465, "SSL_TLS"), Triple(domain, 587, "STARTTLS")
        )
        val imap = imapCandidates.firstOrNull { (h, p, _) -> portOpen(h, p) } ?: return null
        val smtp = smtpCandidates.firstOrNull { (h, p, _) -> portOpen(h, p) }
        return AutodiscoverResponse(
            email = email, domain = domain, found = true, source = "port probe", provider = null,
            incomingHost = imap.first, incomingPort = imap.second, protocol = "IMAP", incomingSecurity = imap.third,
            smtpHost = smtp?.first ?: "smtp.$domain", smtpPort = smtp?.second ?: 587, smtpSecurity = smtp?.third ?: "STARTTLS",
            username = email,
            notes = listOf("Settings were guessed by probing common host names; verify before saving.")
        )
    }

    private fun portOpen(host: String, port: Int): Boolean = runCatching {
        Socket().use { s -> s.connect(InetSocketAddress(host, port), 1500); true }
    }.getOrDefault(false)
}
