package info.socrtwo.quillbox.web

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.ThreadLocalRandom

/**
 * Small, dependency-free HTTP and DNS helpers used by Autodiscover and the Ollama bridge.
 *
 * They are written against `java.net.HttpURLConnection` and plain UDP sockets rather than
 * `java.net.http.HttpClient` and JNDI, so the very same backend sources compile and run on
 * the desktop JVM, on a server, in Termux and inside the Android app.
 */
object Http {
    data class Response(val status: Int, val body: String)

    fun get(url: String, timeoutMs: Int = 5000, headers: Map<String, String> = emptyMap()): Response =
        request("GET", url, null, null, timeoutMs, headers)

    fun postJson(url: String, json: String, timeoutMs: Int = 120_000): Response =
        request("POST", url, json.toByteArray(Charsets.UTF_8), "application/json", timeoutMs, emptyMap())

    private fun request(method: String, url: String, body: ByteArray?, contentType: String?, timeoutMs: Int, headers: Map<String, String>): Response {
        var current = url
        repeat(5) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = minOf(timeoutMs, 5000)
                readTimeout = timeoutMs
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Quillbox/1.4 (+https://github.com/socrtwo/quillbox)")
                setRequestProperty("Accept", "*/*")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
                if (body != null) {
                    doOutput = true
                    if (contentType != null) setRequestProperty("Content-Type", contentType)
                }
            }
            try {
                if (body != null) conn.outputStream.use { it.write(body) }
                val status = conn.responseCode
                if (status in 300..399) {
                    val loc = conn.getHeaderField("Location")
                    if (loc != null) { current = URL(URL(current), loc).toString(); return@repeat }
                }
                val stream = if (status >= 400) conn.errorStream else conn.inputStream
                val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                return Response(status, text)
            } finally {
                conn.disconnect()
            }
        }
        return Response(310, "Too many redirects")
    }
}

/**
 * Minimal DNS client (RFC 1035 over UDP) for record types `InetAddress` cannot fetch — MX in
 * particular. Servers are taken from `/etc/resolv.conf` when readable (Linux, macOS, Termux),
 * followed by two public resolvers as a fallback (Windows, Android).
 */
object Dns {
    private const val TYPE_MX = 15
    private val fallbackServers = listOf("1.1.1.1", "8.8.8.8", "9.9.9.9")

    fun servers(): List<String> {
        val fromFile = runCatching {
            val f = File("/etc/resolv.conf")
            if (!f.canRead()) emptyList() else f.readLines()
                .map { it.trim() }
                .filter { it.startsWith("nameserver") }
                .mapNotNull { it.removePrefix("nameserver").trim().takeIf { s -> s.isNotBlank() } }
                .map { it.substringBefore('%') }
        }.getOrDefault(emptyList())
        return (fromFile + fallbackServers).distinct()
    }

    /** Mail exchangers for [domain], lowest preference first, or null when the lookup fails. */
    fun mx(domain: String, timeoutMs: Int = 2000): List<String>? {
        for (server in servers()) {
            val answer = runCatching { query(domain, TYPE_MX, server, timeoutMs) }.getOrNull() ?: continue
            return answer
        }
        return null
    }

    private fun query(name: String, type: Int, server: String, timeoutMs: Int): List<String>? {
        val id = ThreadLocalRandom.current().nextInt(1, 65535)
        val out = ByteArrayOutputStream()
        fun u16(v: Int) { out.write((v shr 8) and 0xff); out.write(v and 0xff) }
        u16(id); u16(0x0100); u16(1); u16(0); u16(0); u16(0)
        for (label in name.trimEnd('.').split('.')) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            if (bytes.isEmpty() || bytes.size > 63) return null
            out.write(bytes.size); out.write(bytes)
        }
        out.write(0); u16(type); u16(1)
        val packet = out.toByteArray()
        val buf = ByteArray(4096)
        val response = DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            socket.send(DatagramPacket(packet, packet.size, InetSocketAddress(InetAddress.getByName(server), 53)))
            val reply = DatagramPacket(buf, buf.size)
            socket.receive(reply)
            ByteBuffer.wrap(buf, 0, reply.length)
        }
        if (response.remaining() < 12) return null
        val rid = response.short.toInt() and 0xffff
        if (rid != id) return null
        val flags = response.short.toInt() and 0xffff
        val rcode = flags and 0x000f
        if (rcode == 3) return emptyList()            // NXDOMAIN: definite answer, no records
        if (rcode != 0) return null                   // SERVFAIL / REFUSED: try the next server
        val qd = response.short.toInt() and 0xffff
        val an = response.short.toInt() and 0xffff
        response.short; response.short                // NS / AR counts
        repeat(qd) { readName(response); response.short; response.short }
        val results = ArrayList<Pair<Int, String>>()
        repeat(an) {
            readName(response)
            val rtype = response.short.toInt() and 0xffff
            response.short                            // class
            response.int                              // ttl
            val rdlen = response.short.toInt() and 0xffff
            val end = response.position() + rdlen
            if (rtype == TYPE_MX) {
                val pref = response.short.toInt() and 0xffff
                val host = readName(response)
                results += pref to host.lowercase().trimEnd('.')
            }
            response.position(end)
        }
        return results.sortedBy { it.first }.map { it.second }.filter { it.isNotBlank() }
    }

    private fun readName(buf: ByteBuffer): String {
        val labels = ArrayList<String>()
        var jumped = false
        var returnTo = -1
        var hops = 0
        while (true) {
            val len = buf.get().toInt() and 0xff
            if (len == 0) break
            if (len and 0xC0 == 0xC0) {
                val pointer = ((len and 0x3f) shl 8) or (buf.get().toInt() and 0xff)
                if (!jumped) returnTo = buf.position()
                jumped = true
                if (++hops > 20) break
                buf.position(pointer)
                continue
            }
            val bytes = ByteArray(len); buf.get(bytes)
            labels += String(bytes, Charsets.ISO_8859_1)
        }
        if (jumped && returnTo >= 0) buf.position(returnTo)
        return labels.joinToString(".")
    }
}
