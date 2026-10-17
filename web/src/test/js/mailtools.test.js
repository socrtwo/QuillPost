"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const T = require("../../main/resources/web/mailtools.js");

test("quoted text folding", () => {
  const s = T.splitQuoted("Thanks!\n\nOn Mon, Jan 5, 2026 at 10:00 Bob Smith wrote:\n> hi\n> there");
  assert.equal(s.fresh, "Thanks!");
  assert.match(s.quoted, /^On Mon/);
  assert.equal(T.freshText("Yes.\n\n-- \nPaul\n0123"), "Yes.");
  assert.equal(T.splitQuoted("Ok\nFrom: Bob\nSent: Monday\nSubject: x\n\nold").fresh, "Ok");
  assert.equal(T.splitQuoted("> a\nmy answer\n> b\nmore").fresh, "my answer\nmore");
});

test("thread digest finds questions, requests, dates and links", () => {
  const d = T.threadDigest([
    { fromName: "Bob", date: 2, text: "Sure, see https://x.example/f\n\nOn Mon, Jan 5, 2026 at 10:00 Ann wrote:\n> Can you send it?" },
    { fromName: "Ann", date: 1, text: "Can you send the file? Please call me tomorrow." },
  ]);
  assert.deepEqual(d.items.map((i) => i.from), ["Ann", "Bob"]);
  assert.deepEqual(d.questions, [{ from: "Ann", q: "Can you send the file?" }]);
  assert.deepEqual(d.asks.map((a) => a.a), ["Can you send the file?", "Please call me tomorrow."]);
  assert.deepEqual(d.links, ["https://x.example/f"]);
  assert.deepEqual(d.items[0].dates, ["tomorrow"]);
});

test("clean up conversation finds fully quoted messages", () => {
  const r = T.redundantMessages([
    { key: "a", date: 1, text: "The meeting moves to Thursday at ten." },
    { key: "b", date: 2, text: "Fine by me.\n\nOn Mon, Jan 5, 2026 at 10:00 Ann wrote:\n> The meeting moves to Thursday at ten." },
    { key: "c", date: 3, text: "Something new and unquoted here." },
  ]);
  assert.deepEqual(r, ["a"]);
});

test("quote integrity spots altered and trimmed sentences", () => {
  const r = T.compareQuote("We will ship on Monday. The price is 40 dollars.", "> We will ship on Monday.\n> The price is 400 dollars.");
  assert.equal(r.ok, false);
  assert.deepEqual(r.altered, ["The price is 400 dollars."]);
  assert.deepEqual(r.missing, ["The price is 40 dollars."]);
  assert.equal(T.compareQuote("One sentence here. Two sentence here.", "> One sentence here.").ok, true);
  assert.equal(T.compareQuote("One sentence here. Two sentence here.", "> One sentence here.").trimmed, true);
});

test("mutt patterns", () => {
  const now = Date.UTC(2026, 9, 17);
  const m = { fromName: "Jane Smith", fromAddress: "jane@example.com", to: ["me@x.org"], cc: [], subject: "Q3 Report", date: now - 20 * 864e5, read: false, flagged: false, size: 2e6, hasAttachments: true, keywords: ["$label1"], messageId: "<a.b@c>" };
  const env = { me: new Set(["me@x.org"]), now };
  const t = (p) => T.compilePattern(p)(m, env);
  assert.ok(t("~f jane"));
  assert.ok(t("~f jane ~s report !~F"));
  assert.ok(!t("~f bob"));
  assert.ok(t("~f bob | ~s report"));
  assert.ok(t("!(~f bob | ~F)"));
  assert.ok(t("~d >2w") && !t("~d <2w"));
  assert.ok(t("~d 2026-09-01-2026-10-01"));
  assert.ok(t("~z >1M") && !t("~z <1M") && t("~z 1M-3M"));
  assert.ok(t("~p") && !t("~P"));
  assert.ok(t("~N ~X ~y label1"));
  assert.ok(t("=i <a.b@c>"));
  assert.ok(t("smith"), "a bare word searches From and Subject");
  assert.ok(!t("~s REPORT"), "a pattern with capitals is case-sensitive, as in mutt");
  assert.throws(() => T.parsePattern("~f"), /needs a value/);
  assert.throws(() => T.parsePattern("~q x"), /Unknown/);
  assert.throws(() => T.parsePattern("(~f x"), /Missing \)/);
});

test("pipe filters", () => {
  const text = "b\na\nb\nc https://x.example/p\n";
  assert.equal(T.runPipeline(text, "sort | uniq -c"), "      1 a\n      2 b\n      1 c https://x.example/p");
  assert.equal(T.runPipeline(text, "grep -c b"), "2");
  assert.equal(T.runPipeline(text, "grep -v -n b"), "2:a\n4:c https://x.example/p");
  assert.equal(T.runPipeline(text, "urls"), "https://x.example/p");
  assert.equal(T.runPipeline("Hello", "rot13 | tr a-z A-Z"), "URYYB");
  assert.equal(T.runPipeline("a,b,c", "cut -d , -f 2-3"), "b,c");
  assert.equal(T.runPipeline("foo bar", "sed s/o/0/g"), "f00 bar");
  assert.equal(T.runPipeline("one two\nthree", "wc"), "2 3 13");
  assert.equal(T.runPipeline("x\ny\nz", "tac | head -n 2"), "z\ny");
  assert.equal(T.runPipeline("aGk=", "base64 -d"), "hi");
  assert.throws(() => T.runPipeline("x", "rm -rf /"), /Unknown filter/);
});

