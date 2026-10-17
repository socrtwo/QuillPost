package info.socrtwo.quillpost.web.mailstore

import info.socrtwo.quillpost.web.mailstore.PstWriter.Companion.guid
import info.socrtwo.quillpost.web.mailstore.PstWriter.Companion.le16
import info.socrtwo.quillpost.web.mailstore.PstWriter.Companion.le32
import info.socrtwo.quillpost.web.mailstore.PstWriter.V
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Builds every fixture programmatically, so the tests need no binary files in the repository. */
object Fixtures {
    fun msg(subject: String, from: String = "Alice Example <alice@example.com>", body: String = "Hello from $subject.\n", date: String = "Thu, 24 Sep 2026 09:12:00 +0000", extra: String = ""): String =
        "From: $from\nTo: Bob <bob@example.org>\nSubject: $subject\nDate: $date\nMessage-ID: <${subject.lowercase().replace(Regex("[^a-z0-9]"), "")}@fixture>\n${extra}MIME-Version: 1.0\nContent-Type: text/plain; charset=utf-8\n\n$body"

    // ------------------------------------------------------------------------------- mbox family

    /** mboxrd: a body line "From here" quoted as ">From", and ">From" quoted as ">>From". */
    fun mboxrd(): String = "From alice@example.com Thu Sep 24 09:12:00 2026\n" +
        msg("First", body = "Line one\n>From here on it was quoted\n>>From the archive\n") + "\n" +
        "From bob@example.org Fri Sep 25 10:00:00 2026\n" + msg("Second", extra = "Status: RO\nX-Status: AF\n") + "\n"

    /** mboxcl2: Content-Length bodies containing an unquoted "From " line that is not a separator. */
    fun mboxcl2(): String {
        val body1 = "Before\nFrom me to you Thu Sep 24 09:12:00 2026 looks like a separator\nAfter\n"
        val m1 = msg("CL one", body = body1).replace("MIME-Version", "Content-Length: ${body1.length}\nMIME-Version")
        val body2 = "Second body\n"
        val m2 = msg("CL two", body = body2).replace("MIME-Version", "Content-Length: ${body2.length}\nMIME-Version")
        return "From a@b Thu Sep 24 09:12:00 2026\n$m1\nFrom c@d Thu Sep 24 10:12:00 2026\n$m2\n"
    }

    fun thunderbird(): String = "From - Thu Sep 24 09:12:00 2026\n" + msg("Mozilla read flagged", extra = "X-Mozilla-Status: 0005\nX-Mozilla-Status2: 00000000\nX-Mozilla-Keys: \$label1 projectx\n") + "\n" +
        "From - Thu Sep 24 09:13:00 2026\n" + msg("Mozilla forwarded", extra = "X-Mozilla-Status: 1001\n") + "\n"

    fun takeout(): String = "From 1234@xxx Thu Sep 24 09:12:00 +0000 2026\n" + msg("Takeout inbox", extra = "X-GM-THRID: 1\nX-Gmail-Labels: Inbox,Important,Unread,Category Personal\n") + "\n" +
        "From 1235@xxx Thu Sep 24 09:13:00 +0000 2026\n" + msg("Takeout work", extra = "X-Gmail-Labels: Opened,\"Work, 2026\",Starred\n") + "\n" +
        "From 1236@xxx Thu Sep 24 09:14:00 +0000 2026\n" + msg("Takeout sent", extra = "X-Gmail-Labels: Sent,Opened\n") + "\n"

    // ------------------------------------------------------------------------------- Eudora

