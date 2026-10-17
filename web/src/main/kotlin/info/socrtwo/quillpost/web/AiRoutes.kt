package info.socrtwo.quillpost.web

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

private val aiLog = LoggerFactory.getLogger("quillpost.ai")

@Serializable
data class AiProxyRequest(val url: String, val method: String = "POST", val headers: Map<String, String> = emptyMap(), val body: String? = null)

@Serializable
data class AiEndpointRequest(val baseUrl: String)

@Serializable
data class AiKeyValue(val value: String = "")

@Serializable
data class AiInfo(val keyStore: String, val relay: Boolean)

/**
 * Where the AI relay may send requests: the base URLs of the providers in web/ai/providers.js
 * ({accountId}, {resource} and {region} become one wildcard DNS label or path segment), plus the
 * custom or local server addresses an account registered. Everything else is refused, so the
 * relay is not an open proxy.
 */
object AiAllowList {
    val PROVIDER_BASES = listOf(
        "https://openrouter.ai/api/v1",
        "https://generativelanguage.googleapis.com/v1beta",
        "https://api.groq.com/openai/v1",
        "https://api.mistral.ai/v1",
        "https://api.cerebras.ai/v1",
        "https://api.sambanova.ai/v1",
        "https://api.z.ai/api/paas/v4",
        "https://api.cohere.ai/compatibility/v1",
        "https://integrate.api.nvidia.com/v1",
        "https://api.cloudflare.com/client/v4/accounts/*/ai/v1",
        "http://localhost:11434/v1",
        "http://localhost:1234/v1",
        "https://api.openai.com/v1",
        "https://api.anthropic.com/v1",
        "https://api.deepseek.com",
        "https://api.x.ai/v1",
        "https://api.perplexity.ai",
        "https://api.together.xyz/v1",
        "https://api.fireworks.ai/inference/v1",
        "https://router.huggingface.co/v1",
        "https://api.moonshot.ai/v1",
        "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
        "https://*.openai.azure.com/openai/v1",
        "https://bedrock-runtime.*.amazonaws.com/openai/v1",
    )

    private val loopback = setOf("localhost", "127.0.0.1", "[::1]", "::1")

    private data class Parsed(val scheme: String, val host: String, val port: Int, val segments: List<String>)

    private fun defaultPort(scheme: String) = if (scheme == "https") 443 else 80

    /** A target URL, or null when it is not a plain http(s) URL without credentials or dot segments. */
    private fun parseTarget(url: String): Parsed? = runCatching {
        val u = URI(url)
        val scheme = u.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (u.rawUserInfo != null || u.host.isNullOrBlank()) return null
        val segs = (u.rawPath ?: "").split('/').filter { it.isNotEmpty() }
        if (segs.any { s -> java.net.URLDecoder.decode(s, Charsets.UTF_8).let { it == "." || it == ".." || it.contains('/') || it.contains('\\') } }) return null
        Parsed(scheme, u.host.lowercase(), if (u.port == -1) defaultPort(scheme) else u.port, segs)
    }.getOrNull()

    private fun parsePattern(base: String): Parsed? {
        val i = base.indexOf("://")
        if (i < 0) return null
        val scheme = base.substring(0, i).lowercase()
        val rest = base.substring(i + 3)
        val slash = rest.indexOf('/').let { if (it < 0) rest.length else it }
        val hostPort = rest.substring(0, slash)
        val path = rest.substring(slash)
        val colon = hostPort.lastIndexOf(':')
        val (host, port) = if (colon > 0 && !hostPort.endsWith("]") && hostPort.substring(colon + 1).all { it.isDigit() }) hostPort.substring(0, colon) to hostPort.substring(colon + 1).toInt() else hostPort to defaultPort(scheme)
        return Parsed(scheme, host.lowercase(), port, path.split('/').filter { it.isNotEmpty() })
    }

    private fun hostMatches(pattern: String, host: String): Boolean {
        if (pattern in loopback && host in loopback) return true
        if (!pattern.contains('*')) return pattern == host
        val rx = Regex("^" + pattern.split('*').joinToString("[a-z0-9-]+") { Regex.escape(it) } + "$")
        return rx.matches(host)
    }

    /** True when [url] is under one of the provider bases or the account's [custom] bases. */
    fun allowed(url: String, custom: Collection<String> = emptyList()): Boolean {
        val t = parseTarget(url) ?: return false
        return (PROVIDER_BASES + custom).any { base ->
            val p = parsePattern(base) ?: return@any false
            p.scheme == t.scheme && p.port == t.port && hostMatches(p.host, t.host) &&
                t.segments.size >= p.segments.size &&
                p.segments.indices.all { k -> p.segments[k] == "*" || p.segments[k] == t.segments[k] }
        }
    }

