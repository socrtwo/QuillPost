package info.socrtwo.quillpost.web.mailstore

import info.socrtwo.quillpost.web.MessageImport
import java.io.File

/**
 * Opens any supported mail store — a single file, or a folder holding one (Maildir, MH, Apple Mail
 * .mbox bundles, Thunderbird/Netscape profiles with .sbd sub-folders, Eudora folders, Outlook
 * Express stores, Windows Live Mail / Windows Mail folders of .eml files, Pegasus Mail, The Bat!,
 * or any mixture of mailbox files) — as a [MailStore].
 */
object MailStores {

    private class Ctx(val root: File, val warnings: MutableList<String>, val closers: MutableList<() -> Unit>, val cancelled: () -> Boolean) {
        val attachDirs: List<File> by lazy {
            root.walk().maxDepth(6).filter { it.isDirectory && (it.name.lowercase().startsWith("attach") || it.name.equals("Embedded", true)) }.toList()
        }
    }

    /** Opens [path] (file or directory). Throws [MailStoreException] with an explanation when it cannot be read. */
    fun open(path: File, displayName: String = path.name, cancelled: () -> Boolean = { false }): MailStore {
        if (path.isDirectory) return openDir(path, displayName, cancelled)
        val det = Sniffer.sniffFile(path, displayName)
        if (!det.supported) throw MailStoreException(det.note.ifBlank { "${det.label} cannot be opened." })
        val warnings = newWarnings()
        val closers = ArrayList<() -> Unit>()
        val name = baseName(displayName)
        return when (det.format) {
            "pst", "ost", "pab" -> PstStore.open(path, name)
            "mbox" -> {
                val toc = path.parentFile?.listFiles()?.firstOrNull { it.name.equals(path.nameWithoutExtension + ".toc", true) }
                if (toc != null || det.program == "Eudora") {
                    val ctx = Ctx(path.parentFile ?: path, warnings, closers, cancelled)
                    val (f, fb) = Eudora.folder(path, name, ctx.attachDirs, warnings)
                    store(det.copy(format = "eudora", label = "Eudora mailbox (.mbx${if (toc != null) " + .toc" else ""})", program = "Eudora"), name, listOf(f), warnings) { fb.close() }
                } else Mbox.open(path, name, cancelled)
            }
            else -> {
                val f = fileFolders(path, name, Ctx(path.parentFile ?: path, warnings, closers, cancelled))
                    ?: throw MailStoreException("${det.label} holds no mail QuillPost can read.")
                store(det, name, f, warnings) { closers.forEach { runCatching { it() } } }
            }
        }
    }

    private fun store(det: Detection, name: String, folders: List<StoreFolder>, warnings: MutableList<String>, close: () -> Unit): MailStore {
        val root = StoreFolder(name)
        folders.forEach { root.add(it) }
        return MailStore(det, root, close, warnings)
    }

    fun baseName(n: String): String {
        val b = n.substringAfterLast('/').substringAfterLast('\\')
        val lower = b.lowercase()
        for (e in listOf(".partial.emlx", ".mbox", ".mbx", ".mbs", ".dbx", ".pst", ".ost", ".pab", ".pmm", ".tbb", ".fol", ".sbd", ".eml", ".emlx", ".msg", ".zip", ".tgz", ".tar.gz", ".gz", ".tar", ".imapmbox"))
            if (lower.endsWith(e) && b.length > e.length) return b.dropLast(e.length)
        return b
    }

    /** One mailbox file inside a larger store, as folder(s); null when it is not mail. */
    private fun fileFolders(file: File, name: String, ctx: Ctx): List<StoreFolder>? {
        val det = runCatching { Sniffer.sniffFile(file, file.name) }.getOrNull() ?: return null
        if (!det.supported) { if (det.category == "mailbox") ctx.warnings += "${file.name}: ${det.note}"; return null }
        fun keep(fb: FileBytes) { ctx.closers += { fb.close() } }
        return when (det.format) {
            "pst", "ost", "pab" -> runCatching { PstStore.open(file, name) }.onFailure { ctx.warnings += "${file.name}: ${it.message}" }.getOrNull()?.let { s ->
                ctx.closers += { s.close() }; ctx.warnings += s.warnings
                listOf(StoreFolder(name).also { f -> s.root.children.forEach { f.add(it) } })
            }
            "mbox" -> {
                val hasToc = file.parentFile?.listFiles()?.any { it.name.equals(file.nameWithoutExtension + ".toc", true) } == true
                if (hasToc || det.program == "Eudora") { val (f, fb) = Eudora.folder(file, name, ctx.attachDirs, ctx.warnings); keep(fb); listOf(f) }
                else { val fb = FileBytes(file); keep(fb); val ix = Mbox.index(fb, ctx.cancelled); listOf(Mbox.folder(fb, ix, name)) }
            }
            "dbx" -> { val (f, fb) = Dbx.folder(file, name, ctx.warnings); keep(fb); listOf(f) }
            "oe4" -> { val (f, fb) = Dbx.oe4Folder(file, name); keep(fb); listOf(f) }
            "pegasus" -> { val (f, fb) = ScanStores.pegasusFolder(file, name); keep(fb); listOf(f) }
            "thebat" -> { ctx.warnings += "${file.name}: read best-effort (The Bat! format is undocumented)."; val (f, fb) = ScanStores.scanFolder(file, name); keep(fb); listOf(f) }
            "eml", "emlx", "msg", "oft", "tnef" -> listOf(StoreFolder(name, counter = { 1 }, lister = { sequenceOf(looseMessage(file, null)).filterNotNull() }))
            else -> null
        }
    }

