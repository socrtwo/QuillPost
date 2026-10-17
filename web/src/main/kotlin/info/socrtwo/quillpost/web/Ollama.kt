package info.socrtwo.quillpost.web

import info.socrtwo.quillpost.web.spam.MessageFacts
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Optional enrichment with a locally running open-source LLM served by Ollama
 * (https://ollama.com). Everything stays on the user's machine — no cloud API, no key.
 * The built-in engine works without this; when enabled it adds a second opinion on which
 * organisation the message impersonates and suggests extra rule conditions.
 */
class Ollama(private val cfg: OllamaConfigDto) {
    private val json = Json { ignoreUnknownKeys = true }

    fun test(): OllamaTestResponse = runCatching {
        val res = Http.get(cfg.url.trimEnd('/') + "/api/tags", timeoutMs = 5000)
        if (res.status != 200) return OllamaTestResponse(false, error = "HTTP ${res.status}")
        val models = json.parseToJsonElement(res.body).jsonObject["models"]?.jsonArray
            ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content } ?: emptyList()
        OllamaTestResponse(true, models)
    }.getOrElse { OllamaTestResponse(false, error = it.message ?: "cannot reach ${cfg.url}") }

    fun assess(facts: MessageFacts, senderDomain: String): LlmAssessmentDto {
        if (!cfg.enabled) return LlmAssessmentDto(cfg.model, available = false)
        val body = facts.bodyText.ifBlank { facts.bodyHtml?.let { info.socrtwo.quillpost.web.spam.HeaderParser.htmlToText(it) } ?: "" }.take(3500)
        val system = """
            You are an email security analyst. You will be given the headers and body of one email.
            Respond ONLY with a JSON object with these keys:
              "claimed_organization": the company or organisation the email presents itself as coming from, or null if it is personal mail;
              "is_spam": true if this is spam, phishing or a scam, false otherwise;
              "confidence": a number from 0 to 1;
              "summary": one or two sentences explaining your judgement for a non-technical reader;
              "rule_conditions": an array of up to 2 objects {"field": one of SENDER, SENDER_NAME, SENDER_DOMAIN, SUBJECT, BODY; "operator": "CONTAINS"; "value": a short literal string taken from the email} that would identify other emails from the same campaign. Prefer sender-related conditions. Use an empty array if the email is legitimate.
            The sender's real domain is: $senderDomain
        """.trimIndent()
        val user = buildString {
            append("From: ").append(facts.fromName).append(" <").append(facts.fromAddress).append(">\n")
            if (facts.replyTo.isNotBlank()) append("Reply-To: ").append(facts.replyTo).append('\n')
            append("Subject: ").append(facts.subject).append('\n')
            append("\n").append(body)
        }
        val payload = buildJsonObject {
            put("model", cfg.model)
            put("stream", false)
            put("format", "json")
            put("options", buildJsonObject { put("temperature", 0.0) })
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", system) })
                add(buildJsonObject { put("role", "user"); put("content", user) })
            })
        }
        return runCatching {
            val res = Http.postJson(cfg.url.trimEnd('/') + "/api/chat", payload.toString(), timeoutMs = 120_000)
            if (res.status != 200) return LlmAssessmentDto(cfg.model, true, error = "Ollama HTTP ${res.status}: ${res.body.take(200)}")
            val content = json.parseToJsonElement(res.body).jsonObject["message"]?.jsonObject?.get("content")?.jsonPrimitive?.content
                ?: return LlmAssessmentDto(cfg.model, true, error = "Empty response from model")
            val obj = json.parseToJsonElement(extractJson(content)).jsonObject
            LlmAssessmentDto(
                model = cfg.model,
                available = true,
                claimedOrganisation = obj["claimed_organization"]?.let { (it as? JsonPrimitive)?.content }?.takeIf { it != "null" && it.isNotBlank() },
                isSpam = obj["is_spam"]?.let { (it as? JsonPrimitive)?.content?.toBooleanStrictOrNull() },
                confidence = obj["confidence"]?.let { (it as? JsonPrimitive)?.content?.toDoubleOrNull() },
                summary = obj["summary"]?.let { (it as? JsonPrimitive)?.content } ?: "",
                extraCriteria = (obj["rule_conditions"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { c ->
                    val o = c as? JsonObject ?: return@mapNotNull null
                    val field = o["field"]?.jsonPrimitive?.content?.uppercase() ?: return@mapNotNull null
                    val value = o["value"]?.jsonPrimitive?.content?.trim() ?: return@mapNotNull null
                    if (field !in setOf("SENDER", "SENDER_NAME", "SENDER_DOMAIN", "SUBJECT", "BODY") || value.length < 3) return@mapNotNull null
                    RuleCriterionDto(field, "CONTAINS", value.take(80))
                }?.take(2) ?: emptyList()
            )
        }.getOrElse { LlmAssessmentDto(cfg.model, true, error = it.message ?: "Ollama request failed") }
    }

    /** Translates [text] into [language] with the local model; the text never leaves this machine. */
    fun translate(text: String, language: String): String {
        require(cfg.enabled) { "Turn on a local Ollama model under Settings → AI models to translate messages on this machine" }
        val payload = buildJsonObject {
            put("model", cfg.model)
            put("stream", false)
            put("options", buildJsonObject { put("temperature", 0.1) })
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", "You are a professional translator. Translate the user's email into $language. Keep the paragraph breaks, names, numbers and links. Output only the translation, with no preface or notes.") })
                add(buildJsonObject { put("role", "user"); put("content", text.take(12_000)) })
            })
        }
        val res = Http.postJson(cfg.url.trimEnd('/') + "/api/chat", payload.toString(), timeoutMs = 300_000)
        if (res.status != 200) throw IllegalArgumentException("Ollama HTTP ${res.status}: ${res.body.take(200)}")
        return json.parseToJsonElement(res.body).jsonObject["message"]?.jsonObject?.get("content")?.jsonPrimitive?.content?.trim()
            ?: throw IllegalArgumentException("The model returned no translation")
    }

    private fun extractJson(s: String): String {
        val start = s.indexOf('{'); val end = s.lastIndexOf('}')
        return if (start >= 0 && end > start) s.substring(start, end + 1) else "{}"
    }
}
