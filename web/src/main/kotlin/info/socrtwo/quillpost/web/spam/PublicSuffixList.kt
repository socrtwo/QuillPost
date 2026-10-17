package info.socrtwo.quillpost.web.spam

/**
 * The Public Suffix List (ICANN section, from `psl/icann.dat` on the class path) used to find
 * the registrable ("organisation-level") domain of a host: `mail.example.co.uk` → `example.co.uk`,
 * `a.b.example.com` → `example.com`. Rules follow https://publicsuffix.org/list/: exact suffixes,
 * `*.` wildcards (one more label is public) and `!` exceptions. When the resource is missing a
 * small built-in table keeps the most common two-level suffixes working.
 */
object PublicSuffixList {
    private val rules: Set<String>
    private val wildcards: Set<String>
    private val exceptions: Set<String>
    val loadedFromResource: Boolean

    private val fallback: List<String> = listOf(
        "com", "net", "org", "edu", "gov", "mil", "int", "info", "biz", "io", "co", "me", "uk", "de", "fr", "it", "es", "nl", "be", "ch", "at", "se", "no", "dk", "fi", "pl", "cz", "ru", "ua", "jp", "cn", "kr", "in", "au", "nz", "br", "mx", "ar", "ca", "za",
        "co.uk", "org.uk", "ac.uk", "gov.uk", "me.uk", "ltd.uk", "plc.uk", "net.uk", "sch.uk", "nhs.uk",
        "com.au", "net.au", "org.au", "edu.au", "gov.au", "co.nz", "org.nz", "net.nz", "govt.nz",
        "co.jp", "ne.jp", "or.jp", "ac.jp", "go.jp", "co.kr", "or.kr", "ac.kr", "go.kr", "com.br", "net.br", "org.br", "gov.br",
        "com.mx", "org.mx", "gob.mx", "com.ar", "com.co", "com.pe", "com.ve", "com.cl", "co.za", "org.za", "gov.za", "ac.za",
        "com.cn", "net.cn", "org.cn", "gov.cn", "edu.cn", "com.hk", "com.tw", "com.sg", "com.my", "co.id", "co.in", "net.in", "org.in", "ac.in", "gov.in",
        "com.tr", "com.pl", "com.ua", "com.ru", "co.il", "org.il", "com.eg", "com.sa", "com.ph", "com.vn", "com.pk", "com.bd", "com.ng", "co.ke",
        "co.th", "or.th", "ac.th", "go.th", "com.ec", "com.uy", "com.py", "com.bo", "com.gt", "com.do", "com.pr", "com.ni", "com.sv", "com.hn", "com.pa"
    )

    init {
        val text = runCatching {
            PublicSuffixList::class.java.classLoader?.getResourceAsStream("psl/icann.dat")?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()
        val lines = text?.lineSequence()?.map { it.trim() }?.filter { it.isNotEmpty() && !it.startsWith("#") }?.toList()
        loadedFromResource = lines != null && lines.size > 100
        val src = lines?.takeIf { loadedFromResource } ?: fallback
        rules = src.filter { !it.startsWith("*.") && !it.startsWith("!") }.map { it.lowercase() }.toHashSet()
        wildcards = src.filter { it.startsWith("*.") }.map { it.removePrefix("*.").lowercase() }.toHashSet()
        exceptions = src.filter { it.startsWith("!") }.map { it.removePrefix("!").lowercase() }.toHashSet()
    }

    /** Number of labels of [host] that form its public suffix (1 for `com`, 2 for `co.uk`, …). */
    fun publicSuffixLength(labels: List<String>): Int {
        var best = 1                                             // unknown TLDs behave like plain TLDs
        for (i in labels.indices) {
            val candidate = labels.subList(i, labels.size).joinToString(".")
            val parent = if (i + 1 < labels.size) labels.subList(i + 1, labels.size).joinToString(".") else ""
            val len = labels.size - i
            if (candidate in exceptions) { best = maxOf(best, len - 1); break }
            if (candidate in rules) { best = maxOf(best, len); break }
            if (parent.isNotEmpty() && parent in wildcards) { best = maxOf(best, len); break }
        }
        return best
    }

    /** The registrable domain of [host] (lower-cased, trailing dot removed), or the host itself when it is a public suffix. */
    fun registrableDomain(host: String): String {
        val h = host.lowercase().trimEnd('.')
        val labels = h.split('.').filter { it.isNotEmpty() }
        if (labels.size <= 1) return h
        val suffix = publicSuffixLength(labels)
        if (labels.size <= suffix) return h
        return labels.takeLast(suffix + 1).joinToString(".")
    }

}
