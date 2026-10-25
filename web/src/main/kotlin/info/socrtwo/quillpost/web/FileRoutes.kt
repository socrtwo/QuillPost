package info.socrtwo.quillpost.web

import info.socrtwo.quillpost.web.fileformats.MailAddress
import info.socrtwo.quillpost.web.fileformats.MessageFormats
import info.socrtwo.quillpost.web.fileformats.MimeWriter
import info.socrtwo.quillpost.web.fileformats.ParsedMessage
import info.socrtwo.quillpost.web.fileformats.PimFormats
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.toByteArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.time.ZoneId

// ---------------------------------------------------------------------------------------
// "Open file": any message, contact, calendar or office file dropped on or opened in Anne's Post.
// ---------------------------------------------------------------------------------------

@Serializable
data class OpenedAttachmentDto(
    val index: Int,
    val fileName: String,
    val mimeType: String,
    val size: Long,
    val inline: Boolean,
    val contentId: String? = null,
    /** What opening it would show: message, contacts, calendar, document, spreadsheet, presentation, other. */
    val kind: String = "other"
)

@Serializable
data class OpenedHeaderDto(val name: String, val value: String)

@Serializable
data class OpenedMessageDto(
    val subject: String,
    val fromName: String,
    val fromAddress: String,
    val senderName: String = "",
    val senderAddress: String = "",
    /** Addresses (for replying); the *Display lists hold "Name <address>". */
    val to: List<String>,
    val cc: List<String>,
    val bcc: List<String>,
    val toDisplay: List<String>,
    val ccDisplay: List<String>,
    val bccDisplay: List<String>,
    val replyTo: String = "",
    val date: Long? = null,
    val messageId: String = "",
    val inReplyTo: String = "",
    val references: String = "",
    val bodyText: String,
    val bodyHtml: String? = null,
    val attachments: List<OpenedAttachmentDto>,
    val importance: String = "normal",
    val messageClass: String = "IPM.Note",
    val security: String? = null,
    val newsgroups: String = "",
    val template: Boolean = false,
    val headers: List<OpenedHeaderDto> = emptyList()
)

@Serializable
data class SuiteAppDto(val id: String, val label: String, val androidPackage: String, val kind: String)

@Serializable
data class FileOpenResponse(
    val id: String,
    val name: String,
    val size: Long,
    /** message, contacts, calendar, document, spreadsheet, presentation, mailbox, other */
    val kind: String,
    val format: String,
    val label: String,
    val confidence: Double,
    val wrappedIn: String? = null,
    val message: OpenedMessageDto? = null,
    val contacts: List<ContactDto> = emptyList(),
    val events: List<EventDto> = emptyList(),
    val app: SuiteAppDto? = null,
    val notes: List<String> = emptyList(),
    /** The desktop app can hand the file to the program registered for it. */
    val nativeOpen: Boolean = false,
    /** MIME type the file is served with (for handing it to other apps). */
    val mimeType: String = "application/octet-stream",
    val error: String? = null
)

@Serializable
data class FileSaveResponse(val folder: String, val saved: Int)

/**
 * Files opened in this backend. Kept in memory only (never written to disk), per signed-in
 * account, oldest dropped first: at most [MAX_FILES] files and [MAX_BYTES] in all.
 */
object OpenedFiles {
    const val MAX_FILES = 40
    const val MAX_BYTES = 400L * 1024 * 1024
    const val MAX_FILE = 200 * 1024 * 1024

    class Entry(val id: String, val owner: String, val name: String, val bytes: ByteArray, val classified: MessageFormats.Classified, val message: ParsedMessage?, val events: List<EventDto>, val notes: List<String>)

    private val files = LinkedHashMap<String, Entry>()
    private val random = java.security.SecureRandom()

    /** Files handed over by the Android or desktop shell before or while the UI runs ("open with Anne's Post"). */
    private val pending = ArrayList<Pair<String, ByteArray>>()

    /** Called by the desktop launcher and the Android shell (same process) with a file the system asked Anne's Post to open. */
    @Synchronized fun enqueue(name: String, bytes: ByteArray) { if (bytes.size <= MAX_FILE) { pending += name to bytes; while (pending.size > 10) pending.removeAt(0) } }
    @Synchronized fun takePending(): List<Pair<String, ByteArray>> = ArrayList(pending).also { pending.clear() }

    /** The desktop launcher installs this to open a file with the operating system's registered program. */
    @Volatile var nativeOpener: ((java.io.File) -> Boolean)? = null