    /** A custom base URL an account may register: http(s), no credentials, no query, no wildcards. */
    fun validCustomBase(base: String): Boolean {
        if (base.length > 300 || base.contains('*') || base.contains('?') || base.contains('#')) return false
        return parseTarget(base) != null
    }
}

/** Request headers the relay forwards (credentials for the provider, content negotiation, attribution). */
private val FORWARDED_HEADERS = setOf("authorization", "x-api-key", "api-key", "x-goog-api-key", "anthropic-version", "content-type", "accept", "http-referer", "x-title")

private val aiJson = Json { ignoreUnknownKeys = true; prettyPrint = true }

/** True when this backend was started by the desktop launcher (it stores AI keys for the page). */
internal fun desktopMode(): Boolean =
    qpProp("quillpost.desktop") == "true" || qpEnv("QUILLPOST_DESKTOP") == "1"

private fun relayEnabled(): Boolean = qpEnv("QUILLPOST_AI_RELAY")?.lowercase() != "off"

/**
 * Which networks the relay may reach. Link-local addresses (cloud metadata services live there),
 * "any" and multicast addresses are always refused. Loopback and private addresses (a local
 * Ollama, a server on the LAN) are allowed only when the browser asking is itself on loopback or
 * a private network — so a QuillPost server reachable from the internet does not become a way into
 * its own network. QUILLPOST_AI_RELAY_PRIVATE=on / off overrides that ("off" is the safe choice
 * behind a reverse proxy on the same machine, where every client appears as loopback).
 */
object AiRelayNetwork {
    private fun isPrivate(a: java.net.InetAddress): Boolean =
        a.isLoopbackAddress || a.isSiteLocalAddress ||
            (a is java.net.Inet6Address && (a.address[0].toInt() and 0xfe) == 0xfc) || // fc00::/7 unique local
            (a is java.net.Inet4Address && a.address[0].toInt() and 0xff == 100 && (a.address[1].toInt() and 0xc0) == 64) // 100.64/10 shared
    private fun isForbidden(a: java.net.InetAddress): Boolean = a.isLinkLocalAddress || a.isAnyLocalAddress || a.isMulticastAddress

    /** Null when the relay may connect to [targets] for a browser at [client]; otherwise the reason. */
    fun refusal(targets: List<java.net.InetAddress>, client: java.net.InetAddress?, mode: String? = qpEnv("QUILLPOST_AI_RELAY_PRIVATE")): String? {
        if (targets.isEmpty()) return "the address does not resolve"
        if (targets.any(::isForbidden)) return "link-local and broadcast addresses are never relayed"
        if (targets.none(::isPrivate)) return null
        return when (mode?.lowercase()) {
            "on" -> null
            "off" -> "this server does not relay to local or private addresses (QUILLPOST_AI_RELAY_PRIVATE=off)"
            else -> if (client != null && isPrivate(client)) null else "a local or private address is relayed only for browsers on a local network"
        }
    }
}

/**
 * The desktop app's API-key store: one JSON file in the data folder, readable and writable only
 * by the user account that runs QuillPost (POSIX 0600 in a 0700 folder; on Windows the file sits
 * in the user's profile, whose default permissions already exclude other standard users). The
 * file is not encrypted — the desktop launcher opens the system browser and has no OS keychain
 * bridge — and docs/AI.md says so.
 */
internal class AiKeyFile(root: File) {
    private val dir = File(root, "ai")
    private val file = File(dir, "keys.json")
    private val lock = Any()

    private fun restrict(f: File, directory: Boolean) {
        runCatching { Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString(if (directory) "rwx------" else "rw-------")) }
            .onFailure {
                // Not a POSIX file system (Windows): owner-only flags as far as java.io allows.
                f.setReadable(false, false); f.setReadable(true, true)
                f.setWritable(false, false); f.setWritable(true, true)
                if (directory) { f.setExecutable(false, false); f.setExecutable(true, true) }
            }
    }

    private fun read(): MutableMap<String, String> =
        if (!file.isFile) mutableMapOf() else runCatching { aiJson.decodeFromString<Map<String, String>>(file.readText()).toMutableMap() }.getOrElse { mutableMapOf() }

    private fun write(map: Map<String, String>) {
        if (!dir.isDirectory) dir.mkdirs()
        restrict(dir, true)
        val tmp = File(dir, "keys.json.tmp")
        tmp.writeText("")
        restrict(tmp, false)
        tmp.writeText(aiJson.encodeToString(kotlinx.serialization.serializer<Map<String, String>>(), map))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        restrict(file, false)
    }

    fun get(name: String): String? = synchronized(lock) { read()[name] }
    fun put(name: String, value: String) = synchronized(lock) { write(read().apply { put(name, value) }) }
    fun remove(name: String) = synchronized(lock) { val m = read(); if (m.remove(name) != null) write(m) }
}

