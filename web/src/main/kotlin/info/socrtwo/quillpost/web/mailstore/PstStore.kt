package info.socrtwo.quillpost.web.mailstore

import jakarta.activation.DataHandler
import jakarta.mail.Message
import jakarta.mail.Part
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.InternetHeaders
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.internet.MimeUtility
import jakarta.mail.util.ByteArrayDataSource
import java.io.ByteArrayInputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The messaging layer of Outlook .pst/.ost/.pab files ([MS-PST] section 2.4, property tags from
 * [MS-OXPROPS]): the folder tree, mail with its original Internet headers (or headers rebuilt from
 * the recipients and sender), plain/HTML/RTF bodies (compressed RTF expanded and encapsulated HTML
 * recovered), attachments including attached Outlook items, read/replied/forwarded/flag status and
 * categories; contacts, appointments and tasks become vCard / iCalendar items.
 */
class PstStore private constructor(private val pst: PstFile) {

    companion object {
        const val PS_PUBLIC_STRINGS = "00020329-0000-0000-c000-000000000046"
        const val PS_MAPI = "00020328-0000-0000-c000-000000000046"
        const val PSETID_ADDRESS = "00062004-0000-0000-c000-000000000046"
        const val PSETID_APPOINTMENT = "00062002-0000-0000-c000-000000000046"
        const val PSETID_TASK = "00062003-0000-0000-c000-000000000046"
        const val PSETID_COMMON = "00062008-0000-0000-c000-000000000046"

        /** Opens a .pst/.ost/.pab file as a mail store. */
        fun open(file: File, displayName: String): MailStore {
            val pst = PstFile(FileBytes(file))
            return try {
                PstStore(pst).build(displayName)
            } catch (e: Exception) {
                pst.close(); throw e
            }
        }

        private val DROP_HEADERS = setOf("content-type", "content-transfer-encoding", "mime-version", "content-disposition", "content-id", "content-description")
    }

    private val named: Map<String, Int> by lazy { readNameMap() }

    private fun readNameMap(): Map<String, Int> {
        val node = pst.nodes[PstFile.NID_NAME_TO_ID_MAP] ?: return emptyMap()
        val p = runCatching { PstProps.of(pst, node.bidData, node.bidSub) }.getOrNull() ?: return emptyMap()
        val guids = p.binary(0x0002) ?: ByteArray(0)
        val entries = p.binary(0x0003) ?: return emptyMap()
        val strings = p.binary(0x0004) ?: ByteArray(0)
        val out = HashMap<String, Int>()
        for (o in 0 until entries.size - 7 step 8) {
            val idOrOffset = entries.i32(o)
            val w = entries.u16(o + 4)
            val isString = w and 1 != 0
            val gi = w ushr 1
            val propId = 0x8000 + entries.u16(o + 6)
            val guid = when (gi) {
                1 -> PS_MAPI
                2 -> PS_PUBLIC_STRINGS
                else -> { val g = (gi - 3) * 16; if (g >= 0 && g + 16 <= guids.size) guidString(guids, g) else continue }
            }
            if (isString) {
                if (idOrOffset < 0 || idOrOffset + 4 > strings.size) continue
                val len = strings.i32(idOrOffset)
                if (len < 0 || idOrOffset + 4 + len > strings.size) continue
                out["$guid/" + String(strings, idOrOffset + 4, len, Charsets.UTF_16LE).lowercase()] = propId
            } else out["$guid/#$idOrOffset"] = propId
        }
        return out
    }

    private fun guidString(b: ByteArray, o: Int): String =
        "%08x-%04x-%04x-%02x%02x-%02x%02x%02x%02x%02x%02x".format(b.u32(o), b.u16(o + 4), b.u16(o + 6), b.u8(o + 8), b.u8(o + 9), b.u8(o + 10), b.u8(o + 11), b.u8(o + 12), b.u8(o + 13), b.u8(o + 14), b.u8(o + 15))

    private fun lid(guid: String, lid: Int): Int? = named["$guid/#$lid"]
    private fun name(guid: String, n: String): Int? = named["$guid/${n.lowercase()}"]

    private fun props(node: PstFile.Node): PstProps = PstProps.of(pst, node.bidData, node.bidSub)

    // ---------------------------------------------------------------------------------- folders

