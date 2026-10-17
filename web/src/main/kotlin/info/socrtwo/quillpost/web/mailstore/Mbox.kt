package info.socrtwo.quillpost.web.mailstore

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Calendar
import java.util.TimeZone

/**
 * The mbox family (RFC 4155 and its dialects), read in one streaming pass that records where each
 * message starts and ends:
 *
 *  - mboxo / mboxrd: messages separated by "From " lines; body lines that began with "From " are
 *    quoted as ">From " (mboxrd also quotes ">From " as ">>From "). One level is removed on read.
 *  - mboxcl / mboxcl2 (SVR4, Solaris mail, old mutt): a Content-Length header gives the body size,
 *    so body lines may start with "From " unquoted (cl2). When Content-Length is present and lands
 *    exactly on the next separator (or the end of the file) it is trusted.
 *  - Eudora .mbx: separators are "From ???@??? Mon Jan 01 00:00:00 2001".
 *  - Thunderbird / Netscape / SeaMonkey / Mozilla Mail: X-Mozilla-Status (read, replied, flagged,
 *    deleted, forwarded), X-Mozilla-Status2 labels and X-Mozilla-Keys tags. The .msf index (Mork)
 *    is ignored — everything it holds is rebuilt from the messages.
 *  - Pine / Alpine, mutt, Evolution, KMail, Apple Mail 1.x, Opera (.mbs), Zimbra and others:
 *    Status (R, O) and X-Status (A, F, D, T) headers; X-Keywords / Keywords tags.
 *  - Gmail Takeout: X-Gmail-Labels become folders (Inbox, Sent, Drafts, Spam, Trash, user labels)
 *    plus read/starred status and the remaining labels as keywords.
 */
object Mbox {
    /** A separator line: "From " + sender + an asctime date (or Eudora's ???@??? form). */
    private val SEPARATOR = Regex("""^From (\?\?\?@\?\?\?|\S+)\s+.*\d{1,2}:\d{2}""")
    private val HEADER_LINE = Regex("""^[!-9;-~]+:""")

    internal class Entry(val fromLineStart: Long, val start: Long) {
        var end: Long = start
        var fromLine: String = ""
        var byContentLength = false
        var status = ""
        var xStatus = ""
        var mozStatus = -1
        var mozStatus2 = -1L
        val keywords = ArrayList<String>()
        var gmailLabels: String? = null
        var dateHeader: String? = null
        var contentLength: Long? = null
    }

    internal class Index(val entries: List<Entry>, val quoted: Boolean, val unquotedFromInBody: Boolean, val contentLength: Boolean, val rdQuoting: Boolean, val eudora: Boolean, val mozilla: Boolean, val gmail: Boolean, val statusHeaders: Boolean)

    /** True when [head] looks like an mbox (a separator line first, ignoring blank lines). */
    fun looksLike(head: ByteArray): Boolean {
        var i = 0
        while (i < head.size && (head[i] == '\n'.code.toByte() || head[i] == '\r'.code.toByte())) i++
        val line = String(head, i, minOf(400, head.size - i), Charsets.ISO_8859_1).substringBefore('\n').trimEnd('\r')
        return SEPARATOR.containsMatchIn(line)
    }

