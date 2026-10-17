package info.socrtwo.quillpost.web.fileformats

import info.socrtwo.quillpost.web.ContactDto
import info.socrtwo.quillpost.web.EventDto
import info.socrtwo.quillpost.web.ICal
import info.socrtwo.quillpost.web.LabeledValue
import info.socrtwo.quillpost.web.PostalAddress
import info.socrtwo.quillpost.web.VCard
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Contact and calendar files opened on their own: vCard 2.1 / 3.0 / 4.0 (.vcf, any encoding),
 * Windows Contacts (.contact), iCalendar (.ics) and vCalendar 1.0 (.vcs, as written by Outlook 97-2003,
 * Palm, Nokia and Sony Ericsson phones). The existing [VCard] and [ICal] readers do the parsing; this
 * object handles encodings and converts the older formats first.
 */
object PimFormats {

    /** Text of a vCard / iCalendar file: BOM-aware, UTF-16 tolerant, UTF-8 or windows-1252. */
    fun text(bytes: ByteArray): String = TextCodec.auto(bytes)

    fun contacts(bytes: ByteArray, format: String): List<ContactDto> = when (format) {
        "contact" -> listOfNotNull(windowsContact(bytes))
        else -> VCard.parse(normaliseVCard21(text(bytes)))
    }

    private val ENCODINGS = setOf("QUOTED-PRINTABLE", "BASE64", "8BIT", "7BIT", "B")

    /**
     * vCard 2.1 writes bare parameters ("TEL;WORK;VOICE:", "NOTE;QUOTED-PRINTABLE:"); vCard 3.0
     * and later name them ("TYPE=WORK,VOICE", "ENCODING=QUOTED-PRINTABLE"). Rewrites the former.
     */
    fun normaliseVCard21(t: String): String {
        val sb = StringBuilder()
        for (line in t.replace("\r\n", "\n").split('\n')) {
            val colon = line.indexOf(':')
            val semi = line.indexOf(';')
            if (colon > 0 && semi in 1 until colon && !line.startsWith(" ") && !line.startsWith("\t")) {
                val head = line.substring(0, colon).split(';')
                val types = ArrayList<String>()
                val params = ArrayList<String>()
                for (p in head.drop(1)) {
                    val v = p.trim()
                    when {
                        v.isEmpty() -> {}
                        v.contains('=') -> params += v
                        v.uppercase() in ENCODINGS -> params += "ENCODING=" + v.uppercase()
                        else -> types += v
                    }
                }
                if (types.isNotEmpty()) params += "TYPE=" + types.joinToString(",")
                // Quoted-printable without a charset: UTF-8 when the bytes are valid UTF-8, else windows-1252.
                if (params.any { it.equals("ENCODING=QUOTED-PRINTABLE", true) } && params.none { it.startsWith("CHARSET=", true) }) {
                    val qp = TextCodec.decodeQuotedPrintable(line.substring(colon + 1))
                    params += "CHARSET=" + if (!TextCodec.hasHighBytes(qp) || TextCodec.isValidUtf8(qp)) "UTF-8" else "windows-1252"
                }
                sb.append(head[0]).append(params.joinToString("") { ";$it" }).append(line.substring(colon))
            } else sb.append(line)
            sb.append("\r\n")
        }
        return sb.toString()
    }

    /** Events from an .ics or .vcs file; vCalendar 1.0 is converted to iCalendar first. */
    fun events(bytes: ByteArray, zone: ZoneId = ZoneId.systemDefault()): ICal.Parsed {
        val t = text(bytes)
        val ics = if (isVCalendar1(t)) vcalToIcal(t) else t
        return ICal.toEvents(ics, zone)
    }

    fun isVCalendar1(t: String): Boolean = Regex("(?im)^VERSION:\\s*1\\.0\\s*$").containsMatchIn(t.take(4096)) && t.contains("BEGIN:VCALENDAR", ignoreCase = true)