    private fun build(displayName: String): MailStore {
        val nodes = pst.nodes
        val storeProps = nodes[PstFile.NID_MESSAGE_STORE]?.let { runCatching { props(it) }.getOrNull() }
        val storeName = storeProps?.string(0x3001)?.takeIf { it.isNotBlank() }
        val label = when (pst.kind) { "ost" -> "Outlook offline folders (OST)"; "pab" -> "Outlook personal address book (PAB)"; else -> "Outlook data file (PST)" }
        val det = Detection("mailbox", pst.kind, "$label, ${pst.variant}", "Microsoft Outlook", pst.variant)
        val root = StoreFolder(storeName ?: displayName)
        val store = MailStore(det, root, { pst.close() })
        val folders = nodes.values.filter { PstFile.nidType(it.nid) == 2 }
        val childFolders = folders.filter { it.parent != it.nid }.groupBy { it.parent }
        val messages = nodes.values.filter { PstFile.nidType(it.nid) == 4 }.sortedBy { it.nid }.groupBy { it.parent }
        val ipm = storeProps?.binary(0x35E0)?.takeIf { it.size >= 24 }?.let { it.i32(it.size - 4) }?.takeIf { nodes.containsKey(it) }
        // Exchange .ost files keep mail under "Root - Mailbox/IPM_SUBTREE" (public folders sit beside it).
        val ipmByName = if (ipm == null) folders.filter { f ->
            val n = runCatching { props(f).string(0x3001) }.getOrNull()
            n.equals("IPM_SUBTREE", true) && nodes[f.parent]?.let { p -> runCatching { props(p).string(0x3001) }.getOrNull()?.contains("Mailbox", true) } == true
        }.firstOrNull()?.nid else null
        val start = ipm ?: ipmByName ?: PstFile.NID_ROOT_FOLDER
        val reached = HashSet<Int>()
        fun add(node: PstFile.Node, into: StoreFolder, depth: Int) {
            if (depth > 40 || !reached.add(node.nid)) return
            val fp = runCatching { props(node) }.getOrNull()
            val name = fp?.string(0x3001)?.takeIf { it.isNotBlank() } ?: "Folder ${node.nid}"
            val cls = fp?.string(0x3613).orEmpty()
            val kind = kindOf(cls)
            val msgs = messages[node.nid].orEmpty()
            val folder = folderFor(name, kind, msgs, store)
            folder.unread = fp?.int(0x3603) ?: -1
            into.add(folder)
            for (c in childFolders[node.nid].orEmpty().sortedBy { it.nid }) add(c, folder, depth + 1)
        }
        val startNode = nodes[start]
        if (startNode != null) {
            reached += start
            messages[start]?.takeIf { it.isNotEmpty() }?.let { root.add(folderFor(storeName ?: displayName, FolderKind.MAIL, it, store)) }
            for (c in childFolders[start].orEmpty().sortedBy { it.nid }) add(c, root, 1)
        }
        if (pst.kind == "pab" || root.children.isEmpty()) {
            // Address books keep their contacts outside an IPM subtree; take every folder that has items.
            for (f in folders.sortedBy { it.nid }) if (f.nid !in reached && messages[f.nid].orEmpty().isNotEmpty()) add(f, root, 1)
        }
        // Messages whose folder is missing from the tree (damaged hierarchy) are not lost.
        val orphans = messages.filterKeys { it !in reached && nodes[it]?.let { n -> PstFile.nidType(n.nid) == 2 && n.nid !in reached } != false }
        val orphanMsgs = orphans.values.flatten().filter { m -> nodes[m.parent] == null }
        if (orphanMsgs.isNotEmpty()) {
            root.add(folderFor("Recovered items", FolderKind.MAIL, orphanMsgs, store))
            store.warnings += "${orphanMsgs.size} messages whose folder is missing were put in \"Recovered items\"."
        }
        return store
    }

    private fun kindOf(cls: String): FolderKind = when {
        cls.startsWith("IPF.Contact", true) -> FolderKind.CONTACTS
        cls.startsWith("IPF.Appointment", true) -> FolderKind.CALENDAR
        cls.startsWith("IPF.Task", true) -> FolderKind.TASKS
        cls.startsWith("IPF.StickyNote", true) -> FolderKind.NOTES
        cls.startsWith("IPF.Journal", true) -> FolderKind.JOURNAL
        else -> FolderKind.MAIL
    }

    private fun folderFor(name: String, kind: FolderKind, msgs: List<PstFile.Node>, store: MailStore): StoreFolder = when (kind) {
        FolderKind.CONTACTS, FolderKind.CALENDAR, FolderKind.TASKS -> StoreFolder(name, kind, counter = { msgs.size }, pimLister = {
            msgs.mapNotNull { n ->
                runCatching { pimItem(props(n), kind) }.onFailure { store.warnings += "$name: an item could not be read (${it.message})" }.getOrNull()
            }
        })
        else -> StoreFolder(name, kind, counter = { msgs.size }, lister = {
            msgs.asSequence().mapNotNull { n ->
                runCatching { storeMessage(n) }.onFailure { store.warnings += "$name: a message could not be read (${it.message})" }.getOrNull()
            }
        })
    }

    // --------------------------------------------------------------------------------- messages

