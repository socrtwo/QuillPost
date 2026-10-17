package info.socrtwo.quillpost.web.fileformats

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Opt-in check against real files that are not part of the repository (see
 * src/test/resources/fixtures/README.md): SAMPLES_DIR=/path/to/files ./gradlew test --tests '*ExternalSamplesTest*'
 * opens every file in the folder as "Open file" would and prints what was found. It fails only
 * when a reader throws on a file it claimed to recognise.
 */
class ExternalSamplesTest {
    @Test fun `every file in SAMPLES_DIR opens without errors`() {
        val dir = System.getenv("SAMPLES_DIR")?.let { File(it) }?.takeIf { it.isDirectory } ?: return
        val failures = ArrayList<String>()
        for (f in dir.walkTopDown().filter { it.isFile }.sortedBy { it.path }) {
            val bytes = f.readBytes()
            val c = MessageFormats.classify(bytes, f.name)
            val line = StringBuilder("${f.relativeTo(dir)}: ${c.kind} / ${c.format} (${c.label}, ${"%.2f".format(c.confidence)})")
            try {
                when (c.kind) {
                    "message" -> {
                        val m = MessageFormats.parse(c.bytes, c.name, c.format)
                        line.append("\n    subject=\"${m.subject.take(70)}\" from=${m.from?.display()} to=${m.to.size} cc=${m.cc.size} date=${m.date?.let { java.time.Instant.ofEpochMilli(it) }}")
                        line.append("\n    class=${m.messageClass} html=${m.bodyHtml?.length ?: 0} text=${m.bodyText.length} atts=${m.attachments.joinToString { "${it.fileName}${if (it.inline) "*" else ""}(${it.size})" }}")
                        if (m.notes.isNotEmpty()) line.append("\n    notes=${m.notes}")
                        if (m.contacts.isNotEmpty()) line.append("\n    contacts=${m.contacts.map { it.bestName() }}")
                        if (m.events.isNotEmpty()) line.append("\n    events=${m.events.map { it.title + " " + it.start }}")
                        line.append("\n    text: ${m.bodyText.replace(Regex("\\s+"), " ").take(120)}")
                        // The RFC 822 conversion must read back.
                        val back = MimeReader.parse(MimeWriter.toRfc822(m))
                        if (back.subject != m.subject) failures += "${f.name}: subject changed in the .eml conversion"
                    }
                    "contacts" -> line.append("\n    ${PimFormats.contacts(c.bytes, c.format).map { it.bestName() + " " + it.emails.map { e -> e.value } }}")
                    "calendar" -> PimFormats.events(c.bytes).let { line.append("\n    events=${it.events.map { e -> e.title + " " + e.start + (e.recurrence?.let { r -> " " + r.freq } ?: "") }} warnings=${it.warnings}") }
                }
            } catch (e: Throwable) {
                failures += "${f.name}: ${e.javaClass.simpleName}: ${e.message}"
                line.append("\n    ERROR $e")
            }
            println(line)
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
}