    private val BASIC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    /** vCalendar 1.0 → iCalendar 2.0: quoted-printable values, the TZ offset, and the 1.0 RRULE grammar. */
    fun vcalToIcal(src: String): String {
        val lines = ArrayList<String>()
        // Quoted-printable soft breaks first ("=" at the end of a line).
        val raw = ICal.unfold(src)
        var i = 0
        while (i < raw.size) {
            var l = raw[i]
            if (l.contains("QUOTED-PRINTABLE", true)) while (l.endsWith("=") && i + 1 < raw.size) { l = l.dropLast(1) + raw[i + 1]; i++ }
            lines += l; i++
        }
        val tz = lines.firstNotNullOfOrNull { Regex("^TZ:\\s*([+-])(\\d{1,2}):?(\\d{2})?", RegexOption.IGNORE_CASE).find(it.trim()) }?.let {
            val sign = if (it.groupValues[1] == "-") -1 else 1
            ZoneOffset.ofHoursMinutes(sign * it.groupValues[2].toInt(), sign * (it.groupValues[3].toIntOrNull() ?: 0))
        }
        val out = StringBuilder()
        for (l in lines) {
            val p = ICal.parseLine(l)
            if (p == null) { if (l.isNotBlank()) out.append(l).append("\r\n"); continue }
            val name = p.name
            when {
                name == "VERSION" -> { out.append("VERSION:2.0\r\n"); continue }
                name == "TZ" || name == "DAYLIGHT" || name == "AALARM" || name == "DALARM" || name == "PALARM" || name == "MALARM" -> continue
                name == "RRULE" -> { rrule(p.value, tz)?.let { out.append("RRULE:").append(it).append("\r\n") }; continue }
            }
            var value = p.value
            val params = p.params.toMutableMap()
            if (params["ENCODING"]?.equals("QUOTED-PRINTABLE", true) == true) {
                val cs = params["CHARSET"] ?: "UTF-8"
                value = ICal.escape(TextCodec.decode(TextCodec.decodeQuotedPrintable(value), cs))
                params.remove("ENCODING"); params.remove("CHARSET")
            }
            // vCalendar 1.0 wrote bare parameter values (DESCRIPTION;ENCODING=…) and floating times relative to TZ.
            if ((name == "DTSTART" || name == "DTEND" || name == "DUE" || name == "EXDATE") && tz != null) value = value.split(',').joinToString(",") { toUtc(it.trim(), tz) }
            val head = buildString { append(name); for ((k, v) in params) { if (v.isEmpty()) continue; append(';').append(k).append('=').append(v) } }
            out.append(head).append(':').append(value).append("\r\n")
        }
        return out.toString()
    }

    private fun toUtc(v: String, tz: ZoneOffset): String {
        if (v.endsWith("Z") || v.length != 15) return v
        return runCatching { LocalDateTime.parse(v, BASIC).atOffset(tz).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime().format(BASIC) + "Z" }.getOrDefault(v)
    }

    private val DAYS = setOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")

    /** "W2 MO WE #10" → "FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE;COUNT=10" (vCalendar 1.0 section 3.8). */
    fun rrule(v: String, tz: ZoneOffset? = null): String? {
        val tokens = v.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        val m = Regex("^(D|W|MP|MD|YM|YD)(\\d+)$").find(tokens[0].uppercase()) ?: return null
        val freq = when (m.groupValues[1]) { "D" -> "DAILY"; "W" -> "WEEKLY"; "MP", "MD" -> "MONTHLY"; else -> "YEARLY" }
        val parts = mutableListOf("FREQ=$freq")
        val interval = m.groupValues[2].toInt()
        if (interval > 1) parts += "INTERVAL=$interval"
        val days = ArrayList<String>(); val nums = ArrayList<String>()
        var pendingPos: String? = null
        val end = ArrayList<String>()
        for (t0 in tokens.drop(1)) {
            val t = t0.uppercase()
            when {
                t.startsWith("#") -> { val c = t.drop(1).toIntOrNull() ?: 0; if (c > 0) end += "COUNT=$c" }
                Regex("^\\d{8}(T\\d{6}Z?)?$").matches(t) -> end += "UNTIL=" + (if (tz != null && t.length == 15) toUtc(t, tz) else t)
                t.removeSuffix("$") in DAYS -> days += (pendingPos ?: "") + t.removeSuffix("$")
                Regex("^\\d+[+-]$").matches(t) && m.groupValues[1] == "MP" -> pendingPos = (if (t.endsWith("-")) "-" else "") + t.dropLast(1)
                Regex("^\\d+[+-]?$").matches(t) -> nums += if (t.endsWith("-")) "-" + t.dropLast(1) else t.removeSuffix("+")
                t == "LD" -> nums += "-1"
            }
        }
        if (days.isNotEmpty()) parts += "BYDAY=" + days.joinToString(",")
        if (nums.isNotEmpty()) parts += when (m.groupValues[1]) { "MD" -> "BYMONTHDAY="; "YM" -> "BYMONTH="; "YD" -> "BYYEARDAY="; else -> "BYMONTHDAY=" } + nums.joinToString(",")
        return (parts + end).joinToString(";")
    }

