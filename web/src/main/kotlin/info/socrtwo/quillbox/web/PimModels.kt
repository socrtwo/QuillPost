package info.socrtwo.quillbox.web

import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------------------
// Calendar, People and To Do. IMAP and POP3 carry none of these, so — exactly like a desktop
// mail client keeping an IMAP account's calendar "on this computer" — they live in the
// account's data directory and travel as iCalendar / vCard files and email invitations.
//
// Times are stored the way iCalendar stores them: a local wall-clock time plus its IANA time
// zone. Timed values are "YYYY-MM-DDTHH:mm", all-day values "YYYY-MM-DD" (end exclusive).
// ---------------------------------------------------------------------------------------

@Serializable
data class LabeledValue(val label: String = "", val value: String = "")

@Serializable
data class PostalAddress(
    val label: String = "home",
    val street: String = "",
    val city: String = "",
    val region: String = "",
    val postalCode: String = "",
    val country: String = ""
)

@Serializable
data class ContactDto(
    val id: String = "",
    val prefix: String = "",
    val firstName: String = "",
    val middleName: String = "",
    val lastName: String = "",
    val suffix: String = "",
    val nickname: String = "",
    val displayName: String = "",
    val emails: List<LabeledValue> = emptyList(),
    val phones: List<LabeledValue> = emptyList(),
    val addresses: List<PostalAddress> = emptyList(),
    val company: String = "",
    val department: String = "",
    val jobTitle: String = "",
    /** "YYYY-MM-DD", or "--MM-DD" when the year is unknown. */
    val birthday: String = "",
    val anniversary: String = "",
    val website: String = "",
    val notes: String = "",
    val categories: List<String> = emptyList(),
    val favorite: Boolean = false,
    val created: Long = 0,
    val updated: Long = 0
) {
    /** The name to show: explicit display name, else "First Last", else company, else first email. */
    fun bestName(): String = displayName.ifBlank {
        listOf(firstName, middleName, lastName).filter { it.isNotBlank() }.joinToString(" ")
    }.ifBlank { company }.ifBlank { emails.firstOrNull()?.value.orEmpty() }
}

@Serializable
data class ListMember(val name: String = "", val email: String = "")

/** A contact list (distribution list / contact group). */
@Serializable
data class ContactListDto(
    val id: String = "",
    val name: String = "",
    val members: List<ListMember> = emptyList(),
    val notes: String = "",
    val created: Long = 0,
    val updated: Long = 0
)

@Serializable
data class CalendarDto(
    val id: String = "",
    val name: String = "",
    val color: String = "#0f6cbd",
    /** local, subscription */
    val kind: String = "local",
    val url: String? = null,
    val visible: Boolean = true,
    val readOnly: Boolean = false,
    val lastSync: Long = 0,
    val error: String? = null
)

/** The supported subset of RRULE; covers every pattern a desktop mail client's recurrence dialog offers. */
@Serializable
data class RecurrenceDto(
    /** DAILY, WEEKLY, MONTHLY, YEARLY */
    val freq: String = "WEEKLY",
    val interval: Int = 1,
    /** Two-letter weekdays: MO TU WE TH FR SA SU. */
    val byDay: List<String> = emptyList(),
    val byMonthDay: Int? = null,
    val byMonth: Int? = null,
    /** With byDay in MONTHLY/YEARLY: 1..4 = first..fourth, -1 = last. */
    val bySetPos: Int? = null,
    val count: Int? = null,
    /** Last day an occurrence may start on, "YYYY-MM-DD". */
    val until: String? = null
)

@Serializable
data class AttendeeDto(
    val email: String = "",
    val name: String = "",
    /** REQ-PARTICIPANT, OPT-PARTICIPANT */
    val role: String = "REQ-PARTICIPANT",
    /** NEEDS-ACTION, ACCEPTED, TENTATIVE, DECLINED */
    val status: String = "NEEDS-ACTION"
)