    /**
     * Desktop only: the secret a second launch of the app (a file double-clicked while Anne's Post
     * runs) presents to hand its files to this instance ("/api/file/handoff"). Null disables the route.
     */
    @Volatile var handoffKey: String? = null

    @Synchronized fun get(id: String, owner: String): Entry? = files[id]?.takeIf { it.owner == owner }

    fun open(owner: String, name: String, bytes: ByteArray): Entry {
        val c = MessageFormats.classify(bytes, name)
        val notes = ArrayList<String>()
        var message: ParsedMessage? = null
        var events: List<EventDto> = emptyList()
        when (c.kind) {
            "message" -> {
                message = runCatching { MessageFormats.parse(c.bytes, c.name, c.format) }.onFailure { notes += "Anne's Post could not read this ${c.label}: ${it.message ?: it.javaClass.simpleName}" }.getOrNull()
                message?.let { m ->
                    events = m.events.ifEmpty { m.calendar?.let { runCatching { info.socrtwo.quillpost.web.ICal.toEvents(it, ZoneId.systemDefault()).events }.getOrNull() } ?: emptyList() }
                }
            }
            "calendar" -> runCatching { PimFormats.events(c.bytes) }.onSuccess { events = it.events; notes += it.warnings }.onFailure { notes += "The calendar file could not be read: ${it.message}" }
        }
        if (c.wrappedIn != null) notes += "Decoded from ${c.wrappedIn}."
        if (c.format == "wab") notes += "Windows Address Book files use an undocumented format that Anne's Post cannot read. In Windows Contacts or Outlook Express, export the contacts as vCards (.vcf) or a CSV file and open that instead."
        val e = Entry(newId(), owner, c.name, c.bytes, c, message, events, notes)
        synchronized(this) {
            files[e.id] = e
            var total = files.values.sumOf { it.bytes.size.toLong() }
            val it = files.entries.iterator()
            while ((files.size > MAX_FILES || total > MAX_BYTES) && it.hasNext()) { val x = it.next(); if (x.key == e.id) continue; total -= x.value.bytes.size; it.remove() }
        }
        return e
    }

    private fun newId(): String = (1..20).map { "abcdefghijkmnpqrstuvwxyz23456789"[random.nextInt(32)] }.joinToString("")

    fun response(e: Entry): FileOpenResponse {
        val c = e.classified
        val doc = c.document
        val app = doc?.let { SuiteAppDto(it.kind.app, it.kind.appLabel, it.kind.androidPackage, it.kind.name.lowercase()) }
        val contacts = if (c.kind == "contacts") runCatching { PimFormats.contacts(c.bytes, c.format) }.getOrDefault(emptyList()) else e.message?.contacts ?: emptyList()
        val notes = e.notes + (e.message?.notes ?: emptyList()) + (if (c.kind == "contacts" && contacts.isEmpty()) listOf("No contacts were found in the file.") else emptyList())
        return FileOpenResponse(
            id = e.id, name = e.name, size = e.bytes.size.toLong(), kind = c.kind, format = c.format,
            label = c.label, confidence = c.confidence, wrappedIn = c.wrappedIn,
            message = e.message?.let { messageDto(it) }, contacts = contacts, events = e.events, app = app, notes = notes.distinct(),
            nativeOpen = nativeOpener != null && QuillPenEmbed.desktop,
            mimeType = mimeOf(e)
        )
    }

    fun mimeOf(e: Entry): String = e.classified.document?.let { info.socrtwo.quillpost.web.fileformats.MimeTypes.byName("x." + it.format) }?.takeIf { it != "application/octet-stream" }
        ?: info.socrtwo.quillpost.web.fileformats.MimeTypes.byName(e.name)

    private fun addr(a: MailAddress) = a.address.ifBlank { a.name }