    /** Windows Contacts (.contact, Windows Vista and later): the contact XML schema. */
    fun windowsContact(bytes: ByteArray): ContactDto? {
        val f = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = true
        runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { f.isExpandEntityReferences = false }
        val doc = runCatching { f.newDocumentBuilder().parse(java.io.ByteArrayInputStream(bytes)) }.getOrNull() ?: return null
        fun kids(n: org.w3c.dom.Node, local: String): List<org.w3c.dom.Element> {
            val out = ArrayList<org.w3c.dom.Element>()
            val l = n.childNodes
            for (k in 0 until l.length) { val c = l.item(k); if (c is org.w3c.dom.Element && (c.localName ?: c.nodeName.substringAfter(':')).equals(local, true)) out += c }
            return out
        }
        fun all(local: String): List<org.w3c.dom.Element> {
            val nl = doc.getElementsByTagNameNS("*", local)
            return (0 until nl.length).map { nl.item(it) as org.w3c.dom.Element }
        }
        fun txt(n: org.w3c.dom.Node?, local: String): String = n?.let { kids(it, local).firstOrNull()?.textContent?.trim() } ?: ""
        fun labels(e: org.w3c.dom.Element): List<String> = kids(e, "LabelCollection").flatMap { kids(it, "Label") }.map { it.textContent.trim().lowercase() }
        fun label(ls: List<String>, default: String) = when {
            ls.any { it.contains("cellular") || it.contains("mobile") } -> "mobile"
            ls.any { it.contains("fax") } -> "fax"
            ls.any { it.contains("business") || it.contains("work") } -> "work"
            ls.any { it.contains("personal") || it.contains("home") } -> "home"
            else -> default
        }
        val name = all("Name").firstOrNull()
        val emails = all("EmailAddress").mapNotNull { e -> txt(e, "Address").takeIf { it.contains('@') }?.let { LabeledValue(label(labels(e), "home"), it) } }
        val phones = all("PhoneNumber").mapNotNull { e -> txt(e, "Number").takeIf { it.isNotBlank() }?.let { LabeledValue(label(labels(e), "home"), it) } }
        val addrs = all("PhysicalAddress").map { e -> PostalAddress(label(labels(e), "home"), txt(e, "Street"), txt(e, "Locality"), txt(e, "Region"), txt(e, "PostalCode"), txt(e, "Country")) }
            .filter { it.street.isNotBlank() || it.city.isNotBlank() || it.country.isNotBlank() }
        val pos = all("Position").firstOrNull()
        val birthday = all("Date").firstOrNull { labels(it).any { l -> l.contains("birthday") } }?.let { txt(it, "Value").ifBlank { it.textContent.trim() } }?.take(10) ?: ""
        val c = ContactDto(
            prefix = txt(name, "Prefix"), firstName = txt(name, "GivenName"), middleName = txt(name, "MiddleName"), lastName = txt(name, "FamilyName"),
            suffix = txt(name, "Suffix"), nickname = txt(name, "NickName"), displayName = txt(name, "FormattedName"),
            emails = emails, phones = phones, addresses = addrs,
            company = txt(pos, "Company"), department = txt(pos, "Department"), jobTitle = txt(pos, "JobTitle"),
            birthday = VCard.normaliseDate(birthday), website = all("Url").firstOrNull()?.let { txt(it, "Value") } ?: "",
            notes = all("Notes").firstOrNull()?.textContent?.trim() ?: ""
        )
        return if (c.bestName().isBlank() && c.emails.isEmpty() && c.phones.isEmpty()) null else c
    }

    /** A short description of an event for previews ("Tue 3 Nov 2026 10:00–11:00"). */
    fun describe(e: EventDto): String = if (e.allDay) e.start + (if (e.end.isNotBlank() && e.end != e.start) " – " + e.end else "") + " (all day)"
        else e.start.replace('T', ' ') + (if (e.end.isNotBlank()) " – " + e.end.substringAfter('T') else "") + (if (e.tz.isNotBlank()) " (${e.tz})" else "")
}