    private fun storeMessage(node: PstFile.Node): StoreMessage {
        val p = props(node)
        setCodepage(p)
        val mflags = p.int(0x0E07) ?: 0
        val verb = p.int(0x1081) ?: 0
        val flagStatus = p.int(0x1090) ?: 0
        val flags = MsgFlags(
            seen = mflags and 0x01 != 0, draft = mflags and 0x08 != 0, flagged = flagStatus == 2,
            answered = verb == 102 || verb == 103, forwarded = verb == 104
        )
        val kw = ArrayList<String>(categories(p))
        if (flagStatus == 1) kw += "\$FlagComplete"
        val date = p.time(0x0E06) ?: p.time(0x0039) ?: p.time(0x3007)
        return StoreMessage(flags, date, kw) { mime(PstProps.of(pst, node.bidData, node.bidSub), 0).bytes() }
    }

    private fun setCodepage(p: PstProps) {
        p.codepage = Rtf.charsetForCodepage((p.int(0x3FFD) ?: p.int(0x3FDE))?.takeIf { it > 0 } ?: 1252)
    }

    private fun categories(p: PstProps): List<String> = name(PS_PUBLIC_STRINGS, "Keywords")?.let { p.strings(it) }.orEmpty().filter { it.isNotBlank() }

    private fun addr(name: String?, email: String?): InternetAddress? {
        val e = email?.trim().orEmpty()
        val n = name?.trim().orEmpty()
        if (e.isEmpty() && n.isEmpty()) return null
        return runCatching { InternetAddress(if (e.contains('@')) e else "", n.ifBlank { null }, "UTF-8") }.getOrNull()
    }

    private fun senderOf(p: PstProps): InternetAddress? {
        val repSmtp = p.string(0x5D02) ?: p.string(0x0065)?.takeIf { p.string(0x0064)?.equals("SMTP", true) == true || it.contains('@') }
        val sndSmtp = p.string(0x5D01) ?: p.string(0x0C1F)?.takeIf { p.string(0x0C1E)?.equals("SMTP", true) == true || it.contains('@') }
        return addr(p.string(0x0042) ?: p.string(0x0C1A), repSmtp ?: sndSmtp)
    }

    private data class Recipient(val type: Int, val name: String, val email: String)

    private fun recipients(p: PstProps): List<Recipient> {
        val sub = p.subnode(PstFile.NID_RECIPIENT_TABLE) ?: return emptyList()
        return runCatching {
            val heap = PstHeap(pst.nodeData(sub.bidData))
            val t = PstTable(pst, heap, pst.subnodes(sub.bidSub))
            t.rows.map { r ->
                val v = t.row(r)
                val type = (v[0x0C15] as? Int) ?: 1
                val addrType = v[0x3002] as? String
                val email = (v[0x39FE] as? String)?.takeIf { it.contains('@') }
                    ?: (v[0x3003] as? String)?.takeIf { addrType.equals("SMTP", true) || it.contains('@') } ?: ""
                Recipient(type, (v[0x3001] as? String).orEmpty(), email)
            }
        }.getOrDefault(emptyList())
    }

