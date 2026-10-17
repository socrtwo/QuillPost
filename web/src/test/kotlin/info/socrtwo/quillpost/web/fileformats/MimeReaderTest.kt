package info.socrtwo.quillpost.web.fileformats

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MimeReaderTest {

    private fun eml(s: String) = s.trimIndent().replace("\n", "\r\n").toByteArray(Charsets.ISO_8859_1)
    private fun parse(s: String, name: String = "m.eml") = MessageFormats.parse(eml(s), name)

    @Test fun `plain message with RFC 2047 headers in several charsets`() {
        val m = parse("""
            From: =?ISO-8859-1?Q?Andr=E9?= Pirard <PIRARD@vm1.ulg.ac.be>
            To: =?utf-8?B?0JjQstCw0L0=?= <ivan@example.ru>, "Doe, John" <john@example.com>
            Cc: =?UNICODE-1-1-UTF-7?Q?+AMk-milie?= <e@example.fr>
            Subject: =?utf-8?B?5pel5pys6Kqe44Gu?= =?utf-8?B?5Lu25ZCN?= and =?iso-8859-2?q?=BFó=B3w?=
            Date: Tue, 22 Sep 2026 09:15:00 +0200
            Message-ID: <a1@example.com>
            X-Priority: 1 (Highest)

            Body line
        """)
        assertEquals(MailAddress("André Pirard", "PIRARD@vm1.ulg.ac.be"), m.from)
        assertEquals(listOf("Иван", "Doe, John"), m.to.map { it.name })
        assertEquals("Émilie", m.cc.single().name)
        assertEquals("日本語の件名 and żółw", m.subject)
        assertEquals(1_790_061_300_000L, m.date)
        assertEquals("high", m.importance)
        assertEquals("Body line", m.bodyText)
    }

    @Test fun `encoded words split inside a multi-byte character are joined before decoding`() {
        // "€uro" in UTF-8 split between two B words after the first byte of the euro sign
        val full = "€uro".toByteArray()
        val w1 = Base64.getEncoder().encodeToString(full.copyOfRange(0, 1))
        val w2 = Base64.getEncoder().encodeToString(full.copyOfRange(1, full.size))
        assertEquals("€uro", TextCodec.decodeHeader("=?UTF-8?B?$w1?= =?UTF-8?B?$w2?="))
        assertEquals("a b", TextCodec.decodeHeader("=?us-ascii?q?a?= b"))
        assertEquals("unknown", TextCodec.decodeHeader("=?x-not-a-charset?q?unknown?="))
    }

    @Test fun `8-bit headers are read as UTF-8 or windows-1252`() {
        val utf8 = "From: Jürgen <j@example.de>\r\nSubject: Grüße\r\n\r\nx".toByteArray(Charsets.UTF_8)
        assertEquals("Grüße", MessageFormats.parse(utf8).subject)
        val latin = "From: Jürgen <j@example.de>\r\nSubject: Grüße \u0080\r\n\r\nx".toByteArray(Charsets.ISO_8859_1)
        assertEquals("Grüße €", MessageFormats.parse(latin).subject)
        assertEquals("Jürgen", MessageFormats.parse(latin).from?.name)
    }

    @Test fun `RFC 2231 file names with continuations and charset`() {
        val m = parse("""
            From: a@example.com
            Subject: files
            Content-Type: multipart/mixed; boundary="XX"

            --XX
            Content-Type: text/plain

            see files
            --XX
            Content-Type: application/pdf
            Content-Disposition: attachment; filename*0*=UTF-8''%E6%97%A5%E6%9C%AC; filename*1="-report.pdf"
            Content-Transfer-Encoding: base64

            JVBERi0xLjQK
            --XX
            Content-Type: application/octet-stream; name="=?UTF-8?B?w6l0w6kuZG9jeA==?="
            Content-Transfer-Encoding: base64

            UEsDBA==
            --XX--
        """)
        assertEquals(listOf("日本-report.pdf", "été.docx"), m.attachments.map { it.fileName })
        assertEquals("%PDF-1.4\n", String(m.attachments[0].bytes))
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", m.attachments[1].mimeType)
    }

    @Test fun `broken MIME - missing closing boundary, missing boundary parameter and damaged base64`() {
        val noClose = parse("""
            From: a@example.com
            Content-Type: multipart/mixed; boundary=abc

            --abc
            Content-Type: text/plain

            first
            --abc
            Content-Type: image/png; name=x.png
            Content-Transfer-Encoding: base64

            iVBO!!Rw0KGgo=garbage==
        """)
        assertEquals("first", noClose.bodyText)
        assertEquals("x.png", noClose.attachments.single().fileName)
        assertEquals(0x89.toByte(), noClose.attachments.single().bytes[0])
        val noParam = parse("""
            From: a@example.com
            Content-Type: multipart/alternative

            --zzz
            Content-Type: text/plain

            plain
            --zzz
            Content-Type: text/html

            <p>html</p>
            --zzz--
        """)
        assertEquals("plain", noParam.bodyText)
        assertEquals("<p>html</p>", noParam.bodyHtml)
    }

    @Test fun `unlabelled 8-bit bodies, UTF-7 bodies and html meta charsets`() {
        val raw = "From: a@example.com\r\nContent-Type: text/plain\r\n\r\nnaïve café".toByteArray(Charsets.ISO_8859_1)
        assertEquals("naïve café", MessageFormats.parse(raw).bodyText)
        assertEquals("Hi Mom -☺-!", parse("From: a@example.com\nContent-Type: text/plain; charset=utf-7\n\nHi Mom -+Jjo--!").bodyText)
        val html = "From: a@example.com\r\nContent-Type: text/html\r\n\r\n<meta charset=\"windows-1251\"><p>Ïðèâåò</p>".toByteArray(Charsets.ISO_8859_1)
        assertContains(MessageFormats.parse(html).bodyHtml!!, "Привет")
    }

    @Test fun `format flowed and text enriched`() {
        val flowed = parse("From: a@example.com\nContent-Type: text/plain; format=flowed; delsp=yes\n\nThis is a long \nline.\n> quoted \n> more\n-- \nsig")
        assertEquals("This is a longline.\n> quotedmore\n-- \nsig", flowed.bodyText)
        val enriched = parse("From: a@example.com\nContent-Type: text/enriched\n\n<bold>Now</bold> is <italic>the</italic> <<time>\n\n<color><param>red</param>alert</color>")
        assertContains(enriched.bodyHtml!!, "<b>Now</b>")
        assertContains(enriched.bodyHtml!!, "&lt;time&gt;")
        assertContains(enriched.bodyHtml!!, "<span style=\"color:red\">alert</span>")
    }

    @Test fun `uuencode, yEnc and BinHex inside text bodies become attachments`() {
        val uu = "begin 644 hello.txt\n%:&5L;&\\`\n`\nend\n"
        val m = parse("From: a@example.com\nSubject: old style\n\nHere it is:\n\n$uu\nbye")
        assertEquals("hello.txt", m.attachments.single().fileName)
        assertEquals("hello", String(m.attachments.single().bytes))
        assertFalse(m.bodyText.contains("begin 644"))
        assertContains(m.bodyText, "bye")
        val data = "yEnc!".toByteArray()
        val enc = String(data.map { ((it + 42) and 0xff).toByte() }.toByteArray(), Charsets.ISO_8859_1)
        val y = parse("From: a@example.com\n\n=ybegin line=128 size=5 name=y.bin\n$enc\n=yend size=5\n")
        assertEquals("yEnc!", String(y.attachments.single().bytes))
        val bh = parse("From: a@example.com\n\n(This file must be converted with BinHex 4.0)\n:" + binhex("note.txt", "Mac text".toByteArray()) + ":\n")
        assertEquals("note.txt", bh.attachments.single().fileName)
        assertEquals("Mac text", String(bh.attachments.single().bytes))
    }

    /** A BinHex 4.0 encoder for the test (no run-length compression, CRCs left zero). */
    private fun binhex(name: String, data: ByteArray): String {
        val o = java.io.ByteArrayOutputStream()
        o.write(name.length); o.write(name.toByteArray()); o.write(0)
        o.write("TEXTttxt".toByteArray()); o.write(byteArrayOf(0, 0))
        fun be32(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
        o.write(be32(data.size)); o.write(be32(0)); o.write(byteArrayOf(0, 0))
        o.write(data); o.write(byteArrayOf(0, 0)); o.write(byteArrayOf(0, 0))
        val alpha = "!\"#$%&'()*+,-012345689@ABCDEFGHIJKLMNPQRSTUVXYZ[`abcdefhijklmpqr"
        val bytes = o.toByteArray().flatMap { if (it == 0x90.toByte()) listOf(it, 0) else listOf(it) }.toByteArray()
        val sb = StringBuilder(); var acc = 0; var bits = 0
        for (b in bytes) { acc = (acc shl 8) or (b.toInt() and 0xff); bits += 8; while (bits >= 6) { bits -= 6; sb.append(alpha[(acc shr bits) and 63]) } }
        if (bits > 0) sb.append(alpha[(acc shl (6 - bits)) and 63])
        return sb.toString()
    }

    @Test fun `AppleDouble and AppleSingle attachments keep the data fork and real name`() {
        fun apple(magic: Int, name: String, data: ByteArray?): ByteArray {
            val entries = listOfNotNull(3 to name.toByteArray(), data?.let { 1 to it })
            val o = java.io.ByteArrayOutputStream()
            fun be32(v: Int) = o.write(byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte()))
            be32(magic); be32(0x00020000); o.write(ByteArray(16)); o.write(byteArrayOf(0, entries.size.toByte()))
            var off = 26 + entries.size * 12
            for ((id, d) in entries) { be32(id); be32(off); be32(d.size); off += d.size }
            entries.forEach { o.write(it.second) }
            return o.toByteArray()
        }
        val header = Base64.getMimeEncoder().encodeToString(apple(LegacyEncodings.APPLE_DOUBLE, "Résumé.doc", null))
        val m = parse("""
            From: a@example.com
            Content-Type: multipart/mixed; boundary=b

            --b
            Content-Type: multipart/appledouble; boundary=c

            --c
            Content-Type: application/applefile
            Content-Transfer-Encoding: base64

            $header
            --c
            Content-Type: application/msword
            Content-Transfer-Encoding: base64

            ${Base64.getEncoder().encodeToString("DOCDATA".toByteArray())}
            --c--
            --b--
        """)
        assertEquals("Résumé.doc", m.attachments.single().fileName)
        assertEquals("DOCDATA", String(m.attachments.single().bytes))
        val single = apple(LegacyEncodings.APPLE_SINGLE, "photo.jpg", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1))
        val c = MessageFormats.classify(single, "photo")
        assertEquals("AppleSingle file", c.wrappedIn)
        assertEquals("photo.jpg", c.name)
        assertEquals(3, c.bytes.size)
    }

    @Test fun `related HTML with cid images, attached messages and Gmail labels`() {
        val m = parse("""
            From: a@example.com
            To: b@example.com
            Subject: report
            X-Gmail-Labels: Inbox,Important,"Work/Projects"
            Content-Type: multipart/mixed; boundary=m

            --m
            Content-Type: multipart/related; boundary=r; type="text/html"

            --r
            Content-Type: text/html; charset=utf-8

            <p>Chart:</p><img src="cid:img1@x"><img src="https://tracker.example/p.gif">
            --r
            Content-Type: image/gif
            Content-ID: <img1@x>
            Content-Transfer-Encoding: base64

            R0lGODlhAQABAAAAACw=
            --r--
            --m
            Content-Type: message/rfc822

            From: c@example.com
            Subject: The original

            original text
            --m--
        """)
        val img = m.attachments.first { it.contentId == "img1@x" }
        assertTrue(img.inline)
        val att = m.attachments.first { it.embedded != null }
        assertEquals("The original.eml", att.fileName)
        assertEquals("original text", att.embedded!!.bodyText)
        assertTrue(m.notes.any { it.contains("Work/Projects") })
    }

    @Test fun `S-MIME signed messages show the signed content and say the signature is not verified`() {
        val signed = parse("""
            From: a@example.com
            Subject: signed
            Content-Type: multipart/signed; protocol="application/pkcs7-signature"; micalg=sha-256; boundary=s

            --s
            Content-Type: text/plain

            I agree.
            --s
            Content-Type: application/pkcs7-signature; name=smime.p7s
            Content-Transfer-Encoding: base64

            MIAGCSqGSIb3DQEHAqCAMIACAQEx
            --s--
        """)
        assertEquals("I agree.", signed.bodyText)
        assertEquals("smime-signed", signed.security)
        assertTrue(signed.attachments.isEmpty())
        assertTrue(signed.notes.any { it.contains("does not verify") })
    }

    private fun der(tag: Int, content: ByteArray): ByteArray {
        val len = content.size
        val l = if (len < 128) byteArrayOf(len.toByte()) else if (len < 256) byteArrayOf(0x81.toByte(), len.toByte()) else byteArrayOf(0x82.toByte(), (len shr 8).toByte(), len.toByte())
        return byteArrayOf(tag.toByte()) + l + content
    }

    @Test fun `opaque S-MIME signed data is unwrapped, enveloped data is reported`() {
        val inner = "Content-Type: text/plain; charset=utf-8\r\n\r\nOpaque signed text ✓".toByteArray()
        val oidSigned = byteArrayOf(0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x07, 0x02)
        val oidData = byteArrayOf(0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x07, 0x01)
        val encap = der(0x30, oidData + der(0xA0, der(0x04, inner)))
        val sd = der(0x30, der(0x02, byteArrayOf(1)) + der(0x31, ByteArray(0)) + encap + der(0x31, ByteArray(0)))
        val ci = der(0x30, oidSigned + der(0xA0, sd))
        val m = parse("From: a@example.com\nSubject: opaque\nContent-Type: application/pkcs7-mime; smime-type=signed-data; name=smime.p7m\nContent-Transfer-Encoding: base64\n\n" + Base64.getMimeEncoder().encodeToString(ci))
        assertEquals("Opaque signed text ✓", m.bodyText)
        assertEquals("smime-signed", m.security)
        assertEquals("p7m", MessageFormats.detect(ci, "smime.p7m")!!.first)
        assertEquals("Opaque signed text ✓", MessageFormats.parse(ci, "smime.p7m").bodyText)
        val enveloped = parse("From: a@example.com\nContent-Type: application/pkcs7-mime; smime-type=enveloped-data; name=smime.p7m\nContent-Transfer-Encoding: base64\n\nMIAGCSqGSIb3DQEHA6CAMIACAQAx")
        assertEquals("smime-encrypted", enveloped.security)
        assertTrue(enveloped.notes.any { it.contains("cannot decrypt") })
        assertEquals("smime.p7m", enveloped.attachments.single().fileName)
    }

    @Test fun `PGP clear-signed and PGP-MIME encrypted messages`() {
        val clear = parse("From: a@example.com\n\n-----BEGIN PGP SIGNED MESSAGE-----\nHash: SHA256\n\nMeet at noon.\n- -- dash escaped\n-----BEGIN PGP SIGNATURE-----\n\niQEz\n-----END PGP SIGNATURE-----\n")
        assertEquals("Meet at noon.\n-- dash escaped", clear.bodyText.trim())
        assertEquals("pgp-signed", clear.security)
        val enc = parse("""
            From: a@example.com
            Content-Type: multipart/encrypted; protocol="application/pgp-encrypted"; boundary=e

            --e
            Content-Type: application/pgp-encrypted

            Version: 1
            --e
            Content-Type: application/octet-stream

            -----BEGIN PGP MESSAGE-----
            hQEM
            -----END PGP MESSAGE-----
            --e--
        """)
        assertEquals("pgp-encrypted", enc.security)
        assertEquals("encrypted.asc", enc.attachments.single().fileName)
    }

    @Test fun `mbox From line, emlx wrapper, news articles and unsent drafts`() {
        val one = MessageFormats.detect(eml("From MAILER-DAEMON Thu Jan  1 00:00:00 2026\nFrom: a@example.com\nSubject: one\n\nbody"), "x")
        assertEquals("eml", one!!.first)
        assertNull(MessageFormats.detect(eml("From a Thu Jan  1 00:00:00 2026\nFrom: a@example.com\nSubject: one\n\nbody\n\nFrom b Thu Jan  1 00:00:01 2026\nFrom: b@example.com\nSubject: two\n\nbody"), "x.mbox"))
        val msg = "From: a@example.com\r\nSubject: Apple\r\n\r\nHello from Mail.app\r\n".toByteArray()
        val emlx = "${msg.size}\n".toByteArray() + msg + "<?xml version=\"1.0\"?><plist><dict><key>flags</key><integer>8590195713</integer></dict></plist>".toByteArray()
        assertEquals("emlx", MessageFormats.detect(emlx, "1234.emlx")!!.first)
        val p = MessageFormats.parse(emlx, "1234.emlx")
        assertEquals("Apple", p.subject); assertEquals("Hello from Mail.app", p.bodyText)
        assertTrue(MessageFormats.toRfc822(emlx, "1234.emlx").contentEquals(msg))
        val nws = eml("From: poster@example.org\nNewsgroups: comp.lang.kotlin,comp.misc\nSubject: Hello group\nPath: news.example.org\n\nArticle")
        assertEquals("nws", MessageFormats.detect(nws, "a.nws")!!.first)
        assertEquals("comp.lang.kotlin,comp.misc", MessageFormats.parse(nws, "a.nws").newsgroups)
        assertTrue(parse("From: a@example.com\nX-Unsent: 1\nSubject: Template\n\nFill me").template)
    }

    @Test fun `MHTML web archives resolve Content-Location references and inline stylesheets`() {
        val mht = """
            From: <Saved by Blink>
            Snapshot-Content-Location: https://example.org/page/index.html
            Subject: Example page
            MIME-Version: 1.0
            Content-Type: multipart/related; type="text/html"; boundary="----MultipartBoundary--x"

            ------MultipartBoundary--x
            Content-Type: text/html
            Content-Location: https://example.org/page/index.html

            <html><head><link rel="stylesheet" href="style.css"></head><body><img src="img/logo.png"><img src="https://example.org/page/img/logo.png"></body></html>
            ------MultipartBoundary--x
            Content-Type: text/css
            Content-Location: https://example.org/page/style.css

            body { background: url("img/logo.png") }
            ------MultipartBoundary--x
            Content-Type: image/png
            Content-Transfer-Encoding: base64
            Content-Location: https://example.org/page/img/logo.png

            iVBORw0KGgo=
            ------MultipartBoundary--x--
        """
        assertEquals("mhtml", MessageFormats.detect(eml(mht), "page.mht")!!.first)
        val m = parse(mht, "page.mht")
        val logo = m.attachments.single()
        assertNotNull(logo.contentId)
        assertTrue(logo.inline)
        val html = m.bodyHtml!!
        assertEquals(2, Regex("src=\"cid:" + Regex.escape(logo.contentId!!) + "\"").findAll(html).count())
        assertContains(html, "<style>body { background: url(\"cid:${logo.contentId}\") }")
    }

    @Test fun `delivery reports and digests`() {
        val digest = parse("""
            From: list@example.org
            Subject: Digest
            Content-Type: multipart/digest; boundary=d

            --d

            From: one@example.org
            Subject: First

            first
            --d

            From: two@example.org
            Subject: Second

            second
            --d--
        """)
        assertEquals(listOf("First", "Second"), digest.attachments.mapNotNull { it.embedded?.subject })
    }

    @Test fun `headers-only and empty bodies do not fail`() {
        val m = MessageFormats.parse("Subject: nothing\r\nFrom: x@example.com\r\n".toByteArray())
        assertEquals("nothing", m.subject)
        assertEquals("", m.bodyText)
        assertNull(m.bodyHtml)
    }
}
