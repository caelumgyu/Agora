package com.newoether.agora.data

import androidx.datastore.preferences.core.Preferences
import com.newoether.agora.util.Constants
import com.newoether.agora.util.DebugLog
import com.newoether.agora.util.SecretCrypto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class ApiKeyEntry(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val key: String,
    val provider: String = Constants.PROVIDER_GOOGLE
)

@Serializable
data class ShellDeviceConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val type: String = "conch",          // "conch" | "ssh"
    // Conch fields (type=conch)
    val serverUrl: String = "",
    val apiKey: String = "",
    val conchPublicKey: String = "",
    // SSH fields (type=ssh)
    val sshHost: String = "",
    val sshPort: Int = 22,
    val sshUser: String = "root",
    val sshPassword: String = "",
    // Pinned SSH host key (base64 of the server public-key blob). Blank = not yet
    // pinned (trust-on-first-use); once set, connections must match or are rejected.
    val sshHostKey: String = ""
)

@Serializable
enum class McpTransportType {
    @SerialName("streamable_http")
    STREAMABLE_HTTP,

    @SerialName("sse")
    SSE,
}

@Serializable
data class McpServerConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val enabled: Boolean = true,
    val url: String = "",
    val transport: McpTransportType = McpTransportType.STREAMABLE_HTTP,
    val headers: Map<String, String> = emptyMap(),
    /** Raw MCP tool names disabled for this server. New tools stay enabled by default. */
    val disabledTools: Set<String> = emptySet(),
)

@Serializable
data class SystemPromptEntry(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val content: String = "",
    val systemItems: List<PromptTemplateItem> = emptyList(),
    val userItems: List<PromptTemplateItem> = emptyList(),
    val assistantItems: List<PromptTemplateItem> = emptyList(),
    // Legacy fields retained only so prompts saved before structured message templates deserialize.
    val userPrependItems: List<PromptTemplateItem> = emptyList(),
    val userPostpendItems: List<PromptTemplateItem> = emptyList(),
) {
    val resolvedSystemItems: List<PromptTemplateItem>
        get() = if (systemItems.isNotEmpty()) systemItems
        else if (content.isNotBlank()) listOf(PromptTemplateItem(type = PromptItemType.CUSTOM, value = content))
        else emptyList()

    val resolvedUserItems: List<PromptTemplateItem>
        get() = PredefinedVariables.normalizeMessageTemplate(
            if (userItems.isNotEmpty()) {
                userItems
            } else {
                userPrependItems + PredefinedVariables.promptItem() + userPostpendItems
            },
        )

    val resolvedAssistantItems: List<PromptTemplateItem>
        get() = PredefinedVariables.normalizeMessageTemplate(assistantItems)
}

internal val WEB_SEARCH_PROVIDERS = setOf(
    "duckduckgo", "brave", "kagi", "serper", "tavily", "searxng",
)

internal fun normalizeWebSearchProvider(provider: String?): String =
    provider?.trim()?.lowercase()?.takeIf(WEB_SEARCH_PROVIDERS::contains) ?: "duckduckgo"

// ── TTS (IndexTTS / OpenAI-compatible speech endpoint) ──────
/** Languages IndexTTS-2.5 accepts via `extra_params.lang`; "zhen" is the vLLM-Omni
 *  Chinese-English mixed preprocessing mode. */
internal val TTS_LANGUAGES = setOf("zh", "en", "ja", "es", "ar", "zhen")

internal const val DEFAULT_TTS_LANGUAGE = "zh"
internal const val TTS_MIN_SPEED = 0.5f
internal const val TTS_MAX_SPEED = 2.0f
internal const val DEFAULT_TTS_SPEED = 1.0f
/** Model id served by the vLLM-Omni IndexTTS-2.5 deployment (overridable in settings). */
internal const val DEFAULT_TTS_MODEL_NAME = "IndexTeam/IndexTTS-2.5"

/**
 * Built-in description for the `speak` tool. Users may override it in TTS settings; the tone
 * guidance (including the Chinese-only `emotion` requirement) is appended by the tool itself
 * whenever the selected transport supports it.
 */