    /** Reads every message boundary of [file]. */
    internal fun index(fb: FileBytes, cancelled: () -> Boolean = { false }): Index {
        val lines = LineReader(fb, 0, fb.length)
        val entries = ArrayList<Entry>()
        var cur: Entry? = null
        var inHeaders = false
        var lastHeader = ""
        var prevBlank = true
        var prevBlankStart = -1L
        var quoted = false; var rd = false; var unquotedFrom = false; var cl = false
        var eudora = false; var moz = false; var gmail = false; var statusH = false
        var n = 0
        while (true) {
            val line = lines.next() ?: break
            if ((++n and 0xFFFF) == 0 && cancelled()) throw MailStoreException("Cancelled")
            val text = line.prefix
            val isFrom = text.startsWith("From ") && SEPARATOR.containsMatchIn(text)
            if (isFrom && (prevBlank || cur == null || lines.peekIsHeader())) {
                cur?.let { it.end = if (prevBlank && prevBlankStart >= it.start) prevBlankStart else line.start; entries += it }
                val e = Entry(line.start, line.next).also { it.fromLine = text }
                if (text.startsWith("From ???@???")) eudora = true
                cur = e
                inHeaders = true; lastHeader = ""; prevBlank = false
                continue
            }
            val blank = line.length == 0L
            if (cur != null && inHeaders) {
                if (blank) {
                    inHeaders = false
                    // mboxcl/cl2: trust Content-Length when it lands on the next separator or the end.
                    val clen = cur.contentLength
                    if (clen != null) {
                        val bodyStart = line.next
                        val expected = bodyStart + clen
                        if (expected <= fb.length && separatorAt(fb, expected)) {
                            cur.byContentLength = true; cl = true
                            // mboxcl2 leaves "From " lines in bodies unquoted; mboxcl quotes them.
                            if (!unquotedFrom && clen in 1..(4L shl 20)) {
                                val body = String(fb.read(bodyStart, clen.toInt()), Charsets.ISO_8859_1)
                                if (body.startsWith("From ") || body.contains("\nFrom ")) unquotedFrom = true
                                if (body.startsWith(">From ") || body.contains("\n>From ")) quoted = true
                            }
                            cur.end = expected
                            entries += cur
                            cur = null
                            lines.seek(skipNewlines(fb, expected))
                            prevBlank = true; prevBlankStart = expected
                            continue
                        }
                    }
                } else if ((text.startsWith(" ") || text.startsWith("\t")) && lastHeader.isNotEmpty()) {
                    cur.append(lastHeader, text.trim())
                } else if (HEADER_LINE.containsMatchIn(text)) {
                    val name = text.substringBefore(':').lowercase()
                    val value = text.substringAfter(':').trim()
                    lastHeader = name
                    when (name) {
                        "status" -> { cur.status = value; statusH = true }
                        "x-status" -> { cur.xStatus = value; statusH = true }
                        "x-mozilla-status" -> { cur.mozStatus = value.toIntOrNull(16) ?: -1; moz = true }
                        "x-mozilla-status2" -> cur.mozStatus2 = value.toLongOrNull(16) ?: -1L
                        "x-mozilla-keys", "x-keywords", "keywords", "x-label" -> cur.keywords += value.split(Regex("[,\\s]+")).filter { it.isNotBlank() }
                        "x-gmail-labels" -> { cur.gmailLabels = value; gmail = true }
                        "content-length" -> cur.contentLength = value.toLongOrNull()
                        "date" -> cur.dateHeader = value
                        else -> lastHeader = ""
                    }
                    if (name in setOf("x-mozilla-keys", "x-keywords", "keywords", "x-label")) lastHeader = ""
                }
            } else if (cur != null) {
                if (text.startsWith(">From ")) quoted = true
                if (text.startsWith(">>From ")) rd = true
                if (text.startsWith("From ")) unquotedFrom = true
            }
            if (blank) prevBlankStart = line.start
            prevBlank = blank
        }
        cur?.let { it.end = if (prevBlank && prevBlankStart >= it.start) prevBlankStart else fb.length; entries += it }
        return Index(entries, quoted, unquotedFrom, cl, rd, eudora, moz, gmail, statusH)
    }

    private fun Entry.append(header: String, more: String) {
        when (header) {
            "x-gmail-labels" -> gmailLabels = (gmailLabels ?: "") + more
        }
    }

    private fun separatorAt(fb: FileBytes, pos: Long): Boolean {
        if (pos == fb.length) return true
        val p = skipNewlines(fb, pos)
        if (p >= fb.length) return true
        val b = fb.readUpTo(p, 200)
        val s = String(b, Charsets.ISO_8859_1).substringBefore('\n')
        return s.startsWith("From ") && SEPARATOR.containsMatchIn(s)
    }

    private fun skipNewlines(fb: FileBytes, pos: Long): Long {
        var p = pos
        val b = fb.readUpTo(pos, 4)
        var i = 0
        while (i < b.size && (b[i] == '\n'.code.toByte() || b[i] == '\r'.code.toByte())) { i++; p++ }
        return p
    }

