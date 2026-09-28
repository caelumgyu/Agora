package com.newoether.agora.api.tts

import android.content.Context
import java.io.File
import java.net.URI

/**
 * Speech backend selected from the configured server URL.
 *
 * DashScope's native Qwen-TTS API is a different protocol (endpoint, request and response shape),
 * so the well-known DashScope hosts route to that adapter; every other URL is treated as an
 * OpenAI-compatible `/v1/audio/speech` server.
 */
enum class TtsProviderKind {
    OPENAI_SPEECH,
    DASHSCOPE_QWEN_TTS,
}

object TtsProviders {
    /** Model id DashScope serves Qwen-TTS under. */
    const val DASHSCOPE_DEFAULT_MODEL = "qwen3-tts-flash"

    /** System voice the settings placeholder suggests. */
    const val DASHSCOPE_VOICE_EXAMPLE = "Cherry"

    private val DASHSCOPE_HOSTS = listOf("dashscope.aliyuncs.com", "dashscope-intl.aliyuncs.com")

    /** Picks the transport for [baseUrl]; unknown or blank URLs stay OpenAI-compatible. */
    fun kindFor(baseUrl: String): TtsProviderKind {
        val base = baseUrl.trim().lowercase()
        if (base.isEmpty()) return TtsProviderKind.OPENAI_SPEECH
        val host = runCatching { URI(base).host }.getOrNull()?.lowercase().orEmpty()
        val dashScope = if (host.isNotEmpty()) {
            DASHSCOPE_HOSTS.any { host == it || host.endsWith(".$it") }
        } else {
            // Tolerate a scheme-less entry here; actual requests still require a scheme.
            DASHSCOPE_HOSTS.any { base.contains(it) }
        }
        return if (dashScope) TtsProviderKind.DASHSCOPE_QWEN_TTS else TtsProviderKind.OPENAI_SPEECH
    }
}

/** One speech transport behind [TtsClient]. */
internal interface TtsProvider {
    /** Synthesize [request] and return the playable file in the app cache. */
    suspend fun synthesize(context: Context, config: TtsServerConfig, request: TtsRequest): File

    /** Model names the endpoint advertises; empty when the protocol has no such listing. */
    suspend fun listModels(config: TtsServerConfig): List<String>

    /** Voices selectable by name; empty when the protocol has no such listing. */
    suspend fun listVoices(config: TtsServerConfig): List<TtsVoice>
}