internal const val DEFAULT_TTS_SPEAK_PROMPT =
    "Read a message aloud to the user through text-to-speech. The audio plays immediately; " +
        "never repeat this call just to show the text and never embed raw audio data. " +
        "Write the exact words to speak yourself in `text` — they may differ from the written " +
        "answer. Keep them short, natural and speech-friendly: plain sentences only, no " +
        "Markdown, lists, code, URLs or emoji. Use it when the user asks you to speak or read " +
        "something aloud, or when a spoken reply clearly fits the conversation."

internal fun normalizeTtsLanguage(language: String?): String =
    language?.trim()?.lowercase()?.takeIf(TTS_LANGUAGES::contains) ?: DEFAULT_TTS_LANGUAGE

internal fun normalizeTtsSpeed(speed: Float): Float = speed.coerceIn(TTS_MIN_SPEED, TTS_MAX_SPEED)

// ── IndexTTS emotion controls (use_emo_text / emo_alpha / use_random) ──────
internal const val DEFAULT_TTS_EMOTION_AUTO = false
internal const val TTS_MIN_EMOTION_ALPHA = 0.1f
internal const val TTS_MAX_EMOTION_ALPHA = 1.0f
/** Full strength by default; the docs recommend around 0.6 for text-derived emotion. */
internal const val DEFAULT_TTS_EMOTION_ALPHA = 1.0f
internal const val DEFAULT_TTS_EMOTION_RANDOM = false

internal fun normalizeTtsEmotionAlpha(alpha: Float): Float =
    alpha.coerceIn(TTS_MIN_EMOTION_ALPHA, TTS_MAX_EMOTION_ALPHA)

internal fun decodeWebSearchApiKeys(preferences: Preferences, json: Json): Map<String, String> {
    val raw = SecretCrypto.decrypt(preferences[WEB_SEARCH_API_KEYS_JSON] ?: "{}")
    return try {
        json.decodeFromString<Map<String, String>>(raw)
    } catch (error: Exception) {
        DebugLog.e("SettingsManager", "Failed to decode webSearchApiKeys", error)
        emptyMap()
    }
}

internal fun decodeConversationSettings(
    preferences: Preferences,
    json: Json,
): Map<String, ConversationSettings> = try {
    json.decodeFromString(preferences[CONVERSATION_SETTINGS_JSON] ?: "{}")
} catch (_: Exception) {
    emptyMap()
}

internal fun decodeEncryptedShellDevices(preferences: Preferences, json: Json): List<ShellDeviceConfig> {
    val raw = SecretCrypto.decrypt(preferences[SHELL_DEVICES_JSON] ?: "[]")
    return runCatching { json.decodeFromString<List<ShellDeviceConfig>>(raw) }.getOrDefault(emptyList())
}

@Serializable
data class ConversationSettings(
    /** Provider-visible conversation token budget. Values <=100 are legacy message windows. */
    val contextWindow: Int? = null,
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    val topP: Float? = null,
    val frequencyPenalty: Float? = null,
    val presencePenalty: Float? = null,
    val codeExecutionEnabled: Boolean? = null,
    val googleSearchEnabled: Boolean? = null,
    val openAiWebSearchEnabled: Boolean? = null,
    val thinkingEnabled: Boolean? = null,
    val thinkingLevel: String? = null,
    val thinkingBudgetEnabled: Boolean? = null,
    val thinkingBudgetTokens: Int? = null,
    val openAiServiceTierEnabled: Boolean? = null,
    val openAiServiceTier: String? = null,
    val webSearchEnabled: Boolean? = null,
    val shellEnabled: Boolean? = null,
    val lowContextModeEnabled: Boolean? = null,
) {
    fun isAllNull() = contextWindow == null && temperature == null && maxTokens == null && topP == null
        && frequencyPenalty == null && presencePenalty == null
        && codeExecutionEnabled == null && googleSearchEnabled == null
        && openAiWebSearchEnabled == null && thinkingEnabled == null
        && thinkingLevel == null && thinkingBudgetEnabled == null && thinkingBudgetTokens == null
        && openAiServiceTierEnabled == null && openAiServiceTier == null
        && webSearchEnabled == null && shellEnabled == null
        && lowContextModeEnabled == null
}