test("MoodWatch", () => {
  assert.equal(T.moodScore("Thanks for the update, see you Monday.").peppers, 0);
  const s = T.moodScore("You are an IDIOT and this is RIDICULOUS!!! You never listen.");
  assert.equal(s.peppers, 3);
  assert.ok(s.hits.includes("idiot"));
  assert.ok(T.moodScore("This is frankly disappointed, seriously.").peppers >= 1);
});

test("tracking pixels and tracking parameters", () => {
  const html = '<img src="https://x.list-manage.com/track/open.php?u=1" width="1" height="1"><img src="https://img.example/logo.png" width="300"><img src="https://t.example/o.gif"><img style="display:none" src="https://a.example/x.png">';
  const r = T.findTrackers(html);
  assert.deepEqual(r.map((x) => x.reason), ["1×1 image", "tracking address", "hidden image"]);
  assert.equal(T.cleanUrl("https://a.com/p?id=3&utm_source=x&fbclid=2"), "https://a.com/p?id=3");
  assert.equal(T.cleanUrl("https://a.com/p?utm_medium=x"), "https://a.com/p");
  assert.equal(T.cleanUrl("https://a.com/p?q=1"), "https://a.com/p?q=1");
  assert.equal(T.cleanUrl("not a url"), "not a url");
});

test("free time slots avoid busy times and stay in working hours", () => {
  const busy = [{ start: new Date(2026, 9, 19, 9, 0), end: new Date(2026, 9, 19, 10, 0) }];
  const s = T.freeSlots(busy, { from: new Date(2026, 9, 17, 20, 0), max: 4 });
  assert.equal(s.length, 4);
  assert.equal(s[0].start.getDate(), 19); // Saturday and Sunday are skipped
  assert.equal(s[0].start.getHours(), 10);
  for (const x of s) { assert.ok(x.start.getHours() >= 9 && x.end.getHours() <= 17); assert.ok(![0, 6].includes(x.start.getDay())); }
});

test("receipts, newsletters, snippets, case, word counts, vCards, Markdown", () => {
  assert.ok(T.isReceipt({ subject: "Your Amazon.com order #112 has shipped" }));
  assert.ok(!T.isReceipt({ subject: "Lunch on Friday?" }));
  assert.ok(T.isNewsletter({ fromAddress: "newsletter@x.example", subject: "Issue 4" }));
  assert.ok(T.isNewsletter({ focused: false }));
  assert.equal(T.expandSnippet("Hi {first_name}, {cursor}thanks — {my_name}", { name: "Jane Smith", my_name: "Paul" }), "Hi Jane, {cursor}thanks — Paul");
  assert.equal(T.changeCase("hello world. bye", "sentence"), "Hello world. Bye");
  assert.equal(T.changeCase("hello world", "title"), "Hello World");
  assert.equal(T.changeCase("aB", "toggle"), "Ab");
  assert.deepEqual(T.wordStats("One two.\n\nThree!"), { words: 3, chars: 16, charsNoSpaces: 13, sentences: 2, paragraphs: 2 });
  const v = T.vcardOf({ firstName: "Jane", lastName: "Smith", emails: [{ value: "jane@example.com" }], company: "Acme; Inc" });
  assert.ok(v.startsWith("BEGIN:VCARD\r\nVERSION:3.0\r\nN:Smith;Jane;;;\r\nFN:Jane Smith\r\nORG:Acme\\; Inc\r\n"), v);
  assert.match(T.threadMarkdown("S", [{ fromName: "A", from: "a@x", date: 0, text: "hi\n\nOn Mon, Jan 5, 2026 at 10:00 B wrote:\n> old" }]), /# S\n\n## A <a@x> — 1970-01-01 00:00\n\nhi\n/);
});

test("pre-send checks", () => {
  const w = T.presendChecks({ to: ["a@b.com"], subject: "", body: "Hi Tom, see attached.", names: [{ name: "Tom Rivera", email: "tom@x.com" }], myDomain: "b.com" });
  const texts = w.map((x) => x.text).join("\n");
  assert.match(texts, /subject is empty/);
  assert.match(texts, /mention an attachment/);
  assert.match(texts, /tom@x.com is not a recipient/);
  assert.ok(!/Going outside/.test(texts));
  assert.match(T.presendChecks({ to: [], body: "x" }).map((x) => x.text).join(), /no recipients/);
  assert.match(T.presendChecks({ to: ["a@b.com"], subject: "s", body: "You IDIOT, this is RIDICULOUS!!! You never listen." }).map((x) => x.text).join(), /MoodWatch/);
});