    /** Builds the RFC 5322 form of a message (also used for attached Outlook items). */
    private fun mime(p: PstProps, depth: Int): ImportedMimeMessage {
        setCodepage(p)
        val msg = ImportedMimeMessage(localSession)
        val transport = p.string(0x007D)?.takeIf { it.contains(':') }
        if (transport != null) {
            val ih = InternetHeaders(ByteArrayInputStream((transport.trimEnd() + "\r\n\r\n").toByteArray(Charsets.UTF_8)))
            val e = ih.allHeaders
            while (e.hasMoreElements()) {
                val h = e.nextElement()
                if (h.name.lowercase() in DROP_HEADERS) continue
                msg.addHeader(h.name, h.value)
            }
        }
        val recips = recipients(p)
        fun list(type: Int) = recips.filter { it.type == type }.mapNotNull { addr(it.name, it.email) }.toTypedArray()
        if (msg.getHeader("From") == null) senderOf(p)?.let { msg.setFrom(it) }
        if (msg.getHeader("To") == null) list(1).takeIf { it.isNotEmpty() }?.let { msg.setRecipients(Message.RecipientType.TO, it) }
        if (msg.getHeader("Cc") == null) list(2).takeIf { it.isNotEmpty() }?.let { msg.setRecipients(Message.RecipientType.CC, it) }
        if (msg.getHeader("Bcc") == null) list(3).takeIf { it.isNotEmpty() }?.let { msg.setRecipients(Message.RecipientType.BCC, it) }
        if (msg.getHeader("Subject") == null) (p.string(0x0037) ?: p.string(0x0070))?.let { msg.setSubject(stripSubjectPrefix(it), "UTF-8") }
        if (msg.getHeader("Date") == null) (p.time(0x0039) ?: p.time(0x0E06) ?: p.time(0x3007))?.let { msg.sentDate = Date(it) }
        if (msg.getHeader("Message-ID") == null) p.string(0x1035)?.takeIf { it.isNotBlank() }?.let { msg.setHeader("Message-ID", it) }
        if (msg.getHeader("In-Reply-To") == null) p.string(0x1042)?.takeIf { it.isNotBlank() }?.let { msg.setHeader("In-Reply-To", it) }
        if (msg.getHeader("References") == null) p.string(0x1039)?.takeIf { it.isNotBlank() }?.let { msg.setHeader("References", it) }
        if (msg.getHeader("Importance") == null) when (p.int(0x0017)) { 0 -> msg.setHeader("Importance", "Low"); 2 -> msg.setHeader("Importance", "High") }
        if (msg.getHeader("Sensitivity") == null) when (p.int(0x0036)) { 1 -> msg.setHeader("Sensitivity", "Personal"); 2 -> msg.setHeader("Sensitivity", "Private"); 3 -> msg.setHeader("Sensitivity", "Company-Confidential") }
        val cats = categories(p)
        if (cats.isNotEmpty() && msg.getHeader("Keywords") == null) msg.setHeader("Keywords", MimeUtility.fold(10, MimeUtility.encodeText(cats.joinToString(", "), "UTF-8", null)))
        p.string(0x001A)?.takeIf { !it.equals("IPM.Note", true) }?.let { msg.setHeader("X-Outlook-Message-Class", it) }

        // Bodies: HTML (PidTagHtml), else RTF (encapsulated HTML/text, or real RTF converted), plus plain text.
        var html: String? = p.binary(0x1013)?.let { decodeHtml(it, p) } ?: p.string(0x1013)
        var text: String? = p.string(0x1000)
        if (html == null) {
            val rtfBytes = p.binary(0x1009)?.let { runCatching { Rtf.decompress(it) }.getOrNull() }
            if (rtfBytes != null) {
                val rtf = String(rtfBytes, Charsets.ISO_8859_1)
                val enc = runCatching { Rtf.deencapsulate(rtf) }.getOrNull()
                when {
                    enc != null && enc.html -> html = enc.content
                    enc != null -> if (text.isNullOrBlank()) text = enc.content
                    else -> html = runCatching { "<div>" + Rtf.toHtml(rtf) + "</div>" }.getOrNull()
                }
                if (text.isNullOrBlank()) text = runCatching { Rtf.toText(rtf) }.getOrNull()
            }
        }
        if (text.isNullOrBlank() && html != null) text = Rtf.htmlToText(html)

        // Attachments.
        val inline = ArrayList<MimeBodyPart>()
        val attached = ArrayList<MimeBodyPart>()
        for (sub in p.subs.values.filter { PstFile.nidType(it.nid) == 5 }.sortedBy { it.nid }) {
            val ap = runCatching { PstProps.of(pst, sub.bidData, sub.bidSub) }.getOrNull() ?: continue
            setCodepage(ap)
            val part = runCatching { attachmentPart(ap, depth) }.getOrNull() ?: continue
            val cid = ap.string(0x3712)?.trim('<', '>', ' ')?.takeIf { it.isNotBlank() }
            if (cid != null && html != null && (html.contains("cid:$cid", true) || ap.bool(0x7FFE) == true)) {
                part.setHeader("Content-ID", "<$cid>")
                part.disposition = Part.INLINE
                inline += part
            } else attached += part
        }

        val alt = MimeMultipart("alternative")
        alt.addBodyPart(MimeBodyPart().apply { setText(text.orEmpty(), "UTF-8") })
        if (html != null) alt.addBodyPart(MimeBodyPart().apply { setContent(fixCharsetMeta(html), "text/html; charset=UTF-8") })
        var body: MimeMultipart = alt
        if (inline.isNotEmpty()) {
            val rel = MimeMultipart("related")
            rel.addBodyPart(MimeBodyPart().apply { setContent(alt) })
            inline.forEach { rel.addBodyPart(it) }
            body = rel
        }
        if (attached.isNotEmpty()) {
            val mixed = MimeMultipart("mixed")
            mixed.addBodyPart(MimeBodyPart().apply { setContent(body) })
            attached.forEach { mixed.addBodyPart(it) }
            body = mixed
        }
        if (html == null && inline.isEmpty() && attached.isEmpty()) msg.setText(text.orEmpty(), "UTF-8") else msg.setContent(body)
        msg.saveChanges()
        return msg
    }

    private fun stripSubjectPrefix(s: String): String =
        // PidTagSubject may start with 0x01 and a length byte marking the prefix ("RE: ").
        if (s.length >= 2 && s[0] == '\u0001') s.substring(2) else s

    private fun decodeHtml(b: ByteArray, p: PstProps): String {
        val head = String(b, 0, minOf(b.size, 4096), Charsets.ISO_8859_1)
        val declared = Regex("(?i)charset\\s*=\\s*[\"']?([A-Za-z0-9_.:-]+)").find(head)?.groupValues?.get(1)
        val cs = declared?.let { runCatching { charset(it) }.getOrNull() }
            ?: p.int(0x3FDE)?.let { Rtf.charsetForCodepage(it) }
            ?: Charsets.UTF_8
        return String(b, cs)
    }