    /** Describes the dialect from what the index pass saw. */
    internal fun variantOf(ix: Index): String = when {
        ix.contentLength && ix.unquotedFromInBody -> "mboxcl2"
        ix.contentLength -> "mboxcl"
        ix.rdQuoting -> "mboxrd"
        else -> if (ix.quoted) "mboxo/mboxrd" else "mboxo"
    }

    internal fun programOf(ix: Index): String = when {
        ix.gmail -> "Gmail (Google Takeout)"
        ix.eudora -> "Eudora"
        ix.mozilla -> "Thunderbird / Netscape / SeaMonkey"
        ix.statusHeaders -> "Unix mail (Pine/Alpine, mutt, Evolution, KMail…)"
        else -> ""
    }

    /** Opens [file] as a mail store. Gmail Takeout files become one folder per label. */
    fun open(file: File, displayName: String, cancelled: () -> Boolean = { false }): MailStore {
        val fb = FileBytes(file)
        val ix = index(fb, cancelled)
        val det = Detection("mailbox", "mbox", "mbox mailbox", programOf(ix), variantOf(ix))
        val root = StoreFolder(displayName)
        if (ix.gmail) {
            for ((label, entries) in gmailFolders(ix.entries)) {
                root.add(StoreFolder(label, counter = { entries.size }, lister = { entries.asSequence().map { message(fb, ix, it) } }))
            }
            return MailStore(det.copy(label = "Gmail Takeout mailbox (mbox with X-Gmail-Labels)"), root, { fb.close() })
        }
        val folder = root.add(folder(fb, ix, displayName))
        folder.unread = ix.entries.count { !flagsOf(it).seen }
        return MailStore(det, root, { fb.close() })
    }

    /** A single folder for an mbox file inside a larger store (Thunderbird profile, Eudora folder…). */
    internal fun folder(fb: FileBytes, ix: Index, name: String, extra: (Entry) -> Pair<MsgFlags?, List<String>> = { null to emptyList() }): StoreFolder =
        StoreFolder(name, counter = { ix.entries.size }, lister = { ix.entries.asSequence().map { e -> val (f, n) = extra(e); message(fb, ix, e, f, n) } })

    internal fun message(fb: FileBytes, ix: Index, e: Entry, overrideFlags: MsgFlags? = null, notes: List<String> = emptyList(), transform: ((ByteArray) -> ByteArray)? = null): StoreMessage {
        val flags = overrideFlags ?: flagsOf(e)
        val kw = keywordsOf(e)
        return StoreMessage(flags, fromLineDate(e.fromLine), kw, notes) {
            val len = e.end - e.start
            if (len > Int.MAX_VALUE - 16) throw MailStoreException("A message larger than 2 GB cannot be imported")
            val raw = fb.read(e.start, len.toInt())
            val body = if (e.byContentLength && ix.unquotedFromInBody) raw else unquote(raw)
            transform?.invoke(body) ?: body
        }
    }

    /** Removes one level of ">From " quoting at line starts. */
    fun unquote(raw: ByteArray): ByteArray {
        // Fast path: nothing quoted.
        var found = false
        var i = 0
        while (i < raw.size) {
            if ((i == 0 || raw[i - 1] == '\n'.code.toByte()) && raw[i] == '>'.code.toByte()) {
                var j = i
                while (j < raw.size && raw[j] == '>'.code.toByte()) j++
                if (j + 5 <= raw.size && String(raw, j, 5, Charsets.ISO_8859_1) == "From ") { found = true; break }
            }
            i++
        }
        if (!found) return raw
        val out = ByteArrayOutputStream(raw.size)
        var lineStart = true
        i = 0
        while (i < raw.size) {
            val b = raw[i]
            if (lineStart && b == '>'.code.toByte()) {
                var j = i
                while (j < raw.size && raw[j] == '>'.code.toByte()) j++
                if (j + 5 <= raw.size && String(raw, j, 5, Charsets.ISO_8859_1) == "From ") { i++; lineStart = false; continue }
            }
            out.write(b.toInt())
            lineStart = b == '\n'.code.toByte()
            i++
        }
        return out.toByteArray()
    }

