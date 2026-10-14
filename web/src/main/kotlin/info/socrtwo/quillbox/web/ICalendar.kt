package info.socrtwo.quillbox.web

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.zone.ZoneOffsetTransitionRule

/**
 * Dependency-free iCalendar (RFC 5545) reader and writer for the parts a mail client needs:
 * VEVENT with time zones, recurrence (RRULE / EXDATE / RECURRENCE-ID), alarms, organizer and
 * attendees, and the iTIP methods (RFC 5546) used by meeting invitations — REQUEST, REPLY and
 * CANCEL. Time zones are resolved from IANA names, Windows names and common vendor prefixes.
 */
object ICal {

    data class Prop(val name: String, val params: Map<String, String>, val value: String) {
        fun param(n: String): String? = params[n.uppercase()]
    }

    class Component(val name: String) {
        val props = ArrayList<Prop>()
        val children = ArrayList<Component>()
        fun first(n: String): Prop? = props.firstOrNull { it.name == n }
        fun all(n: String): List<Prop> = props.filter { it.name == n }
        fun text(n: String): String = first(n)?.let { unescape(it.value) } ?: ""
    }

    // --- reading --------------------------------------------------------------------------

    /** Unfolds and parses [text] into its top-level components (normally one VCALENDAR). */
    fun parse(text: String): List<Component> {
        val roots = ArrayList<Component>()
        val stack = ArrayList<Component>()
        for (line in unfold(text)) {
            if (line.isBlank()) continue
            val prop = parseLine(line) ?: continue
            when (prop.name) {
                "BEGIN" -> {
                    val c = Component(prop.value.trim().uppercase())
                    if (stack.isEmpty()) roots += c else stack.last().children += c
                    stack += c
                }
                "END" -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                else -> stack.lastOrNull()?.props?.add(prop)
            }
        }
        return roots
    }

    fun unfold(text: String): List<String> {
        val out = ArrayList<String>()
        for (raw in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            if ((raw.startsWith(" ") || raw.startsWith("\t")) && out.isNotEmpty()) out[out.size - 1] = out.last() + raw.substring(1)
            else out += raw
        }
        return out
    }

    /** NAME;P1=v;P2="quoted:value":VALUE — colons inside quoted parameter values are allowed. */
    fun parseLine(line: String): Prop? {
        var i = 0
        var inQuote = false
        var colon = -1
        while (i < line.length) {
            val ch = line[i]
            if (ch == '"') inQuote = !inQuote
            else if (ch == ':' && !inQuote) { colon = i; break }
            i++
        }
        if (colon <= 0) return null
        val head = line.substring(0, colon)
        val value = line.substring(colon + 1)
        val parts = splitOutsideQuotes(head, ';')
        val name = parts.first().trim().uppercase()
        val params = LinkedHashMap<String, String>()
        for (p in parts.drop(1)) {
            val eq = p.indexOf('=')
            if (eq > 0) params[p.substring(0, eq).trim().uppercase()] = p.substring(eq + 1).trim().trim('"')
        }
        return Prop(name, params, value)
    }

