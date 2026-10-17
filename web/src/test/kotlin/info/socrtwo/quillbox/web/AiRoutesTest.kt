package info.socrtwo.quillbox.web

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiRoutesTest {
    @Test
    fun `provider addresses are allowed, with their paths`() {
        for (u in listOf(
            "https://api.openai.com/v1/chat/completions",
            "https://api.openai.com/v1/models",
            "https://openrouter.ai/api/v1/chat/completions",
            "https://generativelanguage.googleapis.com/v1beta/models/g:streamGenerateContent?alt=sse",
            "https://api.deepseek.com/chat/completions",
            "https://my-res.openai.azure.com/openai/v1/chat/completions",
            "https://bedrock-runtime.eu-west-1.amazonaws.com/openai/v1/chat/completions",
            "https://api.cloudflare.com/client/v4/accounts/0123abc/ai/v1/chat/completions",
            "http://localhost:11434/v1/chat/completions",
            "http://127.0.0.1:11434/v1/models",
            "http://localhost:1234/v1/models",
        )) assertTrue(AiAllowList.allowed(u), u)
    }

    @Test
    fun `everything else is refused`() {
        for (u in listOf(
            "https://api.openai.com.evil.test/v1/chat/completions",
            "https://evil.test/api.openai.com/v1",
            "https://api.openai.com@evil.test/v1/chat/completions",
            "https://user:pw@api.openai.com/v1/chat/completions",
            "http://api.openai.com/v1/chat/completions",
            "https://api.openai.com:8443/v1/chat/completions",
            "https://api.openai.com/v2/chat/completions",
            "https://api.openai.com/",
            "https://api.openai.com/v1/../admin",
            "https://api.openai.com/v1/%2e%2e/admin",
            "https://a.b.openai.azure.com/openai/v1/chat/completions",
            "https://bedrock-runtime.amazonaws.com/openai/v1/chat/completions",
            "https://api.cloudflare.com/client/v4/accounts/ai/v1/chat/completions",
            "http://localhost:8080/api/messages",
            "http://169.254.169.254/latest/meta-data",
            "file:///etc/passwd",
            "javascript:alert(1)",
            "not a url",
        )) assertFalse(AiAllowList.allowed(u), u)
    }

    @Test
    fun `registered custom bases are allowed and nothing beside them`() {
        val custom = listOf("https://llm.example.com/v1", "http://192.168.1.20:8000/v1")
        assertTrue(AiAllowList.allowed("https://llm.example.com/v1/chat/completions", custom))
        assertTrue(AiAllowList.allowed("http://192.168.1.20:8000/v1/models", custom))
        assertFalse(AiAllowList.allowed("https://llm.example.com/admin", custom))
        assertFalse(AiAllowList.allowed("http://192.168.1.20:8001/v1/models", custom))
        assertTrue(AiAllowList.validCustomBase("https://llm.example.com/v1"))
        assertFalse(AiAllowList.validCustomBase("https://*.example.com/v1"))
        assertFalse(AiAllowList.validCustomBase("https://u:p@example.com/v1"))
        assertFalse(AiAllowList.validCustomBase("ftp://example.com/v1"))
        assertFalse(AiAllowList.validCustomBase("https://example.com/v1?key=1"))
    }

    /** The relay's list must cover every provider of the web UI's table (web/ai/providers.js). */
    @Test
    fun `allow-list matches the web UI's provider table`() {
        val js = javaClass.classLoader.getResourceAsStream("web/ai/providers.js")!!.readBytes().toString(Charsets.UTF_8)
        val bases = Regex("baseUrl: \"([^\"]+)\"").findAll(js).map { it.groupValues[1] }.toList()
        assertTrue(bases.size >= 20, "found ${bases.size} provider base URLs")
        val asPatterns = bases.map { it.replace(Regex("\\{[a-zA-Z]+\\}"), "*") }.toSet()
        assertEquals(asPatterns, AiAllowList.PROVIDER_BASES.toSet())
    }

    @Test
    fun `desktop key file is private to the user and round-trips`() {
        val root = Files.createTempDirectory("qb-ai").toFile()
        try {
            val f = AiKeyFile(root)
            assertNull(f.get("ai.key.groq"))
            f.put("ai.key.groq", "gsk_secret")
            f.put("ai.key.openai", "sk-secret")
            assertEquals("gsk_secret", f.get("ai.key.groq"))
            f.remove("ai.key.groq")
            assertNull(f.get("ai.key.groq"))
            assertEquals("sk-secret", AiKeyFile(root).get("ai.key.openai"))
            val keys = File(root, "ai/keys.json").toPath()
            runCatching { Files.getPosixFilePermissions(keys) }.getOrNull()?.let {
                assertEquals("rw-------", PosixFilePermissions.toString(it))
                assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(keys.parent)))
            }
        } finally { root.deleteRecursively() }
    }
}
