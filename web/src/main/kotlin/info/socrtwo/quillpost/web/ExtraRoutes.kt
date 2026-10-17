package info.socrtwo.quillpost.web

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.utils.io.toByteArray
import kotlinx.serialization.json.JsonObject

/**
 * Routes for calendar, contacts, tasks, message metadata, folders, advanced search,
 * conversations, snooze, scheduled send, receipts and import/export.
 */
internal fun Routing.extraRoutes(sessions: Sessions) {
    fun ApplicationCall.ctx(): AccountContext = sessions.get(token())
    fun ApplicationCall.q(name: String): String? = request.queryParameters[name]?.takeIf { it.isNotBlank() }

    suspend fun ApplicationCall.download(name: String, type: ContentType, bytes: ByteArray) {
        response.header(HttpHeaders.ContentDisposition, ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, name).toString())
        respondBytes(bytes, type)
    }

    fun safeFileName(s: String) = s.replace(Regex("[\\\\/:*?\"<>|\\r\\n]+"), "_").trim().take(80).ifBlank { "export" }

    // --- calendar, contacts, tasks ------------------------------------------------------------
    get("/api/pim") { call.handle { call.ctx().also { it.touch() }.pim.data() } }

    put("/api/pim/{kind}") {
        call.handle {
            val kind = call.parameters["kind"] ?: ""
            require(kind in PimStore.KINDS) { "Unknown item kind: $kind" }
            val ctx = call.ctx()
            ctx.touch()
            val saved = ctx.pim.upsert(kind, call.receive<JsonObject>())
            ctx.bump()
            saved
        }
    }

    delete("/api/pim/{kind}/{id}") {
        call.handle {
            val ctx = call.ctx()
            val ok = ctx.pim.remove(call.parameters["kind"] ?: "", call.parameters["id"] ?: "")
            ctx.bump()
            ApiStatus(if (ok) "deleted" else "missing")
        }
    }

    post("/api/pim/import") {
        call.handle {
            val ctx = call.ctx()
            val text = call.receiveText()
            val result = when (val kind = call.q("kind") ?: "ics") {
                "ics" -> ctx.importIcs(text, call.q("calendarId"), call.q("tz") ?: "")
                "vcf", "csv" -> ctx.importContacts(text, kind)
                else -> throw IllegalArgumentException("Unknown import type $kind")
            }
            ctx.bump()
            result
        }
    }

    get("/api/pim/export") {
        call.handle {
            val ctx = call.ctx()
            when (val kind = call.q("kind") ?: "ics") {
                "ics" -> {
                    val calId = call.q("calendarId")
                    val name = calId?.let { ctx.pim.calendar(it)?.name } ?: "Calendar"
                    call.download(safeFileName(name) + ".ics", ContentType.parse("text/calendar").withCharset(Charsets.UTF_8), ctx.exportIcs(calId).toByteArray())
                }
                "vcf" -> call.download("Contacts.vcf", ContentType.parse("text/vcard").withCharset(Charsets.UTF_8), ctx.exportContacts("vcf").toByteArray())
                "csv" -> call.download("Contacts.csv", ContentType.Text.CSV.withCharset(Charsets.UTF_8), ("﻿" + ctx.exportContacts("csv")).toByteArray())
                else -> throw IllegalArgumentException("Unknown export type $kind")
            }
            Responded
        }
    }

    post("/api/calendars/subscribe") { call.handle { call.ctx().subscribe(call.receive()) } }
    post("/api/calendars/{id}/refresh") { call.handle { call.ctx().refreshSubscription(call.parameters["id"] ?: "") } }

    post("/api/invite/send") { call.handle { call.ctx().sendInvite(call.receive()) } }
    post("/api/invite/respondEvent") { call.handle { call.ctx().respondEvent(call.receive()) ?: ApiStatus("ok") } }
    post("/api/translate") { call.handle { call.ctx().translate(call.receive()) } }
    get("/api/attachments/zip") {
        call.handle {
            val ctx = call.ctx()
            val folder = call.q("folder") ?: MailSession.INBOX
            val uid = call.q("uid")?.toLongOrNull() ?: throw IllegalArgumentException("uid is required")
            call.response.header(HttpHeaders.ContentDisposition, ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "attachments.zip").toString())
            call.respondOutputStream(ContentType.Application.Zip) { ctx.attachmentsZip(folder, uid, this) }
            Responded
        }
    }
    post("/api/invite/respond") { call.handle { call.ctx().respondInvite(call.receive()) ?: ApiStatus("ok") } }

    // --- message metadata, snooze, ignore ---------------------------------------------------------
    get("/api/meta") { call.handle { call.ctx().pim.allMeta() } }
    post("/api/meta") { call.handle { call.ctx().updateMeta(call.receive()) } }
    post("/api/snooze") { call.handle { call.ctx().snooze(call.receive()) } }
    post("/api/ignore") { call.handle { call.ctx().ignore(call.receive()) } }

    // --- scheduled send ----------------------------------------------------------------------------
    get("/api/outbox") { call.handle { call.ctx().outbox() } }
    delete("/api/outbox/{id}") {
        call.handle {
            call.ctx().cancelScheduled(call.parameters["id"] ?: "", call.q("drafts") == "true") ?: ApiStatus("canceled")
        }
    }

    // --- folders -------------------------------------------------------------------------------------
    post("/api/folders/create") { call.handle { call.ctx().createFolder(call.receive()) } }
    post("/api/folders/rename") { call.handle { call.ctx().renameFolder(call.receive()) } }
    post("/api/folders/move") { call.handle { call.ctx().moveFolder(call.receive()) } }
    post("/api/folders/delete") { call.handle { call.ctx().deleteFolder(call.receive()) } }
    post("/api/folders/empty") { call.handle { call.ctx().emptyFolder(call.receive()) } }
    post("/api/folders/markread") { call.handle { call.ctx().markAllRead(call.receive()) } }
    post("/api/copy") {
        call.handle {
            val ctx = call.ctx()
            val req = call.receive<MoveRequest>()
            var n = 0
            for ((f, refs) in req.items.groupBy { it.folder }) n += ctx.io { ctx.mail.copy(f, refs.map { it.uid }, req.to) }
            ctx.bump()
            BatchResult(n, req.items.size - n)
        }
    }

    // --- search & conversations ----------------------------------------------------------------------
    get("/api/search/advanced") {
        call.handle {
            fun bool(n: String) = call.q(n) == "true"
            val c = SearchCriteria(
                text = call.q("text") ?: "", from = call.q("from") ?: "", to = call.q("to") ?: "",
                subject = call.q("subject") ?: "", body = call.q("body") ?: "",
                hasAttachments = bool("hasAttachments"), unread = bool("unread"), flagged = bool("flagged"),
                since = call.q("since")?.toLongOrNull(), before = call.q("before")?.toLongOrNull()
            )
            require(!c.isEmpty()) { "Enter something to search for" }
            val limit = (call.q("limit")?.toIntOrNull() ?: 200).coerceIn(1, 500)
            call.ctx().searchAdvanced(call.q("folder") ?: MailSession.INBOX, c, call.q("scope") == "all", limit)
        }
    }

    get("/api/thread") {
        call.handle {
            val uid = call.q("uid")?.toLongOrNull() ?: throw IllegalArgumentException("uid is required")
            call.ctx().thread(call.q("folder") ?: MailSession.INBOX, uid)
        }
    }

    // --- receipts, quota -------------------------------------------------------------------------------
    post("/api/mdn") { call.handle { call.ctx().sendReadReceipt(call.receive()) } }
    post("/api/mdn/decline") { call.handle { call.ctx().declineReadReceipt(call.receive()) } }
    get("/api/quota") { call.handle { call.ctx().quota() ?: QuotaDto(-1, -1) } }

    // --- raw source, import & export of messages ---------------------------------------------------------
    get("/api/raw") {
        call.handle {
            val folder = call.q("folder") ?: MailSession.INBOX
            val uid = call.q("uid")?.toLongOrNull() ?: throw IllegalArgumentException("uid is required")
            val bytes = call.ctx().rawMessage(folder, uid) ?: return@handle null
            if (call.q("download") == "true") {
                val subject = Regex("(?im)^Subject:[ \\t]*(.*)$").find(String(bytes.take(16384).toByteArray(), Charsets.ISO_8859_1))?.groupValues?.get(1)?.trim().orEmpty()
                val name = runCatching { jakarta.mail.internet.MimeUtility.decodeText(subject) }.getOrDefault(subject)
                call.download(safeFileName(name.ifBlank { "message" }) + ".eml", ContentType.parse("message/rfc822"), bytes)
            } else {
                call.respondBytes(bytes, ContentType.Text.Plain.withCharset(Charsets.UTF_8))
            }
            Responded
        }
    }

    get("/api/export/mbox") {
        call.handle {
            val ctx = call.ctx()
            val folder = call.q("folder") ?: MailSession.INBOX
            call.response.header(HttpHeaders.ContentDisposition, ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, safeFileName(folder.substringAfterLast('/')) + ".mbox").toString())
            call.respondOutputStream(ContentType.parse("application/mbox")) { ctx.exportMbox(folder, this) }
            Responded
        }
    }

    post("/api/import/messages") {
        call.handle {
            val folder = call.q("folder") ?: MailSession.INBOX
            val bytes = call.receiveChannel().toByteArray()
            require(bytes.size <= 200 * 1024 * 1024) { "The file is larger than 200 MB; split it first" }
            call.ctx().importMessages(folder, bytes)
        }
    }
}
