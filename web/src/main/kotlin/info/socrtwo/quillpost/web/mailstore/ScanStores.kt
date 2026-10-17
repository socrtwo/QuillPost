package info.socrtwo.quillpost.web.mailstore

import java.io.File

/**
 * Pegasus Mail folders and best-effort recovery from undocumented stores.
 *
 *  - Pegasus Mail .pmm: a 128-byte header (the folder's long name, NUL-padded) followed by the
 *    messages as received, each ended by Ctrl-Z (0x1A). The .pmi index next to it is not needed.
 *    New mail lives in separate .cnm files (one message each, read as plain messages).
 *  - The Bat! .tbb and other unknown binary stores: the messages are located by scanning for blocks
 *    of RFC 5322 header lines (at least three header fields including From: or Date:, after a
 *    binary record header). Status, flags and the folder hierarchy cannot be recovered this way.
 */
object ScanStores {
    fun pegasusName(file: File): String? {
        val h = head(file, 128)
        if (h.size < 128) return null
        val end = h.indexOf(0).let { if (it < 0) 128 else it }
        val s = String(h, 0, end, Charsets.ISO_8859_1).trim()
        return s.takeIf { it.isNotEmpty() && it.all { c -> c >= ' ' && c.code < 0x7F } }
    }

    fun looksLikePmm(head: ByteArray): Boolean {
        if (head.size < 160) return false
        val text = String(head, 128, minOf(head.size - 128, 600), Charsets.ISO_8859_1)
        return Regex("^(Received|Return-Path|From|Date|Message-ID|Subject|To|X-[A-Za-z-]+|MIME-Version):", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE)).containsMatchIn(text.take(400))
    }

    /** Pegasus .pmm folder: messages after the 128-byte header, separated by Ctrl-Z. */
    fun pegasusFolder(file: File, name: String): Pair<StoreFolder, FileBytes> {
        val fb = FileBytes(file)
        val list by lazy {
            val out = ArrayList<Pair<Long, Int>>()
            var start = 128L
            var p = 128L
            val buf = 1 shl 20
            while (p < fb.length) {
                val chunk = fb.readUpTo(p, buf)
                for (i in chunk.indices) if (chunk[i] == 0x1A.toByte()) {
                    val at = p + i
                    if (at - start > 2) out += start to (at - start).toInt()
                    start = at + 1
                }
                p += chunk.size
                if (chunk.isEmpty()) break
            }
            if (fb.length - start > 2) out += start to (fb.length - start).toInt()
            out.filter { (o, n) -> String(fb.readUpTo(o, minOf(n, 300)), Charsets.ISO_8859_1).trimStart().contains(':') }
        }
        val f = StoreFolder(pegasusName(file) ?: name, counter = { list.size }, lister = {
            list.asSequence().map { (o, n) -> StoreMessage(MsgFlags(seen = true)) { trimLeadingBlank(fb.read(o, n)) } }
        })
        return f to fb
    }

    private fun trimLeadingBlank(b: ByteArray): ByteArray {
        var i = 0
        while (i < b.size && (b[i] == '\r'.code.toByte() || b[i] == '\n'.code.toByte() || b[i] == 0.toByte())) i++
        return if (i == 0) b else b.copyOfRange(i, b.size)
    }

    private val HEADER = Regex("^([A-Za-z][A-Za-z0-9-]{1,40}):[ \\t]")

    /** Finds messages in an unknown binary store by their header blocks (The Bat! .tbb, damaged files). */
    fun scanFolder(file: File, name: String): Pair<StoreFolder, FileBytes> {
        val fb = FileBytes(file)
        val list by lazy { scan(fb) }
        val f = StoreFolder(name, counter = { list.size }, lister = { list.asSequence().map { (o, n) -> StoreMessage(MsgFlags(seen = true)) { fb.read(o, n) } } })
        return f to fb
    }

    internal fun scan(fb: FileBytes): List<Pair<Long, Int>> {
        val starts = ArrayList<Long>()
        val ends = ArrayList<Long>()
        val lines = LineReader(fb, 0, fb.length)
        var run = 0
        var runStart = -1L
        var sawKey = false
        var prevBinary = true
        var inMessage = false
        var lastTextEnd = 0L
        while (true) {
            val l = lines.next() ?: break
            val t = l.prefix
            val binary = t.any { it.code < 9 || (it.code in 14..31 && it.code != 27) }
            if (binary) {
                if (inMessage) { ends += binaryCut(fb, l.start, t); inMessage = false }
                prevBinary = true; run = 0; sawKey = false
                continue
            }
            if (!inMessage) {
                if (HEADER.containsMatchIn(t)) {
                    if (run == 0) runStart = l.start
                    run++
                    val k = t.substringBefore(':').lowercase()
                    if (k == "from" || k == "date" || k == "received" || k == "return-path") sawKey = true
                    if (run >= 3 && sawKey) { starts += runStart; inMessage = true; run = 0; sawKey = false }
                } else if (!(t.startsWith(" ") || t.startsWith("\t")) || run == 0) { run = 0; sawKey = false }
            }
            lastTextEnd = l.next
            prevBinary = false
        }
        if (inMessage) ends += lastTextEnd
        return starts.indices.mapNotNull { i -> val e = ends.getOrElse(i) { fb.length }; val n = e - starts[i]; if (n in 20..Int.MAX_VALUE.toLong()) starts[i] to n.toInt() else null }
    }

    /** Where text ends inside a line that contains binary bytes (the next record header). */
    private fun binaryCut(fb: FileBytes, lineStart: Long, prefix: String): Long {
        val i = prefix.indexOfFirst { it.code < 9 || (it.code in 14..31 && it.code != 27) }
        return lineStart + maxOf(0, i)
    }
}
