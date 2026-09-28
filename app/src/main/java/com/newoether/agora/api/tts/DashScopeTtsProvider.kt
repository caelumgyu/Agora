package com.newoether.agora.api.tts

import android.content.Context
import com.newoether.agora.api.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * DashScope's native Qwen-TTS API
 * (`POST /api/v1/services/aigc/multimodal-generation/generation`).
 *
 * A non-streaming response carries the finished audio either as base64 (`output.audio.data`) or
 * as a pre-signed download URL (`output.audio.url`). Qwen-TTS has no reference-audio cloning and
 * no speed control; the configured language is mapped onto its `language_type` enum.
 */
internal class DashScopeTtsProvider : TtsProvider {

    companion object {
        internal const val SPEECH_PATH = "/api/v1/services/aigc/multimodal-generation/generation"

        /** Qwen-TTS voice cloning (enrollment) endpoint. */
        internal const val ENROLLMENT_PATH = "/api/v1/services/audio/tts/customization"

        /** GPU/cloud synthesis can take a while; allow up to 10 minutes. */
        private const val SYNTH_READ_TIMEOUT_MS = 10 * 60 * 1000L

        /** Audio download from the pre-signed OSS URL. */
        private const val DOWNLOAD_TIMEOUT_MS = 2 * 60 * 1000L

        /** Accepts a host root, an `/api/v1` base, or the full generation endpoint. */
        internal fun speechEndpoint(baseUrl: String): String {
            val base = baseUrl.trim().removeSuffix("/")
            require(base.isNotBlank()) { "TTS server URL must not be blank" }
            return when {
                base.endsWith(SPEECH_PATH) -> base
                base.endsWith("/api/v1") -> base + SPEECH_PATH.removePrefix("/api/v1")
                else -> base + SPEECH_PATH
            }
        }

        /** Same URL normalization as [speechEndpoint], for the voice-enrollment endpoint. */
        internal fun enrollmentEndpoint(baseUrl: String): String {
            val base = baseUrl.trim().removeSuffix("/")
            require(base.isNotBlank()) { "TTS server URL must not be blank" }
            return when {
                base.endsWith(ENROLLMENT_PATH) -> base
                base.endsWith("/api/v1") -> base + ENROLLMENT_PATH.removePrefix("/api/v1")
                else -> base + ENROLLMENT_PATH
            }
        }

        /** DashScope accepts the sample inline as a Data URI, so a local pick needs no public host. */
        internal fun cloneMimeType(file: File): String = when (file.extension.lowercase()) {
            "mp3", "mpeg" -> "audio/mpeg"
            "m4a", "mp4" -> "audio/mp4"
            else -> "audio/wav"
        }

        /** `preferred_name` is a label; keep it to the ASCII subset the enrollment API accepts. */
        internal fun clonePreferredName(raw: String): String {
            val sanitized = raw.lowercase()
                .map { char -> if (char in 'a'..'z' || char in '0'..'9') char else '-' }
                .joinToString("")
                .replace(Regex("-+"), "-")
                .trim('-')
                .take(32)
            return sanitized.ifBlank { "voice" }
        }

        internal fun cloneRequestBody(sample: File, preferredName: String): String {
            val dataUri = "data:${cloneMimeType(sample)};base64," +
                Base64.getEncoder().encodeToString(sample.readBytes())
            return buildJsonObject {
                put("model", "qwen-voice-enrollment")
                put("input", buildJsonObject {
                    put("action", "create")
                    put("target_model", TtsProviders.DASHSCOPE_CLONE_MODEL)
                    put("preferred_name", clonePreferredName(preferredName))
                    put("audio", buildJsonObject { put("data", dataUri) })
                })
            }.toString()
        }

        /** Qwen-TTS answers with the new voice id in `output.voice`. */
        internal fun parseEnrolledVoice(body: String): TtsVoice {
            val voice = TtsWire.parseJson(body)
                ?.get("output")?.jsonObject
                ?.get("voice")?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }
            if (voice == null) {
                val message = TtsWire.parseJson(body)
                    ?.get("message")?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                throw TtsError(message ?: "Unexpected voice enrollment response from DashScope")
            }
            return TtsVoice(voice)
        }

        /** DashScope has no Arabic type; mixed zh/en (`zhen`) and unknown codes fall back to Auto. */
        internal fun languageType(language: String): String = when (language.trim().lowercase()) {
            "zh" -> "Chinese"
            "en" -> "English"
            "ja" -> "Japanese"
            "es" -> "Spanish"
            else -> "Auto"
        }

