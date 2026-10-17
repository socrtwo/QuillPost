package info.socrtwo.quillpost.web.spam

import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Result of one DNS A-record lookup. */
sealed class DnsResult {
    data class Found(val addresses: List<String>) : DnsResult()
    object NotFound : DnsResult()
    data class Error(val message: String) : DnsResult()
}

/** Abstracts DNS so the engine is testable and portable (JVM here, could be Android's resolver). */
interface DnsResolver {
    fun lookupA(name: String, timeoutMs: Int): DnsResult
}

/** Default resolver built on java.net.InetAddress with a hard timeout per query. */
class InetAddressDnsResolver : DnsResolver {
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "quillpost-dns").apply { isDaemon = true } }

    override fun lookupA(name: String, timeoutMs: Int): DnsResult {
        val future = pool.submit<DnsResult> {
            try {
                val addrs = InetAddress.getAllByName(name).map { it.hostAddress }
                if (addrs.isEmpty()) DnsResult.NotFound else DnsResult.Found(addrs)
            } catch (_: UnknownHostException) {
                DnsResult.NotFound
            } catch (e: Exception) {
                DnsResult.Error(e.message ?: e.javaClass.simpleName)
            }
        }
        return try {
            future.get(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            DnsResult.Error("timeout after ${timeoutMs}ms")
        } catch (e: Exception) {
            DnsResult.Error(e.message ?: "lookup failed")
        }
    }
}

/**
 * Queries free, open DNS-based blocklists (DNSBL for sending IPs; RHSBL/URIBL for sender and
 * link domains). No API keys are involved — it is plain DNS. Results are cached in memory.
 */
