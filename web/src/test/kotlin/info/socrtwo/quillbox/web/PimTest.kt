package info.socrtwo.quillbox.web

import info.socrtwo.quillbox.web.spam.MessageFacts
import info.socrtwo.quillbox.web.spam.Rule
import info.socrtwo.quillbox.web.spam.RuleCriterion
import info.socrtwo.quillbox.web.spam.RuleEngine
import info.socrtwo.quillbox.web.spam.RuleField
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PimTest {
    private val ny = ZoneId.of("America/New_York")

    // --- iCalendar --------------------------------------------------------------------------------

    private val outlookInvite = """
        BEGIN:VCALENDAR
        METHOD:REQUEST
        PRODID:Microsoft Exchange Server 2010
        VERSION:2.0
        BEGIN:VTIMEZONE
        TZID:Eastern Standard Time
        BEGIN:STANDARD
        DTSTART:16010101T020000
        TZOFFSETFROM:-0400
        TZOFFSETTO:-0500
        RRULE:FREQ=YEARLY;INTERVAL=1;BYDAY=1SU;BYMONTH=11
        END:STANDARD
        END:VTIMEZONE
        BEGIN:VEVENT
        ORGANIZER;CN=Jane Smith:mailto:jane.smith@example.com
        ATTENDEE;ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;CN=Demo User:mailto:demo@quillbox.test
        DESCRIPTION;LANGUAGE=en-US:Agenda:\n1. Census records\, 1910\n2. Next steps
        RRULE:FREQ=MONTHLY;COUNT=6;BYDAY=2TU
        SUMMARY;LANGUAGE=en-US:Genealogy society board meeting
        DTSTART;TZID=Eastern Standard Time:20261013T190000
        DTEND;TZID=Eastern Standard Time:20261013T203000
        UID:040000008200E00074C5B7101A82E0080000000012345
        CLASS:PUBLIC
        SEQUENCE:2
        LOCATION;LANGUAGE=en-US:Library annex
        X-MICROSOFT-CDO-BUSYSTATUS:TENTATIVE
        BEGIN:VALARM
        TRIGGER:-PT30M
        ACTION:DISPLAY
        END:VALARM
        END:VEVENT
        END:VCALENDAR
    """.trimIndent()

    @Test
    fun `parses a desktop-client invitation with a Windows time zone and an nth-weekday rule`() {
        val p = ICal.toEvents(outlookInvite, ZoneId.of("Europe/London"))
        assertEquals("REQUEST", p.method)
        val e = p.events.single()
        assertEquals("Genealogy society board meeting", e.title)
        assertEquals("2026-10-13T19:00", e.start)
        assertEquals("2026-10-13T20:30", e.end)
        assertEquals("America/New_York", e.tz)
        assertEquals("Agenda:\n1. Census records, 1910\n2. Next steps", e.notes)
        assertEquals("MONTHLY", e.recurrence?.freq)
        assertEquals(listOf("TU"), e.recurrence?.byDay)
        assertEquals(2, e.recurrence?.bySetPos)
        assertEquals(6, e.recurrence?.count)
        assertEquals(30, e.reminderMinutes)
        assertEquals("tentative", e.showAs)
        assertEquals(2, e.sequence)
        assertEquals("jane.smith@example.com", e.organizer?.email)
        assertEquals("Demo User", e.attendees.single().name)
    }

    @Test
    fun `utc and all-day times, exdates and folded lines`() {
        val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:a1\r\nSUMMARY:A very long summary that is folded across two lines because it is longe\r\n r than seventy-five octets\r\n" +
            "DTSTART:20261014T133000Z\r\nDURATION:PT45M\r\nRRULE:FREQ=WEEKLY;BYDAY=WE;UNTIL=20261231T235959Z\r\nEXDATE:20261021T133000Z\r\nEND:VEVENT\r\n" +
            "BEGIN:VEVENT\r\nUID:a2\r\nSUMMARY:Holiday\r\nDTSTART;VALUE=DATE:20261126\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        val p = ICal.toEvents(ics, ny)
        val a = p.events.first { it.uid == "a1" }
        assertTrue(a.title.endsWith("longer than seventy-five octets"))
        assertEquals("2026-10-14T09:30", a.start)
        assertEquals("2026-10-14T10:15", a.end)
        assertEquals(listOf("2026-10-21T09:30"), a.exdates)
        assertEquals("2026-12-31", a.recurrence?.until)
        val h = p.events.first { it.uid == "a2" }
        assertTrue(h.allDay)
        assertEquals("2026-11-26", h.start)
        assertEquals("2026-11-27", h.end)
    }

    @Test
    fun `written calendars read back identically`() {
        val e = EventDto(
            id = "x", calendarId = "calendar", uid = "u-1@quillbox", title = "Board; review, Q3", location = "Room 2",
            notes = "Line one\nLine two", start = "2026-03-08T01:30", end = "2026-03-08T03:30", tz = "America/New_York",
            recurrence = RecurrenceDto("WEEKLY", 2, listOf("MO", "WE"), until = "2026-06-30"), exdates = listOf("2026-03-23T01:30"),
            reminderMinutes = 10, showAs = "oof", categories = listOf("Blue category"),
            organizer = AttendeeDto("demo@quillbox.test", "Demo"), attendees = listOf(AttendeeDto("a@example.com", "A, B"))
        )
        val text = ICal.calendar(listOf(e), ny, "REQUEST")
        assertTrue(text.contains("BEGIN:VTIMEZONE") && text.contains("TZID:America/New_York") && text.contains("BYDAY=2SU"), text)
        assertTrue(text.split("\r\n").all { it.toByteArray().size <= 75 }, text.split("\r\n").filter { it.toByteArray().size > 75 }.toString())
        val back = ICal.toEvents(text, ZoneId.of("UTC")).events.single()
        assertEquals(e.title, back.title)
        assertEquals(e.notes, back.notes)
        assertEquals(e.start, back.start)
        assertEquals(e.end, back.end)
        assertEquals(e.tz, back.tz)
        assertEquals(e.recurrence, back.recurrence)
        assertEquals(e.exdates, back.exdates)
        assertEquals(10, back.reminderMinutes)
        assertEquals("oof", back.showAs)
        assertEquals("A, B", back.attendees.single().name)
        val reply = ICal.calendar(listOf(e), ny, "REPLY", onlyAttendee = AttendeeDto("a@example.com", status = "ACCEPTED"))
        assertTrue(reply.contains("METHOD:REPLY") && reply.contains("PARTSTAT=ACCEPTED") && !reply.contains("RRULE:FREQ=WEEKLY"), reply)
    }

    @Test
    fun `time zone names from various vendors resolve`() {
        assertEquals(ZoneId.of("America/Chicago"), ICal.resolveZone("Central Standard Time"))
        assertEquals(ZoneId.of("Europe/Berlin"), ICal.resolveZone("/mozilla.org/20050126_1/Europe/Berlin"))
        assertEquals(ZoneId.of("Asia/Kolkata"), ICal.resolveZone("India Standard Time"))
        assertNull(ICal.resolveZone("Not A Zone"))
    }

    // --- vCard & CSV ---------------------------------------------------------------------------------

    @Test
    fun `vcard 2_1 with quoted-printable and vcard 3 round trip`() {
        val v21 = "BEGIN:VCARD\r\nVERSION:2.1\r\nN;CHARSET=utf-8:Pruitt;Paul;D.;;\r\nFN:Paul D. Pruitt\r\nORG:Socrtwo;Research\r\n" +
            "NOTE;ENCODING=QUOTED-PRINTABLE;CHARSET=utf-8:Genealogy =E2=80=94 Pruitt line=0D=0A=\r\nsecond line\r\n" +
            "TEL;CELL;VOICE:+1 555 0100\r\nEMAIL;PREF;INTERNET:paul@example.com\r\nBDAY:19500517\r\n" +
            "ADR;HOME:;;12 Main St;Springfield;IL;62701;USA\r\nEND:VCARD\r\n"
        val c = VCard.parse(v21).single()
        assertEquals("Paul", c.firstName); assertEquals("Pruitt", c.lastName); assertEquals("D.", c.middleName)
        assertEquals("Socrtwo", c.company); assertEquals("Research", c.department)
        assertEquals("Genealogy — Pruitt line\r\nsecond line", c.notes)
        assertEquals("mobile", c.phones.single().label)
        assertEquals("1950-05-17", c.birthday)
        assertEquals("Springfield", c.addresses.single().city)
        val back = VCard.parse(VCard.write(listOf(c))).single()
        assertEquals(c.copy(notes = back.notes), back.copy(notes = back.notes))
    }

    @Test
    fun `csv from a desktop client and from google contacts`() {
        val outlook = "First Name,Last Name,E-mail Address,Mobile Phone,Company,Birthday,Categories\r\n" +
            "Jane,Smith,jane.smith@example.com,555-0101,\"Smith, Jones & Co\",5/17/1980,Family;Genealogy\r\n"
        val a = VCard.parseCsv(outlook).single()
        assertEquals("Smith, Jones & Co", a.company)
        assertEquals("1980-05-17", a.birthday)
        assertEquals(listOf("Family", "Genealogy"), a.categories)
        val google = "Name,Given Name,Family Name,E-mail 1 - Type,E-mail 1 - Value,Phone 1 - Type,Phone 1 - Value\n" +
            "Tom Rivera,Tom,Rivera,* Home,tom@example.org ::: tom.r@example.net,Mobile,555-0199\n"
        val g = VCard.parseCsv(google).single()
        assertEquals(listOf("tom@example.org", "tom.r@example.net"), g.emails.map { it.value })
        assertEquals("555-0199", g.phones.single().value)
        val csv = VCard.writeCsv(listOf(a))
        assertEquals(a.emails, VCard.parseCsv(csv).single().emails)
    }

    // --- store ----------------------------------------------------------------------------------------

    @Test
    fun `pim store crud, imports merge duplicates, metadata and outbox persist`() {
        val dir = Files.createTempDirectory("qb-pim").toFile()
        try {
            val ds = DataStore(dir)
            val s = PimStore(ds, "me@example.com")
            assertEquals("calendar", s.data().calendars.single().id)
            val json = Json { encodeDefaults = true }
            val saved = s.upsert("contacts", json.parseToJsonElement("""{"firstName":"Jane","emails":[{"label":"work","value":"Jane.Smith@example.com"}]}""").jsonObject)
            assertTrue(saved.toString().contains("\"id\":\"ct-"))
            assertTrue("jane.smith@example.com" in s.contactEmails())
            val r = s.importContacts(listOf(ContactDto(lastName = "Smith", emails = listOf(LabeledValue("home", "jane.smith@example.com")), phones = listOf(LabeledValue("mobile", "555")))))
            assertEquals(0, r.imported); assertEquals(1, r.updated)
            assertEquals("Smith", s.data().contacts.single().lastName)
            s.upsertEvent(EventDto(title = "Dentist", start = "2026-10-20T09:00", end = "2026-10-20T10:00", tz = "America/New_York"))
            s.updateMeta(MetaUpdateRequest(listOf("<m1@x>"), categoriesAdd = listOf("Red category"), pinned = true, flagDue = "2026-10-21"))
            s.schedule(SendRequest(account = AccountDto("", "me@example.com", "h", 1, "IMAP", "NONE", "h", 1, "NONE", "u", "secret"), to = listOf("a@b.c"), subject = "Later", body = "x"), 123L)
            val again = PimStore(ds, "me@example.com")
            assertEquals("Dentist", again.data().events.single().title)
            assertEquals(listOf("Red category"), again.meta("<m1@x>")?.categories)
            assertTrue(again.meta("<m1@x>")?.pinned == true)
            val queued = again.outboxItems().single()
            assertNull(queued.request.account, "scheduled messages must not store the password")
            assertEquals(1, again.dueOutbox(200L).size)
            again.updateMeta(MetaUpdateRequest(listOf("<m1@x>"), categoriesSet = emptyList(), pinned = false, clearFlag = true))
            assertNull(again.meta("<m1@x>"), "empty metadata is dropped")
            assertFalse(runCatching { again.remove("calendars", "calendar") }.isSuccess, "default calendar is protected")
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun `mbox split undoes from-quoting`() {
        val mbox = "From a@b Thu Sep 24 09:12:00 2026\nSubject: one\n\n>From the start\nbody\n\nFrom c@d Thu Sep 24 09:13:00 2026\nSubject: two\n\nhello\n"
        val parts = Mbox.split(mbox.toByteArray())
        assertEquals(2, parts.size)
        assertTrue(String(parts[0]).contains("\r\nFrom the start\r\n"))
        assertTrue(String(parts[1]).startsWith("Subject: two"))
        assertEquals(1, Mbox.split("Subject: single\r\n\r\nx".toByteArray()).size)
    }

    // --- new rule conditions ----------------------------------------------------------------------------

    @Test
    fun `rule conditions on cc, attachments and importance`() {
        val facts = MessageFacts(messageId = "<m>", fromName = "", fromAddress = "a@b.c", subject = "s", bodyText = "",
            ccAddresses = listOf("boss@work.example"), attachmentNames = listOf("report.pdf"), importance = "high")
        fun rule(f: RuleField, v: String) = Rule("r", "r", criteria = listOf(RuleCriterion(f, value = v)))
        assertTrue(RuleEngine.matches(rule(RuleField.CC, "boss@"), facts))
        assertTrue(RuleEngine.matches(rule(RuleField.TO_OR_CC, "work.example"), facts))
        assertTrue(RuleEngine.matches(rule(RuleField.HAS_ATTACHMENT, "true"), facts))
        assertFalse(RuleEngine.matches(rule(RuleField.HAS_ATTACHMENT, "false"), facts))
        assertTrue(RuleEngine.matches(rule(RuleField.IMPORTANCE, "high"), facts))
        assertEquals("high", Mapping.importanceOf(mapOf("X-Priority" to listOf("1 (Highest)"))))
        assertEquals("low", Mapping.importanceOf(mapOf("Importance" to listOf("Low"))))
        assertNotNull(Mapping.messageIds("<a@x> <b@y>").takeIf { it == listOf("<a@x>", "<b@y>") })
    }
}
