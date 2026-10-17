package info.socrtwo.quillpost.web

import info.socrtwo.quillpost.web.spam.BlacklistChecker
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.MessagingException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("quillpost")

/**
 * The whole QuillPost backend as a Ktor module. The JVM server (`ServerMain.kt`), the desktop
 * launcher and the Android app all install exactly this module on their own engine, so every
 * platform serves the same API and the same Outlook-style UI.
 */
fun Application.module() {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }) }

    val store = DataStore()
    val blacklists = BlacklistChecker()
    val sessions = Sessions(store, blacklists)
    log.info("QuillPost data directory: ${store.root.absolutePath}")

    fun ApplicationCall.ctx(): AccountContext = sessions.get(token())

    routing {
        extraRoutes(sessions)
        commandRoutes(sessions)
        fileRoutes(sessions) // "Open file": messages, contacts, calendars and office files (FileRoutes.kt)
        // QuillPen embedded editor (desktop): /api/quillpen and /quillpen-embed/<path>. See QuillPenEmbed.kt.
        quillPenEmbedRoutes(sessions, store.root)
        aiRoutes(sessions, store) // AI tab: ai/*.js, relay, desktop key store (AiRoutes.kt)
        // The UI (index.html, styles.css, app.js) is read straight from the class path. This
        // deliberately avoids Ktor's file/jar resource resolution so it also works when the
        // resources live inside an Android APK.
        get("/") { call.respondStatic("index.html") }
        get("/index.html") { call.respondStatic("index.html") }
        get("/{file}") {
            val name = call.parameters["file"] ?: ""
            if (name.startsWith("api")) { call.respond(HttpStatusCode.NotFound, ApiError("Not found")); return@get }
            call.respondStatic(name)
        }

        // --- setup ---------------------------------------------------------------------
        get("/api/providers") { call.handle { ProvidersResponse(Providers.regions(), Providers.catalogue()) } }
        get("/api/provider") {
            call.handle {
                val id = call.request.queryParameters["id"] ?: throw IllegalArgumentException("id is required")
                val email = call.request.queryParameters["email"]?.trim()?.lowercase().orEmpty()
                val p = Providers.byId(id) ?: throw IllegalArgumentException("Unknown provider")
                Autodiscover.fromProvider(email, email.substringAfterLast('@', ""), p, "built-in provider list")
            }
        }
        get("/api/autodiscover") {
            call.handle {
                val email = call.request.queryParameters["email"] ?: throw IllegalArgumentException("email is required")
                withContext(Dispatchers.IO) { Autodiscover.discover(email) }
            }
        }

        post("/api/verify") {
            call.handle {
                val req = call.receive<SessionRequest>()
                val session = MailSession(req.account)
                val inc = withContext(Dispatchers.IO) { session.testIncoming() }
                val smtp = withContext(Dispatchers.IO) { session.testSmtp() }
                VerifyResponse(
                    ok = inc.isSuccess && smtp.isSuccess,
                    incoming = inc.fold({ "ok" }, { friendlyError(it) }),
                    smtp = smtp.fold({ "ok" }, { friendlyError(it) })
                )
            }
        }

        // --- session ---------------------------------------------------------------------
        post("/api/session") {
            call.handle {
                val req = call.receive<SessionRequest>()
                val a = req.account
                require(a.email.contains('@')) { "A valid email address is required" }
                require(a.incomingHost.isNotBlank()) { "Incoming mail server is required" }
                val probe = MailSession(a)
                withContext(Dispatchers.IO) { probe.testIncoming() }.getOrThrow()
                val (token, ctx) = sessions.open(a)
                SessionResponse(token, a.email, a.displayName.ifBlank { a.email }, a.protocol, ctx.capabilities, ctx.settings)
            }
        }

        delete("/api/session") { call.handle { sessions.close(call.token()) } }

        get("/api/session") {
            call.handle {
                val ctx = call.ctx()
                SessionResponse(call.token()!!, ctx.account.email, ctx.account.displayName.ifBlank { ctx.account.email }, ctx.account.protocol, ctx.capabilities, ctx.settings)
            }
        }

        // --- mailbox -----------------------------------------------------------------------
        get("/api/folders") { call.handle { call.ctx().folders() } }

        get("/api/messages") {
            call.handle {
                val ctx = call.ctx()
                val folder = call.request.queryParameters["folder"] ?: MailSession.INBOX
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 500)
                val offset = (call.request.queryParameters["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
                val rescan = call.request.queryParameters["rescan"] == "true"
                ctx.listMessages(folder, limit, offset, rescan)
            }
        }

        get("/api/message") {
            call.handle {
                val ctx = call.ctx()
                val folder = call.request.queryParameters["folder"] ?: MailSession.INBOX
                val uid = call.request.queryParameters["uid"]?.toLongOrNull() ?: throw IllegalArgumentException("uid is required")
                ctx.message(folder, uid, call.request.queryParameters["tz"] ?: "")
            }
        }

        get("/api/attachment") {
            val ctx = try { call.ctx() } catch (e: AuthException) { call.respond(HttpStatusCode.Unauthorized, ApiError(e.message ?: "")); return@get }
            val folder = call.request.queryParameters["folder"] ?: MailSession.INBOX
            val uid = call.request.queryParameters["uid"]?.toLongOrNull()
            val index = call.request.queryParameters["index"]?.toIntOrNull()
            val cid = call.request.queryParameters["cid"]
            if (uid == null || (index == null && cid == null)) { call.respond(HttpStatusCode.BadRequest, ApiError("uid and index or cid required")); return@get }
            val att = try { ctx.attachment(folder, uid, index, cid) } catch (e: Exception) { call.respond(HttpStatusCode.BadGateway, ApiError(e.message ?: "fetch failed")); return@get }
            if (att == null) { call.respond(HttpStatusCode.NotFound, ApiError("Attachment not found")); return@get }
            val inline = call.request.queryParameters["inline"] == "true" || cid != null
            val safeType = if (inline && (att.mimeType.startsWith("image/") || att.mimeType == "application/pdf")) att.mimeType else if (inline) "application/octet-stream" else att.mimeType
            call.response.header(HttpHeaders.ContentDisposition,
                (if (inline) ContentDisposition.Inline else ContentDisposition.Attachment).withParameter(ContentDisposition.Parameters.FileName, att.fileName).toString())
            call.response.header("X-Content-Type-Options", "nosniff")
            call.respondBytes(att.bytes, ContentType.parse(safeType.ifBlank { "application/octet-stream" }))
        }

        get("/api/search") {
            call.handle {
                val ctx = call.ctx()
                val folder = call.request.queryParameters["folder"] ?: MailSession.INBOX
                val q = call.request.queryParameters["q"]?.trim().orEmpty()
                require(q.isNotEmpty()) { "q is required" }
                ctx.search(folder, q, (call.request.queryParameters["limit"]?.toIntOrNull() ?: 100).coerceIn(1, 500))
            }
        }

        post("/api/move") { call.handle { call.ctx().move(call.receive()) } }
        post("/api/flags") { call.handle { call.ctx().flags(call.receive()) } }
        post("/api/delete") { call.handle { call.ctx().delete(call.receive()) } }
        post("/api/junk") { call.handle { call.ctx().junk(call.receive()) } }
        post("/api/train") { call.handle { call.ctx().train(call.receive()) } }
        post("/api/draft") { call.handle { call.ctx().saveDraft(call.receive()) } }

        // --- spam engine -----------------------------------------------------------------
        post("/api/analyze") {
            call.handle {
                val ref = call.receive<MessageRef>()
                call.ctx().analyze(ref.folder, ref.uid)
            }
        }
        post("/api/rules/preview") { call.handle { call.ctx().previewRule(call.receive()) } }
        post("/api/rules/apply") { call.handle { call.ctx().applyRule(call.receive()) } }
        get("/api/spam/status") { call.handle { call.ctx().status() } }
        get("/api/spam/activity") { call.handle { call.ctx().activity(call.request.queryParameters["since"]?.toLongOrNull() ?: 0L) } }
        get("/api/spam/blacklists") { call.handle { info.socrtwo.quillpost.web.spam.Blacklists.catalogue.map { BlacklistDefDto(it.id, it.label, it.zone, it.kind, it.weight, it.enabled, true, it.homepage) } } }

        get("/api/settings") { call.handle { call.ctx().settings } }
        put("/api/settings") { call.handle { call.ctx().updateSettings(call.receive()) } }

        post("/api/ollama/test") {
            call.handle {
                val cfg = call.receive<OllamaConfigDto>()
                withContext(Dispatchers.IO) { Ollama(cfg).test() }
            }
        }

        // --- sending (session or legacy stateless) ----------------------------------------
        post("/api/send") {
            call.handle {
                val req = call.receive<SendRequest>()
                val token = call.token()
                if (token != null) {
                    call.ctx().send(req)
                } else {
                    val account = req.account ?: throw IllegalArgumentException("account is required")
                    withContext(Dispatchers.IO) { MailSession(account).send(req) }
                    ApiStatus("sent")
                }
            }
        }

        // --- legacy inbox endpoint used by the iOS client ------------------------------------
        post("/api/inbox") {
            call.handle {
                val req = call.receive<InboxRequest>()
                withContext(Dispatchers.IO) {
                    val session = MailSession(req.account)
                    try {
                        val (_, summaries) = session.listMessages(MailSession.INBOX, req.limit.coerceIn(1, 500))
                        val full = session.fetchMessages(MailSession.INBOX, summaries.map { it.uid })
                        summaries.map { s ->
                            val raw = full[s.uid]
                            MessageDto(
                                from = if (s.fromName.isNotBlank()) "${s.fromName} <${s.fromAddress}>" else s.fromAddress,
                                to = s.to.joinToString(", "),
                                subject = s.subject,
                                bodyText = raw?.bodyText ?: "",
                                bodyHtml = raw?.bodyHtml,
                                sentDate = s.date,
                                hasAttachments = raw?.summary?.hasAttachments ?: s.hasAttachments
                            )
                        }
                    } finally {
                        session.close()
                    }
                }
            }
        }
    }
}