    // --------------------------------------------------------------------------------- folders

    private fun openDir(dir: File, displayName: String, cancelled: () -> Boolean): MailStore {
        val warnings = newWarnings()
        val closers = ArrayList<() -> Unit>()
        val ctx = Ctx(dir, warnings, closers, cancelled)
        val det = Sniffer.sniffDir(dir)
        if (!det.supported) throw MailStoreException(det.note)
        val top = dirFolder(dir, baseName(displayName), ctx, 0) ?: throw MailStoreException("No mail was found in this folder.")
        // A folder that only wraps one mailbox shows that mailbox's folders at the top.
        val root = StoreFolder(top.name)
        if (top.count == 0 && top.children.isNotEmpty()) top.children.forEach { root.add(it) } else root.add(top)
        return MailStore(det, root, { closers.forEach { runCatching { it() } } }, warnings)
    }

    private val INDEX_EXT = setOf("msf", "toc", "idx", "pmi", "pmx", "ind", "plist", "mh_sequences", "lock", "dat", "db", "sqlite", "ini", "txt", "log", "json", "xml", "cfg", "pce", "lnk", "edb", "chk", "jrs", "jcp", "sdb", "tbk", "tbn", "ffs_db")

    private fun isMaildir(d: File) = File(d, "cur").isDirectory || File(d, "new").isDirectory

    private fun dirFolder(dir: File, name: String, ctx: Ctx, depth: Int): StoreFolder? {
        if (depth > 16 || ctx.cancelled()) return null
        if (isMaildir(dir)) return maildir(dir, name, ctx, depth)
        val kids = dir.listFiles()?.sortedBy { it.name.lowercase() } ?: return null
        if (kids.any { it.isFile && it.name.endsWith(".dbx", true) }) {
            val s = Dbx.openStore(dir, name, ctx.warnings) { root -> MailStore(Detection("mailbox", "oe-store", "Outlook Express store"), root, {}, ctx.warnings) }
            ctx.closers += { s.close() }
            val f = StoreFolder(name)
            s.root.children.forEach { f.add(it) }
            return f.takeIf { it.children.isNotEmpty() }
        }
        val isBundle = dir.name.endsWith(".mbox", true) || dir.name.endsWith(".imapmbox", true)
        val loose = ArrayList<File>()
        val mh = mhSequences(dir)
        val children = ArrayList<StoreFolder>()
        val consumed = HashSet<String>()
        for (f in kids.filter { it.isFile }) {
            val lower = f.name.lowercase()
            if (f.name.startsWith(".") || f.length() == 0L) continue
            when {
                lower.endsWith(".emlx") || lower.endsWith(".eml") || lower.endsWith(".msg") || lower.endsWith(".cnm") || lower.endsWith(".oft") -> loose += f
                mh != null && f.name.all { it.isDigit() } -> loose += f
                isBundle && lower == "mbox" -> {
                    val fb = FileBytes(f); ctx.closers += { fb.close() }
                    val ix = Mbox.index(fb, ctx.cancelled)
                    children += Mbox.folder(fb, ix, baseName(dir.name))
                }
                lower.substringAfterLast('.', "") in INDEX_EXT && lower.contains('.') -> {}
                lower == "info.plist" || lower == "table_of_contents" -> {}
                else -> {
                    val sub = fileFolders(f, baseName(f.name), ctx) ?: continue
                    // Thunderbird/Netscape: "Inbox" + "Inbox.sbd/" holds Inbox's sub-folders.
                    val sbd = kids.firstOrNull { it.isDirectory && it.name.equals(f.name + ".sbd", true) }
                    if (sbd != null && sub.size == 1) { consumed += sbd.name; dirFolder(sbd, baseName(f.name), ctx, depth + 1)?.let { s -> (if (s.count == 0) s.children else listOf(s)).forEach { sub[0].add(it) } } }
                    children += sub
                }
            }
        }
        for (d in kids.filter { it.isDirectory && it.name !in consumed }) {
            val ln = d.name.lowercase()
            if (ln in setOf("cur", "new", "tmp", "attachments", "data") && isBundle) continue
            if (ln == "messages" && isBundle) { d.listFiles()?.filter { it.isFile && it.name.endsWith(".emlx", true) }?.let { loose += it }; continue }
            if (ln.startsWith("attach") || ln == "embedded" || ln.startsWith(".")) continue
            if (isBundle && !d.name.endsWith(".mbox", true)) {
                // Apple Mail 3+: Folder.mbox/<UUID>/Data/…/Messages/*.emlx
                d.walk().maxDepth(8).filter { it.isFile && it.name.endsWith(".emlx", true) && !it.path.substringAfter(d.path).contains(".mbox", true) }.forEach { loose += it }
                continue
            }
            dirFolder(d, baseName(d.name), ctx, depth + 1)?.let { children += it }
        }
        if (loose.isEmpty() && children.isEmpty()) return null
        val sortedLoose = loose.sortedWith(compareBy({ it.name.substringBefore('.').toLongOrNull() ?: Long.MAX_VALUE }, { it.name }))
        val folder = StoreFolder(name, counter = { sortedLoose.size }, lister = {
            sortedLoose.asSequence().mapNotNull { f ->
                runCatching { looseMessage(f, mh) }.onFailure { ctx.warnings += "${f.name}: ${it.message}" }.getOrNull()
            }
        })
        if (loose.isEmpty() && children.size == 1 && children[0].name == name) return children[0]
        children.forEach { folder.add(it) }
        return folder
    }