    fun messageDto(m: ParsedMessage): OpenedMessageDto = OpenedMessageDto(
        subject = m.subject, fromName = m.from?.name ?: "", fromAddress = m.from?.address ?: "",
        senderName = m.sender?.name ?: "", senderAddress = m.sender?.address ?: "",
        to = m.to.map(::addr), cc = m.cc.map(::addr), bcc = m.bcc.map(::addr),
        toDisplay = m.to.map { it.display() }, ccDisplay = m.cc.map { it.display() }, bccDisplay = m.bcc.map { it.display() },
        replyTo = m.replyTo.firstOrNull()?.address ?: "", date = m.date, messageId = m.messageId, inReplyTo = m.inReplyTo, references = m.references,
        bodyText = m.bodyText, bodyHtml = m.bodyHtml,
        attachments = m.attachments.mapIndexed { i, a ->
            OpenedAttachmentDto(i, a.fileName, a.mimeType, a.size.toLong(), a.inline, a.contentId,
                if (a.embedded != null) "message" else if (a.inline) "other" else runCatching { MessageFormats.classify(a.bytes, a.fileName).kind }.getOrDefault("other"))
        },
        importance = m.importance, messageClass = m.messageClass, security = m.security, newsgroups = m.newsgroups, template = m.template,
        headers = m.headers.take(200).map { OpenedHeaderDto(it.first, it.second.take(4000)) }
    )
}