/** Custom AI server addresses an account registered for the relay (account data folder). */
private fun customBases(ctx: AccountContext): MutableList<String> {
    val f = File(ctx.store.accountDir(ctx.account.email), "ai-endpoints.json")
    return if (!f.isFile) mutableListOf() else runCatching { aiJson.decodeFromString<List<String>>(f.readText()).toMutableList() }.getOrElse { mutableListOf() }
}

private fun saveCustomBases(ctx: AccountContext, list: List<String>) {
    File(ctx.store.accountDir(ctx.account.email), "ai-endpoints.json").writeText(aiJson.encodeToString(kotlinx.serialization.serializer<List<String>>(), list))
}

private suspend fun ApplicationCall.relayRefusal(status: HttpStatusCode, why: String) {
    // The page tells a refusal by the relay itself apart from an error answered by the provider.
    response.header("X-QuillPost-Proxy-Error", URLEncoder.encode(why, Charsets.UTF_8).replace("+", "%20"))
    respond(status, ApiError(why))
}

private val keyName = Regex("^ai\\.key\\.[a-z0-9_-]{1,40}$")

/**
 * AI routes: the web UI scripts under ai/, /api/ai/info, the relay (/api/ai/proxy) for AI
 * services that refuse calls from web pages, custom relay addresses, and the desktop key store.
 * Keys and request bodies are never logged.
 */
