/* Quillbox — shared calendar/contacts/tasks client: dates and time zones, recurrence
 * expansion, holiday and birthday calendars, the data cache and reminders.
 * Loaded before app.js; everything here only touches app.js globals (api, prefs, state, el,
 * icon, toast) inside functions, never at load time.
 */
"use strict";

// ============================================================================ dates & time zones
const VIEW_TZ = (() => { try { return Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC"; } catch { return "UTC"; } })();
const DAY_MS = 86400000;
const pad2 = (n) => String(n).padStart(2, "0");
const ymd = (d) => `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`;
const localIso = (d) => `${ymd(d)}T${pad2(d.getHours())}:${pad2(d.getMinutes())}`;
function parseYmd(s) { const [y, m, d] = String(s).slice(0, 10).split("-").map(Number); return new Date(y, m - 1, d); }
function addDays(d, n) { const x = new Date(d); x.setDate(x.getDate() + n); return x; }
function addMinutes(d, n) { return new Date(d.getTime() + n * 60000); }
function startOfDay(d) { return new Date(d.getFullYear(), d.getMonth(), d.getDate()); }
function sameDay(a, b) { return a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate(); }
function startOfWeek(d, firstDay = 0) { const x = startOfDay(d); const diff = (x.getDay() - firstDay + 7) % 7; return addDays(x, -diff); }
function fmtTime(d) { return d.toLocaleTimeString(undefined, { hour: "numeric", minute: "2-digit" }); }
function fmtDay(d, opts = { weekday: "short", month: "short", day: "numeric" }) { return d.toLocaleDateString(undefined, opts); }
function firstDayOfWeek() { return Number(prefs.get("weekStart", 0)) || 0; }

const tzFormatters = {};
function tzFormatter(tz) {
  return tzFormatters[tz] || (tzFormatters[tz] = new Intl.DateTimeFormat("en-US", { timeZone: tz, hourCycle: "h23", year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit" }));
}
/** Offset of [tz] from UTC at [date], in milliseconds (local − UTC). */
function tzOffsetMs(tz, date) {
  try {
    const p = tzFormatter(tz).formatToParts(date);
    const g = (t) => Number(p.find((x) => x.type === t).value);
    return Date.UTC(g("year"), g("month") - 1, g("day"), g("hour") % 24, g("minute"), g("second")) - Math.floor(date.getTime() / 1000) * 1000;
  } catch { return -date.getTimezoneOffset() * 60000; }
}
/** "YYYY-MM-DDTHH:mm" wall-clock time in [tz] → Date (an instant). Dates without a time are local midnight. */
function zonedToDate(local, tz) {
  const [d, t = "00:00"] = String(local).split("T");
  const [Y, M, D] = d.split("-").map(Number);
  const [h, mi] = t.split(":").map(Number);
  if (!tz || tz === VIEW_TZ || String(local).length === 10) return new Date(Y, M - 1, D, h || 0, mi || 0);
  const wall = Date.UTC(Y, M - 1, D, h || 0, mi || 0);
  let utc = wall;
  for (let i = 0; i < 3; i++) utc = wall - tzOffsetMs(tz, new Date(utc));
  return new Date(utc);
}
/** Date → "YYYY-MM-DDTHH:mm" wall-clock time in [tz]. */
function dateToZoned(date, tz) {
  if (!tz || tz === VIEW_TZ) return localIso(date);
  const p = tzFormatter(tz).formatToParts(date);
  const g = (t) => p.find((x) => x.type === t).value;
  return `${g("year")}-${g("month")}-${g("day")}T${pad2(Number(g("hour")) % 24)}:${g("minute")}`;
}

// ============================================================================ recurrence
const WEEKDAYS = ["SU", "MO", "TU", "WE", "TH", "FR", "SA"];
const civilStr = (y, m, d) => `${y}-${pad2(m)}-${pad2(d)}`;
const daysInMonth = (y, m) => new Date(Date.UTC(y, m, 0)).getUTCDate();
const civilDow = (y, m, d) => new Date(Date.UTC(y, m - 1, d)).getUTCDay();
function civilAdd(y, m, d, n) { const t = new Date(Date.UTC(y, m - 1, d + n)); return [t.getUTCFullYear(), t.getUTCMonth() + 1, t.getUTCDate()]; }
function civilDays(y, m, d) { return Math.floor(Date.UTC(y, m - 1, d) / DAY_MS); }

/** Days of month (y, m) whose weekday is in [days], then the [pos]th of them (1-based, −1 = last). */
function nthWeekdayOfMonth(y, m, days, pos) {
  const want = new Set(days.map((x) => WEEKDAYS.indexOf(x)));
  const list = [];
  for (let d = 1; d <= daysInMonth(y, m); d++) if (want.has(civilDow(y, m, d))) list.push(d);
  if (!list.length) return null;
  return pos > 0 ? list[pos - 1] ?? null : list[list.length + pos] ?? null;
}

/**
 * Occurrences of [ev] overlapping [from, to). Recurrence is expanded on the calendar of the
 * event's own time zone (so a 9:00 meeting stays at 9:00 across daylight-saving changes), then
 * converted to instants. [overrides] holds occurrence keys replaced by edited single instances.
 */
function expandEvent(ev, from, to, overrides) {
  const out = [];
  const allDay = !!ev.allDay || String(ev.start).length === 10;
  const tz = allDay ? null : (ev.tz || VIEW_TZ);
  const toDate = (local) => (allDay ? parseYmd(local) : zonedToDate(local, tz));
  const s0 = toDate(ev.start);
  const e0 = toDate(ev.end || ev.start);
  const durMs = Math.max(0, e0 - s0);
  const durDays = allDay ? Math.max(1, Math.round((parseYmd(ev.end || ev.start) - parseYmd(ev.start)) / DAY_MS)) : 0;
  const make = (key) => { const s = toDate(key); const e = allDay ? addDays(s, durDays) : new Date(s.getTime() + durMs); return { ev, start: s, end: e, key, allDay }; };
  if (!ev.recurrence) {
    const o = make(ev.start);
    if (o.end > from && o.start < to || (o.start >= from && o.start < to)) out.push(o);
    return out;
  }
  const r = ev.recurrence;
  const interval = Math.max(1, r.interval || 1);
  const timePart = allDay ? "" : String(ev.start).slice(10);
  const [Y, M, D] = String(ev.start).slice(0, 10).split("-").map(Number);
  const startStr = civilStr(Y, M, D);
  const ex = new Set(ev.exdates || []);
  const limit = ymd(addDays(to, 1));
  let count = 0, guard = 0;
  // Returns false once the series is exhausted or past the window.
  const emit = (y, m, d) => {
    const ds = civilStr(y, m, d);
    if (ds < startStr) return true;
    if (r.until && ds > r.until) return false;
    if (r.count && count >= r.count) return false;
    count++;
    if (ds > limit) return false;
    const key = ds + timePart;
    if (!ex.has(key) && !(overrides && overrides.has(key))) {
      const o = make(key);
      if (o.end > from && o.start < to) out.push(o);
    }
    return true;
  };
  // Without COUNT, whole periods before the window can be skipped.
  const fromDays = civilDays(from.getFullYear(), from.getMonth() + 1, from.getDate()) - durDays - 2;
  const startDays = civilDays(Y, M, D);
  const freq = String(r.freq || "WEEKLY").toUpperCase();
  if (freq === "DAILY") {
    let k = !r.count ? Math.max(0, Math.floor((fromDays - startDays) / interval)) : 0;
    for (; guard++ < 5000; k++) { const [y, m, d] = civilAdd(Y, M, D, k * interval); if (!emit(y, m, d)) break; }
  } else if (freq === "WEEKLY") {
    const days = (r.byDay && r.byDay.length ? r.byDay : [WEEKDAYS[civilDow(Y, M, D)]]).map((x) => (WEEKDAYS.indexOf(x) + 6) % 7).sort((a, b) => a - b);
    const mondayOffset = (civilDow(Y, M, D) + 6) % 7;
    let w = !r.count ? Math.max(0, Math.floor((fromDays - startDays) / (7 * interval))) : 0;
    outer: for (; guard++ < 3000; w++) {
      for (const di of days) {
        const [y, m, d] = civilAdd(Y, M, D, w * 7 * interval - mondayOffset + di);
        if (!emit(y, m, d)) break outer;
      }
    }
  } else if (freq === "MONTHLY") {
    let k = 0;
    if (!r.count) { const months = (from.getFullYear() - Y) * 12 + from.getMonth() - (M - 1) - 1; k = Math.max(0, Math.floor(months / interval)); }
    for (; guard++ < 2400; k++) {
      const idx = M - 1 + k * interval;
      const y = Y + Math.floor(idx / 12), m = (idx % 12) + 1;
      let day;
      if (r.byDay && r.byDay.length && r.bySetPos) day = nthWeekdayOfMonth(y, m, r.byDay, r.bySetPos);
      else { const want = r.byMonthDay ?? D; day = want < 0 ? daysInMonth(y, m) + 1 + want : want; if (day > daysInMonth(y, m)) day = null; }
      if (day == null) { if (civilStr(y, m, 1) > limit) break; continue; }
      if (!emit(y, m, day)) break;
    }
  } else if (freq === "YEARLY") {
    let k = !r.count ? Math.max(0, Math.floor((from.getFullYear() - Y - 1) / interval)) : 0;
    for (; guard++ < 500; k++) {
      const y = Y + k * interval, m = r.byMonth || M;
      let day;
      if (r.byDay && r.byDay.length && r.bySetPos) day = nthWeekdayOfMonth(y, m, r.byDay, r.bySetPos);
      else { day = r.byMonthDay ?? D; if (day > daysInMonth(y, m)) day = null; }
      if (day == null) { if (civilStr(y, m, 1) > limit) break; continue; }
      if (!emit(y, m, day)) break;
    }
  }
  return out;
}

/** Human description of a recurrence: "Every 2 weeks on Mon, Wed until Dec 31, 2026". */
function describeRecurrence(r) {
  if (!r) return "";
  const names = { MO: "Mon", TU: "Tue", WE: "Wed", TH: "Thu", FR: "Fri", SA: "Sat", SU: "Sun" };
  const ord = { 1: "first", 2: "second", 3: "third", 4: "fourth", "-1": "last" };
  const months = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"];
  const n = Math.max(1, r.interval || 1);
  const unit = { DAILY: "day", WEEKLY: "week", MONTHLY: "month", YEARLY: "year" }[r.freq] || "week";
  let s = n > 1 ? `Every ${n} ${unit}s` : `Every ${unit}`;
  if (r.freq === "DAILY" && n === 1) s = "Daily";
  if (r.freq === "WEEKLY") {
    const days = (r.byDay || []);
    if (days.length === 5 && ["MO", "TU", "WE", "TH", "FR"].every((d) => days.includes(d)) && n === 1) s = "Every weekday";
    else if (days.length) s += " on " + days.map((d) => names[d]).join(", ");
  }
  if ((r.freq === "MONTHLY" || r.freq === "YEARLY") && r.byDay?.length && r.bySetPos) s += ` on the ${ord[r.bySetPos] || r.bySetPos} ${r.byDay.map((d) => names[d]).join("/")}`;
  else if (r.freq === "MONTHLY" && r.byMonthDay) s += ` on day ${r.byMonthDay}`;
  if (r.freq === "YEARLY") s += ` of ${months[(r.byMonth || 1) - 1]}` + (r.byMonthDay && !r.bySetPos ? ` ${r.byMonthDay}` : "");
  if (r.until) s += ` until ${fmtDay(parseYmd(r.until), { month: "short", day: "numeric", year: "numeric" })}`;
  else if (r.count) s += `, ${r.count} times`;
  return s;
}

// ============================================================================ holidays & birthdays
function easterSunday(y) {
  const a = y % 19, b = Math.floor(y / 100), c = y % 100, d = Math.floor(b / 4), e = b % 4, f = Math.floor((b + 8) / 25);
  const g = Math.floor((b - f + 1) / 3), h = (19 * a + b - d - g + 15) % 30, i = Math.floor(c / 4), k = c % 4;
  const l = (32 + 2 * e + 2 * i - h - k) % 7, m = Math.floor((a + 11 * h + 22 * l) / 451);
  const month = Math.floor((h + l - 7 * m + 114) / 31), day = ((h + l - 7 * m + 114) % 31) + 1;
  return new Date(y, month - 1, day);
}
function orthodoxEaster(y) {
  const a = y % 4, b = y % 7, c = y % 19, d = (19 * c + 15) % 30, e = (2 * a + 4 * b - d + 34) % 7;
  const month = Math.floor((d + e + 114) / 31), day = ((d + e + 114) % 31) + 1;
  return addDays(new Date(y, month - 1, day), 13); // Julian → Gregorian (1900–2099)
}
const nth = (y, m, dow, n) => { const d = nthWeekdayOfMonth(y, m, [WEEKDAYS[dow]], n); return new Date(y, m - 1, d); };

/** Days of [year] mapped onto another calendar system ("hebrew", "islamic-umalqura"). */
const altCalendarCache = {};
function altCalendarDays(year, system) {
  const key = system + year;
  if (altCalendarCache[key]) return altCalendarCache[key];
  const out = [];
  try {
    const f = new Intl.DateTimeFormat("en-US-u-ca-" + system, { day: "numeric", month: "numeric", year: "numeric", timeZone: "UTC" });
    for (let d = new Date(Date.UTC(year, 0, 1)); d.getUTCFullYear() === year; d = new Date(d.getTime() + DAY_MS)) {
      const p = f.formatToParts(d);
      const month = p.find((x) => x.type === "month")?.value || "";
      const day = Number(p.find((x) => x.type === "day")?.value);
      out.push({ date: new Date(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate()), month, day });
    }
  } catch {}
  return (altCalendarCache[key] = out);
}

const HOLIDAY_CALENDARS = [
  { id: "holidays-us", name: "United States holidays", color: "#107c10" },
  { id: "holidays-christian", name: "Christian holidays", color: "#8764b8" },
  { id: "holidays-jewish", name: "Jewish holidays", color: "#0f6cbd" },
  { id: "holidays-islamic", name: "Islamic holidays (approx.)", color: "#038387" },
];

function holidaysFor(id, year) {
  const H = (date, title) => ({ date, title });
  if (id === "holidays-us") {
    return [
      H(new Date(year, 0, 1), "New Year's Day"), H(nth(year, 1, 1, 3), "Martin Luther King Jr. Day"), H(new Date(year, 1, 14), "Valentine's Day"),
      H(nth(year, 2, 1, 3), "Washington's Birthday (Presidents' Day)"), H(nth(year, 5, 0, 2), "Mother's Day"), H(nth(year, 5, 1, -1), "Memorial Day"),
      H(nth(year, 6, 0, 3), "Father's Day"), H(new Date(year, 5, 19), "Juneteenth"), H(new Date(year, 6, 4), "Independence Day"),
      H(nth(year, 9, 1, 1), "Labor Day"), H(nth(year, 10, 1, 2), "Columbus Day / Indigenous Peoples' Day"), H(new Date(year, 9, 31), "Halloween"),
      H(new Date(year, 10, 11), "Veterans Day"), H(nth(year, 11, 4, 4), "Thanksgiving Day"), H(new Date(year, 11, 24), "Christmas Eve"),
      H(new Date(year, 11, 25), "Christmas Day"), H(new Date(year, 11, 31), "New Year's Eve"),
    ];
  }
  if (id === "holidays-christian") {
    const e = easterSunday(year);
    const xmas = new Date(year, 11, 25);
    const advent = addDays(xmas, -((xmas.getDay() || 7) + 21));
    return [
      H(new Date(year, 0, 6), "Epiphany"), H(addDays(e, -46), "Ash Wednesday"), H(addDays(e, -7), "Palm Sunday"), H(addDays(e, -3), "Maundy Thursday"),
      H(addDays(e, -2), "Good Friday"), H(e, "Easter Sunday"), H(addDays(e, 1), "Easter Monday"), H(addDays(e, 39), "Ascension Day"),
      H(addDays(e, 49), "Pentecost"), H(addDays(e, 56), "Trinity Sunday"), H(orthodoxEaster(year), "Orthodox Easter"),
      H(new Date(year, 10, 1), "All Saints' Day"), H(advent, "First Sunday of Advent"), H(new Date(year, 11, 24), "Christmas Eve"), H(xmas, "Christmas Day"),
    ];
  }
  if (id === "holidays-jewish") {
    // ICU reports Hebrew months by name ("Adar II" in leap years); some engines give numbers
    // (Tishri = 1 … Adar I = 6, Adar / Adar II = 7, Nisan = 8 … Elul = 13).
    const names = { tishri: 1, heshvan: 2, cheshvan: 2, kislev: 3, tevet: 4, shevat: 5, "adar i": 6, adar: 7, "adar ii": 7, nisan: 8, iyar: 9, sivan: 10, tamuz: 11, av: 12, elul: 13 };
    const num = (m) => /^\d+$/.test(m) ? Number(m) : names[String(m).toLowerCase()] || 0;
    const table = [[1, 1, "Rosh Hashanah"], [1, 2, "Rosh Hashanah (day 2)"], [1, 10, "Yom Kippur"], [1, 15, "Sukkot"], [1, 22, "Shemini Atzeret"], [1, 23, "Simchat Torah"],
      [3, 25, "Hanukkah begins"], [7, 14, "Purim"], [8, 15, "Passover"], [8, 21, "Passover (day 7)"], [10, 6, "Shavuot"], [12, 9, "Tisha B'Av"]];
    const out = [];
    for (const x of altCalendarDays(year, "hebrew")) { const m = num(x.month); for (const [mm, dd, title] of table) if (m === mm && x.day === dd) out.push(H(x.date, title)); }
    return out;
  }
  if (id === "holidays-islamic") {
    const days = altCalendarDays(year, "islamic-umalqura"); // months are numbered 1 (Muharram) … 12 (Dhuʻl-Hijjah)
    const table = [[1, 1, "Islamic New Year"], [1, 10, "Ashura"], [3, 12, "Mawlid (Prophet's Birthday)"], [9, 1, "Ramadan begins"], [10, 1, "Eid al-Fitr"], [12, 9, "Day of Arafah"], [12, 10, "Eid al-Adha"]];
    const out = [];
    for (const x of days) { const m = Number(String(x.month).replace(/\D/g, "")); for (const [mm, dd, title] of table) if (m === mm && x.day === dd) out.push(H(x.date, title)); }
    return out;
  }
  return [];
}

// ============================================================================ data cache
const pim = {
  data: { calendars: [], events: [], contacts: [], contactLists: [], taskLists: [], tasks: [] },
  loaded: false,
  listeners: new Set(),
  async load() {
    try { this.data = await api("/api/pim"); this.loaded = true; this.emit(); }
    catch (e) { console.warn("PIM load failed", e); }
  },
  on(fn) { this.listeners.add(fn); },
  emit() { for (const fn of this.listeners) { try { fn(); } catch (e) { console.error(e); } } },
  async save(kind, obj) {
    const saved = await api(`/api/pim/${kind}`, { method: "PUT", body: obj });
    const arr = this.data[kind] || (this.data[kind] = []);
    const i = arr.findIndex((x) => x.id === saved.id);
    if (i >= 0) arr[i] = saved; else arr.push(saved);
    this.emit();
    return saved;
  },
  async remove(kind, id) {
    await api(`/api/pim/${kind}/${encodeURIComponent(id)}`, { method: "DELETE" });
    this.data[kind] = (this.data[kind] || []).filter((x) => x.id !== id);
    if (kind === "calendars") this.data.events = this.data.events.filter((e) => e.calendarId !== id);
    if (kind === "taskLists") this.data.tasks = this.data.tasks.filter((t) => t.listId !== id);
    this.emit();
  },
  calendar(id) { return this.data.calendars.find((c) => c.id === id) || HOLIDAY_CALENDARS.find((c) => c.id === id) || (id === "birthdays" ? { id, name: "Birthdays", color: "#c239b3" } : null); },
  contactByEmail(email) {
    const e = String(email || "").toLowerCase();
    return this.data.contacts.find((c) => (c.emails || []).some((x) => x.value.toLowerCase() === e)) || null;
  },
  contactName(c) {
    return c.displayName || [c.firstName, c.middleName, c.lastName].filter(Boolean).join(" ") || c.company || (c.emails?.[0]?.value || "");
  },
  /** Visible occurrences of all calendars (plus holidays and birthdays) in [from, to). */
  occurrences(from, to) {
    const hidden = new Set(prefs.get("hiddenCalendars", []));
    const out = [];
    const overrides = {};
    for (const e of this.data.events) if (e.recurrenceId) (overrides[e.uid] || (overrides[e.uid] = new Set())).add(e.recurrenceId);
    for (const e of this.data.events) {
      const cal = this.calendar(e.calendarId);
      if (!cal || hidden.has(cal.id) || cal.visible === false || e.cancelled) continue;
      for (const o of expandEvent(e, from, to, e.recurrenceId ? null : overrides[e.uid])) out.push({ ...o, cal });
    }
    const years = []; for (let y = from.getFullYear(); y <= to.getFullYear(); y++) years.push(y);
    for (const hc of HOLIDAY_CALENDARS) {
      if (!prefs.get("cal." + hc.id, hc.id === "holidays-us")) continue;
      if (hidden.has(hc.id)) continue;
      for (const y of years) for (const h of holidaysFor(hc.id, y)) {
        if (h.date >= addDays(from, -1) && h.date < to) out.push({ ev: { id: hc.id + ":" + ymd(h.date), title: h.title, allDay: true, start: ymd(h.date), end: ymd(addDays(h.date, 1)), readOnly: true, showAs: "free", calendarId: hc.id }, start: h.date, end: addDays(h.date, 1), key: ymd(h.date), allDay: true, cal: hc, virtual: true });
      }
    }
    if (prefs.get("cal.birthdays", true) && !hidden.has("birthdays")) {
      const bc = this.calendar("birthdays");
      for (const c of this.data.contacts) {
        const b = c.birthday; if (!b) continue;
        const mm = Number(b.slice(-5, -3)), dd = Number(b.slice(-2));
        if (!mm || !dd) continue;
        for (const y of years) {
          const d = new Date(y, mm - 1, dd);
          if (d >= addDays(from, -1) && d < to) {
            const age = /^\d{4}/.test(b) ? y - Number(b.slice(0, 4)) : null;
            out.push({ ev: { id: "bday:" + c.id + ":" + y, title: `${this.contactName(c)}'s birthday${age && age > 0 ? ` (${age})` : ""}`, allDay: true, start: ymd(d), end: ymd(addDays(d, 1)), readOnly: true, showAs: "free", calendarId: "birthdays", contactId: c.id }, start: d, end: addDays(d, 1), key: ymd(d), allDay: true, cal: bc, virtual: true });
          }
        }
      }
    }
    return out.sort((a, b) => a.start - b.start || (b.allDay ? 1 : 0) - (a.allDay ? 1 : 0));
  },
};

// ============================================================================ reminders
const reminders = {
  timer: null,
  shown: new Set(),
  start() { clearInterval(this.timer); this.timer = setInterval(() => this.check(), 30000); setTimeout(() => this.check(), 4000); },
  stop() { clearInterval(this.timer); this.timer = null; },
  key(kind, id, at) { return `qb.rem.${state.account?.email}.${kind}.${id}.${at}`; },
  setState(k, snoozeUntil) { try { localStorage.setItem(k, snoozeUntil ? String(snoozeUntil) : "0"); } catch {} },
  /** Stored state per reminder: "0" = dismissed, a timestamp = snoozed until then. */
  silenced(k) { try { const v = localStorage.getItem(k); return v === "0" || (!!v && Number(v) > Date.now()); } catch { return false; } },
  due() {
    const now = new Date();
    const items = [];
    if (!prefs.get("remindersOn", true)) return items;
    for (const o of pim.occurrences(addDays(now, -1), addDays(now, 8))) {
      if (o.virtual || o.ev.reminderMinutes == null) continue;
      const at = addMinutes(o.start, -o.ev.reminderMinutes);
      if (at > now || o.end < addMinutes(now, -60)) continue;
      const k = this.key("ev", o.ev.id, o.key);
      if (this.silenced(k)) continue;
      items.push({ k, kind: "event", title: o.ev.title, when: o.allDay ? fmtDay(o.start) + " (all day)" : `${fmtDay(o.start)} ${fmtTime(o.start)}`, location: o.ev.location, occ: o });
    }
    for (const t of pim.data.tasks) {
      if (!t.reminder || t.completed || t.reminder > Date.now()) continue;
      const k = this.key("task", t.id, t.reminder);
      if (this.silenced(k)) continue;
      items.push({ k, kind: "task", title: t.title, when: t.due ? "Due " + fmtDay(parseYmd(t.due)) : "Task", task: t });
    }
    return items;
  },
  check() {
    if (!state.token || !pim.loaded) return;
    const items = this.due();
    const fresh = items.filter((i) => !this.shown.has(i.k));
    if (!items.length) { if ($("reminderDialog").open) $("reminderDialog").close(); return; }
    this.render(items);
    if (fresh.length) {
      fresh.forEach((i) => this.shown.add(i.k));
      notify("Reminder", fresh.map((i) => `${i.title} — ${i.when}`).join("\n"), () => this.render(this.due()));
      if (!$("reminderDialog").open && !qs("dialog[open]")) $("reminderDialog").showModal();
    }
  },
  render(items) {
    const body = $("remBody"), foot = $("remFoot");
    body.innerHTML = ""; foot.innerHTML = "";
    for (const i of items) {
      body.appendChild(el("div", { class: "rem-item" }, icon(i.kind === "task" ? "todo" : "calendar", 20),
        el("div", { class: "grow" }, el("b", { text: i.title }), el("span", { class: "small", text: [i.when, i.location].filter(Boolean).join(" · ") })),
        el("button", { class: "btn sm", onclick: () => { this.setState(i.k, 0); this.check(); } }, "Dismiss")));
    }
    const snooze = select([["5", "5 minutes"], ["10", "10 minutes"], ["15", "15 minutes"], ["30", "30 minutes"], ["60", "1 hour"], ["240", "4 hours"], ["1440", "1 day"]], "5", { style: { width: "140px" } });
    foot.append(el("span", { class: "grow small muted", text: `${items.length} reminder${items.length === 1 ? "" : "s"}` }),
      snooze,
      el("button", { class: "btn", onclick: () => { const until = Date.now() + Number(snooze.value) * 60000; items.forEach((i) => { this.setState(i.k, until); this.shown.delete(i.k); }); $("reminderDialog").close(); } }, icon("clock"), "Snooze all"),
      el("button", { class: "btn primary", onclick: () => { items.forEach((i) => this.setState(i.k, 0)); $("reminderDialog").close(); } }, "Dismiss all"));
  },
};

/** Desktop notification (when allowed) — falls back to a toast. */
function notify(title, body, onclick) {
  if (!prefs.get("notifyDesktop", true)) return;
  try {
    if ("Notification" in window && Notification.permission === "granted" && document.hidden) {
      const n = new Notification(title, { body, tag: "quillbox-" + title, icon: "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='%230f6cbd' stroke-width='2'%3E%3Cpath d='M3 6h18v12H3z'/%3E%3Cpath d='m3 7 9 6 9-6'/%3E%3C/svg%3E" });
      n.onclick = () => { window.focus(); onclick && onclick(); n.close(); };
      return;
    }
  } catch {}
  if (document.hidden) return;
  toast(`${title}: ${body.split("\n")[0]}`, onclick ? { action: "Open", onAction: onclick, timeout: 8000 } : {});
}