    private fun fixCharsetMeta(html: String): String =
        html.replace(Regex("(?i)(<meta[^>]+charset\\s*=\\s*[\"']?)([A-Za-z0-9_.:-]+)"), "$1utf-8")

    private fun attachmentPart(ap: PstProps, depth: Int): MimeBodyPart? {
        val method = ap.int(0x3705) ?: 1
        val fileName = (ap.string(0x3707) ?: ap.string(0x3704) ?: ap.string(0x3001))?.takeIf { it.isNotBlank() }
        if (method == 5 || ap.type(0x3701) == 0x000D) {
            if (depth > 8) return null
            val raw = ap.props[0x3701]?.raw ?: return null
            val hnid = raw.i32(0)
            val ref = if (hnid and 0x1F == 0) ap.data(hnid)?.takeIf { it.size >= 4 }?.i32(0) else hnid
            val sub = ref?.let { ap.subnode(it) } ?: return null
            val inner = mime(PstProps.of(pst, sub.bidData, sub.bidSub), depth + 1)
            return MimeBodyPart().apply {
                setContent(inner, "message/rfc822")
                disposition = Part.ATTACHMENT
                val subj = runCatching { inner.subject }.getOrNull()
                setFileName(((fileName ?: subj ?: "message").replace(Regex("[\\\\/:*?\"<>|]"), "_").take(100)).let { if (it.endsWith(".eml", true)) it else "$it.eml" })
            }
        }
        if (method in 2..4 || method == 7) {
            // By reference (a path or URL on the original computer): nothing to copy, keep a note.
            val path = ap.string(0x370D) ?: ap.string(0x3708) ?: fileName ?: "attachment"
            return MimeBodyPart().apply { setText("Linked attachment (not stored in the data file): $path", "UTF-8"); setFileName("${fileName ?: "link"}.txt"); disposition = Part.ATTACHMENT }
        }
        val data = ap.binary(0x3701) ?: return null
        val name = fileName ?: if (method == 6) "ole-object.bin" else "attachment.bin"
        val type = ap.string(0x370E)?.takeIf { it.contains('/') } ?: mimeTypeFor(name)
        return MimeBodyPart().apply {
            dataHandler = DataHandler(ByteArrayDataSource(data, type))
            setFileName(name)
            disposition = Part.ATTACHMENT
            ap.string(0x3713)?.takeIf { it.isNotBlank() }?.let { setHeader("Content-Location", it) }
        }
    }

    // ------------------------------------------------------------------------- contacts, events, tasks

    private fun pimItem(p: PstProps, kind: FolderKind): PimItem? {
        setCodepage(p)
        val cls = p.string(0x001A).orEmpty()
        return when (kind) {
            FolderKind.CONTACTS -> when {
                cls.startsWith("IPM.Contact", true) || cls.isEmpty() -> PimItem(kind, vcard(p))
                cls.startsWith("IPM.DistList", true) -> PimItem(kind, distList(p))
                else -> null
            }
            FolderKind.CALENDAR -> PimItem(kind, vevent(p))
            FolderKind.TASKS -> PimItem(kind, vtodo(p))
            else -> null
        }
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\r\n", "\\n").replace("\n", "\\n")

    private fun dateOnly(ms: Long): String {
        // Outlook stores dates (birthdays, all-day events) as local midnight in UTC; the nearest UTC midnight is the date.
        val day = Math.floorDiv(ms + 12 * 3600_000L, 86_400_000L) * 86_400_000L
        return SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(day))
    }

    private fun utc(ms: Long) = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

