package info.socrtwo.quillbox.web

/**
 * Contact interchange: vCard 2.1 / 3.0 / 4.0 import (including the quoted-printable vCard 2.1
 * that desktop mail clients export), vCard 3.0 export, and CSV import/export in the column
 * layout desktop mail clients use, with the Google Contacts layout also understood on import.
 */
object VCard {

    // --- vCard -------------------------------------------------------------------------------

    fun parse(text: String): List<ContactDto> {
        val out = ArrayList<ContactDto>()
        var cur: MutableList<ICal.Prop>? = null
        for (line in unfoldVcard(text)) {
            val p = ICal.parseLine(line) ?: continue
            when {
                p.name == "BEGIN" && p.value.trim().equals("VCARD", true) -> cur = ArrayList()
                p.name == "END" && p.value.trim().equals("VCARD", true) -> { cur?.let { toContact(it)?.let { c -> out += c } }; cur = null }
                else -> cur?.add(p)
            }
        }
        return out
    }

    /** vCard folding plus vCard 2.1 quoted-printable soft line breaks ("=" at end of line). */
    private fun unfoldVcard(text: String): List<String> {
        val lines = ICal.unfold(text)
        val out = ArrayList<String>()
        var i = 0
        while (i < lines.size) {
            var l = lines[i]
            if (l.contains("QUOTED-PRINTABLE", ignoreCase = true)) {
                while (l.endsWith("=") && i + 1 < lines.size) { l = l.dropLast(1) + lines[i + 1]; i++ }
            }
            out += l
            i++
        }
        return out
    }

    private fun value(p: ICal.Prop): String {
        val enc = p.param("ENCODING")?.uppercase()
        val charset = p.param("CHARSET") ?: "UTF-8"
        val raw = if (enc == "QUOTED-PRINTABLE") decodeQp(p.value, charset) else p.value
        return raw
    }