@Serializable
data class EventDto(
    val id: String = "",
    val calendarId: String = "",
    /** iCalendar UID, shared with invitations and their replies. */
    val uid: String = "",
    val title: String = "",
    val location: String = "",
    val notes: String = "",
    val start: String = "",
    val end: String = "",
    val allDay: Boolean = false,
    /** IANA time zone of [start]/[end] (ignored for all-day events). */
    val tz: String = "",
    val recurrence: RecurrenceDto? = null,
    /** Occurrence starts (same format as [start]) removed from the series. */
    val exdates: List<String> = emptyList(),
    /** Set on a changed single occurrence: the original start of the occurrence it replaces. */
    val recurrenceId: String? = null,
    /** Minutes before the start; null = no reminder. */
    val reminderMinutes: Int? = 15,
    /** free, tentative, busy, oof, workingElsewhere */
    val showAs: String = "busy",
    val categories: List<String> = emptyList(),
    val private: Boolean = false,
    val organizer: AttendeeDto? = null,
    val attendees: List<AttendeeDto> = emptyList(),
    val sequence: Int = 0,
    /** Our answer when we are an attendee: ACCEPTED, TENTATIVE, DECLINED, NEEDS-ACTION. */
    val myStatus: String? = null,
    val cancelled: Boolean = false,
    val onlineUrl: String = "",
    val created: Long = 0,
    val updated: Long = 0
)

@Serializable
data class TaskStepDto(val id: String = "", val title: String = "", val done: Boolean = false)

@Serializable
data class TaskListDto(val id: String = "", val name: String = "", val color: String = "", val created: Long = 0)

@Serializable
data class TaskDto(
    val id: String = "",
    val listId: String = "tasks",
    val title: String = "",
    val notes: String = "",
    /** "YYYY-MM-DD" */
    val due: String? = null,
    /** Epoch milliseconds. */
    val reminder: Long? = null,
    val repeat: RecurrenceDto? = null,
    val important: Boolean = false,
    /** The day ("YYYY-MM-DD") this task was added to My Day. */
    val myDay: String? = null,
    val completed: Boolean = false,
    val completedAt: Long = 0,
    val steps: List<TaskStepDto> = emptyList(),
    val categories: List<String> = emptyList(),
    val created: Long = 0,
    val updated: Long = 0
)

@Serializable
data class PimData(
    val version: Int = 1,
    val calendars: List<CalendarDto> = emptyList(),
    val events: List<EventDto> = emptyList(),
    val contacts: List<ContactDto> = emptyList(),
    val contactLists: List<ContactListDto> = emptyList(),
    val taskLists: List<TaskListDto> = emptyList(),
    val tasks: List<TaskDto> = emptyList()
)

@Serializable
data class ImportResult(val imported: Int, val updated: Int = 0, val skipped: Int = 0, val errors: List<String> = emptyList())

@Serializable
data class SubscribeRequest(val name: String = "", val url: String, val color: String = "#8764b8")

@Serializable
data class InviteSendRequest(val eventId: String, /** REQUEST or CANCEL */ val method: String = "REQUEST", val comment: String = "")

@Serializable
data class InviteRespondRequest(
    val folder: String,
    val uid: Long,
    /** ACCEPTED, TENTATIVE, DECLINED, or REMOVE (for a cancellation) */
    val response: String,
    val comment: String = "",
    val sendResponse: Boolean = true,
    val tz: String = "",
    val calendarId: String? = null
)

/** A meeting request, update, reply or cancellation found in a message. */
@Serializable
data class InviteDto(
    /** REQUEST, REPLY, CANCEL, PUBLISH, COUNTER */
    val method: String,
    val event: EventDto,
    /** For REPLY: who answered and how. */
    val replyFrom: String? = null,
    val replyStatus: String? = null,
    /** The matching event already in a calendar, if any. */
    val existingEventId: String? = null,
    val existingStatus: String? = null,
    /** True when the account is the organizer (we sent the original request). */
    val isOrganizer: Boolean = false
)

// ---------------------------------------------------------------------------------------
// Mail metadata kept per Message-ID: categories, pins, follow-up flags and snoozes. Keyed by
// Message-ID rather than UID so it survives moves between folders.
// ---------------------------------------------------------------------------------------

@Serializable
data class MessageMetaDto(
    val categories: List<String> = emptyList(),
    val pinned: Boolean = false,
    /** Follow-up due date "YYYY-MM-DD". */
    val flagDue: String? = null,
    val flagStart: String? = null,
    val flagCompleted: Boolean = false,
    val flagCompletedAt: Long = 0,
    /** Epoch ms of a follow-up reminder. */
    val reminder: Long? = null,
    val snoozeUntil: Long? = null,
    val snoozedFrom: String? = null,
    val subject: String = "",
    val from: String = "",
    val updated: Long = 0
) {
    fun isEmpty() = categories.isEmpty() && !pinned && flagDue == null && flagStart == null && !flagCompleted && reminder == null && snoozeUntil == null
}

