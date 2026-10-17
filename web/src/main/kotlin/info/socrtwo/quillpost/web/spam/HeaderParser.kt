package info.socrtwo.quillpost.web.spam

/** Pure-Kotlin helpers for pulling facts out of RFC 5322 headers, URLs and HTML. */
object HeaderParser {

    private val ipv4InBrackets = Regex("""\[(\d{1,3}(?:\.\d{1,3}){3})]""")
    private val ipv4Loose = Regex("""(?<![\d.])(\d{1,3}(?:\.\d{1,3}){3})(?![\d.])""")
    private val ipv6InBrackets = Regex("""\[(?:IPv6:)?([0-9a-fA-F:]{3,45})]""", RegexOption.IGNORE_CASE)

    /**
     * Returns the public IP addresses that appear in Received headers, nearest hop first.
     * Private/loopback/reserved addresses are dropped. Only the "from" side of each hop is
     * considered so a provider's own inbound MX is not blamed for its clients' mail.
     */
    fun extractReceivedIps(received: List<String>, originatingIpHeader: String? = null): List<String> {
        val out = LinkedHashSet<String>()
        originatingIpHeader?.let { hdr ->
            (ipv4InBrackets.find(hdr)?.groupValues?.get(1) ?: ipv4Loose.find(hdr)?.groupValues?.get(1))
                ?.takeIf { isPublicIpv4(it) }?.let { out.add(it) }
        }
        for (raw in received) {
            val h = raw.replace("\r", " ").replace("\n", " ")
            // Only look at the part before "by" – that is the sending host of this hop.
            val fromPart = h.substringBefore(" by ").substringBefore(" with ")
            if (!fromPart.contains("from", ignoreCase = true)) continue
            val ips = mutableListOf<String>()
            ipv4InBrackets.findAll(fromPart).forEach { ips.add(it.groupValues[1]) }
            if (ips.isEmpty()) ipv4Loose.findAll(fromPart).forEach { ips.add(it.groupValues[1]) }
            ipv6InBrackets.findAll(fromPart).forEach { ips.add(it.groupValues[1]) }
            for (ip in ips) {
                if (ip.contains(':')) {
                    if (isPublicIpv6(ip)) out.add(ip.lowercase())
                } else if (isPublicIpv4(ip)) {
                    out.add(ip)
                }
            }
        }
        return out.toList()
    }

    fun isPublicIpv4(ip: String): Boolean {
        val parts = ip.split('.').map { it.toIntOrNull() ?: return false }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return false
        val (a, b) = parts
        return when {
            a == 10 || a == 127 || a == 0 -> false
            a == 172 && b in 16..31 -> false
            a == 192 && b == 168 -> false
            a == 169 && b == 254 -> false
            a == 100 && b in 64..127 -> false
            a >= 224 -> false
            else -> true
        }
    }

    fun isPublicIpv6(ip: String): Boolean {
        val l = ip.lowercase()
        if (l == "::1" || l == "::" || l.startsWith("fe80") || l.startsWith("fc") || l.startsWith("fd")) return false
        if (l.startsWith("::ffff:")) return false
        return l.contains(':')
    }

    /** Reversed dotted quad for DNSBL lookups: 1.2.3.4 -> 4.3.2.1 */
    fun reverseIpv4(ip: String): String = ip.split('.').reversed().joinToString(".")

    /** Reversed nibble form for IPv6 DNSBL lookups. */
    fun reverseIpv6(ip: String): String? {
        val expanded = expandIpv6(ip) ?: return null
        return expanded.replace(":", "").reversed().toCharArray().joinToString(".")
    }

    fun expandIpv6(ip: String): String? {
        val halves = ip.split("::")
        if (halves.size > 2) return null
        val head = if (halves[0].isEmpty()) emptyList() else halves[0].split(':')
        val tail = if (halves.size == 2 && halves[1].isNotEmpty()) halves[1].split(':') else emptyList()
        val missing = 8 - head.size - tail.size
        if (missing < 0 || (halves.size == 1 && missing != 0)) return null
        val groups = head + List(missing) { "0" } + tail
        if (groups.any { g -> g.length > 4 || g.any { !it.isLetterOrDigit() } }) return null
        return groups.joinToString(":") { g -> g.padStart(4, '0').lowercase() }
    }

    private val spfRe = Regex("""\bspf=(\w+)""", RegexOption.IGNORE_CASE)
    private val dkimRe = Regex("""\bdkim=(\w+)""", RegexOption.IGNORE_CASE)
    private val dmarcRe = Regex("""\bdmarc=(\w+)""", RegexOption.IGNORE_CASE)
    private val dkimDomainRe = Regex("""header\.(?:d|i)=@?([A-Za-z0-9.-]+)""")

