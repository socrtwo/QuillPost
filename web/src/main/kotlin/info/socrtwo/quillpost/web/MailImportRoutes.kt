package info.socrtwo.quillpost.web

import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveStream
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import info.socrtwo.quillpost.web.mailstore.Sniffer
import kotlinx.serialization.Serializable

@Serializable
data class UploadResponse(val upload: String)

@Serializable
data class ArchivesResponse(val archives: List<ArchiveMeta>, val folders: List<FolderDto>)

/**
 * Opening and importing mail files (MailImport.kt) and the Local Archives store (LocalArchives.kt):
 *
 *  POST   /api/mailbox/upload?upload=&path=   one file (raw body); repeat with the returned id for folder uploads
 *  POST   /api/mailbox/sniff?name=&size=      is this (first 64 KB of a file) a mail store?
 *  POST   /api/mailbox/analyze?upload=        detect the format; single messages are opened, mailboxes listed
 *  DELETE /api/mailbox/upload?upload=         discard an upload
 *  POST   /api/mailbox/import                 {ImportRequest} → job
 *  GET    /api/mailbox/job?id=                progress and report
 *  POST   /api/mailbox/job/cancel?id=
 *  GET    /api/archives                       Local Archives and their folders
 *  DELETE /api/archives?id=                   remove an archive
 */
internal fun Routing.mailImportRoutes(sessions: Sessions) {
    fun ApplicationCall.ctx(): AccountContext = sessions.get(token()).also { it.touch() }
    fun ApplicationCall.q(name: String): String? = request.queryParameters[name]?.takeIf { it.isNotBlank() }

    post("/api/mailbox/upload") {
        call.handle {
            val ctx = call.ctx()
            val path = call.q("path") ?: "upload.bin"
            val stream = call.receiveStream()
            UploadResponse(withContext(Dispatchers.IO) { ctx.imports.receive(call.q("upload"), path, stream) })
        }
    }
    post("/api/mailbox/analyze") {
        call.handle {
            val ctx = call.ctx()
            val id = call.q("upload") ?: throw IllegalArgumentException("upload is required")
            withContext(Dispatchers.IO) {
                try { ctx.imports.analyze(id, openMessages = call.q("open") != "false") }
                catch (e: info.socrtwo.quillpost.web.mailstore.MailStoreException) { throw IllegalArgumentException(e.message) }
            }
        }
    }
    post("/api/mailbox/sniff") {
        call.handle {
            call.ctx()
            // The first bytes of a file the browser is about to open: is it a mail store? (mailimport.js)
            val head = call.receiveStream().use { it.readNBytes(65536) }
            Sniffer.sniff(head, call.q("name") ?: "file", call.q("size")?.toLongOrNull() ?: head.size.toLong())
        }
    }
    delete("/api/mailbox/upload") { call.handle { call.q("upload")?.let { call.ctx().imports.discard(it) }; Unit } }
    post("/api/mailbox/import") { call.handle { call.ctx().imports.start(call.receive()) } }
    get("/api/mailbox/job") { call.handle { call.ctx().imports.job(call.q("id") ?: throw IllegalArgumentException("id is required")) } }
    post("/api/mailbox/job/cancel") { call.handle { call.ctx().imports.cancel(call.q("id") ?: throw IllegalArgumentException("id is required")) } }
    get("/api/archives") { call.handle { val ctx = call.ctx(); withContext(Dispatchers.IO) { ArchivesResponse(ctx.archives.list(), ctx.archives.folders()) } } }
    delete("/api/archives") {
        call.handle {
            val ctx = call.ctx()
            val id = call.q("id") ?: throw IllegalArgumentException("id is required")
            withContext(Dispatchers.IO) { ctx.archives.delete(id) }
            ctx.bump()
            Unit
        }
    }
}

/** A message from Local Archives, shaped like a server message (no junk analysis: archives are history). */
internal suspend fun AccountContext.archiveMessage(folder: String, uid: Long, tz: String): MessageDetailDto? = withContext(Dispatchers.IO) {
    val m = archives.mime(folder, uid) ?: return@withContext null
    val raw = mail.localMessage(m, uid)
    val s = raw.summary
    val interesting = listOf("Message-ID", "Return-Path", "Reply-To", "X-Mailer", "Authentication-Results", "List-Unsubscribe", "X-Originating-IP", "Received")
    val bcc = runCatching { m.getRecipients(jakarta.mail.Message.RecipientType.BCC)?.map { (it as? jakarta.mail.internet.InternetAddress)?.address ?: it.toString() } }.getOrNull().orEmpty()
    MessageDetailDto(
        uid = s.uid, folder = folder, messageId = s.messageId, fromName = s.fromName, fromAddress = s.fromAddress,
        to = s.to, cc = s.cc, replyTo = s.replyTo, subject = s.subject, date = s.date, read = s.seen, flagged = s.flagged,
        answered = s.answered, bodyText = raw.bodyText, bodyHtml = raw.bodyHtml, attachments = raw.attachments,
        inReplyTo = raw.inReplyTo, references = raw.references, listUnsubscribe = s.headers["List-Unsubscribe"]?.firstOrNull(),
        headers = interesting.mapNotNull { h -> s.headers[h]?.let { h to it.joinToString("\n") } }.toMap(),
        importance = Mapping.importanceOf(s.headers), forwarded = s.forwarded,
        invite = raw.calendar?.let { runCatching { inviteFrom(it, tz) }.getOrNull() },
        categories = s.keywords.filter { !it.startsWith("$") && !it.startsWith("\\") }, bcc = bcc
    )
}

internal fun archiveReadOnly(n: Int) = BatchResult(0, n, listOf("Local Archives are read-only: copy or forward the message instead of moving or deleting it."))

/** Exports a Local Archives folder as mbox (the stored file is already mboxrd). */
fun LocalArchives.exportMbox(folder: String, out: java.io.OutputStream): Int {
    val r = ref(folder)
    var n = 0
    for (e in entries(r).sortedBy { it.uid }) {
        val raw = raw(folder, e.uid) ?: continue
        info.socrtwo.quillpost.web.mailstore.Mbox.writeEntry(out, raw, e.internalDate ?: e.date.takeIf { it > 0 }, e.fromAddress)
        n++
    }
    out.flush()
    return n
}
