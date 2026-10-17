package info.socrtwo.quillpost.web.mailstore

import java.io.File
import kotlin.test.Test

/**
 * Optional checks against real sample files that are not committed (see
 * src/test/resources/fixtures/README.md). Set QUILLPOST_SAMPLES to a folder holding them;
 * without it these tests do nothing.
 */
class SampleFilesTest {
    private val dir = System.getenv("QUILLPOST_SAMPLES")?.let { File(it) }?.takeIf { it.isDirectory }

    @Test
    fun readsSampleStores() {
        val d = dir ?: return
        for (f in d.walk().filter { it.isFile }) {
            val det = runCatching { Sniffer.sniffFile(f, f.name) }.getOrNull()
            println("== ${f.name}: ${det}")
            val store = runCatching { MailStores.open(f, f.name) }.onFailure { println("   open failed: $it") }.getOrNull() ?: continue
            store.use { s ->
                for ((path, folder) in s.root.walk()) {
                    println("   ${path.joinToString("/")} [${folder.kind}] count=${folder.count}")
                    for (m in folder.messages().take(2)) {
                        val raw = String(m.raw(), Charsets.UTF_8)
                        println("      flags=${m.flags.code()} date=${m.internalDate} kw=${m.keywords} size=${raw.length}")
                        println("      " + raw.take(if (System.getenv("QP_FULL") != null) 20000 else 600).replace("\n", "\n      "))
                    }
                    for (p in folder.pimItems().take(2)) println("      PIM: " + p.text.take(400).replace("\n", "\n      "))
                }
                s.warnings.take(10).forEach { println("   warning: $it") }
            }
        }
    }
}