    /**
     * Parses Authentication-Results headers added by the receiving server. The first header
     * (the one added by the user's own provider) wins because anything below it may have been
     * forged by the sender.
     */
    fun parseAuthenticationResults(headers: List<String>): AuthResults {
        if (headers.isEmpty()) return AuthResults()
        var spf: String? = null
        var dkim: String? = null
        var dmarc: String? = null
        var dkimDomain: String? = null
        for (h in headers) {
            val flat = h.replace(Regex("\\s+"), " ")
            if (spf == null) spf = spfRe.find(flat)?.groupValues?.get(1)?.lowercase()
            if (dkim == null) dkim = dkimRe.find(flat)?.groupValues?.get(1)?.lowercase()
            if (dmarc == null) dmarc = dmarcRe.find(flat)?.groupValues?.get(1)?.lowercase()
            if (dkimDomain == null) dkimDomain = dkimDomainRe.find(flat)?.groupValues?.get(1)?.lowercase()
            if (spf != null && dkim != null && dmarc != null) break
        }
        return AuthResults(spf, dkim, dmarc, dkimDomain, headers.firstOrNull()?.take(400))
    }

    data class Link(val href: String, val text: String)

    private val anchorRe = Regex("""<a\b[^>]*?href\s*=\s*["']?([^"'\s>]+)["']?[^>]*>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val hrefRe = Regex("""href\s*=\s*["']?(https?://[^"'\s>]+)""", RegexOption.IGNORE_CASE)
    private val urlRe = Regex("""https?://[^\s<>"'()\[\]]+""", RegexOption.IGNORE_CASE)

    fun extractLinks(html: String?, text: String): List<Link> {
        val out = LinkedHashMap<String, Link>()
        if (!html.isNullOrBlank()) {
            anchorRe.findAll(html).forEach { m ->
                val href = m.groupValues[1].trim()
                if (href.startsWith("http", ignoreCase = true)) {
                    out.putIfAbsent(href, Link(href, htmlToText(m.groupValues[2]).trim()))
                }
            }
            hrefRe.findAll(html).forEach { m -> out.putIfAbsent(m.groupValues[1], Link(m.groupValues[1], "")) }
        }
        urlRe.findAll(text).forEach { m ->
            val u = m.value.trimEnd('.', ',', ';', ':', '!', '?')
            out.putIfAbsent(u, Link(u, ""))
        }
        return out.values.toList()
    }

    fun hostOf(url: String): String? {
        val m = Regex("""^[a-zA-Z][a-zA-Z0-9+.-]*://(?:[^@/]+@)?\[?([^/:?#\]]+)]?""").find(url.trim()) ?: return null
        return m.groupValues[1].lowercase().trimEnd('.')
    }

    fun isIpLiteral(host: String): Boolean =
        Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(host) || host.contains(':')

    /**
     * The organisation-level domain of a host, per the Public Suffix List (`co.uk`, `com.au`,
     * `pvt.k12.ma.us`, …): `mail.example.co.uk` → `example.co.uk`. IP literals are returned as-is.
     */
    fun registrableDomain(host: String): String {
        val h = host.lowercase().trimEnd('.')
        if (h.isEmpty() || isIpLiteral(h)) return h
        return PublicSuffixList.registrableDomain(h)
    }

    fun addressDomain(address: String): String {
        val at = address.lastIndexOf('@')
        return if (at >= 0) address.substring(at + 1).trim().lowercase().trimEnd('>') else ""
    }

    /** Splits "Name <addr@host>" into (name, address). */
    fun splitAddress(raw: String): Pair<String, String> {
        val s = raw.trim()
        val lt = s.lastIndexOf('<')
        val gt = s.lastIndexOf('>')
        if (lt >= 0 && gt > lt) {
            val name = s.substring(0, lt).trim().trim('"').trim()
            return name to s.substring(lt + 1, gt).trim().lowercase()
        }
        return "" to s.lowercase()
    }

    private val tagRe = Regex("""<[^>]+>""")
    private val scriptRe = Regex("""<(script|style|head)\b.*?</\1>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val commentRe = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)
    private val blockRe = Regex("""</?(p|div|br|tr|li|h[1-6]|table|blockquote)\b[^>]*>""", RegexOption.IGNORE_CASE)

    fun htmlToText(html: String): String {
        var s = commentRe.replace(html, " ")
        s = scriptRe.replace(s, " ")
        s = blockRe.replace(s, "\n")
        s = tagRe.replace(s, " ")
        s = decodeEntities(s)
        return s.replace(Regex("[ \\t\\x0B\\f\\r]+"), " ").replace(Regex("\\n\\s*\\n+"), "\n\n").trim()
    }

    private val entityRe = Regex("""&(#x[0-9a-fA-F]+|#\d+|[a-zA-Z]+);""")
    private val namedEntities = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "copy" to "©", "reg" to "®", "trade" to "™", "hellip" to "…", "mdash" to "—", "ndash" to "–",
        "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "euro" to "€", "pound" to "£", "yen" to "¥"
    )

    fun decodeEntities(s: String): String = entityRe.replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.let { cp -> codePointToString(cp) } ?: m.value
            e.startsWith("#") -> e.substring(1).toIntOrNull()?.let { cp -> codePointToString(cp) } ?: m.value
            else -> namedEntities[e.lowercase()] ?: m.value
        }
    }

    private fun codePointToString(cp: Int): String =
        if (cp in 1..0x10FFFF) String(Character.toChars(cp)) else ""
}
