package com.newoether.agora.api.tts

import android.content.Context
import java.io.File

/**
 * Speech client for the configured server.
 *
 * The transport is selected from the base URL via [TtsProviders.kindFor]: DashScope's native
 * Qwen-TTS API for its well-known hosts, otherwise the OpenAI-compatible `/v1/audio/speech`
 * protocol (IndexTTS/vLLM-Omni, gateways, and cloud services that speak the same shape).
 */
class TtsClient {

    private val openAiSpeech = OpenAiSpeechTtsProvider()
    private val dashScope = DashScopeTtsProvider()

    private fun providerFor(baseUrl: String): TtsProvider = when (TtsProviders.kindFor(baseUrl)) {
        TtsProviderKind.OPENAI_SPEECH -> openAiSpeech
        TtsProviderKind.DASHSCOPE_QWEN_TTS -> dashScope
    }

    /**
     * Synthesize [request.text] and save the result as an audio file in the app cache.
     *
     * @throws TtsError on network failures, HTTP errors or unexpected response shapes
     */
    suspend fun synthesize(context: Context, config: TtsServerConfig, request: TtsRequest): File =
        providerFor(config.baseUrl).synthesize(context, config, request)

    /** Model names served by the endpoint, in server order; empty for protocols without a listing. */
    suspend fun listModels(config: TtsServerConfig): List<String> =
        providerFor(config.baseUrl).listModels(config)

    /** Voices selectable by name; empty for protocols without a listing. */
    suspend fun listVoices(config: TtsServerConfig): List<TtsVoice> =
        providerFor(config.baseUrl).listVoices(config)

    /**
     * Registers [sample] as the named voice [name] on the server, so later requests can select it
     * like any other voice. Not every transport offers this.
     */
    suspend fun uploadVoice(config: TtsServerConfig, sample: File, name: String): TtsVoice =
        providerFor(config.baseUrl).uploadVoice(config, sample, name)
}