    /** A message stored as its own file (.eml, .msg, .emlx, MH, Pegasus .cnm). */
    private fun looseMessage(f: File, mh: Map<String, Set<Int>>?): StoreMessage? {
        val lower = f.name.lowercase()
        if (lower.endsWith(".emlx")) {
            val bundleData = generateSequence(f.parentFile) { it.parentFile }.take(3).map { File(it, "Attachments") }.firstOrNull { it.isDirectory }
            return Emlx.message(f, bundleData)
        }
        val n = f.name.toIntOrNull()
        if (mh != null && n != null) {
            val flags = MsgFlags(seen = n !in mh["unseen"].orEmpty(), flagged = n in mh["flagged"].orEmpty(), answered = n in mh["replied"].orEmpty() || n in mh["answered"].orEmpty(), forwarded = n in mh["forwarded"].orEmpty())
            return StoreMessage(flags, f.lastModified()) { f.readBytes() }
        }
        if (lower.endsWith(".msg") || lower.endsWith(".oft")) return StoreMessage(MsgFlags(seen = true), null) { MessageImport.parse(f.readBytes(), f.name).mime }
        val unread = lower.endsWith(".cnm")
        return StoreMessage(MsgFlags(seen = !unread), null) { MessageImport.parse(f.readBytes(), f.name).mime }
    }

    /** nmh / Claws Mail .mh_sequences: "unseen: 1-3 7", "flagged: 2". */
    private fun mhSequences(dir: File): Map<String, Set<Int>>? {
        val seq = File(dir, ".mh_sequences")
        val numeric = dir.listFiles()?.count { it.isFile && it.name.isNotEmpty() && it.name.all { c -> c.isDigit() } } ?: 0
        if (!seq.isFile && numeric == 0) return null
        if (!seq.isFile) {
            // Numeric files are MH only if they look like messages.
            val first = dir.listFiles()?.firstOrNull { it.isFile && it.name.all { c -> c.isDigit() } } ?: return null
            if (!Sniffer.looksLikeMessage(String(head(first, 4096), Charsets.ISO_8859_1))) return null
            return emptyMap()
        }
        val out = HashMap<String, Set<Int>>()
        for (line in seq.readLines()) {
            val k = line.substringBefore(':').trim().lowercase()
            val nums = HashSet<Int>()
            for (tok in line.substringAfter(':').trim().split(Regex("\\s+")).filter { it.isNotBlank() }) {
                val a = tok.substringBefore('-').toIntOrNull() ?: continue
                val b = tok.substringAfter('-', tok).toIntOrNull() ?: a
                for (i in a..minOf(b, a + 1_000_000)) nums += i
            }
            out[k] = nums
        }
        return out
    }