class BlacklistChecker(
    private val resolver: DnsResolver = InetAddressDnsResolver(),
    private val cacheTtlMs: Long = 6 * 3600_000L
) {
    private data class Cached(val result: DnsResult, val at: Long)
    private val cache = ConcurrentHashMap<String, Cached>()

    data class Outcome(val hits: List<BlacklistHit>, val status: List<BlacklistStatus>)

    fun check(ips: List<String>, domains: List<String>, lists: List<BlacklistDef>, timeoutMs: Int): Outcome {
        val hits = ArrayList<BlacklistHit>()
        val status = ArrayList<BlacklistStatus>()
        for (list in lists.filter { it.enabled }) {
            val subjects = if (list.kind == "ip") ips else domains
            if (subjects.isEmpty()) { status += BlacklistStatus(list.label, list.zone, 0, 0, false); continue }
            var refused = false
            var error: String? = null
            var listHits = 0
            for (subject in subjects) {
                val query = queryName(subject, list) ?: continue
                when (val r = lookup(query, timeoutMs)) {
                    is DnsResult.Found -> {
                        val code = r.addresses.firstOrNull { it.startsWith("127.") } ?: r.addresses.first()
                        val decoded = decode(list, code)
                        if (decoded == null) {
                            refused = true
                        } else {
                            listHits++
                            hits += BlacklistHit(list.label, list.zone, subject, list.kind, code, decoded)
                        }
                    }
                    is DnsResult.NotFound -> {}
                    is DnsResult.Error -> error = r.message
                }
            }
            status += BlacklistStatus(list.label, list.zone, subjects.size, listHits, refused, error)
        }
        return Outcome(hits, status)
    }

    fun queryName(subject: String, list: BlacklistDef): String? = when (list.kind) {
        "ip" -> if (subject.contains(':')) HeaderParser.reverseIpv6(subject)?.let { "$it.${list.zone}" }
                else "${HeaderParser.reverseIpv4(subject)}.${list.zone}"
        else -> subject.takeIf { it.isNotBlank() && !HeaderParser.isIpLiteral(it) }?.let { "$it.${list.zone}" }
    }

    private fun lookup(name: String, timeoutMs: Int): DnsResult {
        val now = System.currentTimeMillis()
        cache[name]?.let { if (now - it.at < cacheTtlMs) return it.result }
        val r = resolver.lookupA(name, timeoutMs)
        if (r !is DnsResult.Error) cache[name] = Cached(r, now)
        return r
    }

    /**
     * Turns a list's answer code into a human note, or null when the code means "query refused /
     * not a listing" (e.g. Spamhaus 127.255.255.x when queried via an open public resolver).
     */
    fun decode(list: BlacklistDef, code: String): String? {
        val octets = code.split('.').map { it.toIntOrNull() ?: -1 }
        if (octets.size != 4 || octets[0] != 127) return null   // only 127.x.x.x answers are listings
        val (_, b, c, d) = octets
        return when (list.id) {
            Blacklists.SPAMHAUS_ZEN -> when {
                b == 255 -> null                                   // 127.255.255.x: blocked / public resolver / errors
                b == 0 && c == 0 && d in 2..3 -> "Spamhaus SBL: known spam source"
                b == 0 && c == 0 && d in 4..7 -> "Spamhaus XBL: compromised / botnet host"
                b == 0 && c == 0 && d in 10..11 -> "Spamhaus PBL: dynamic / residential IP that should not send mail directly"
                b == 0 && c == 0 && d == 9 -> "Spamhaus DROP: hijacked network"
                else -> "Spamhaus ZEN listing ($code)"
            }
            Blacklists.SPAMHAUS_DBL -> when {
                b == 255 -> null
                b == 0 && c == 1 && d == 255 -> null                // "IP queries prohibited"
                b == 0 && c == 1 && d == 2 -> "Spamhaus DBL: spam domain"
                b == 0 && c == 1 && d == 4 -> "Spamhaus DBL: phishing domain"
                b == 0 && c == 1 && d == 5 -> "Spamhaus DBL: malware domain"
                b == 0 && c == 1 && d == 6 -> "Spamhaus DBL: botnet C&C domain"
                b == 0 && c == 1 && d in 102..106 -> "Spamhaus DBL: abused legitimate domain"
                else -> "Spamhaus DBL listing ($code)"
            }
            Blacklists.SURBL -> when {
                b == 0 && c == 0 && d == 1 -> null                  // not a listing
                b == 0 && c == 0 -> {
                    val flags = mutableListOf<String>()
                    if (d and 8 != 0) flags += "phishing"
                    if (d and 16 != 0) flags += "malware"
                    if (d and 64 != 0) flags += "abused (spam)"
                    if (d and 128 != 0) flags += "cracked"
                    "SURBL: " + (flags.ifEmpty { listOf("listed") }).joinToString(", ")
                }
                else -> "SURBL listing ($code)"
            }
            Blacklists.URIBL -> when {
                b == 0 && c == 0 && d == 1 -> null                  // query refused (rate-limited resolver)
                b == 0 && c == 0 -> {
                    val flags = mutableListOf<String>()
                    if (d and 2 != 0) flags += "black"
                    if (d and 4 != 0) flags += "grey"
                    if (d and 8 != 0) flags += "red"
                    if (d and 16 != 0) flags += "multi"
                    if (flags.isEmpty() || flags == listOf("grey")) return null // grey alone is too weak
                    "URIBL: " + flags.joinToString(", ")
                }
                else -> "URIBL listing ($code)"
            }
            Blacklists.SPAMCOP -> if (b == 0 && c == 0 && d == 2) "SpamCop: recently reported spam source" else null
            Blacklists.BARRACUDA -> if (b == 0 && c == 0 && d == 2) "Barracuda: poor reputation sender" else null
            Blacklists.PSBL -> if (b == 0 && c == 0 && d == 2) "PSBL: spam trap hit" else null
            Blacklists.UCEPROTECT1 -> if (b == 0 && c == 0 && d == 2) "UCEPROTECT: spam source" else null
            else -> if (b == 255) null else "Listed ($code)"
        }
    }
}