    private fun decodeQp(s: String, charset: String): String {
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '=' && i + 3 <= s.length) {
                val b = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (b != null) { bytes.write(b); i += 3; continue }
            }
            bytes.write(c.code and 0xff)
            i++
        }
        return runCatching { String(bytes.toByteArray(), charset(charset)) }.getOrDefault(String(bytes.toByteArray(), Charsets.UTF_8))
    }

    /** Splits a structured value on unescaped [sep] and unescapes each part. */
    private fun structured(v: String, sep: Char = ';'): List<String> {
        val parts = ArrayList<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < v.length) {
            val c = v[i]
            if (c == '\\' && i + 1 < v.length) { cur.append(c).append(v[i + 1]); i += 2; continue }
            if (c == sep) { parts += ICal.unescape(cur.toString()); cur.setLength(0) } else cur.append(c)
            i++
        }
        parts += ICal.unescape(cur.toString())
        return parts
    }

    private fun typeLabel(p: ICal.Prop, default: String): String {
        val types = (p.param("TYPE") ?: p.params.entries.filter { it.value.isEmpty() || it.key in setOf("HOME", "WORK", "CELL", "MOBILE", "FAX", "PREF") }.joinToString(",") { it.key })
            .lowercase().split(',').map { it.trim() }.filter { it.isNotBlank() && it != "pref" && it != "internet" && it != "voice" }
        return when {
            "cell" in types || "mobile" in types -> "mobile"
            "fax" in types -> "fax"
            "work" in types -> "work"
            "home" in types -> "home"
            types.isNotEmpty() -> types.first()
            else -> default
        }
    }

    private fun toContact(props: List<ICal.Prop>): ContactDto? {
        fun first(n: String) = props.firstOrNull { it.name == n || it.name.endsWith(".$n") }
        fun all(n: String) = props.filter { it.name == n || it.name.endsWith(".$n") }
        val n = first("N")?.let { structured(value(it)) } ?: emptyList()
        val org = first("ORG")?.let { structured(value(it)) } ?: emptyList()
        val emails = all("EMAIL").map { LabeledValue(typeLabel(it, "home"), ICal.unescape(value(it)).trim()) }.filter { it.value.contains('@') }
        val phones = all("TEL").map { LabeledValue(typeLabel(it, "mobile"), ICal.unescape(value(it)).trim().removePrefix("tel:")) }.filter { it.value.isNotBlank() }
        val addrs = all("ADR").map { p ->
            val a = structured(value(p))
            fun at(i: Int) = a.getOrNull(i)?.trim().orEmpty()
            PostalAddress(typeLabel(p, "home"), listOf(at(1), at(2)).filter { it.isNotBlank() }.joinToString("\n"), at(3), at(4), at(5), at(6))
        }.filter { it.street.isNotBlank() || it.city.isNotBlank() || it.country.isNotBlank() }
        val c = ContactDto(
            prefix = n.getOrNull(3).orEmpty().trim(),
            firstName = n.getOrNull(1).orEmpty().trim(),
            middleName = n.getOrNull(2).orEmpty().trim(),
            lastName = n.getOrNull(0).orEmpty().trim(),
            suffix = n.getOrNull(4).orEmpty().trim(),
            nickname = first("NICKNAME")?.let { ICal.unescape(value(it)) }.orEmpty(),
            displayName = first("FN")?.let { ICal.unescape(value(it)).trim() }.orEmpty(),
            emails = emails, phones = phones, addresses = addrs,
            company = org.getOrNull(0).orEmpty().trim(), department = org.getOrNull(1).orEmpty().trim(),
            jobTitle = first("TITLE")?.let { ICal.unescape(value(it)) }.orEmpty(),
            birthday = first("BDAY")?.let { normaliseDate(value(it)) }.orEmpty(),
            anniversary = (first("ANNIVERSARY") ?: first("X-ANNIVERSARY") ?: first("X-MS-ANNIVERSARY"))?.let { normaliseDate(value(it)) }.orEmpty(),
            website = first("URL")?.let { ICal.unescape(value(it)) }.orEmpty(),
            notes = first("NOTE")?.let { ICal.unescape(value(it)) }.orEmpty(),
            categories = first("CATEGORIES")?.let { structured(value(it), ',') }?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
        )
        return if (c.bestName().isBlank() && c.emails.isEmpty() && c.phones.isEmpty()) null else c
    }

    /** 1980-05-17, 19800517, --0517, --05-17 → "YYYY-MM-DD" or "--MM-DD". */
    fun normaliseDate(v: String): String {
        val s = v.trim().substringBefore('T')
        Regex("^(\\d{4})-?(\\d{2})-?(\\d{2})$").find(s)?.let { return "${it.groupValues[1]}-${it.groupValues[2]}-${it.groupValues[3]}" }
        Regex("^--(\\d{2})-?(\\d{2})$").find(s)?.let { return "--${it.groupValues[1]}-${it.groupValues[2]}" }
        Regex("^(\\d{1,2})/(\\d{1,2})/(\\d{4})$").find(s)?.let { return "%s-%02d-%02d".format(it.groupValues[3], it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
        return ""
    }

    private fun esc(v: String) = ICal.escape(v)

    fun write(contacts: List<ContactDto>): String {
        val sb = StringBuilder()
        for (c in contacts) {
            val lines = ArrayList<String>()
            lines += "BEGIN:VCARD"
            lines += "VERSION:3.0"
            lines += "N:" + listOf(c.lastName, c.firstName, c.middleName, c.prefix, c.suffix).joinToString(";") { esc(it) }
            lines += "FN:" + esc(c.bestName())
            if (c.nickname.isNotBlank()) lines += "NICKNAME:" + esc(c.nickname)
            if (c.company.isNotBlank() || c.department.isNotBlank()) lines += "ORG:" + esc(c.company) + ";" + esc(c.department)
            if (c.jobTitle.isNotBlank()) lines += "TITLE:" + esc(c.jobTitle)
            for (e in c.emails) lines += "EMAIL;TYPE=INTERNET," + e.label.ifBlank { "home" }.uppercase() + ":" + e.value
            for (p in c.phones) lines += "TEL;TYPE=" + (if (p.label == "mobile") "CELL" else p.label.ifBlank { "voice" }.uppercase()) + ":" + p.value
            for (a in c.addresses) lines += "ADR;TYPE=" + a.label.ifBlank { "home" }.uppercase() + ":;;" + listOf(a.street, a.city, a.region, a.postalCode, a.country).joinToString(";") { esc(it) }
            if (c.birthday.isNotBlank()) lines += "BDAY:" + c.birthday
            if (c.anniversary.isNotBlank()) lines += "X-ANNIVERSARY:" + c.anniversary
            if (c.website.isNotBlank()) lines += "URL:" + c.website
            if (c.notes.isNotBlank()) lines += "NOTE:" + esc(c.notes)
            if (c.categories.isNotEmpty()) lines += "CATEGORIES:" + c.categories.joinToString(",") { esc(it) }
            lines += "END:VCARD"
            sb.append(lines.joinToString("\r\n") { ICal.fold(it) }).append("\r\n")
        }
        return sb.toString()
    }

    // --- CSV ---------------------------------------------------------------------------------

    fun parseCsvRows(text: String): List<List<String>> {
        val t = text.removePrefix("\uFEFF")
        val headerLine = t.substringBefore('\n')
        val sep = when { headerLine.contains(',') -> ','; headerLine.contains(';') -> ';'; headerLine.contains('\t') -> '\t'; else -> ',' }
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var q = false
        var i = 0
        while (i < t.length) {
            val c = t[i]
            if (q) {
                if (c == '"') { if (i + 1 < t.length && t[i + 1] == '"') { cell.append('"'); i++ } else q = false }
                else cell.append(c)
            } else when (c) {
                '"' -> q = true
                sep -> { row += cell.toString(); cell.setLength(0) }
                '\r' -> {}
                '\n' -> { row += cell.toString(); cell.setLength(0); if (row.any { it.isNotBlank() }) rows += row; row = ArrayList() }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row += cell.toString(); if (row.any { it.isNotBlank() }) rows += row }
        return rows
    }

    /** Imports desktop-client CSV ("First Name", "E-mail Address", …) or Google CSV ("Given Name", "E-mail 1 - Value", …). */
    fun parseCsv(text: String): List<ContactDto> {
        val rows = parseCsvRows(text)
        if (rows.size < 2) return emptyList()
        val header = rows.first().map { it.trim().lowercase() }
        fun idx(vararg names: String): Int = names.firstNotNullOfOrNull { n -> header.indexOf(n).takeIf { it >= 0 } } ?: -1
        fun idxAll(pred: (String) -> Boolean): List<Int> = header.indices.filter { pred(header[it]) }
        val iFirst = idx("first name", "given name", "firstname")
        val iMiddle = idx("middle name", "additional name")
        val iLast = idx("last name", "family name", "surname", "lastname")
        val iName = idx("name", "display name", "full name")
        val iTitlePrefix = idx("title", "name prefix")
        val iSuffix = idx("suffix", "name suffix")
        val iNick = idx("nickname")
        val iCompany = idx("company", "organization 1 - name", "organization name")
        val iDept = idx("department", "organization 1 - department")
        val iJob = idx("job title", "organization 1 - title")
        val iBday = idx("birthday")
        val iNotes = idx("notes")
        val iWeb = idx("web page", "website 1 - value")
        val iCats = idx("categories", "group membership")
        val emailCols = idxAll { (it.startsWith("e-mail") || it.startsWith("email")) && (it.endsWith("address") || it.endsWith("value") || it == "email" || it == "e-mail") }
        val phoneCols = idxAll { (it.endsWith("phone") || it.endsWith("phone 2") || (it.startsWith("phone") && it.endsWith("value"))) }
        val out = ArrayList<ContactDto>()
        for (r in rows.drop(1)) {
            fun at(i: Int) = if (i >= 0) r.getOrNull(i)?.trim().orEmpty() else ""
            val emails = emailCols.map { at(it) }.filter { it.contains('@') }.flatMap { it.split(":::").map { e -> e.trim() } }.distinct()
                .mapIndexed { n, e -> LabeledValue(if (n == 0) "home" else "other", e) }
            val phones = phoneCols.mapNotNull { col ->
                val v = at(col).takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val h = header[col]
                LabeledValue(when { "mobile" in h || "cell" in h -> "mobile"; "business" in h || "work" in h -> "work"; "home" in h -> "home"; "fax" in h -> "fax"; else -> "other" }, v)
            }
            val addrs = listOf("home", "business", "other").mapNotNull { kind ->
                val street = at(idx("$kind street")); val city = at(idx("$kind city")); val state = at(idx("$kind state")); val zip = at(idx("$kind postal code")); val country = at(idx("$kind country/region", "$kind country"))
                if (listOf(street, city, state, zip, country).all { it.isBlank() }) null
                else PostalAddress(if (kind == "business") "work" else kind, street, city, state, zip, country)
            }
            val bday = at(iBday).let { if (it.isBlank() || it.startsWith("0/0/00")) "" else normaliseDate(it) }
            val c = ContactDto(
                prefix = if (header.getOrNull(iTitlePrefix) == "title" && iJob >= 0) at(iTitlePrefix) else if (header.getOrNull(iTitlePrefix) == "name prefix") at(iTitlePrefix) else "",
                firstName = at(iFirst), middleName = at(iMiddle), lastName = at(iLast), suffix = at(iSuffix), nickname = at(iNick),
                displayName = at(iName), emails = emails, phones = phones, addresses = addrs,
                company = at(iCompany), department = at(iDept), jobTitle = at(iJob), birthday = bday,
                website = at(iWeb), notes = at(iNotes),
                categories = at(iCats).split(';', ':').map { it.trim().removePrefix("* ") }.filter { it.isNotBlank() && it != "myContacts" && it != "* myContacts" }
            )
            if (c.bestName().isNotBlank() || c.emails.isNotEmpty() || c.phones.isNotEmpty()) out += c
        }
        return out
    }

    private fun csvCell(v: String): String = if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v

    /** CSV in the desktop mail client layout, readable by most mail and spreadsheet programs. */
    fun writeCsv(contacts: List<ContactDto>): String {
        val cols = listOf(
            "Title", "First Name", "Middle Name", "Last Name", "Suffix", "Nickname", "Company", "Department", "Job Title",
            "Business Street", "Business City", "Business State", "Business Postal Code", "Business Country/Region",
            "Home Street", "Home City", "Home State", "Home Postal Code", "Home Country/Region",
            "Business Phone", "Home Phone", "Mobile Phone", "Business Fax",
            "E-mail Address", "E-mail Display Name", "E-mail 2 Address", "E-mail 3 Address",
            "Birthday", "Anniversary", "Web Page", "Notes", "Categories"
        )
        val sb = StringBuilder(cols.joinToString(",") { csvCell(it) }).append("\r\n")
        for (c in contacts) {
            val work = c.addresses.firstOrNull { it.label == "work" }
            val home = c.addresses.firstOrNull { it.label != "work" }
            fun phone(label: String) = c.phones.firstOrNull { it.label == label }?.value.orEmpty()
            val row = listOf(
                c.prefix, c.firstName, c.middleName, c.lastName, c.suffix, c.nickname, c.company, c.department, c.jobTitle,
                work?.street.orEmpty(), work?.city.orEmpty(), work?.region.orEmpty(), work?.postalCode.orEmpty(), work?.country.orEmpty(),
                home?.street.orEmpty(), home?.city.orEmpty(), home?.region.orEmpty(), home?.postalCode.orEmpty(), home?.country.orEmpty(),
                phone("work"), phone("home"), phone("mobile"), phone("fax"),
                c.emails.getOrNull(0)?.value.orEmpty(), if (c.emails.isNotEmpty()) c.bestName() else "", c.emails.getOrNull(1)?.value.orEmpty(), c.emails.getOrNull(2)?.value.orEmpty(),
                c.birthday.takeIf { it.length == 10 }.orEmpty(), c.anniversary.takeIf { it.length == 10 }.orEmpty(), c.website, c.notes, c.categories.joinToString(";")
            )
            sb.append(row.joinToString(",") { csvCell(it) }).append("\r\n")
        }
        return sb.toString()
    }
}