    private fun vcard(p: PstProps): String {
        val l = ArrayList<String>()
        l += "BEGIN:VCARD"; l += "VERSION:3.0"
        val first = p.string(0x3A06).orEmpty(); val last = p.string(0x3A11).orEmpty(); val middle = p.string(0x3A44).orEmpty()
        val prefix = p.string(0x3A45).orEmpty(); val suffix = p.string(0x3A05).orEmpty()
        val display = (p.string(0x3001) ?: listOf(first, middle, last).filter { it.isNotBlank() }.joinToString(" ")).trim()
        l += "N:${esc(last)};${esc(first)};${esc(middle)};${esc(prefix)};${esc(suffix)}"
        l += "FN:${esc(display.ifBlank { p.string(0x3A16).orEmpty() })}"
        p.string(0x3A4F)?.takeIf { it.isNotBlank() }?.let { l += "NICKNAME:${esc(it)}" }
        val company = p.string(0x3A16).orEmpty(); val dept = p.string(0x3A18).orEmpty()
        if (company.isNotBlank() || dept.isNotBlank()) l += "ORG:${esc(company)};${esc(dept)}"
        p.string(0x3A17)?.takeIf { it.isNotBlank() }?.let { l += "TITLE:${esc(it)}" }
        for ((emailLid, typeLid) in listOf(0x8083 to 0x8082, 0x8093 to 0x8092, 0x80A3 to 0x80A2)) {
            val id = lid(PSETID_ADDRESS, emailLid) ?: continue
            val v = p.string(id)?.trim().orEmpty()
            val t = lid(PSETID_ADDRESS, typeLid)?.let { p.string(it) }
            val email = if (v.contains('@')) v else if (t.equals("EX", true)) "" else v
            if (email.isNotBlank()) l += "EMAIL;TYPE=INTERNET:${esc(email)}"
        }
        for ((tag, type) in listOf(0x3A08 to "WORK,VOICE", 0x3A1B to "WORK,VOICE", 0x3A09 to "HOME,VOICE", 0x3A2F to "HOME,VOICE", 0x3A1C to "CELL", 0x3A24 to "WORK,FAX", 0x3A25 to "HOME,FAX", 0x3A21 to "PAGER", 0x3A1F to "CAR", 0x3A1D to "VOICE", 0x3A1A to "VOICE"))
            p.string(tag)?.takeIf { it.isNotBlank() }?.let { l += "TEL;TYPE=$type:${esc(it)}" }
        fun adr(type: String, street: Int?, city: Int?, state: Int?, zip: Int?, country: Int?) {
            val parts = listOf(street, city, state, zip, country).map { it?.let { id -> p.string(id) }.orEmpty() }
            if (parts.any { it.isNotBlank() }) l += "ADR;TYPE=$type:;;${parts.joinToString(";") { esc(it) }}"
        }
        adr("HOME", 0x3A5D, 0x3A59, 0x3A5C, 0x3A5B, 0x3A5A)
        adr("WORK", lid(PSETID_ADDRESS, 0x8045) ?: 0x3A29, lid(PSETID_ADDRESS, 0x8046) ?: 0x3A27, lid(PSETID_ADDRESS, 0x8047) ?: 0x3A28, lid(PSETID_ADDRESS, 0x8048) ?: 0x3A2A, lid(PSETID_ADDRESS, 0x8049) ?: 0x3A26)
        adr("OTHER", 0x3A63, 0x3A5F, 0x3A62, 0x3A61, 0x3A60)
        p.time(0x3A42)?.let { val d = dateOnly(it); l += "BDAY:${d.substring(0, 4)}-${d.substring(4, 6)}-${d.substring(6)}" }
        p.time(0x3A41)?.let { val d = dateOnly(it); l += "ANNIVERSARY:${d.substring(0, 4)}-${d.substring(4, 6)}-${d.substring(6)}" }
        (lid(PSETID_ADDRESS, 0x802B)?.let { p.string(it) } ?: p.string(0x3A50) ?: p.string(0x3A51))?.takeIf { it.isNotBlank() }?.let { l += "URL:${esc(it)}" }
        p.string(0x1000)?.takeIf { it.isNotBlank() }?.let { l += "NOTE:${esc(it.trim())}" }
        categories(p).takeIf { it.isNotEmpty() }?.let { l += "CATEGORIES:${it.joinToString(",") { c -> esc(c) }}" }
        l += "END:VCARD"
        return l.joinToString("\r\n") + "\r\n"
    }

    /** A contact group (distribution list) as a vCard 4 group: its one-off members' names and addresses. */
    private fun distList(p: PstProps): String {
        val l = ArrayList<String>()
        l += "BEGIN:VCARD"; l += "VERSION:3.0"; l += "X-ADDRESSBOOKSERVER-KIND:group"; l += "KIND:group"
        val name = (lid(PSETID_ADDRESS, 0x8053)?.let { p.string(it) } ?: p.string(0x3001) ?: p.string(0x0037)).orEmpty()
        l += "N:${esc(name)};;;;"; l += "FN:${esc(name)}"
        // PidLidDistributionListOneOffMembers: one-off entry ids (flags, provider uid, version, flags, then display name, type, address).
        val members = lid(PSETID_ADDRESS, 0x8054)?.let { multiBinary(p, it) }.orEmpty()
        for (m in members) oneOff(m)?.let { (n, e) -> l += "X-ADDRESSBOOKSERVER-MEMBER:mailto:$e"; l += "MEMBER:mailto:$e"; if (n.isNotBlank()) l += "NOTE:${esc(n)} <$e>" }
        l += "END:VCARD"
        return l.joinToString("\r\n") + "\r\n"
    }

    private fun multiBinary(p: PstProps, id: Int): List<ByteArray> {
        if (p.type(id) != 0x1102) return emptyList()
        val b = p.bytes(id) ?: return emptyList()
        if (b.size < 4) return emptyList()
        val n = b.i32(0)
        if (n < 0 || 4 + n * 4 > b.size) return emptyList()
        // Variable-size multi-valued: count, then offsets (4 bytes each), then data.
        val offs = (0 until n).map { b.i32(4 + it * 4) } + b.size
        return (0 until n).mapNotNull { i -> val s = offs[i]; val e = offs[i + 1]; if (s in 0..e && e <= b.size) b.copyOfRange(s, e) else null }
    }