    /** Writes [raw] as one mboxrd entry (separator, quoted body, trailing blank line). */
    fun writeEntry(out: java.io.OutputStream, raw: ByteArray, date: Long?, sender: String = "MAILER-DAEMON"): Pair<Long, Long> {
        val sep = "From ${sender.ifBlank { "MAILER-DAEMON" }.replace(Regex("\\s+"), "")} ${asctime(date ?: System.currentTimeMillis())}\n".toByteArray(Charsets.ISO_8859_1)
        out.write(sep)
        var written = 0L
        var lineStart = true
        var i = 0
        val text = raw
        while (i < text.size) {
            if (lineStart) {
                var j = i
                while (j < text.size && text[j] == '>'.code.toByte()) j++
                if (j + 5 <= text.size && String(text, j, 5, Charsets.ISO_8859_1) == "From ") { out.write('>'.code); written++ }
            }
            val b = text[i]
            if (b == '\r'.code.toByte() && i + 1 < text.size && text[i + 1] == '\n'.code.toByte()) { i++; continue } // store LF line ends
            out.write(b.toInt()); written++
            lineStart = b == '\n'.code.toByte()
            i++
        }
        if (!lineStart) { out.write('\n'.code); written++ }
        out.write('\n'.code)
        return sep.size.toLong() to written
    }

    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val ASCTIME = Regex("""(?i)\b(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\s+(\d{1,2})\s+(\d{1,2}):(\d{2})(?::(\d{2}))?(?:\s+([A-Z]{2,5}|[+-]\d{4}))?\s+(\d{4})(?:\s+([+-]\d{4}))?""")

    /** The arrival time written on the separator line ("From x Thu Sep 24 09:12:00 2026"), as UTC. */
    fun fromLineDate(fromLine: String): Long? {
        val m = ASCTIME.find(fromLine) ?: return null
        val month = MONTHS.indexOf(m.groupValues[1].lowercase())
        val zone = (m.groupValues[6].takeIf { it.startsWith("+") || it.startsWith("-") } ?: m.groupValues[8]).takeIf { it.isNotBlank() }
        val cal = Calendar.getInstance(TimeZone.getTimeZone(if (zone != null) "GMT$zone" else "UTC"))
        cal.clear()
        cal.set(m.groupValues[7].toInt(), month, m.groupValues[2].toInt(), m.groupValues[3].toInt(), m.groupValues[4].toInt(), m.groupValues[5].toIntOrNull() ?: 0)
        val year = m.groupValues[7].toInt()
        if (year < 1970 || year > 2100 || (year == 2001 && month == 0 && m.groupValues[2] == "1")) return null // placeholder dates
        return cal.timeInMillis
    }