@Serializable
data class MailMetaFile(
    val version: Int = 1,
    val meta: Map<String, MessageMetaDto> = emptyMap(),
    /** Thread roots (Message-IDs) whose future messages go straight to Deleted Items. */
    val ignoredThreads: List<String> = emptyList(),
    /** Automatic replies already sent: sender -> epoch ms. */
    val autoReplied: Map<String, Long> = emptyMap()
)

@Serializable
data class MetaUpdateRequest(
    val messageIds: List<String>,
    val categoriesAdd: List<String> = emptyList(),
    val categoriesRemove: List<String> = emptyList(),
    val categoriesSet: List<String>? = null,
    val pinned: Boolean? = null,
    /** Set a follow-up flag due on this date ("" clears the date but keeps the flag). */
    val flagDue: String? = null,
    val flagStart: String? = null,
    val flagCompleted: Boolean? = null,
    val clearFlag: Boolean = false,
    val reminder: Long? = null,
    val clearReminder: Boolean = false
)

@Serializable
data class SnoozeRequest(val items: List<MessageRef>, /** null = return to the Inbox now */ val until: Long? = null)

@Serializable
data class IgnoreRequest(val items: List<MessageRef>, val ignore: Boolean = true)

@Serializable
data class ScheduledSendDto(
    val id: String,
    val sendAt: Long,
    val request: SendRequest,
    val created: Long,
    val error: String? = null,
    val attempts: Int = 0
)

@Serializable
data class OutboxFile(val version: Int = 1, val items: List<ScheduledSendDto> = emptyList())

@Serializable
data class OutboxItemDto(val id: String, val sendAt: Long, val to: List<String>, val subject: String, val created: Long, val error: String? = null)

@Serializable
data class SendResponse(val status: String, val id: String? = null, val sendAt: Long? = null)

// ---------------------------------------------------------------------------------------
// Settings that a desktop mail client keeps per account.
// ---------------------------------------------------------------------------------------

@Serializable
data class CategoryDto(val name: String, val color: String)

@Serializable
data class SignatureDto(val id: String, val name: String, val html: String)

@Serializable
data class TemplateDto(val id: String, val name: String, val subject: String = "", val html: String = "")

/** One step of a Quick Step: MOVE, COPY, DELETE, ARCHIVE, MARK_READ, MARK_UNREAD, FLAG, UNFLAG, PIN, CATEGORIZE, FORWARD, REPLY. */
@Serializable
data class QuickActionDto(
    val type: String,
    val folder: String? = null,
    val category: String? = null,
    val to: String? = null,
    val templateId: String? = null
)

@Serializable
data class QuickStepDto(val id: String, val name: String, val actions: List<QuickActionDto> = emptyList())

@Serializable
data class AutoReplyDto(
    val enabled: Boolean = false,
    /** Optional window, epoch ms. */
    val start: Long? = null,
    val end: Long? = null,
    val subject: String = "",
    val html: String = "",
    /** Only answer people in your contacts. */
    val contactsOnly: Boolean = false,
    /** Answer each sender at most once per this many days. */
    val oncePerDays: Int = 4
)

@Serializable
data class FocusedDto(
    val enabled: Boolean = true,
    val alwaysFocused: List<String> = emptyList(),
    val alwaysOther: List<String> = emptyList()
)

// ---------------------------------------------------------------------------------------
// Mailbox extras
// ---------------------------------------------------------------------------------------

@Serializable
data class FolderCreateRequest(val name: String, val parent: String? = null)

@Serializable
data class FolderRenameRequest(val path: String, val name: String)

@Serializable
data class FolderMoveRequest(val path: String, val parent: String? = null)

@Serializable
data class FolderPathRequest(val path: String)

@Serializable
data class QuotaDto(val usedKb: Long, val limitKb: Long)

@Serializable
data class MdnRequest(val folder: String, val uid: Long)

@Serializable
data class EventRespondRequest(val eventId: String, val response: String, val comment: String = "", val sendResponse: Boolean = true)

@Serializable
data class TranslateRequest(val folder: String, val uid: Long, val language: String = "English")

@Serializable
data class TranslateResponse(val language: String, val text: String)

@Serializable
data class ThreadResponse(val messages: List<MessageSummaryDto>)