    /** An Eudora .mbx with a Windows-layout .toc (104-byte header, 218-byte records) and an attachment folder. */
    fun eudora(dir: File): File {
        dir.mkdirs()
        val m1 = "From ???@??? Thu Sep 24 09:12:00 2026\n" + msg("Eudora read") + "\n"
        val m2 = "From ???@??? Thu Sep 24 09:13:00 2026\n" + msg("Eudora replied", body = "See attached.\nAttachment Converted: \"C:\\Eudora\\Attach\\report.txt\"\nAttachment Converted: \"C:\\Eudora\\Attach\\missing.doc\"\n") + "\n"
        val m3 = "From ???@??? Thu Sep 24 09:14:00 2026\n" + msg("Eudora deleted but not compacted") + "\n"
        val mbx = File(dir, "In.mbx")
        mbx.writeText(m1 + m2 + m3, Charsets.ISO_8859_1)
        val toc = ByteArrayOutputStream()
        toc.write(ByteArray(104).also { "In".toByteArray().copyInto(it, 2) })
        fun rec(off: Int, len: Int, state: Int) { val r = ByteArray(218); le32(off).copyInto(r, 0); le32(len).copyInto(r, 4); le32(1790241120).copyInto(r, 8); r[12] = state.toByte(); toc.write(r) }
        rec(0, m1.length, 1)
        rec(m1.length, m2.length, 2)
        File(dir, "In.toc").writeBytes(toc.toByteArray())
        File(dir, "Attach").mkdirs()
        File(dir, "Attach/report.txt").writeText("Quarterly report\n")
        return mbx
    }

    // ------------------------------------------------------------------------------- Maildir / MH

    fun maildir(dir: File): File {
        for (s in listOf("cur", "new", "tmp")) File(dir, s).mkdirs()
        File(dir, "cur/1700000000.1.host:2,SR").writeText(msg("Maildir seen replied"))
        File(dir, "cur/1700000001.2.host:2,Fa").writeText(msg("Maildir flagged keyword"))
        File(dir, "new/1700000002.3.host").writeText(msg("Maildir new"))
        File(dir, "dovecot-keywords").writeText("0 \$Work\n")
        val sub = File(dir, ".Archive.2020")
        for (s in listOf("cur", "new", "tmp")) File(sub, s).mkdirs()
        File(sub, "cur/1700000003.4.host:2,S").writeText(msg("Maildir sub archived"))
        val sent = File(dir, ".Sent")
        for (s in listOf("cur", "new", "tmp")) File(sent, s).mkdirs()
        File(sent, "cur/1700000004.5.host:2,S").writeText(msg("Maildir sent"))
        File(dir, ".Entw&APw-rfe").let { for (s in listOf("cur", "new", "tmp")) File(it, s).mkdirs(); File(it, "cur/1.6.host:2,D").writeText(msg("Draft in UTF-7 folder")) }
        return dir
    }

    fun mh(dir: File): File {
        dir.mkdirs()
        File(dir, "1").writeText(msg("MH one"))
        File(dir, "2").writeText(msg("MH two"))
        File(dir, "3").writeText(msg("MH three"))
        File(dir, ".mh_sequences").writeText("unseen: 2-3\nflagged: 3\n")
        return dir
    }

    // ------------------------------------------------------------------------------- Apple Mail

    fun emlx(message: String, flags: Long, received: Long): ByteArray {
        val m = message.toByteArray()
        val plist = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n<plist version=\"1.0\">\n<dict>\n\t<key>date-received</key>\n\t<integer>$received</integer>\n\t<key>flags</key>\n\t<integer>$flags</integer>\n</dict>\n</plist>\n"
        return "${m.size}\n".toByteArray() + m + plist.toByteArray()
    }