    private fun splitOutsideQuotes(s: String, sep: Char): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var q = false
        for (ch in s) {
            when {
                ch == '"' -> { q = !q; cur.append(ch) }
                ch == sep && !q -> { out += cur.toString(); cur.setLength(0) }
                else -> cur.append(ch)
            }
        }
        out += cur.toString()
        return out
    }

    fun unescape(v: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < v.length) {
            val c = v[i]
            if (c == '\\' && i + 1 < v.length) {
                when (val n = v[i + 1]) {
                    'n', 'N' -> sb.append('\n')
                    else -> sb.append(n)
                }
                i += 2
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }

    data class Parsed(val method: String?, val events: List<EventDto>, val warnings: List<String>)

    /**
     * Converts every VEVENT in [text] to [EventDto]s. Times in UTC or floating times are placed
     * in [defaultZone]; times with a TZID keep their own zone so recurring events stay correct
     * across daylight-saving changes.
     */
    fun toEvents(text: String, defaultZone: ZoneId, calendarId: String = ""): Parsed {
        val warnings = ArrayList<String>()
        val roots = parse(text)
        val cal = roots.firstOrNull { it.name == "VCALENDAR" }
        val comps = cal?.children ?: roots
        val method = cal?.first("METHOD")?.value?.trim()?.uppercase()
        val tzAliases = HashMap<String, ZoneId>()
        for (tz in comps.filter { it.name == "VTIMEZONE" }) {
            val id = tz.first("TZID")?.value?.trim() ?: continue
            val loc = tz.first("X-LIC-LOCATION")?.value?.trim()
            (resolveZone(id) ?: loc?.let { resolveZone(it) })?.let { tzAliases[id] = it }
        }
        val events = ArrayList<EventDto>()
        for (ve in comps.filter { it.name == "VEVENT" }) {
            runCatching { events += eventFrom(ve, defaultZone, tzAliases, calendarId) }
                .onFailure { warnings += "Skipped an event: ${it.message}" }
        }
        return Parsed(method, events, warnings)
    }

    private val LOCAL_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

    private data class When(val allDay: Boolean, val local: LocalDateTime, val zone: ZoneId)

    private fun parseWhen(p: Prop, defaultZone: ZoneId, aliases: Map<String, ZoneId>, targetZone: ZoneId? = null): When {
        val v = p.value.trim()
        val isDate = p.param("VALUE")?.uppercase() == "DATE" || (v.length == 8 && v.all { it.isDigit() })
        if (isDate) return When(true, LocalDate.parse(v.take(8), DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay(), defaultZone)
        val utc = v.endsWith("Z")
        val ldt = LocalDateTime.parse(v.removeSuffix("Z").take(15), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
        val srcZone: ZoneId = when {
            utc -> ZoneOffset.UTC
            p.param("TZID") != null -> aliases[p.param("TZID")!!] ?: resolveZone(p.param("TZID")!!) ?: defaultZone
            else -> defaultZone
        }
        val zone = targetZone ?: if (utc) defaultZone else srcZone
        val local = if (srcZone == zone) ldt else ldt.atZone(srcZone).withZoneSameInstant(zone).toLocalDateTime()
        return When(false, local, zone)
    }

    private fun fmtLocal(w: When): String = if (w.allDay) w.local.toLocalDate().toString() else w.local.format(LOCAL_FMT)

    private fun eventFrom(ve: Component, defaultZone: ZoneId, aliases: Map<String, ZoneId>, calendarId: String): EventDto {
        val dtStart = ve.first("DTSTART") ?: throw IllegalArgumentException("no DTSTART")
        val start = parseWhen(dtStart, defaultZone, aliases)
        val end: When = ve.first("DTEND")?.let { parseWhen(it, defaultZone, aliases, if (start.allDay) null else start.zone) }
            ?: ve.first("DURATION")?.let { d ->
                val dur = parseDuration(d.value) ?: Duration.ZERO
                When(start.allDay, start.local.plus(dur), start.zone)
            }
            ?: if (start.allDay) When(true, start.local.plusDays(1), start.zone) else start
        val endFixed = if (start.allDay && !end.local.isAfter(start.local)) When(true, start.local.plusDays(1), start.zone) else end
        val busy = ve.first("X-MICROSOFT-CDO-BUSYSTATUS")?.value?.trim()?.uppercase()
        val showAs = when {
            busy == "FREE" -> "free"; busy == "TENTATIVE" -> "tentative"; busy == "OOF" -> "oof"
            busy == "WORKINGELSEWHERE" -> "workingElsewhere"; busy == "BUSY" -> "busy"
            ve.first("TRANSP")?.value?.trim()?.uppercase() == "TRANSPARENT" -> "free"
            else -> "busy"
        }
        val exdates = ve.all("EXDATE").flatMap { p ->
            p.value.split(',').filter { it.isNotBlank() }.map { v -> fmtLocal(parseWhen(Prop("EXDATE", p.params, v), defaultZone, aliases, if (start.allDay) null else start.zone)) }
        }
        val recurrenceId = ve.first("RECURRENCE-ID")?.let { fmtLocal(parseWhen(it, defaultZone, aliases, if (start.allDay) null else start.zone)) }
        val alarm = ve.children.firstOrNull { it.name == "VALARM" }
        val reminder = alarm?.first("TRIGGER")?.let { t ->
            if (t.param("VALUE")?.uppercase() == "DATE-TIME") {
                val at = parseWhen(t, defaultZone, aliases, start.zone).local
                Duration.between(at, start.local).toMinutes().toInt()
            } else parseDuration(t.value)?.let { (-it.toMinutes()).toInt() }
        }?.coerceAtLeast(0)
        val organizer = ve.first("ORGANIZER")?.let { attendee(it) }
        val attendees = ve.all("ATTENDEE").map { attendee(it) }.filter { it.email.isNotBlank() }
        val online = listOf("X-MICROSOFT-SKYPETEAMSMEETINGURL", "X-GOOGLE-CONFERENCE", "URL").firstNotNullOfOrNull { n -> ve.first(n)?.value?.trim()?.takeIf { it.startsWith("http") } } ?: ""
        val uid = ve.text("UID").ifBlank { "qb-" + java.util.UUID.randomUUID() }
        return EventDto(
            id = "", calendarId = calendarId, uid = uid,
            title = ve.text("SUMMARY").ifBlank { "(No title)" },
            location = ve.text("LOCATION"),
            notes = ve.text("DESCRIPTION"),
            start = fmtLocal(start), end = fmtLocal(endFixed), allDay = start.allDay,
            tz = if (start.allDay) "" else start.zone.id,
            recurrence = ve.first("RRULE")?.let { parseRRule(it.value, start, defaultZone) },
            exdates = exdates, recurrenceId = recurrenceId,
            reminderMinutes = reminder, showAs = showAs,
            categories = ve.all("CATEGORIES").flatMap { c -> unescape(c.value).split(',').map { it.trim() }.filter { it.isNotEmpty() } },
            private = ve.first("CLASS")?.value?.trim()?.uppercase() in setOf("PRIVATE", "CONFIDENTIAL"),
            organizer = organizer, attendees = attendees,
            sequence = ve.first("SEQUENCE")?.value?.trim()?.toIntOrNull() ?: 0,
            cancelled = ve.first("STATUS")?.value?.trim()?.uppercase() == "CANCELLED",
            onlineUrl = online
        )
    }

    private fun attendee(p: Prop): AttendeeDto = AttendeeDto(
        email = p.value.trim().removePrefix("mailto:").removePrefix("MAILTO:").trim().lowercase(),
        name = p.param("CN")?.trim('"') ?: "",
        role = p.param("ROLE")?.uppercase() ?: "REQ-PARTICIPANT",
        status = p.param("PARTSTAT")?.uppercase() ?: "NEEDS-ACTION"
    )

    private val DAYS = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")

    private fun parseRRule(value: String, start: When, defaultZone: ZoneId): RecurrenceDto? {
        val parts = value.split(';').mapNotNull { kv -> kv.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim().uppercase() to it[1].trim() } }.toMap()
        val freq = parts["FREQ"]?.uppercase() ?: return null
        if (freq !in setOf("DAILY", "WEEKLY", "MONTHLY", "YEARLY")) return null
        var setPos = parts["BYSETPOS"]?.split(',')?.firstOrNull()?.toIntOrNull()
        val byDay = ArrayList<String>()
        parts["BYDAY"]?.split(',')?.forEach { d ->
            val m = Regex("^([+-]?\\d+)?([A-Za-z]{2})$").find(d.trim()) ?: return@forEach
            val day = m.groupValues[2].uppercase()
            if (day !in DAYS) return@forEach
            m.groupValues[1].takeIf { it.isNotEmpty() }?.toIntOrNull()?.let { setPos = it }
            byDay += day
        }
        val until = parts["UNTIL"]?.let { u ->
            runCatching {
                if (u.length == 8) LocalDate.parse(u, DateTimeFormatter.BASIC_ISO_DATE).toString()
                else parseWhen(Prop("UNTIL", emptyMap(), u), defaultZone, emptyMap(), if (start.allDay) defaultZone else start.zone).local.toLocalDate().toString()
            }.getOrNull()
        }
        val sd = start.local.toLocalDate()
        return RecurrenceDto(
            freq = freq,
            interval = parts["INTERVAL"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
            byDay = when {
                byDay.isNotEmpty() -> byDay.distinct()
                freq == "WEEKLY" -> listOf(DAYS[sd.dayOfWeek.value - 1])
                else -> emptyList()
            },
            byMonthDay = parts["BYMONTHDAY"]?.split(',')?.firstOrNull()?.toIntOrNull()
                ?: if ((freq == "MONTHLY" || freq == "YEARLY") && byDay.isEmpty()) sd.dayOfMonth else null,
            byMonth = parts["BYMONTH"]?.split(',')?.firstOrNull()?.toIntOrNull() ?: if (freq == "YEARLY") sd.monthValue else null,
            bySetPos = if (byDay.isNotEmpty() && freq in setOf("MONTHLY", "YEARLY")) setPos else null,
            count = parts["COUNT"]?.toIntOrNull(),
            until = until
        )
    }

    /** RFC 5545 durations: [+-]P[nW][nD][T[nH][nM][nS]]. */
    fun parseDuration(v: String): Duration? {
        val m = Regex("^([+-])?P(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?$").find(v.trim().uppercase()) ?: return null
        val sign = if (m.groupValues[1] == "-") -1L else 1L
        fun g(i: Int) = m.groupValues[i].toLongOrNull() ?: 0L
        val secs = g(2) * 7 * 86400 + g(3) * 86400 + g(4) * 3600 + g(5) * 60 + g(6)
        return Duration.ofSeconds(sign * secs)
    }

    // --- time zones -------------------------------------------------------------------------

    private val WINDOWS_ZONES = mapOf(
        "eastern standard time" to "America/New_York", "us eastern standard time" to "America/Indiana/Indianapolis",
        "central standard time" to "America/Chicago", "mountain standard time" to "America/Denver",
        "us mountain standard time" to "America/Phoenix", "pacific standard time" to "America/Los_Angeles",
        "alaskan standard time" to "America/Anchorage", "hawaiian standard time" to "Pacific/Honolulu",
        "atlantic standard time" to "America/Halifax", "newfoundland standard time" to "America/St_Johns",
        "canada central standard time" to "America/Regina", "central standard time (mexico)" to "America/Mexico_City",
        "mexico standard time" to "America/Mexico_City", "central america standard time" to "America/Guatemala",
        "sa pacific standard time" to "America/Bogota", "venezuela standard time" to "America/Caracas",
        "e. south america standard time" to "America/Sao_Paulo", "argentina standard time" to "America/Argentina/Buenos_Aires",
        "pacific sa standard time" to "America/Santiago", "utc" to "UTC", "coordinated universal time" to "UTC",
        "gmt standard time" to "Europe/London", "greenwich standard time" to "Atlantic/Reykjavik",
        "w. europe standard time" to "Europe/Berlin", "romance standard time" to "Europe/Paris",
        "central europe standard time" to "Europe/Budapest", "central european standard time" to "Europe/Warsaw",
        "e. europe standard time" to "Europe/Chisinau", "fle standard time" to "Europe/Kiev", "gtb standard time" to "Europe/Bucharest",
        "turkey standard time" to "Europe/Istanbul", "russian standard time" to "Europe/Moscow", "israel standard time" to "Asia/Jerusalem",
        "egypt standard time" to "Africa/Cairo", "south africa standard time" to "Africa/Johannesburg",
        "w. central africa standard time" to "Africa/Lagos", "e. africa standard time" to "Africa/Nairobi",
        "arab standard time" to "Asia/Riyadh", "arabian standard time" to "Asia/Dubai", "iran standard time" to "Asia/Tehran",
        "pakistan standard time" to "Asia/Karachi", "india standard time" to "Asia/Kolkata", "nepal standard time" to "Asia/Kathmandu",
        "bangladesh standard time" to "Asia/Dhaka", "myanmar standard time" to "Asia/Yangon", "se asia standard time" to "Asia/Bangkok",
        "china standard time" to "Asia/Shanghai", "taipei standard time" to "Asia/Taipei", "singapore standard time" to "Asia/Singapore",
        "w. australia standard time" to "Australia/Perth", "tokyo standard time" to "Asia/Tokyo", "korea standard time" to "Asia/Seoul",
        "cen. australia standard time" to "Australia/Adelaide", "aus central standard time" to "Australia/Darwin",
        "e. australia standard time" to "Australia/Brisbane", "aus eastern standard time" to "Australia/Sydney",
        "tasmania standard time" to "Australia/Hobart", "new zealand standard time" to "Pacific/Auckland"
    )

    /** IANA ids, Windows names and vendor-prefixed ids ("/mozilla.org/…/America/New_York"). */
    fun resolveZone(id: String): ZoneId? {
        val clean = id.trim().trim('"')
        if (clean.isEmpty()) return null
        runCatching { return ZoneId.of(clean) }
        WINDOWS_ZONES[clean.lowercase()]?.let { return ZoneId.of(it) }
        val segs = clean.split('/').filter { it.isNotBlank() }
        for (i in segs.indices) runCatching { return ZoneId.of(segs.drop(i).joinToString("/")) }
        Regex("\\(UTC([+-]\\d{2}):?(\\d{2})\\)").find(clean)?.let { m ->
            return ZoneOffset.of(m.groupValues[1] + ":" + m.groupValues[2])
        }
        return null
    }

    // --- writing ------------------------------------------------------------------------------

    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    private val LOCAL_OUT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    fun escape(v: String): String = v.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\r\n", "\\n").replace("\n", "\\n")

    /** Folds a content line at 75 octets without splitting a UTF-8 sequence. */
    fun fold(line: String): String {
        val bytes = line.toByteArray(Charsets.UTF_8)
        if (bytes.size <= 75) return line
        val sb = StringBuilder()
        var count = 0
        var first = true
        for (ch in line) {
            val n = ch.toString().toByteArray(Charsets.UTF_8).size
            val limit = if (first) 75 else 74
            if (count + n > limit) { sb.append("\r\n "); count = 0; first = false }
            sb.append(ch); count += n
        }
        return sb.toString()
    }

    private fun zoneOf(e: EventDto, fallback: ZoneId): ZoneId = resolveZone(e.tz) ?: fallback

    private fun dateProp(name: String, value: String, e: EventDto, zone: ZoneId): String =
        if (e.allDay || value.length == 10) "$name;VALUE=DATE:" + value.take(10).replace("-", "")
        else "$name;TZID=${zone.id}:" + LocalDateTime.parse(value, LOCAL_FMT).format(LOCAL_OUT)

    private fun rrule(r: RecurrenceDto): String {
        val parts = ArrayList<String>()
        parts += "FREQ=${r.freq.uppercase()}"
        if (r.interval > 1) parts += "INTERVAL=${r.interval}"
        if (r.byDay.isNotEmpty()) {
            val pos = if (r.bySetPos != null && r.byDay.size == 1) r.bySetPos.toString() else ""
            parts += "BYDAY=" + r.byDay.joinToString(",") { pos + it }
            if (r.bySetPos != null && r.byDay.size > 1) parts += "BYSETPOS=${r.bySetPos}"
        }
        if (r.byMonthDay != null && r.byDay.isEmpty()) parts += "BYMONTHDAY=${r.byMonthDay}"
        if (r.byMonth != null && r.freq.uppercase() == "YEARLY") parts += "BYMONTH=${r.byMonth}"
        r.count?.let { parts += "COUNT=$it" }
        r.until?.let { parts += "UNTIL=" + it.replace("-", "") + "T235959Z" }
        return "RRULE:" + parts.joinToString(";")
    }

    private fun attendeeLine(name: String, a: AttendeeDto, rsvp: Boolean): String {
        val params = StringBuilder(name)
        if (a.name.isNotBlank()) params.append(";CN=\"").append(a.name.replace("\"", "'")).append('"')
        if (name == "ATTENDEE") {
            params.append(";ROLE=").append(a.role.ifBlank { "REQ-PARTICIPANT" })
            params.append(";PARTSTAT=").append(a.status.ifBlank { "NEEDS-ACTION" })
            if (rsvp) params.append(";RSVP=TRUE")
        }
        return "$params:mailto:${a.email}"
    }

    fun eventLines(e: EventDto, fallback: ZoneId, method: String? = null, onlyAttendee: AttendeeDto? = null): List<String> {
        val zone = zoneOf(e, fallback)
        val out = ArrayList<String>()
        out += "BEGIN:VEVENT"
        out += "UID:" + e.uid
        out += "DTSTAMP:" + STAMP.format(Instant.now())
        out += dateProp("DTSTART", e.start, e, zone)
        if (e.end.isNotBlank()) out += dateProp("DTEND", e.end, e, zone)
        e.recurrenceId?.let { out += dateProp("RECURRENCE-ID", it, e, zone) }
        out += "SUMMARY:" + escape(e.title)
        if (e.location.isNotBlank()) out += "LOCATION:" + escape(e.location)
        if (e.notes.isNotBlank()) out += "DESCRIPTION:" + escape(e.notes)
        if (method != "REPLY" && e.recurrenceId == null) e.recurrence?.let { out += rrule(it) }
        if (method != "REPLY" && e.exdates.isNotEmpty()) {
            out += if (e.allDay) "EXDATE;VALUE=DATE:" + e.exdates.joinToString(",") { it.take(10).replace("-", "") }
            else "EXDATE;TZID=${zone.id}:" + e.exdates.joinToString(",") { LocalDateTime.parse(it, LOCAL_FMT).format(LOCAL_OUT) }
        }
        out += "SEQUENCE:" + e.sequence
        out += "STATUS:" + if (e.cancelled || method == "CANCEL") "CANCELLED" else "CONFIRMED"
        out += "TRANSP:" + if (e.showAs == "free") "TRANSPARENT" else "OPAQUE"
        out += "X-MICROSOFT-CDO-BUSYSTATUS:" + when (e.showAs) { "free" -> "FREE"; "tentative" -> "TENTATIVE"; "oof" -> "OOF"; "workingElsewhere" -> "WORKINGELSEWHERE"; else -> "BUSY" }
        if (e.categories.isNotEmpty()) out += "CATEGORIES:" + e.categories.joinToString(",") { escape(it) }
        if (e.private) out += "CLASS:PRIVATE"
        if (e.onlineUrl.isNotBlank()) out += "URL:" + e.onlineUrl
        e.organizer?.let { out += attendeeLine("ORGANIZER", it, false) }
        val people = if (onlyAttendee != null) listOf(onlyAttendee) else e.attendees
        for (a in people) out += attendeeLine("ATTENDEE", a, method == "REQUEST")
        if (method == null || method == "REQUEST" || method == "PUBLISH") e.reminderMinutes?.let { m ->
            out += "BEGIN:VALARM"; out += "ACTION:DISPLAY"; out += "DESCRIPTION:Reminder"; out += "TRIGGER:-PT${m}M"; out += "END:VALARM"
        }
        out += "END:VEVENT"
        return out
    }

    /** A complete VCALENDAR for [events]; [method] adds an iTIP method (REQUEST, REPLY, CANCEL). */
    fun calendar(events: List<EventDto>, fallback: ZoneId, method: String? = null, name: String? = null, onlyAttendee: AttendeeDto? = null): String {
        val lines = ArrayList<String>()
        lines += "BEGIN:VCALENDAR"
        lines += "VERSION:2.0"
        lines += "PRODID:-//Quillbox//Quillbox Mail//EN"
        lines += "CALSCALE:GREGORIAN"
        method?.let { lines += "METHOD:$it" }
        name?.let { lines += "X-WR-CALNAME:" + escape(it) }
        val zones = events.filter { !it.allDay }.map { zoneOf(it, fallback) }.distinct()
        for (z in zones) lines += vtimezone(z)
        for (e in events) lines += eventLines(e, fallback, method, onlyAttendee)
        lines += "END:VCALENDAR"
        return lines.joinToString("\r\n") { fold(it) } + "\r\n"
    }

    /** VTIMEZONE built from the JDK's zone rules (current DST rules, or a fixed offset). */
    fun vtimezone(zone: ZoneId): List<String> {
        val out = ArrayList<String>()
        out += "BEGIN:VTIMEZONE"
        out += "TZID:" + zone.id
        val rules = zone.rules
        val trs = rules.transitionRules
        if (trs.size >= 2) {
            for (r in trs) {
                val daylight = r.offsetAfter.totalSeconds > r.standardOffset.totalSeconds
                val kind = if (daylight) "DAYLIGHT" else "STANDARD"
                out += "BEGIN:$kind"
                out += "DTSTART:" + r.createTransition(1970).dateTimeBefore.format(LOCAL_OUT)
                out += "TZOFFSETFROM:" + offset(r.offsetBefore)
                out += "TZOFFSETTO:" + offset(r.offsetAfter)
                out += "RRULE:FREQ=YEARLY;BYMONTH=${r.month.value};" + byDayOf(r)
                out += "END:$kind"
            }
        } else {
            val off = rules.getOffset(Instant.now())
            out += "BEGIN:STANDARD"
            out += "DTSTART:19700101T000000"
            out += "TZOFFSETFROM:" + offset(off)
            out += "TZOFFSETTO:" + offset(off)
            out += "END:STANDARD"
        }
        out += "END:VTIMEZONE"
        return out
    }

    private fun byDayOf(r: ZoneOffsetTransitionRule): String {
        val dow = r.dayOfWeek ?: return "BYMONTHDAY=${r.dayOfMonthIndicator}"
        val ind = r.dayOfMonthIndicator
        val pos = when {
            ind < 0 -> -1
            else -> (ind - 1) / 7 + 1
        }
        return "BYDAY=$pos" + DAYS[dow.value - 1]
    }

    private fun offset(o: ZoneOffset): String {
        val s = o.totalSeconds
        val sign = if (s < 0) "-" else "+"
        val a = kotlin.math.abs(s)
        return sign + "%02d%02d".format(a / 3600, (a % 3600) / 60)
    }

    // --- helpers used by the account context ----------------------------------------------------

    /** Epoch ms of an event's start (all-day events: local midnight in [fallback]). */
    fun startMillis(e: EventDto, fallback: ZoneId): Long {
        val zone = zoneOf(e, fallback)
        return if (e.allDay || e.start.length == 10) LocalDate.parse(e.start.take(10)).atStartOfDay(fallback).toInstant().toEpochMilli()
        else LocalDateTime.parse(e.start, LOCAL_FMT).atZone(zone).toInstant().toEpochMilli()
    }

    /** Human description of a recurrence ("Every 2 weeks on Mon, Wed"), for invitation cards. */
    fun describe(r: RecurrenceDto?): String {
        if (r == null) return ""
        val names = mapOf("MO" to "Mon", "TU" to "Tue", "WE" to "Wed", "TH" to "Thu", "FR" to "Fri", "SA" to "Sat", "SU" to "Sun")
        val unit = when (r.freq) { "DAILY" -> "day"; "WEEKLY" -> "week"; "MONTHLY" -> "month"; else -> "year" }
        val every = if (r.interval > 1) "Every ${r.interval} ${unit}s" else "Every $unit"
        val days = if (r.byDay.isNotEmpty()) " on " + r.byDay.joinToString(", ") { names[it] ?: it } else ""
        val until = r.until?.let { " until $it" } ?: r.count?.let { ", $it times" } ?: ""
        return every + days + until
    }
}