    /** Parses a one-off entry id ([MS-OXCDATA] 2.2.5.1): returns (display name, SMTP address). */
    private fun oneOff(b: ByteArray): Pair<String, String>? {
        if (b.size < 24) return null
        val flags = b.u16(22)
        val unicode = flags and 0x8000 != 0
        var o = 24
        fun str(): String {
            val sb = StringBuilder()
            if (unicode) { while (o + 1 < b.size) { val c = b.u16(o); o += 2; if (c == 0) break; sb.append(c.toChar()) } }
            else { while (o < b.size) { val c = b.u8(o); o++; if (c == 0) break; sb.append(c.toChar()) } }
            return sb.toString()
        }
        val name = str(); val type = str(); val email = str()
        return if (email.contains('@') || type.equals("SMTP", true)) name to email else null
    }

    private fun vevent(p: PstProps): String {
        val l = ArrayList<String>()
        val start = lid(PSETID_APPOINTMENT, 0x820D)?.let { p.time(it) } ?: p.time(0x0060)
        val end = lid(PSETID_APPOINTMENT, 0x820E)?.let { p.time(it) } ?: p.time(0x0061) ?: start
        val allDay = lid(PSETID_APPOINTMENT, 0x8215)?.let { p.bool(it) } ?: false
        l += "BEGIN:VEVENT"
        l += "UID:" + (p.string(0x1035)?.trim('<', '>')?.takeIf { it.isNotBlank() } ?: "pst-${start ?: 0}-${(p.string(0x0037) ?: "").hashCode().toUInt().toString(16)}@quillpost-import")
        l += "DTSTAMP:${utc(p.time(0x3008) ?: System.currentTimeMillis())}"
        if (start != null) {
            if (allDay) { l += "DTSTART;VALUE=DATE:${dateOnly(start)}"; l += "DTEND;VALUE=DATE:${dateOnly(end ?: start)}" }
            else { l += "DTSTART:${utc(start)}"; l += "DTEND:${utc(end ?: start)}" }
        }
        l += "SUMMARY:${esc(stripSubjectPrefix(p.string(0x0037).orEmpty()))}"
        (lid(PSETID_APPOINTMENT, 0x8208)?.let { p.string(it) })?.takeIf { it.isNotBlank() }?.let { l += "LOCATION:${esc(it)}" }
        p.string(0x1000)?.takeIf { it.isNotBlank() }?.let { l += "DESCRIPTION:${esc(it.trim())}" }
        if (lid(PSETID_APPOINTMENT, 0x8223)?.let { p.bool(it) } == true || lid(PSETID_APPOINTMENT, 0x8216)?.let { p.has(it) } == true) {
            lid(PSETID_APPOINTMENT, 0x8216)?.let { p.binary(it) }?.let { recurrenceRule(it, start) }?.let { l += "RRULE:$it" }
        }
        when (lid(PSETID_APPOINTMENT, 0x8205)?.let { p.int(it) }) { 0 -> l += "TRANSP:TRANSPARENT"; 1 -> l += "X-MICROSOFT-CDO-BUSYSTATUS:TENTATIVE"; 3 -> l += "X-MICROSOFT-CDO-BUSYSTATUS:OOF" }
        senderOf(p)?.let { a -> if (!a.address.isNullOrBlank()) l += "ORGANIZER;CN=${esc(a.personal ?: a.address)}:mailto:${a.address}" }
        for (r in recipients(p)) if (r.email.isNotBlank()) l += "ATTENDEE;CN=${esc(r.name.ifBlank { r.email })};ROLE=${if (r.type == 2) "OPT-PARTICIPANT" else "REQ-PARTICIPANT"}:mailto:${r.email}"
        categories(p).takeIf { it.isNotEmpty() }?.let { l += "CATEGORIES:${it.joinToString(",") { c -> esc(c) }}" }
        if (lid(PSETID_COMMON, 0x8503)?.let { p.bool(it) } == true) {
            val minutes = lid(PSETID_COMMON, 0x8501)?.let { p.int(it) } ?: 15
            l += "BEGIN:VALARM"; l += "ACTION:DISPLAY"; l += "DESCRIPTION:Reminder"; l += "TRIGGER:-PT${minutes}M"; l += "END:VALARM"
        }
        l += "END:VEVENT"
        return l.joinToString("\r\n")
    }

