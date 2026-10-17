package info.socrtwo.quillpost.web.mailstore

import java.io.File

/**
 * Eudora (Windows and Mac) mailboxes: the .mbx file is an mbox whose separators read
 * "From ???@??? Mon Jan 01 00:00:00 2001"; the binary .toc next to it is Eudora's table of
 * contents. The .toc format was never published and changed between versions, so it is used
 * best-effort: a layout is accepted only when its records point exactly at message boundaries in
 * the .mbx. Two layouts are known from public notes — Windows (104-byte header, 218-byte
 * little-endian records: offset, length, GMT seconds, state) and classic Mac (278-byte header,
 * 220-byte big-endian records: offset, length, body offset, status). From an accepted .toc QuillPost
 * takes the read/replied/forwarded state and skips messages Eudora had deleted but not yet
 * compacted away. Without a usable .toc every message in the .mbx is imported.
 *
 * Eudora saved attachments to a folder and left a line in the message:
 * `Attachment Converted: "C:\Eudora\Attach\report.doc"`. When the attachment folder is part of
 * what was opened, such files are attached back to the message; otherwise they are listed.
 */
object Eudora {
    private val CONVERTED = Regex("""(?m)^Attachment Converted:\s*"?([^"\r\n]+?)"?\s*$""")

    data class TocEntry(val offset: Long, val length: Long, val state: Int, val seconds: Long?)

    /** Reads a .toc, returning entries only if they line up with the .mbx message starts. */
    fun readToc(toc: File, starts: Set<Long>, mbxLength: Long): List<TocEntry>? {
        if (!toc.isFile || toc.length() < 104) return null
        val b = toc.readBytes()
        fun be32(o: Int) = ((b.u8(o).toLong() shl 24) or (b.u8(o + 1).toLong() shl 16) or (b.u8(o + 2).toLong() shl 8) or b.u8(o + 3).toLong())
        val layouts = listOf(
            Triple(104, 218, false),
            Triple(278, 220, true)
        )
        for ((headerSize, recSize, bigEndian) in layouts) {
            if (b.size < headerSize + recSize) continue
            val out = ArrayList<TocEntry>()
            var o = headerSize
            var good = 0; var bad = 0
            while (o + recSize <= b.size) {
                val off = if (bigEndian) be32(o) else b.u32(o)
                val len = if (bigEndian) be32(o + 4) else b.u32(o + 4)
                val state = b.u8(o + 12)
                val secs = if (bigEndian) null else b.u32(o + 8).takeIf { it in 315_532_800L..4_102_444_800L }?.let { it * 1000 }
                if (off + len <= mbxLength && off in starts) good++ else bad++
                out += TocEntry(off, len, state, secs)
                o += recSize
            }
            if (good > 0 && good >= (good + bad) * 0.8) return out.filter { it.offset in starts }
        }
        return null
    }

    /** Eudora message states (Windows): 0 unread, 1 read, 2 replied, 3 forwarded, 4 redirected, 8 sent. */
    fun flagsForState(state: Int): MsgFlags = when (state) {
        0 -> MsgFlags()
        1 -> MsgFlags(seen = true)
        2 -> MsgFlags(seen = true, answered = true)
        3 -> MsgFlags(seen = true, forwarded = true)
        4 -> MsgFlags(seen = true, forwarded = true)
        5, 6, 7, 9, 10, 11 -> MsgFlags(seen = true, draft = true)
        else -> MsgFlags(seen = true)
    }

    /** A folder for one .mbx (+ .toc); [attachDirs] are folders where converted attachments may be found. */
    fun folder(mbx: File, name: String, attachDirs: List<File>, warnings: MutableList<String>): Pair<StoreFolder, FileBytes> {
        val fb = FileBytes(mbx)
        val ix = Mbox.index(fb)
        val toc = File(mbx.parentFile, mbx.nameWithoutExtension + ".toc").takeIf { it.isFile }
            ?: mbx.parentFile?.listFiles()?.firstOrNull { it.name.equals(mbx.nameWithoutExtension + ".toc", true) }
        val entries = toc?.let { readToc(it, ix.entries.map { e -> e.fromLineStart }.toSet(), fb.length) }
        if (toc != null && entries == null) warnings += "${mbx.name}: the table of contents (${toc.name}) is in a layout QuillPost does not know; every message in the file was imported and read status was not restored."
        val byStart = entries?.associateBy { it.offset }
        val list = if (byStart != null) ix.entries.filter { it.fromLineStart in byStart } else ix.entries
        if (byStart != null && list.size < ix.entries.size) warnings += "${mbx.name}: ${ix.entries.size - list.size} deleted messages that Eudora had not compacted away were skipped."
        val index = attachDirs.flatMap { d -> d.walk().filter { it.isFile }.toList() }.groupBy { it.name.lowercase() }
        val folder = StoreFolder(name, counter = { list.size }, lister = {
            list.asSequence().map { e ->
                val t = byStart?.get(e.fromLineStart)
                val flags = t?.let { flagsForState(it.state) } ?: Mbox.flagsOf(e)
                val notes = ArrayList<String>()
                Mbox.message(fb, ix, e, flags, notes) { raw -> resolveAttachments(raw, index, notes) }.let { m ->
                    StoreMessage(m.flags, m.internalDate ?: t?.seconds, m.keywords, notes) { m.raw() }
                }
            }
        })
        return folder to fb
    }

    /** Re-attaches files named in "Attachment Converted:" lines, when they can be found. */
    fun resolveAttachments(raw: ByteArray, files: Map<String, List<File>>, notes: MutableList<String>): ByteArray {
        val text = String(raw, Charsets.ISO_8859_1)
        val refs = CONVERTED.findAll(text).map { it.groupValues[1].trim() }.toList()
        if (refs.isEmpty()) return raw
        val found = ArrayList<File>()
        for (r in refs) {
            val base = r.substringAfterLast('\\').substringAfterLast('/').substringAfterLast(':')
            val f = files[base.lowercase()]?.firstOrNull()
            if (f != null) found += f else notes += "Attachment not found: $base"
        }
        if (found.isEmpty()) return raw
        return runCatching {
            val msg = ImportedMimeMessage(localSession, raw.inputStream())
            val mixed = jakarta.mail.internet.MimeMultipart("mixed")
            val bodyPart = jakarta.mail.internet.MimeBodyPart()
            val content = msg.content
            if (content is jakarta.mail.Multipart) bodyPart.setContent(content) else bodyPart.setContent(content, msg.contentType ?: "text/plain")
            mixed.addBodyPart(bodyPart)
            for (f in found) mixed.addBodyPart(jakarta.mail.internet.MimeBodyPart().apply {
                dataHandler = jakarta.activation.DataHandler(jakarta.mail.util.ByteArrayDataSource(f.readBytes(), mimeTypeFor(f.name)))
                fileName = f.name
                disposition = jakarta.mail.Part.ATTACHMENT
            })
            msg.setContent(mixed)
            msg.saveChanges()
            msg.bytes()
        }.getOrElse { notes += "Attachments could not be re-attached (${it.message})"; raw }
    }
}