    // --------------------------------------------------------------------------------- Maildir

    /** Maildir (and Maildir++ / KMail sub-folders). Flags come from ":2,FRS…", keywords from dovecot-keywords. */
    private fun maildir(dir: File, name: String, ctx: Ctx, depth: Int): StoreFolder {
        val keywords = File(dir, "dovecot-keywords").takeIf { it.isFile }?.readLines()?.mapNotNull { l ->
            val i = l.trim().substringBefore(' ').toIntOrNull() ?: return@mapNotNull null
            if (i in 0..25) ('a' + i) to l.trim().substringAfter(' ') else null
        }?.toMap().orEmpty()
        val files = listOf("cur", "new").flatMap { sub -> (File(dir, sub).listFiles()?.filter { it.isFile && !it.name.startsWith(".") } ?: emptyList()).map { it to (sub == "new") } }
            .sortedBy { it.first.lastModified() }
        val folder = StoreFolder(name, counter = { files.size }, lister = {
            files.asSequence().map { (f, isNew) ->
                val info = f.name.substringAfterLast(":2,", "").ifEmpty { f.name.substringAfterLast("!2,", "") }
                val flags = MsgFlags(seen = !isNew && 'S' in info, answered = 'R' in info, flagged = 'F' in info, deleted = 'T' in info, draft = 'D' in info, forwarded = 'P' in info)
                val kw = info.filter { it in 'a'..'z' }.mapNotNull { keywords[it] }
                StoreMessage(flags, f.lastModified(), kw) { f.readBytes() }
            }
        })
        folder.unread = files.count { (f, isNew) -> isNew || 'S' !in f.name.substringAfterLast(":2,", "") }
        // Maildir++: ".Sent", ".Archive.2020" (dot-separated hierarchy) inside the top Maildir.
        val dotted = (dir.listFiles() ?: emptyArray()).filter { it.isDirectory && it.name.startsWith(".") && it.name.length > 1 && isMaildir(it) && !it.name.endsWith(".directory") }.sortedBy { it.name }
        val byPath = HashMap<String, StoreFolder>()
        fun parentFor(parts: List<String>): StoreFolder {
            if (parts.isEmpty()) return folder
            val key = parts.joinToString(".")
            return byPath.getOrPut(key) { StoreFolder(decodeImapUtf7(parts.last())).also { parentFor(parts.dropLast(1)).add(it) } }
        }
        for (d in dotted) {
            val parts = d.name.substring(1).split('.')
            val sub = maildir(d, decodeImapUtf7(parts.last()), ctx, depth + 1)
            val existing = byPath[parts.joinToString(".")]
            if (existing != null) {
                // A placeholder made for a child that was listed first: move its children under the real folder.
                existing.children.forEach { sub.add(it) }
                val parent = parentFor(parts.dropLast(1))
                parent.children[parent.children.indexOf(existing)] = sub
            } else parentFor(parts.dropLast(1)).add(sub)
            byPath[parts.joinToString(".")] = sub
        }
        // KMail / Evolution style: plain sub-directories that are Maildirs, and ".Name.directory" holding Name's children.
        for (d in (dir.listFiles() ?: emptyArray()).filter { it.isDirectory && !it.name.startsWith(".") && it.name !in setOf("cur", "new", "tmp") }.sortedBy { it.name }) {
            if (isMaildir(d)) {
                val sub = maildir(d, d.name, ctx, depth + 1)
                File(dir, ".${d.name}.directory").takeIf { it.isDirectory }?.listFiles()?.filter { it.isDirectory && isMaildir(it) }?.sortedBy { it.name }?.forEach { sub.add(maildir(it, it.name, ctx, depth + 2)) }
                folder.add(sub)
            } else dirFolder(d, d.name, ctx, depth + 1)?.let { folder.add(it) }
        }
        return folder
    }

    /** Folder names on IMAP servers are "modified UTF-7" (RFC 3501): "Entw&APw-rfe" → "Entwürfe". */
    fun decodeImapUtf7(s: String): String {
        if (!s.contains('&')) return s
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '&') {
                val j = s.indexOf('-', i + 1)
                if (j < 0) { out.append(s.substring(i)); break }
                if (j == i + 1) out.append('&') else {
                    val b64 = s.substring(i + 1, j).replace(',', '/')
                    val padded = b64 + "=".repeat((4 - b64.length % 4) % 4)
                    out.append(runCatching { String(java.util.Base64.getDecoder().decode(padded), Charsets.UTF_16BE) }.getOrDefault(s.substring(i, j + 1)))
                }
                i = j + 1
            } else { out.append(c); i++ }
        }
        return out.toString()
    }
}
