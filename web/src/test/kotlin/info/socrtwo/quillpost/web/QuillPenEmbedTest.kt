package info.socrtwo.quillpost.web

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuillPenEmbedTest {
    private fun embedFolder(): File {
        val root = Files.createTempDirectory("qp-embed").toFile()
        val embed = File(root, "resources/embed").apply { mkdirs() }
        File(embed, "index.html").writeText("<!doctype html>")
        File(embed, "assets").mkdirs()
        File(embed, "assets/app.js").writeText("console.log(1)")
        File(embed, ".secret").writeText("x")
        File(root, "outside.txt").writeText("secret")
        return embed
    }

    @Test fun `serves files inside the folder`() {
        val embed = embedFolder()
        assertEquals(File(embed, "index.html").canonicalFile, QuillPenEmbed.resolve(embed, emptyList()))
        assertEquals(File(embed, "index.html").canonicalFile, QuillPenEmbed.resolve(embed, listOf("")))
        assertEquals(File(embed, "assets/app.js").canonicalFile, QuillPenEmbed.resolve(embed, listOf("assets", "app.js")))
    }

    @Test fun `refuses paths that leave the folder`() {
        val embed = embedFolder()
        for (segments in listOf(
            listOf("..", "outside.txt"), listOf("..", "..", "outside.txt"), listOf("assets", "..", "..", "outside.txt"),
            listOf("../outside.txt"), listOf("..\\outside.txt"), listOf("C:", "Windows"), listOf(".secret"),
            listOf("."), listOf("index.html\u0000.png"), listOf("assets"), listOf("missing.js"),
        )) assertNull(QuillPenEmbed.resolve(embed, segments), "should refuse $segments")
    }

    @Test fun `refuses symbolic links out of the folder`() {
        val embed = embedFolder()
        val link = File(embed, "link.txt").toPath()
        val made = runCatching { Files.createSymbolicLink(link, File(embed.parentFile.parentFile, "outside.txt").toPath()) }.isSuccess
        if (made) assertNull(QuillPenEmbed.resolve(embed, listOf("link.txt")))
    }

    @Test fun `standard install locations per platform`() {
        val env = mapOf("LOCALAPPDATA" to "C:\\Users\\a\\AppData\\Local", "PROGRAMFILES" to "C:\\Program Files")
        val win = QuillPenEmbed.standardLocations("Windows 11", { env[it] }, "C:\\Users\\a").map { it.path }
        assertEquals(2, win.size)
        assertTrue(win[0].endsWith("Programs\\QuillPen\\resources\\embed"))
        assertTrue(win[1].endsWith("QuillPen\\resources\\embed"))
        val mac = QuillPenEmbed.standardLocations("Mac OS X", { null }, "/Users/a").map { it.path }
        assertEquals(listOf("/Applications/QuillPen.app/Contents/Resources/embed", "/Users/a/Applications/QuillPen.app/Contents/Resources/embed"), mac)
        assertEquals(listOf("/opt/QuillPen/resources/embed"), QuillPenEmbed.standardLocations("Linux", { null }, "/home/a").map { it.path })
    }

    @Test fun `chosen folder is used only when it holds index html`() {
        val data = Files.createTempDirectory("qp-data").toFile()
        val embed = embedFolder()
        QuillPenEmbed.setChosenFolder(data, embed.path)
        assertNotNull(QuillPenEmbed.chosenFolder(data))
        assertEquals(embed.path, QuillPenEmbed.root(data)?.path)
        File(embed, "index.html").delete()
        assertFalse(QuillPenEmbed.isEmbedFolder(embed))
        QuillPenEmbed.setChosenFolder(data, null)
        assertNull(QuillPenEmbed.chosenFolder(data))
    }

    @Test fun `module scripts get a JavaScript type`() {
        assertEquals("text/javascript", QuillPenEmbed.contentType("app.mjs").withoutParameters().toString())
        assertEquals("font/woff2", QuillPenEmbed.contentType("a.woff2").toString())
        assertEquals("application/wasm", QuillPenEmbed.contentType("a.wasm").toString())
    }
}