    fun asctime(ms: Long): String {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = ms }
        val days = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        val mon = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        return "%s %s %2d %02d:%02d:%02d %d".format(days[c.get(Calendar.DAY_OF_WEEK) - 1], mon[c.get(Calendar.MONTH)], c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND), c.get(Calendar.YEAR))
    }

    internal fun flagsOf(e: Entry): MsgFlags {
        var f = MsgFlags()
        if (e.mozStatus >= 0) {
            val s = e.mozStatus
            f = f.copy(seen = s and 0x0001 != 0, answered = s and 0x0002 != 0, flagged = s and 0x0004 != 0, deleted = s and 0x0008 != 0, forwarded = s and 0x1000 != 0)
        }
        if (e.status.isNotEmpty()) f = f.copy(seen = f.seen || 'R' in e.status)
        if (e.xStatus.isNotEmpty()) f = f.copy(answered = f.answered || 'A' in e.xStatus, flagged = f.flagged || 'F' in e.xStatus, deleted = f.deleted || 'D' in e.xStatus, draft = f.draft || 'T' in e.xStatus)
        e.gmailLabels?.let { l ->
            val labels = splitLabels(l).map { it.lowercase() }
            f = f.copy(seen = "unread" !in labels, flagged = "starred" in labels, draft = "drafts" in labels || "draft" in labels)
        }
        return f
    }

    private fun keywordsOf(e: Entry): List<String> {
        val out = LinkedHashSet<String>()
        e.keywords.forEach { out += it }
        if (e.mozStatus2 > 0) {
            val label = ((e.mozStatus2 shr 25) and 0x7).toInt() // Netscape 4.x labels 1-5
            if (label in 1..5) out += "\$label$label"
        }
        e.gmailLabels?.let { l ->
            for (lab in splitLabels(l)) {
                val low = lab.lowercase()
                when {
                    low == "important" -> out += "\$Important"
                    low in GMAIL_SYSTEM || low.startsWith("category ") -> {}
                    else -> out += lab
                }
            }
        }
        return out.toList()
    }

    private val GMAIL_SYSTEM = setOf("inbox", "sent", "drafts", "draft", "spam", "trash", "starred", "unread", "opened", "archived", "chat", "important", "sent messages")

    /** Splits "Inbox,Important,\"Work, 2020\",Category Personal" into labels. */
    fun splitLabels(s: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var q = false
        for (ch in s) {
            when {
                ch == '"' -> q = !q
                ch == ',' && !q -> { out += cur.toString().trim(); cur.setLength(0) }
                else -> cur.append(ch)
            }
        }
        if (cur.isNotBlank()) out += cur.toString().trim()
        return out.filter { it.isNotEmpty() }
    }

    /** Gmail: each message goes to one folder — Inbox, Sent, Drafts, Spam, Trash, its first user label, else All Mail. */
    internal fun gmailFolders(entries: List<Entry>): LinkedHashMap<String, MutableList<Entry>> {
        val out = LinkedHashMap<String, MutableList<Entry>>()
        for (name in listOf("Inbox", "Sent", "Drafts")) out[name] = ArrayList()
        for (e in entries) {
            val labels = splitLabels(e.gmailLabels.orEmpty())
            val low = labels.map { it.lowercase() }
            val folder = when {
                "chat" in low -> "Chats"
                "spam" in low -> "Spam"
                "trash" in low -> "Trash"
                "drafts" in low || "draft" in low -> "Drafts"
                "inbox" in low -> "Inbox"
                "sent" in low || "sent messages" in low -> "Sent"
                else -> labels.firstOrNull { it.lowercase() !in GMAIL_SYSTEM && !it.lowercase().startsWith("category ") } ?: "All Mail"
            }
            out.getOrPut(folder) { ArrayList() }.add(e)
        }
        out.entries.removeIf { it.value.isEmpty() }
        return out
    }
}

/** Line-by-line reading of a file region, keeping only the first part of each line. */
internal class LineReader(private val fb: FileBytes, start: Long, private val end: Long) {
    class Line(val start: Long, val length: Long, val next: Long, val prefix: String)

    private var pos = start
    private var buf = ByteArray(0)
    private var bufStart = 0L

    fun seek(p: Long) { pos = p }

    private fun byteAt(p: Long): Int {
        if (p < bufStart || p >= bufStart + buf.size) {
            bufStart = p
            buf = fb.readUpTo(p, minOf(262144L, end - p).toInt())
            if (buf.isEmpty()) return -1
        }
        return buf[(p - bufStart).toInt()].toInt() and 0xFF
    }

    fun next(): Line? {
        if (pos >= end) return null
        val start = pos
        val sb = StringBuilder()
        var p = pos
        while (p < end) {
            val b = byteAt(p)
            if (b < 0) break
            if (b == '\n'.code) break
            if (sb.length < 4096) sb.append(b.toChar())
            p++
        }
        var contentEnd = p
        if (contentEnd > start && byteAt(contentEnd - 1) == '\r'.code) contentEnd--
        val next = if (p < end) p + 1 else p
        pos = next
        val prefix = if (sb.endsWith("\r")) sb.substring(0, sb.length - 1) else sb.toString()
        return Line(start, contentEnd - start, next, prefix)
    }

    /** True when the next line (not consumed) is a header field. */
    fun peekIsHeader(): Boolean {
        val save = pos
        val l = next()
        pos = save
        return l != null && Regex("^[!-9;-~]+:").containsMatchIn(l.prefix)
    }
}