internal fun Routing.aiRoutes(sessions: Sessions, store: DataStore) {
    val keyFile by lazy { AiKeyFile(store.root) }

    get("/ai/{file}") {
        val name = call.parameters["file"] ?: ""
        val bytes = aiStatic(name)
        if (bytes == null) { call.respond(HttpStatusCode.NotFound, ApiError("Not found")); return@get }
        val type = when (name.substringAfterLast('.')) {
            "js" -> ContentType.Application.JavaScript.withCharset(Charsets.UTF_8)
            "css" -> ContentType.Text.CSS.withCharset(Charsets.UTF_8)
            else -> ContentType.Application.OctetStream
        }
        call.response.header(HttpHeaders.CacheControl, if (qpEnv("QUILLPOST_WEB_DIR") != null) "no-cache" else "max-age=300")
        call.respondBytes(bytes, type)
    }

    get("/api/ai/info") { call.respond(AiInfo(keyStore = if (desktopMode()) "desktop-file" else "browser", relay = relayEnabled())) }

    post("/api/ai/endpoints") {
        call.handle {
            val ctx = sessions.get(call.token())
            val base = call.receive<AiEndpointRequest>().baseUrl.trim().trimEnd('/')
            require(AiAllowList.validCustomBase(base)) { "Use an http:// or https:// address without a user name, query or wildcard." }
            val list = customBases(ctx)
            if (base !in list) { list.add(0, base); saveCustomBases(ctx, list.take(20)) }
            ApiStatus("ok")
        }
    }

    post("/api/ai/proxy") {
        if (!relayEnabled()) { call.relayRefusal(HttpStatusCode.Forbidden, "the AI relay is turned off on this QuillPost server (QUILLPOST_AI_RELAY=off)"); return@post }
        if (call.request.header("X-QuillPost-AI") != "1") { call.relayRefusal(HttpStatusCode.BadRequest, "missing X-QuillPost-AI header"); return@post }
        val ctx = try { sessions.get(call.token()) } catch (e: AuthException) { call.relayRefusal(HttpStatusCode.Unauthorized, "sign in to QuillPost first"); return@post }
        val req = try { call.receive<AiProxyRequest>() } catch (e: Exception) { call.relayRefusal(HttpStatusCode.BadRequest, "malformed relay request"); return@post }
        val method = req.method.uppercase()
        if (method != "GET" && method != "POST") { call.relayRefusal(HttpStatusCode.BadRequest, "only GET and POST are relayed"); return@post }
        if (!AiAllowList.allowed(req.url, customBases(ctx))) {
            val host = runCatching { URI(req.url).host }.getOrNull() ?: "this address"
            call.relayRefusal(HttpStatusCode.Forbidden, "$host is not an AI service QuillPost knows. Set it up in AI ▸ Set up AI first.")
            return@post
        }
        val netRefusal = withContext(Dispatchers.IO) {
            val host = runCatching { URI(req.url).host?.removePrefix("[")?.removeSuffix("]") }.getOrNull()
            val targets = runCatching { java.net.InetAddress.getAllByName(host).toList() }.getOrDefault(emptyList())
            val client = runCatching { java.net.InetAddress.getByName(call.request.local.remoteAddress) }.getOrNull()
            AiRelayNetwork.refusal(targets, client)
        }
        if (netRefusal != null) { call.relayRefusal(HttpStatusCode.Forbidden, netRefusal); return@post }
        ctx.touch()
        val conn = try {
            withContext(Dispatchers.IO) {
                (URL(req.url).openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = 15_000
                    readTimeout = 300_000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "QuillPost/1.4 (+https://github.com/socrtwo/QuillPost)")
                    for ((k, v) in req.headers) if (k.lowercase() in FORWARDED_HEADERS && !v.contains('\n') && !v.contains('\r')) setRequestProperty(k, v)
                    if (method == "POST" && req.body != null) {
                        doOutput = true
                        outputStream.use { it.write(req.body.toByteArray(Charsets.UTF_8)) }
                    }
                    responseCode // connect and read the status line
                }
            }
        } catch (e: Exception) {
            aiLog.warn("AI relay could not reach {}: {}", runCatching { URI(req.url).host }.getOrNull(), e.javaClass.simpleName)
            call.relayRefusal(HttpStatusCode.BadGateway, "could not reach ${runCatching { URI(req.url).host }.getOrNull()}: ${e.message ?: e.javaClass.simpleName}")
            return@post
        }
        val status = conn.responseCode
        val type = conn.contentType?.let { runCatching { ContentType.parse(it) }.getOrNull() } ?: ContentType.Application.Json
        val upstream = if (status >= 400) conn.errorStream else conn.inputStream
        call.response.header(HttpHeaders.CacheControl, "no-store")
        try {
            call.respondOutputStream(type, HttpStatusCode.fromValue(status)) {
                withContext(Dispatchers.IO) {
                    upstream?.use { input ->
                        val buf = ByteArray(8192)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            write(buf, 0, n)
                            flush() // server-sent events go out as they arrive
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    // --- desktop key store ---------------------------------------------------------------
    suspend fun ApplicationCall.keyGuard(): String? {
        if (!desktopMode()) { respond(HttpStatusCode.NotFound, ApiError("Not found")); return null }
        if (request.header("X-QuillPost-AI") != "1") { respond(HttpStatusCode.BadRequest, ApiError("missing header")); return null }
        // Only this page: a cross-site request carries a foreign Origin (and could not set the header without a preflight).
        val origin = request.header(HttpHeaders.Origin)
        val host = request.header(HttpHeaders.Host)
        if (origin != null && runCatching { URI(origin).authority }.getOrNull() != host) { respond(HttpStatusCode.Forbidden, ApiError("cross-origin request refused")); return null }
        try { sessions.get(token()) } catch (e: AuthException) { respond(HttpStatusCode.Unauthorized, ApiError("Not signed in")); return null }
        val name = parameters["name"] ?: ""
        if (!keyName.matches(name)) { respond(HttpStatusCode.BadRequest, ApiError("bad key name")); return null }
        return name
    }

    get("/api/ai/keys/{name}") {
        val name = call.keyGuard() ?: return@get
        val v = withContext(Dispatchers.IO) { keyFile.get(name) }
        call.response.header(HttpHeaders.CacheControl, "no-store")
        if (v == null) call.respond(HttpStatusCode.NotFound, ApiError("Not found")) else call.respond(AiKeyValue(v))
    }
    put("/api/ai/keys/{name}") {
        val name = call.keyGuard() ?: return@put
        val v = call.receive<AiKeyValue>().value
        if (v.length > 4096) { call.respond(HttpStatusCode.BadRequest, ApiError("key too long")); return@put }
        withContext(Dispatchers.IO) { keyFile.put(name, v) }
        call.respond(ApiStatus("ok"))
    }
    delete("/api/ai/keys/{name}") {
        val name = call.keyGuard() ?: return@delete
        withContext(Dispatchers.IO) { keyFile.remove(name) }
        call.respond(ApiStatus("deleted"))
    }
}

private val aiStaticCache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
private val aiSafeName = Regex("^[A-Za-z0-9_-]+\\.(js|css)$")

/** web/ai/<name> from the class path (or QUILLPOST_WEB_DIR/ai during development). */
private fun aiStatic(name: String): ByteArray? {
    if (!aiSafeName.matches(name)) return null
    qpEnv("QUILLPOST_WEB_DIR")?.takeIf { it.isNotBlank() }?.let { d -> val f = File(File(d, "ai"), name); return if (f.isFile) f.readBytes() else null }
    aiStaticCache[name]?.let { return it }
    val loaded = AiAllowList::class.java.classLoader?.getResourceAsStream("web/ai/$name")?.use { it.readBytes() } ?: return null
    aiStaticCache[name] = loaded
    return loaded
}