        internal fun requestBody(request: TtsRequest): String {
            val voice = request.voiceName?.trim()?.takeIf { it.isNotBlank() }
                ?: throw TtsError("DashScope Qwen-TTS requires a named voice (e.g. Cherry)")
            return buildJsonObject {
                put("model", request.model.trim())
                put("input", buildJsonObject {
                    put("text", request.text)
                    put("voice", voice)
                    put("language_type", languageType(request.language))
                })
            }.toString()
        }

        /** Provider-level failure envelope: a non-blank `code` in an otherwise successful body. */
        internal fun providerError(body: String): String? {
            val root = TtsWire.parseJson(body) ?: return null
            val code = root["code"]?.jsonPrimitive?.contentOrNull
            if (code.isNullOrBlank()) return null
            return root["message"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: code
        }

        internal fun audioBase64(body: String): ByteArray? {
            val encoded = audioField(body, "data")
                ?.takeIf { it.isNotBlank() }
                ?: return null
            return try {
                Base64.getDecoder().decode(encoded)
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        internal fun audioUrl(body: String): String? =
            audioField(body, "url")?.takeIf { it.isNotBlank() }

        private fun audioField(body: String, key: String): String? =
            TtsWire.parseJson(body)
                ?.get("output")?.jsonObject
                ?.get("audio")?.jsonObject
                ?.get(key)?.jsonPrimitive?.contentOrNull
    }

    override suspend fun synthesize(context: Context, config: TtsServerConfig, request: TtsRequest): File =
        withContext(Dispatchers.IO) {
            if (request.text.isBlank()) throw TtsError("Text to synthesize must not be blank")
            if (request.model.isBlank()) throw TtsError("Model name must not be blank")
            val endpoint = speechEndpoint(config.baseUrl)
            val headers = TtsWire.authHeaders(config)
            TtsWire.guardCleartext(endpoint, headers)

            val client = HttpClient.client.newBuilder()
                .readTimeout(SYNTH_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build()
            val httpRequest = Request.Builder()
                .url(endpoint)
                .post(requestBody(request).toRequestBody("application/json; charset=utf-8".toMediaType()))
                .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
                .build()

            client.newCall(httpRequest).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    throw TtsError(TtsWire.friendlyError(response.code, body.toByteArray()), response.code)
                }
                providerError(body)?.let { throw TtsError(it, response.code) }
                val bytes = audioBase64(body)
                    ?: audioUrl(body)?.let { url ->
                        HttpClient.getBytes(
                            url,
                            callTimeoutMillis = DOWNLOAD_TIMEOUT_MS,
                            readTimeoutMillis = DOWNLOAD_TIMEOUT_MS,
                        )?.takeIf { it.isNotEmpty() }
                            ?: throw TtsError("Could not download the synthesized audio")
                    }
                    ?: throw TtsError(
                        "Unexpected response from the DashScope speech endpoint: ${body.take(200)}",
                        response.code,
                    )
                TtsWire.writeCacheAudio(context, bytes)
            }
        }

    /** DashScope has no public model/voice listing on this API. */
    override suspend fun listModels(config: TtsServerConfig): List<String> = emptyList()

    /** DashScope system voices are documented values, not discoverable per deployment. */
    override suspend fun listVoices(config: TtsServerConfig): List<TtsVoice> = emptyList()

    /**
     * Creates a cloned voice from a local clip. The sample is sent inline as a base64 Data URI,
     * so no public URL is needed; DashScope binds the new voice to [TtsProviders.DASHSCOPE_CLONE_MODEL].
     */
    override suspend fun uploadVoice(config: TtsServerConfig, sample: File, name: String): TtsVoice =
        withContext(Dispatchers.IO) {
            if (!sample.isFile) throw TtsError("The selected audio file is missing")
            if (sample.length() > TtsProviders.MAX_VOICE_SAMPLE_BYTES) {
                throw TtsError("Audio clip exceeds the 10 MB upload limit")
            }
            val endpoint = enrollmentEndpoint(config.baseUrl)
            val headers = TtsWire.authHeaders(config)
            TtsWire.guardCleartext(endpoint, headers)

            val httpRequest = Request.Builder()
                .url(endpoint)
                .post(cloneRequestBody(sample, name).toRequestBody("application/json; charset=utf-8".toMediaType()))
                .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
                .build()

            HttpClient.client.newBuilder()
                .readTimeout(SYNTH_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build()
                .newCall(httpRequest).execute().use { response ->
                    val text = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        throw TtsError(TtsWire.friendlyError(response.code, text.toByteArray()), response.code)
                    }
                    providerError(text)?.let { throw TtsError(it, response.code) }
                    parseEnrolledVoice(text)
                }
        }
}