    /** RRULE from an appointment recurrence blob (PidLidAppointmentRecur, [MS-OXOCAL] 2.2.1.44.1). */
    internal fun recurrenceRule(b: ByteArray, start: Long?): String? {
        if (b.size < 26 || b.u16(0) != 0x3004) return null
        val freq = b.u16(4); val pattern = b.u16(6)
        val period = b.i32(14)
        var o = 22
        val days = listOf("SU", "MO", "TU", "WE", "TH", "FR", "SA")
        fun mask(m: Int) = (0..6).filter { m and (1 shl it) != 0 }.joinToString(",") { days[it] }
        var specific = ""
        when (pattern) {
            0x0001 -> { specific = "BYDAY=" + mask(b.i32(o)); o += 4 }
            0x0002, 0x0003, 0x000A, 0x000C -> { val d = b.i32(o); specific = if (pattern == 0x0003 || pattern == 0x000C || d == 31) "BYMONTHDAY=-1" else "BYMONTHDAY=$d"; o += 4 }
            0x0004, 0x000B -> { val m = b.i32(o); val n = b.i32(o + 4); specific = "BYDAY=" + (if (n == 5) "-1" else "$n") + mask(m).substringBefore(','); o += 8 }
        }
        if (o + 12 > b.size) return null
        val endType = b.i32(o); val count = b.i32(o + 4)
        val parts = ArrayList<String>()
        when (freq) {
            0x200A -> if (pattern == 0x0001) parts += "FREQ=WEEKLY" else { parts += "FREQ=DAILY"; if (period >= 1440) parts += "INTERVAL=${period / 1440}" }
            0x200B -> { parts += "FREQ=WEEKLY"; if (period > 1) parts += "INTERVAL=$period" }
            0x200C -> { parts += "FREQ=MONTHLY"; if (period > 1) parts += "INTERVAL=$period" }
            0x200D -> { parts += "FREQ=YEARLY"; if (period > 12) parts += "INTERVAL=${period / 12}"; start?.let { val c = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = it + 12 * 3600_000L }; parts += "BYMONTH=${c.get(java.util.Calendar.MONTH) + 1}" } }
            else -> return null
        }
        if (specific.isNotEmpty()) parts += specific
        when (endType) {
            0x2022 -> if (count > 0) parts += "COUNT=$count"
            0x2021 -> {
                // EndDate: minutes since 1601 at the end of the recurrence blob's pattern section.
                var q = o + 12
                if (q + 4 <= b.size) { val del = b.i32(q); q += 4 + maxOf(0, del) * 4 }
                if (q + 4 <= b.size) { val mod = b.i32(q); q += 4 + maxOf(0, mod) * 4 }
                if (q + 8 <= b.size) {
                    val endMinutes = b.u32(q + 4)
                    val ms = endMinutes * 60_000L - 11_644_473_600_000L
                    parts += "UNTIL=${dateOnly(ms)}T235959Z"
                }
            }
        }
        return parts.joinToString(";")
    }

    private fun vtodo(p: PstProps): String {
        val l = ArrayList<String>()
        l += "BEGIN:VTODO"
        l += "UID:pst-task-${p.time(0x3007) ?: 0}-${(p.string(0x0037) ?: "").hashCode().toUInt().toString(16)}@quillpost-import"
        l += "SUMMARY:${esc(stripSubjectPrefix(p.string(0x0037).orEmpty()))}"
        lid(PSETID_TASK, 0x8105)?.let { p.time(it) }?.let { l += "DUE;VALUE=DATE:${dateOnly(it)}" }
        lid(PSETID_TASK, 0x8104)?.let { p.time(it) }?.let { l += "DTSTART;VALUE=DATE:${dateOnly(it)}" }
        val complete = lid(PSETID_TASK, 0x811C)?.let { p.bool(it) } == true || lid(PSETID_TASK, 0x8101)?.let { p.int(it) } == 2
        l += "STATUS:" + if (complete) "COMPLETED" else when (lid(PSETID_TASK, 0x8101)?.let { p.int(it) }) { 1 -> "IN-PROCESS"; 4 -> "CANCELLED"; else -> "NEEDS-ACTION" }
        lid(PSETID_TASK, 0x810F)?.let { p.time(it) }?.let { l += "COMPLETED:${utc(it)}" }
        lid(PSETID_TASK, 0x8102)?.let { p.bytes(it) }?.takeIf { it.size >= 8 }?.let { l += "PERCENT-COMPLETE:${(java.lang.Double.longBitsToDouble(it.i64(0)) * 100).toInt()}" }
        when (p.int(0x0017)) { 2 -> l += "PRIORITY:1"; 0 -> l += "PRIORITY:9" }
        p.string(0x1000)?.takeIf { it.isNotBlank() }?.let { l += "DESCRIPTION:${esc(it.trim())}" }
        categories(p).takeIf { it.isNotEmpty() }?.let { l += "CATEGORIES:${it.joinToString(",") { c -> esc(c) }}" }
        if (lid(PSETID_COMMON, 0x8503)?.let { p.bool(it) } == true) (lid(PSETID_COMMON, 0x8560)?.let { p.time(it) } ?: lid(PSETID_COMMON, 0x8502)?.let { p.time(it) })?.let {
            l += "BEGIN:VALARM"; l += "ACTION:DISPLAY"; l += "DESCRIPTION:Reminder"; l += "TRIGGER;VALUE=DATE-TIME:${utc(it)}"; l += "END:VALARM"
        }
        l += "END:VTODO"
        return l.joinToString("\r\n")
    }
}