    /** Apple Mail V2+ layout: Inbox.mbox/<uuid>/Data/Messages/N.emlx, with a nested folder and a .partial.emlx. */
    fun appleMail(root: File): File {
        val inbox = File(root, "Inbox.mbox/1A2B/Data/Messages").apply { mkdirs() }
        File(inbox, "1.emlx").writeBytes(emlx(msg("Apple read flagged"), 1L or (1L shl 4) or (2L shl 41), 1790241120))
        File(inbox, "2.emlx").writeBytes(emlx(msg("Apple unread"), 0, 1790241180))
        val boundary = "b1"
        val partial = "From: a@example.com\nTo: b@example.org\nSubject: Apple partial\nDate: Thu, 24 Sep 2026 09:12:00 +0000\nMIME-Version: 1.0\nContent-Type: multipart/mixed; boundary=\"$boundary\"\n\n" +
            "--$boundary\nContent-Type: text/plain\n\nSee the picture.\n--$boundary\nContent-Type: image/png; name=\"pic.png\"\nContent-Disposition: attachment; filename=\"pic.png\"\nX-Apple-Content-Length: 4\n\n\n--$boundary--\n"
        File(inbox, "3.partial.emlx").writeBytes(emlx(partial, 1, 1790241240))
        File(root, "Inbox.mbox/1A2B/Data/Attachments/3/2").apply { mkdirs() }.let { File(it, "pic.png").writeBytes(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())) }
        val nested = File(root, "Inbox.mbox/Projects.mbox/9F/Data/Messages").apply { mkdirs() }
        File(nested, "10.emlx").writeBytes(emlx(msg("Apple nested"), 1, 1790241300))
        return root
    }

    // ------------------------------------------------------------------------------- Outlook Express

    /** A message .dbx: header, one B-tree node, indexed-info objects and 0x200-byte message block chains. */
    fun dbx(messages: List<Pair<String, Long>>): ByteArray {
        val out = ByteArray(0x24BC + 0x20000)
        byteArrayOf(0xCF.toByte(), 0xAD.toByte(), 0x12, 0xFE.toByte(), 0xC5.toByte(), 0xFD.toByte(), 0x74, 0x6F, 0x66, 0xE3.toByte(), 0xD1.toByte(), 0x11).copyInto(out, 0)
        var p = 0x24BC
        val infos = ArrayList<Int>()
        for ((text, flags) in messages) {
            val data = text.toByteArray()
            var first = 0; var prev = -1
            for (chunk in data.toList().chunked(0x200)) {
                val at = p
                if (first == 0) first = at
                if (prev >= 0) le32(at).copyInto(out, prev + 12)
                le32(at).copyInto(out, at); le32(0x200).copyInto(out, at + 4); le32(chunk.size).copyInto(out, at + 8); le32(0).copyInto(out, at + 12)
                chunk.toByteArray().copyInto(out, at + 16)
                prev = at; p += 16 + 0x200
            }
            // Indexed info: 0x01 flags (direct), 0x04 message address (direct), 0x12 received FILETIME (data).
            val at = p
            val entries = ByteArrayOutputStream()
            fun direct(idx: Int, v: Int) { entries.write(idx or 0x80); entries.write(v); entries.write(v shr 8); entries.write(v shr 16) }
            direct(0x01, flags.toInt()); direct(0x04, first)
            entries.write(0x12); entries.write(0); entries.write(0); entries.write(0)
            val ft = PstWriter.le64((1790241120000L + 11_644_473_600_000L) * 10_000L)
            val body = entries.toByteArray() + ft
            le32(at).copyInto(out, at); le32(body.size).copyInto(out, at + 4); le16(0).copyInto(out, at + 8); out[at + 10] = 3; out[at + 11] = 1
            body.copyInto(out, at + 12)
            infos += at; p += 12 + body.size + 4
        }
        val node = p
        le32(node).copyInto(out, node); out[node + 0x11] = infos.size.toByte(); le32(infos.size).copyInto(out, node + 0x14)
        infos.forEachIndexed { i, v -> le32(v).copyInto(out, node + 0x18 + i * 12) }
        p += 0x18 + infos.size * 12
        le32(messages.size).copyInto(out, 0xC4); le32(node).copyInto(out, 0xE4)
        return out.copyOf(p + 16)
    }

    /** Folders.dbx listing folders with parents and file names. */
    fun foldersDbx(folders: List<List<Any?>>): ByteArray {
        val out = ByteArray(0x24BC + 0x4000)
        byteArrayOf(0xCF.toByte(), 0xAD.toByte(), 0x12, 0xFE.toByte(), 0xC6.toByte(), 0xFD.toByte(), 0x74, 0x6F).copyInto(out, 0)
        var p = 0x24BC
        val infos = ArrayList<Int>()
        for (f in folders) {
            val id = f[0] as Int; val parent = f[1] as Int; val name = f[2] as String; val file = f[3] as String?
            val data = ByteArrayOutputStream()
            val ents = ByteArrayOutputStream()
            fun direct(idx: Int, v: Int) { ents.write(idx or 0x80); ents.write(v); ents.write(v shr 8); ents.write(v shr 16) }
            fun ref(idx: Int, s: String) { val off = data.size(); ents.write(idx); ents.write(off); ents.write(off shr 8); ents.write(off shr 16); data.write(s.toByteArray(Charsets.ISO_8859_1)); data.write(0) }
            direct(0x00, id); direct(0x01, parent); ref(0x02, name); if (file != null) ref(0x03, file)
            val body = ents.toByteArray() + data.toByteArray()
            le32(p).copyInto(out, p); le32(body.size).copyInto(out, p + 4); out[p + 10] = (ents.size() / 4).toByte()
            body.copyInto(out, p + 12)
            infos += p; p += 12 + body.size + 4
        }
        val node = p
        le32(node).copyInto(out, node); out[node + 0x11] = infos.size.toByte()
        infos.forEachIndexed { i, v -> le32(v).copyInto(out, node + 0x18 + i * 12) }
        p += 0x18 + infos.size * 12
        le32(node).copyInto(out, 0xE4)
        return out.copyOf(p + 16)
    }

    fun oeStore(dir: File): File {
        dir.mkdirs()
        File(dir, "Inbox.dbx").writeBytes(dbx(listOf(msg("OE inbox read") to 0x80L, msg("OE inbox replied", body = "x".repeat(1500)) to 0x80080L)))
        File(dir, "Sent Items.dbx").writeBytes(dbx(listOf(msg("OE sent") to 0x80L)))
        File(dir, "Clients.dbx").writeBytes(dbx(listOf(msg("OE nested") to 0L)))
        File(dir, "Folders.dbx").writeBytes(foldersDbx(listOf(
            listOf(1, 0, "Local Folders", null), listOf(2, 1, "Inbox", "Inbox.dbx"), listOf(3, 1, "Sent Items", "Sent Items.dbx"), listOf(4, 2, "Clients", "Clients.dbx"), listOf(5, 1, "Empty", null)
        )))
        return dir
    }

    fun oe4(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("JMF6".toByteArray()); out.write(ByteArray(0x30))
        for ((i, m) in listOf(msg("OE4 one"), msg("OE4 two")).withIndex()) {
            val b = m.toByteArray()
            out.write(le32(0x007F007F)); out.write(le32(i + 1)); out.write(le32(16 + b.size)); out.write(le32(b.size)); out.write(b)
        }
        return out.toByteArray()
    }

    fun pegasus(): ByteArray {
        val h = ByteArray(128).also { "Project mail".toByteArray().copyInto(it) }
        return h + (msg("Pegasus one") + "\u001A" + msg("Pegasus two") + "\u001A").toByteArray(Charsets.ISO_8859_1)
    }

    fun theBat(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x01, 0x00, 0x00, 0x00, 0x1E, 0x02, 0x00, 0x00, '\n'.code.toByte()))
        out.write(("Return-Path: <a@example.com>\n" + msg("Bat one")).toByteArray())
        out.write(byteArrayOf(0x02, 0x00, 0x00, 0x00, 0x05, 0x01, 0x00, 0x00, '\n'.code.toByte()))
        out.write(("Return-Path: <a@example.com>\n" + msg("Bat two")).toByteArray())
        return out.toByteArray()
    }

    // ------------------------------------------------------------------------------- archives

    fun zipDir(dir: File, zip: File) {
        ZipOutputStream(zip.outputStream()).use { z ->
            dir.walk().filter { it.isFile }.forEach { f -> z.putNextEntry(ZipEntry(dir.name + "/" + f.relativeTo(dir).path.replace('\\', '/'))); z.write(f.readBytes()); z.closeEntry() }
        }
    }

    fun tgz(name: String, content: ByteArray, out: File) {
        val tar = ByteArrayOutputStream()
        val h = ByteArray(512)
        name.toByteArray().copyInto(h, 0)
        "0000644\u0000".toByteArray().copyInto(h, 100)
        String.format("%011o\u0000", content.size).toByteArray().copyInto(h, 124)
        h[156] = '0'.code.toByte()
        "ustar\u000000".toByteArray().copyInto(h, 257)
        tar.write(h); tar.write(content); tar.write(ByteArray((512 - content.size % 512) % 512)); tar.write(ByteArray(1024))
        GZIPOutputStream(out.outputStream()).use { it.write(tar.toByteArray()) }
    }

    // ------------------------------------------------------------------------------- OLE2 (CFB)

    /** An OLE2 compound file holding [streams] (built with the shared test CFB writer). */
    fun cfb(streams: List<String>): ByteArray {
        val root = info.socrtwo.quillpost.web.fileformats.CfbWriter.Node("Root Entry")
        for (n in streams) root.stream(n, ByteArray(64))
        return info.socrtwo.quillpost.web.fileformats.CfbWriter.write(root)
    }

    // ------------------------------------------------------------------------------- compressed RTF

    /** LZFu-compresses [rtf] (literals only, plus one dictionary reference when it starts with "{\rtf1"). */
    fun lzfu(rtf: String): ByteArray {
        val raw = rtf.toByteArray(Charsets.ISO_8859_1)
        val body = ByteArrayOutputStream()
        var wp = 207
        var i = 0
        val tokens = ArrayList<ByteArray?>() // null = reference token handled inline
        val group = ArrayList<ByteArray>(); var control = 0
        fun flushGroup() { body.write(control); group.forEach { body.write(it) }; group.clear(); control = 0 }
        if (rtf.startsWith("{\\rtf1")) { control = control or 1; group += byteArrayOf(0x00, 0x04); i = 6; wp += 6 } // "{\rtf1" is at offset 0 of the dictionary
        while (i < raw.size) {
            if (group.size == 8) flushGroup()
            group += byteArrayOf(raw[i]); i++; wp++
        }
        if (group.size == 8) flushGroup()
        val end = (wp and 0xFFF) shl 4
        control = control or (1 shl group.size)
        group += byteArrayOf((end shr 8).toByte(), end.toByte())
        flushGroup()
        val comp = body.toByteArray()
        return le32(comp.size + 12) + le32(raw.size) + "LZFu".toByteArray() + le32(0) + comp
    }

    // ------------------------------------------------------------------------------- PST

    const val PSETID_ADDRESS = "00062004-0000-0000-c000-000000000046"
    const val PSETID_APPOINTMENT = "00062002-0000-0000-c000-000000000046"
    const val PSETID_TASK = "00062003-0000-0000-c000-000000000046"

    /**
     * A PST with: Inbox (HTML message with transport headers, an attachment and an attached
     * Outlook item; an RTF-only message that encapsulates HTML, read+flagged+replied with categories),
     * Inbox/Projects (genuine RTF), Sent Items (no transport headers: To/Cc/Bcc from the recipient
     * table), Contacts (a contact), Calendar (a weekly appointment) and Tasks (a task).
     */
    fun pst(file: File, unicode: Boolean, encode: Boolean) {
        val w = PstWriter(unicode, encode)
        // Named property map: Keywords (string) → 0x8000; Email1 → 0x8001; start/end/location/recurrence → 0x8002..0x8005; task due/complete → 0x8006/0x8007.
        val guids = guid(PSETID_ADDRESS) + guid(PSETID_APPOINTMENT) + guid(PSETID_TASK)
        val strings = le32(16) + "Keywords".toByteArray(Charsets.UTF_16LE)
        fun ent(idOrOff: Int, guidIndex: Int, isString: Boolean, idx: Int) = le32(idOrOff) + le16((guidIndex shl 1) or (if (isString) 1 else 0)) + le16(idx)
        val entries = ent(0, 2, true, 0) + ent(0x8083, 3, false, 1) + ent(0x820D, 4, false, 2) + ent(0x820E, 4, false, 3) + ent(0x8208, 4, false, 4) + ent(0x8216, 4, false, 5) + ent(0x8105, 5, false, 6) + ent(0x811C, 5, false, 7)
        run {
            val subs = ArrayList<PstWriter.Sub>()
            val pc = w.pc(mapOf(0x0001 to V.I32(251), 0x0002 to V.Bin(guids), 0x0003 to V.Bin(entries), 0x0004 to V.Bin(strings)), subs)
            w.node(0x61, w.external(pc), w.subnodes(subs), 0)
        }
        val ipm = 0x8022; val inbox = 0x8042; val sent = 0x8062; val contacts = 0x8082; val calendar = 0x80A2; val tasks = 0x80C2; val projects = 0x80E2
        fun folder(nid: Int, parent: Int, name: String, cls: String) { val subs = ArrayList<PstWriter.Sub>(); w.node(nid, w.external(w.pc(mapOf(0x3001 to V.Str(name), 0x3613 to V.Str(cls), 0x3602 to V.I32(0), 0x3603 to V.I32(0)), subs)), w.subnodes(subs), parent) }
        run {
            val subs = ArrayList<PstWriter.Sub>()
            w.node(0x21, w.external(w.pc(mapOf(0x3001 to V.Str("Fixture Data File"), 0x35E0 to V.Bin(ByteArray(20) + le32(ipm))), subs)), 0, 0)
        }
        folder(0x122, 0x122, "", "")
        folder(ipm, 0x122, "Top of Personal Folders", "")
        folder(inbox, ipm, "Inbox", "IPF.Note")
        folder(projects, inbox, "Projects", "IPF.Note")
        folder(sent, ipm, "Sent Items", "IPF.Note")
        folder(contacts, ipm, "Contacts", "IPF.Contact")
        folder(calendar, ipm, "Calendar", "IPF.Appointment")
        folder(tasks, ipm, "Tasks", "IPF.Task")
        var nextMsg = 0x100
        fun message(parent: Int, props: Map<Int, V>, extraSubs: (MutableList<PstWriter.Sub>) -> Unit = {}): Int {
            val nid = (nextMsg++ shl 5) or 0x04
            val subs = ArrayList<PstWriter.Sub>()
            extraSubs(subs)
            val pc = w.pc(props, subs)
            w.node(nid, w.external(pc), w.subnodes(subs), parent)
            return nid
        }
        val t0 = 1790241120000L
        // 1. HTML mail with transport headers, an attachment and an attached Outlook item.
        val headers = "Return-Path: <carol@example.net>\r\nReceived: from mx.example.net by mx.example.org; Thu, 24 Sep 2026 09:12:01 +0000\r\nFrom: Carol Sender <carol@example.net>\r\nTo: Bob <bob@example.org>\r\nSubject: Quarterly figures\r\nDate: Thu, 24 Sep 2026 09:12:00 +0000\r\nMessage-ID: <q3@example.net>\r\nMIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=\"zzz\"\r\n"
        val html = "<html><body><p>Figures <b>attached</b>. <img src=\"cid:logo@x\"></p></body></html>"
        message(inbox, mapOf(
            0x001A to V.Str("IPM.Note"), 0x0037 to V.Str("Quarterly figures"), 0x007D to V.Str(headers), 0x1000 to V.Str("Figures attached."),
            0x1013 to V.Big(html.toByteArray(), 0x0102), 0x0E06 to V.Time(t0), 0x0E07 to V.I32(0x01), 0x3FDE to V.I32(65001)
        )) { subs ->
            fun attachment(index: Int, props: Map<Int, V>, inner: (MutableList<PstWriter.Sub>) -> Unit = {}) {
                val asubs = ArrayList<PstWriter.Sub>()
                inner(asubs)
                subs += PstWriter.Sub((index shl 5) or 0x05, w.external(w.pc(props, asubs)), w.subnodes(asubs))
            }
            attachment(0x21, mapOf(0x3705 to V.I32(1), 0x3707 to V.Str("figures.csv"), 0x370E to V.Str("text/csv"), 0x3701 to V.Bin("q,amount\n3,100\n".toByteArray())))
            attachment(0x22, mapOf(0x3705 to V.I32(1), 0x3707 to V.Str("logo.png"), 0x3712 to V.Str("logo@x"), 0x370E to V.Str("image/png"), 0x3701 to V.Bin(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))))
            attachment(0x23, mapOf(0x3705 to V.I32(5), 0x3001 to V.Str("Original request"), 0x3701 to V.Obj(0x2141, 0))) { asubs ->
                val esubs = ArrayList<PstWriter.Sub>()
                val epc = w.pc(mapOf(0x001A to V.Str("IPM.Note"), 0x0037 to V.Str("Original request"), 0x1000 to V.Str("Please send the figures."), 0x0042 to V.Str("Bob"), 0x5D02 to V.Str("bob@example.org"), 0x0039 to V.Time(t0 - 86_400_000L)), esubs)
                asubs += PstWriter.Sub(0x2141, w.external(epc), w.subnodes(esubs))
            }
        }
        // 2. RTF-only body that encapsulates HTML; read, flagged, replied, with categories.
        val encRtf = "{\\rtf1\\ansi\\ansicpg1252\\fromhtml1 \\deff0{\\fonttbl{\\f0\\fswiss Arial;}}{\\*\\htmltag19 <html>}{\\*\\htmltag34 <body>}\\htmlrtf {\\htmlrtf0 Caf\\'e9 menu\\htmlrtf }\\htmlrtf0 {\\*\\htmltag84 <br>}\\htmlrtf \\par\\htmlrtf0 \\u8364? price{\\*\\htmltag42 </body>}{\\*\\htmltag27 </html>}}"
        message(inbox, mapOf(
            0x001A to V.Str("IPM.Note"), 0x0037 to V.Str("Lunch menu"), 0x0042 to V.Str("Dave"), 0x5D02 to V.Str("dave@example.com"),
            0x1009 to V.Bin(lzfu(encRtf)), 0x0E06 to V.Time(t0 + 60_000), 0x0039 to V.Time(t0 + 50_000), 0x0E07 to V.I32(0x01),
            0x1090 to V.I32(2), 0x1081 to V.I32(102), 0x8000 to V.MultiStr(listOf("Food", "Team lunch"))
        )) { subs -> subs += PstWriter.Sub(0x692, w.external(w.recipientTable(listOf(listOf(1, "Bob", "bob@example.org", "SMTP", "bob@example.org")))), 0) }
        // 3. Genuine RTF in a sub-folder.
        val realRtf = "{\\rtf1\\ansi\\deff0{\\fonttbl{\\f0 Arial;}}{\\colortbl;\\red255\\green0\\blue0;}\\pard\\qc\\b Plan\\b0\\par\\pard Item {\\cf1 red} and {\\i italic}\\par}"
        message(projects, mapOf(0x001A to V.Str("IPM.Note"), 0x0037 to V.Str("Project plan"), 0x0042 to V.Str("Erin"), 0x5D02 to V.Str("erin@example.com"), 0x1009 to V.Bin(lzfu(realRtf)), 0x0E06 to V.Time(t0 + 120_000)))
        // 4. Sent item without transport headers; recipients from the table; long body split over a data tree.
        val longBody = "Report line.\n".repeat(900)
        message(sent, mapOf(
            0x001A to V.Str("IPM.Note"), 0x0037 to V.Str("Status report"), 0x0042 to V.Str("Bob"), 0x5D02 to V.Str("bob@example.org"),
            0x0039 to V.Time(t0 + 180_000), 0x0E07 to V.I32(0x01), 0x1035 to V.Str("<status@example.org>"), 0x0017 to V.I32(2),
            0x1000 to V.SubRef(0x1000 shl 5 or 0x1F, if (unicode) 0x001F else 0x001E)
        )) { subs ->
            val tree = w.dataTree(w.str(longBody), 4000)
            subs += PstWriter.Sub(0x1000 shl 5 or 0x1F, tree, 0)
            subs += PstWriter.Sub(0x692, w.external(w.recipientTable(listOf(
                listOf(1, "Carol Sender", "carol@example.net", "SMTP", "carol@example.net"),
                listOf(2, "Dave", "/o=Org/cn=dave", "EX", "dave@example.com"),
                listOf(3, "Hidden Helper", "helper@example.com", "SMTP", "helper@example.com")
            ))), 0)
        }
        // 5. Contact.
        message(contacts, mapOf(0x001A to V.Str("IPM.Contact"), 0x3001 to V.Str("Frank Contact"), 0x3A06 to V.Str("Frank"), 0x3A11 to V.Str("Contact"), 0x3A16 to V.Str("Acme"), 0x3A08 to V.Str("+1 555 0100"), 0x8001 to V.Str("frank@example.com"), 0x3A42 to V.Time(-86_400_000L * 365 * 10 + 5 * 3600_000L)))
        // 6. Weekly appointment (Tuesdays, 10 occurrences).
        val recur = le16(0x3004) + le16(0x3004) + le16(0x200B) + le16(0x0001) + le16(0) + le32(0) + le32(1) + le32(0) + le32(1 shl 2) + le32(0x2022) + le32(10) + le32(0) + le32(0) + le32(0) + le32(0) + le32(0)
        message(calendar, mapOf(0x001A to V.Str("IPM.Appointment"), 0x0037 to V.Str("Team sync"), 0x8002 to V.Time(t0), 0x8003 to V.Time(t0 + 1_800_000), 0x8004 to V.Str("Room 2"), 0x8005 to V.Bin(recur), 0x1000 to V.Str("Weekly team sync.")))
        // 7. Task.
        message(tasks, mapOf(0x001A to V.Str("IPM.Task"), 0x0037 to V.Str("File expenses"), 0x8006 to V.Time(t0 + 3 * 86_400_000L), 0x8007 to V.Bool(false), 0x0017 to V.I32(2)))
        w.write(file)
    }
}