internal fun ApplicationCall.token(): String? =
    request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotBlank() }
        ?: request.queryParameters["token"]

/** Returned by a handler that already wrote its own response (file downloads). */
internal object Responded

internal suspend fun ApplicationCall.handle(block: suspend () -> Any?) {
    try {
        when (val result = block()) {
            Responded -> {}
            null -> respond(HttpStatusCode.NotFound, ApiError("Not found"))
            is Unit -> respond(ApiStatus("ok"))
            // A JsonObject is also a Map, and Ktor would pick a map serializer for it by runtime type.
            is JsonElement -> respondText(Json.encodeToString(JsonElement.serializer(), result), ContentType.Application.Json)
            else -> respond(result)
        }
    } catch (e: AuthException) {
        respond(HttpStatusCode.Unauthorized, ApiError(e.message ?: "Not signed in"))
    } catch (e: AuthenticationFailedException) {
        respond(HttpStatusCode.BadGateway, ApiError(friendlyAuthError(e)))
    } catch (e: MessagingException) {
        log.warn("Mail error: ${e.message}")
        respond(HttpStatusCode.BadGateway, ApiError(e.message ?: "Mail server error"))
    } catch (e: IllegalArgumentException) {
        respond(HttpStatusCode.BadRequest, ApiError(e.message ?: "Bad request"))
    } catch (e: Exception) {
        log.error("Unhandled error", e)
        respond(HttpStatusCode.InternalServerError, ApiError(e.message ?: e.javaClass.simpleName))
    }
}