internal fun Routing.fileRoutes(sessions: Sessions) {
    fun ApplicationCall.ctx(): AccountContext = sessions.get(token())
    fun ApplicationCall.q(name: String): String? = request.queryParameters[name]?.takeIf { it.isNotBlank() }
    fun ApplicationCall.entry(): OpenedFiles.Entry {
        val ctx = ctx()
        val id = q("id") ?: throw IllegalArgumentException("id is required")
        return OpenedFiles.get(id, ctx.account.email) ?: throw IllegalArgumentException("This file is no longer open; open it again")
    }
    suspend fun ApplicationCall.sendFile(name: String, mime: String, bytes: ByteArray, inline: Boolean) {
        // Only images and PDF may display inline; everything else downloads (never rendered as HTML here).
        val safeType = if (inline && (mime.startsWith("image/") && mime != "image/svg+xml" || mime == "application/pdf")) mime else if (inline) "application/octet-stream" else mime.ifBlank { "application/octet-stream" }
        response.header(HttpHeaders.ContentDisposition, (if (inline) ContentDisposition.Inline else ContentDisposition.Attachment).withParameter(ContentDisposition.Parameters.FileName, name).toString())
        response.header("X-Content-Type-Options", "nosniff")
        response.header(HttpHeaders.CacheControl, "private, no-store")
        respondBytes(bytes, runCatching { ContentType.parse(safeType) }.getOrDefault(ContentType.Application.OctetStream))
    }

    /** Body: the file's bytes. Query: name (the file name, used only as a hint). */
    post("/api/file/open") {
        call.handle {
            val ctx = call.ctx()
            val bytes = call.receiveChannel().toByteArray()
            require(bytes.size <= OpenedFiles.MAX_FILE) { "The file is larger than 200 MB" }
            val name = call.q("name") ?: "file"
            withContext(Dispatchers.Default) { OpenedFiles.response(OpenedFiles.open(ctx.account.email, name, bytes)) }
        }
    }

    /** Opens an attachment of an opened message as a file of its own (attached messages, documents, vCards…). */
    post("/api/file/open-attachment") {
        call.handle {
            val e = call.entry()
            val i = call.q("index")?.toIntOrNull() ?: throw IllegalArgumentException("index is required")
            val a = e.message?.attachments?.getOrNull(i) ?: throw IllegalArgumentException("No such attachment")
            withContext(Dispatchers.Default) { OpenedFiles.response(OpenedFiles.open(e.owner, a.fileName, a.bytes)) }
        }
    }

    /** Desktop: a second launch hands over the files it was asked to open (local, key-protected). */
    post("/api/file/handoff") {
        val key = OpenedFiles.handoffKey
        val given = call.request.headers["X-QuillPost-Handoff"]
        if (key == null || given == null || !java.security.MessageDigest.isEqual(key.toByteArray(), given.toByteArray())) { call.respond(HttpStatusCode.NotFound, ApiError("Not found")); return@post }
        val bytes = call.receiveChannel().toByteArray()
        if (bytes.size > OpenedFiles.MAX_FILE) { call.respond(HttpStatusCode.PayloadTooLarge, ApiError("The file is larger than 200 MB")); return@post }
        OpenedFiles.enqueue(call.q("name") ?: "file", bytes)
        call.respond(ApiStatus("queued"))
    }

    /** Files the desktop or Android app was asked to open ("Open with Anne's Post"), opened for this account. */
    get("/api/file/pending") {
        call.handle {
            val ctx = call.ctx()
            OpenedFiles.takePending().map { (n, b) -> withContext(Dispatchers.Default) { OpenedFiles.response(OpenedFiles.open(ctx.account.email, n, b)) } }
        }
    }

    get("/api/file/attachment") {
        val e = try { call.entry() } catch (x: AuthException) { call.respond(HttpStatusCode.Unauthorized, ApiError(x.message ?: "")); return@get } catch (x: IllegalArgumentException) { call.respond(HttpStatusCode.NotFound, ApiError(x.message ?: "")); return@get }
        val atts = e.message?.attachments ?: emptyList()
        val cid = call.q("cid")
        val a = call.q("index")?.toIntOrNull()?.let { atts.getOrNull(it) } ?: cid?.let { c -> atts.firstOrNull { it.contentId.equals(c, true) } }
        if (a == null) { call.respond(HttpStatusCode.NotFound, ApiError("Attachment not found")); return@get }
        call.sendFile(a.fileName, a.mimeType, a.bytes, call.q("inline") == "true" || cid != null)
    }

    /** The file itself (download, "Send as attachment", open with another program). */
    get("/api/file/raw") {
        val e = try { call.entry() } catch (x: AuthException) { call.respond(HttpStatusCode.Unauthorized, ApiError(x.message ?: "")); return@get } catch (x: IllegalArgumentException) { call.respond(HttpStatusCode.NotFound, ApiError(x.message ?: "")); return@get }
        call.sendFile(e.name, OpenedFiles.mimeOf(e), e.bytes, false)
    }

    /** An opened message as an RFC 822 .eml file (Outlook items and winmail.dat converted). */
    get("/api/file/eml") {
        val e = try { call.entry() } catch (x: AuthException) { call.respond(HttpStatusCode.Unauthorized, ApiError(x.message ?: "")); return@get } catch (x: IllegalArgumentException) { call.respond(HttpStatusCode.NotFound, ApiError(x.message ?: "")); return@get }
        val m = e.message ?: run { call.respond(HttpStatusCode.BadRequest, ApiError("Not a message")); return@get }
        call.sendFile(MimeWriter.safeFileName(m.subject.ifBlank { e.name.substringBeforeLast('.') }) + ".eml", "message/rfc822", MimeWriter.toRfc822(m), false)
    }

    /** Saves an opened message to a mail folder (converted to MIME when it is not MIME already). */
    post("/api/file/save") {
        call.handle {
            val e = call.entry()
            val ctx = call.ctx()
            val folder = call.q("folder") ?: MailSession.INBOX
            val m = e.message ?: throw IllegalArgumentException("Only messages can be saved to a mail folder")
            val r = ctx.importMessages(folder, MimeWriter.toRfc822(m))
            if (r.imported == 0) throw IllegalArgumentException(r.errors.firstOrNull() ?: "The server did not accept the message")
            FileSaveResponse(folder, r.imported)
        }
    }

    /** Adds an opened file's contacts to People or its events to a calendar. */
    post("/api/file/import") {
        call.handle {
            val e = call.entry()
            val ctx = call.ctx()
            ctx.touch()
            val r = OpenedFiles.response(e)
            val result = when {
                r.contacts.isNotEmpty() -> ctx.pim.importContacts(r.contacts)
                r.events.isNotEmpty() -> {
                    val cal = ctx.pim.calendar(call.q("calendarId") ?: PimStore.DEFAULT_CALENDAR) ?: throw IllegalArgumentException("Unknown calendar")
                    require(!cal.readOnly) { "\"${cal.name}\" is read-only" }
                    ctx.pim.importEvents(cal.id, r.events.filter { !it.cancelled })
                }
                else -> throw IllegalArgumentException("This file has no contacts or events to add")
            }
            ctx.bump()
            result
        }
    }

    /** Desktop app: opens the file with the program the operating system has registered for it. */
    post("/api/file/native") {
        call.handle {
            val e = call.entry()
            val opener = OpenedFiles.nativeOpener ?: throw IllegalArgumentException("Opening files with other programs is only available in the desktop app")
            val dir = java.io.File(System.getProperty("java.io.tmpdir"), "QuillPost-open").apply { mkdirs() }
            val f = java.io.File(dir, MimeWriter.safeFileName(e.name).ifBlank { "file" })
            withContext(Dispatchers.IO) { f.writeBytes(e.bytes); f.deleteOnExit() }
            if (!withContext(Dispatchers.IO) { opener(f) }) throw IllegalArgumentException("No program is registered to open ${e.classified.label} files")
            ApiStatus("opened")
        }
    }
}
