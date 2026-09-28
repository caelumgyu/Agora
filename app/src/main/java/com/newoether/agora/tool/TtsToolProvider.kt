package com.newoether.agora.tool

import android.app.Application
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.api.tts.TtsClient
import com.newoether.agora.api.tts.TtsError
import com.newoether.agora.api.tts.TtsProviderKind
import com.newoether.agora.api.tts.TtsProviders
import com.newoether.agora.api.tts.TtsRequest
import com.newoether.agora.api.tts.TtsServerConfig
import com.newoether.agora.data.DEFAULT_TTS_MODEL_NAME
import com.newoether.agora.util.DebugLog
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Tool that reads a message aloud through the self-hosted OpenAI-compatible `/audio/speech`
 * endpoint configured under Settings → TTS.
 *
 * The model writes the spoken text itself (the tool argument), so a reply can summarize or
 * rephrase what it wrote instead of being read verbatim. Successful audio is persisted by
 * [ToolAudioStore]; the model-facing result stays a tiny status envelope while the private file
 * path travels in the provider-declared structured result that the chat UI uses for replay.
 */
class TtsToolProvider(private val app: Application) : ToolProvider {

    companion object {
        const val TOOL_NAME = "speak"

        /** The tool is only offered when a usable server and voice configuration exists. */
        internal fun isConfigured(ctx: GenerationContext): Boolean {
            if (!ctx.ttsEnabled || ctx.ttsBaseUrl.isBlank()) return false
            return when (TtsProviders.kindFor(ctx.ttsBaseUrl)) {
                // DashScope Qwen-TTS has no reference-audio input, so a named voice is required.
                TtsProviderKind.DASHSCOPE_QWEN_TTS -> ctx.ttsVoiceName.isNotBlank()
                TtsProviderKind.OPENAI_SPEECH ->
                    ctx.ttsVoiceName.isNotBlank() || ctx.ttsRefAudioUrl.isNotBlank()
            }
        }

        /** Model the transport falls back to when the setting is blank. */
        internal fun defaultModel(baseUrl: String): String =
            when (TtsProviders.kindFor(baseUrl)) {
                TtsProviderKind.DASHSCOPE_QWEN_TTS -> TtsProviders.DASHSCOPE_DEFAULT_MODEL
                TtsProviderKind.OPENAI_SPEECH -> DEFAULT_TTS_MODEL_NAME
            }

        internal fun definition(emotionSupported: Boolean): ToolDefinition {
            val description = buildString {
                append(
                    "Read a message aloud to the user through text-to-speech. The audio plays immediately; " +
                        "never repeat this call just to show the text and never embed raw audio data. " +
                        "Write the exact words to speak yourself in `text` — they may differ from the written " +
                        "answer. Keep them short, natural and speech-friendly: plain sentences only, no " +
                        "Markdown, lists, code, URLs or emoji. Use it when the user asks you to speak or read " +
                        "something aloud, or when a spoken reply clearly fits the conversation.",
                )
                if (emotionSupported) {
                    append(
                        " When the tone matters, set `emotion` to a short description (for example 开心, " +
                            "疲惫, 兴奋地) or \"auto\" to let the server infer it from the text. A reply may " +
                            "mix sentences with different tone: call this tool once per sentence instead of " +
                            "merging them into one line.",
                    )
                }
            }
            val properties = buildMap {
                put("text", ToolProperty("string", "The exact words to read aloud."))
                if (emotionSupported) {
                    put(
                        "emotion",
                        ToolProperty(
                            "string",
                            "Optional tone, e.g. 开心 or 难过、语速缓慢; \"auto\" infers it from the text.",
                        ),
                    )
                }
            }
            return ToolDefinition(function = ToolFunction(
                name = TOOL_NAME,
                description = description,
                parameters = ToolParameters(
                    properties = properties,
                    required = listOf("text"),
                ),
            ))
        }

        /** The spoken line the model asked for; null when the argument is missing or blank. */
        internal fun parseText(arguments: String): String? = parseArgument(arguments, "text")

        /** Optional tone description; null when the argument is missing or blank. */
        internal fun parseEmotion(arguments: String): String? = parseArgument(arguments, "emotion")

        private fun parseArgument(arguments: String, key: String): String? =
            runCatching { Json.parseToJsonElement(arguments.ifBlank { "{}" }).jsonObject }
                .getOrNull()
                ?.get(key)
                ?.let { (it as? JsonPrimitive)?.contentOrNull }
                ?.trim()
                ?.takeIf { it.isNotBlank() }
    }

    private val audioStore = ToolAudioStore(app)
    private val client = TtsClient()

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> =
        if (isConfigured(ctx)) {
            // DashScope's Qwen-TTS has no text-based tone control; only offer the argument where
            // the OpenAI-compatible path can forward it (IndexTTS).
            val extendedControls = TtsProviders.kindFor(ctx.ttsBaseUrl) == TtsProviderKind.OPENAI_SPEECH
            listOf(definition(emotionSupported = extendedControls))
        } else {
            emptyList()
        }

    override fun handles(name: String): Boolean = name == TOOL_NAME

    override suspend fun execute(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): String = executeResult(arguments, ctx).text

    override fun executeEvents(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): Flow<ToolExecutionEvent> = flow {
        emit(ToolExecutionEvent.Completed(executeResult(arguments, ctx)))
    }

    private suspend fun executeResult(
        arguments: String,
        ctx: GenerationContext,
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        if (!isConfigured(ctx)) {
            return@withContext fail("not_configured", "Text-to-speech is not configured in Settings.")
        }
        val text = parseText(arguments)
            ?: return@withContext fail("no_text", "The `text` argument must not be blank.")
        try {
            val cacheFile = client.synthesize(
                context = app,
                config = TtsServerConfig(baseUrl = ctx.ttsBaseUrl, apiKey = ctx.ttsApiKey),
                request = TtsRequest(
                    text = text,
                    model = ctx.ttsModelName.ifBlank { defaultModel(ctx.ttsBaseUrl) },
                    voiceName = ctx.ttsVoiceName.takeIf { it.isNotBlank() },
                    refAudioUrl = ctx.ttsRefAudioUrl.takeIf { it.isNotBlank() },
                    language = ctx.ttsLanguage,
                    speed = ctx.ttsSpeed,
                    emotion = parseEmotion(arguments),
                ),
            )
            val stored = try {
                audioStore.persistFile(cacheFile)
            } finally {
                cacheFile.delete()
            }
            ToolExecutionResult(
                text = buildJsonObject {
                    put("type", "tts_speech")
                    put("status", "ok")
                    put("characters", text.length)
                }.toString(),
                structuredContent = buildJsonObject {
                    put("type", "tts_speech")
                    put("audio_path", stored.absolutePath)
                    put("text", text)
                }.toString(),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: TtsError) {
            fail("speech_failed", error.message)
        } catch (error: Exception) {
            DebugLog.e("TtsTool", "speak failed", error)
            fail("speech_failed", error.localizedMessage)
        }
    }

    private fun fail(code: String, message: String?): ToolExecutionResult = ToolExecutionResult(
        text = buildJsonObject {
            put("type", "tts_speech")
            put("error", code)
            if (!message.isNullOrBlank()) put("message", message.take(200))
        }.toString(),
        isError = true,
    )
}