private object StaticFiles {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    private val safeName = Regex("^[A-Za-z0-9._-]+$")

    /** Development aid: QUILLPOST_WEB_DIR serves the UI from a folder, re-read on every request. */
    val devDir: java.io.File? = qpEnv("QUILLPOST_WEB_DIR")?.takeIf { it.isNotBlank() }?.let { java.io.File(it) }?.takeIf { it.isDirectory }

    fun bytes(name: String): ByteArray? {
        if (!safeName.matches(name) || name.startsWith(".")) return null
        devDir?.let { d -> val f = java.io.File(d, name); return if (f.isFile) f.readBytes() else null }
        cache[name]?.let { return it }
        val loaded = StaticFiles::class.java.classLoader?.getResourceAsStream("web/$name")?.use { it.readBytes() } ?: return null
        cache[name] = loaded
        return loaded
    }

    fun contentType(name: String): ContentType = when (name.substringAfterLast('.', "").lowercase()) {
        "html" -> ContentType.Text.Html.withCharset(Charsets.UTF_8)
        "css" -> ContentType.Text.CSS.withCharset(Charsets.UTF_8)
        "js" -> ContentType.Application.JavaScript.withCharset(Charsets.UTF_8)
        "json", "webmanifest" -> ContentType.Application.Json
        "svg" -> ContentType.Image.SVG
        "png" -> ContentType.Image.PNG
        "ico" -> ContentType.parse("image/x-icon")
        "woff2" -> ContentType.parse("font/woff2")
        else -> ContentType.Application.OctetStream
    }
}

private suspend fun ApplicationCall.respondStatic(name: String) {
    val bytes = StaticFiles.bytes(name)
    if (bytes == null) { respond(HttpStatusCode.NotFound, ApiError("Not found")); return }
    response.header(HttpHeaders.CacheControl, if (name.endsWith(".html") || StaticFiles.devDir != null) "no-cache" else "max-age=300")
    respondBytes(bytes, StaticFiles.contentType(name))
}

private fun friendlyAuthError(e: Throwable): String {
    val m = e.message ?: ""
    return "Sign-in was rejected by the mail server ($m). Check the user name and password; Gmail, Yahoo, iCloud, AOL and Fastmail require an app password, and Outlook.com/Hotmail no longer accept passwords for IMAP."
}

private fun friendlyError(e: Throwable): String = when (e) {
    is AuthenticationFailedException -> friendlyAuthError(e)
    is MessagingException -> e.message ?: "mail server error"
    else -> e.message ?: e.javaClass.simpleName
}
