package com.newoether.agora.api.tts

import android.content.Context
import com.newoether.agora.api.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

/** TTS synthesis failure. [code] carries the HTTP status when the server answered. */
class TtsError(message: String, val code: Int? = null) : Exception(message)

/** Connection settings for a self-hosted TTS server. */
data class TtsServerConfig(
    /** Base address of the server, e.g. `http://192.168.1.50:8092` (no trailing slash needed). */
    val baseUrl: String,
    /** Optional Bearer token for servers that require authentication. */
    val apiKey: String = "",
)

/** One synthesis request against an OpenAI-compatible `/v1/audio/speech` endpoint. */
data class TtsRequest(
    val text: String,
    /** Model name the server expects (e.g. `IndexTeam/IndexTTS-2.5` under vLLM-Omni). */
    val model: String,
    /** Named voice previously uploaded via `/v1/audio/voices`; takes precedence over [refAudioUrl]. */
    val voiceName: String? = null,
    /** Reference clip for zero-shot cloning: http(s) URL, `data:` URI or `file://` path. */
    val refAudioUrl: String? = null,
    /** IndexTTS-2.5 language code passed as `extra_params.lang` (zh/en/ja/es/ar, plus the
     *  vLLM-Omni "zhen" Chinese-English mixed mode). */
    val language: String,
    /** Speech rate, 0.5–2.0 (vLLM maps it to the model-native duration factor). */
    val speed: Float = 1.0f,
)

/** A voice selectable by name: a built-in speaker or one uploaded via `/v1/audio/voices`. */
data class TtsVoice(
    val name: String,
    val description: String? = null,
)

/**
 * Client for self-hosted TTS servers speaking the OpenAI-compatible speech API — in particular
 * IndexTTS-2.5 served through vLLM-Omni (see https://recipes.vllm.ai/IndexTeam/IndexTTS-2.5).
 *
 * The synthesized audio is written into the app's cache directory and the resulting [File] is
 * returned so callers can hand it to a media player via `Uri.fromFile`.
 */
class TtsClient {
    companion object {
        /** GPU inference for a short sentence can take a while; allow up to 10 minutes. */
        private const val SYNTH_READ_TIMEOUT_MS = 10 * 60 * 1000L

        private fun apiRoot(baseUrl: String): String {
            val base = baseUrl.trim().removeSuffix("/")
            require(base.isNotBlank()) { "TTS server URL must not be blank" }
            return if (base.endsWith("/v1")) base else "$base/v1"
        }

        private fun speechEndpoint(baseUrl: String): String = apiRoot(baseUrl) + "/audio/speech"

        private fun authHeaders(config: TtsServerConfig): Map<String, String> = buildMap {
            config.apiKey.trim().takeIf { it.isNotBlank() }?.let { put("Authorization", "Bearer $it") }
        }

        private fun buildBody(request: TtsRequest): String {
            val voice = request.voiceName?.trim()?.takeIf { it.isNotBlank() }
                ?: request.refAudioUrl?.trim()?.takeIf { it.isNotBlank() }
                ?: throw TtsError("voice or ref_audio is required")
            return buildJsonObject {
                put("model", request.model.trim())
                put("input", request.text)
                if (voice.startsWith("http") || voice.startsWith("data:") || voice.startsWith("file:")) {
                    put("ref_audio", voice)
                } else {
                    put("voice", voice)
                }
                put("response_format", "wav")
                if (request.speed != 1.0f) put("speed", request.speed.toDouble())
                put(
                    "extra_params",
                    buildJsonObject {
                        put("lang", request.language)
                        put("text_normalization", true)
                    },
                )
            }.toString()
        }

        /** Best-effort human-readable failure from a non-2xx body. */
        private fun friendlyError(code: Int, bytes: ByteArray): String {
            val text = String(bytes, Charsets.UTF_8).trim()
            val detail = parseJson(text)
                ?.let { obj ->
                    obj["detail"]?.jsonPrimitive?.contentOrNull
                        ?: obj["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                        ?: obj["message"]?.jsonPrimitive?.contentOrNull
                }
                ?.takeIf { it.isNotBlank() }
            val base = when (code) {
                401, 403 -> "Authentication rejected"
                404 -> "Endpoint not found (check the server URL and model name)"
                in 500..599 -> "Server error"
                else -> "HTTP $code"
            }
            return detail?.let { "$base: $it" } ?: base
        }

        private fun parseJson(text: String): JsonObject? = try {
            Json.parseToJsonElement(text).jsonObject
        } catch (_: Exception) {
            null
        }

        /** Some servers answer with JSON (an audio URL, base64, or an error) even on success. */
        private fun resolveJsonAudio(code: Int, bytes: ByteArray, authHeaders: Map<String, String>): ByteArray {
            val text = String(bytes, Charsets.UTF_8)
            val obj = parseJson(text)
            if (obj != null) {
                obj["detail"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                    ?.let { throw TtsError(it, code) }
                val url = obj["url"]?.jsonPrimitive?.contentOrNull ?: obj["audio_url"]?.jsonPrimitive?.contentOrNull
                if (url != null) {
                    if (url.startsWith("file://")) {
                        val file = File(url.removePrefix("file://"))
                        if (file.isFile) return file.readBytes()
                    } else {
                        HttpClient.getBytes(url, authHeaders)?.takeIf { it.isNotEmpty() }?.let { return it }
                    }
                }
                (obj["audio"]?.jsonPrimitive?.contentOrNull ?: obj["data"]?.jsonPrimitive?.contentOrNull)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { encoded ->
                        try {
                            val decoded = Base64.getDecoder().decode(encoded.trim())
                            if (decoded.isNotEmpty()) return decoded
                        } catch (_: IllegalArgumentException) {
                            // not base64 — fall through to the generic error below
                        }
                    }
            }
            throw TtsError("Unexpected JSON response from TTS server: ${text.take(200)}", code)
        }

        private fun audioExtension(bytes: ByteArray): String {
            if (bytes.size >= 12 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
                bytes[8] == 'W'.code.toByte() && bytes[9] == 'A'.code.toByte()
            ) return "wav"
            if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && (bytes[1].toInt() and 0xE0) == 0xE0) return "mp3"
            if (bytes.size >= 4 && String(bytes, 0, 4) == "OggS") return "ogg"
            if (bytes.size >= 12 && String(bytes, 4, 8) == "ftyp") return "m4a"
            return "wav" // default; ExoPlayer sniffs the container anyway
        }
    }

    /**
     * Synthesize [request.text] and save the result as an audio file in the app cache.
     *
     * @throws TtsError on network failures, HTTP errors or unexpected response shapes
     */
    suspend fun synthesize(context: Context, config: TtsServerConfig, request: TtsRequest): File =
        withContext(Dispatchers.IO) {
            if (request.text.isBlank()) throw TtsError("Text to synthesize must not be blank")
            if (request.model.isBlank()) throw TtsError("Model name must not be blank")
            val endpoint = speechEndpoint(config.baseUrl)
            val headers = authHeaders(config)
            HttpClient.guardCleartextCredentials(endpoint, headers)

            val client = HttpClient.client.newBuilder()
                .readTimeout(SYNTH_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build()
            val httpRequest = Request.Builder()
                .url(endpoint)
                .post(buildBody(request).toRequestBody("application/json; charset=utf-8".toMediaType()))
                .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
                .build()

            client.newCall(httpRequest).execute().use { response ->
                val bytes = response.body?.bytes() ?: ByteArray(0)
                if (!response.isSuccessful) {
                    throw TtsError(friendlyError(response.code, bytes), response.code)
                }
                val contentType = (response.header("Content-Type") ?: "").lowercase()
                val audioBytes: ByteArray = when {
                    bytes.isEmpty() -> throw TtsError("Empty response from TTS server", response.code)
                    contentType.contains("json") -> resolveJsonAudio(response.code, bytes, headers)
                    contentType.contains("html") ->
                        throw TtsError("Unexpected HTML response — wrong endpoint or a proxy page?", response.code)
                    else -> bytes
                }
                val dir = File(context.cacheDir, "tts").apply { mkdirs() }
                File(dir, "tts_${System.currentTimeMillis()}.${audioExtension(audioBytes)}")
                    .also { it.writeBytes(audioBytes) }
            }
        }

    /** Model names served by the endpoint (`GET /v1/models`), in server order. */
    suspend fun listModels(config: TtsServerConfig): List<String> = withContext(Dispatchers.IO) {
        val endpoint = apiRoot(config.baseUrl) + "/models"
        val headers = authHeaders(config)
        HttpClient.guardCleartextCredentials(endpoint, headers)
        val response = HttpClient.getTextResponse(endpoint, headers)
        if (!response.isSuccessful) {
            throw TtsError(friendlyError(response.code, response.body.toByteArray()), response.code)
        }
        val root = parseJson(response.body) ?: throw TtsError("Unexpected model list response")
        root["data"]?.jsonArray
            ?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
            ?.distinct()
            ?: emptyList()
    }

    /** Voices selectable by name (`GET /v1/audio/voices`): built-in speakers plus uploaded ones. */
    suspend fun listVoices(config: TtsServerConfig): List<TtsVoice> = withContext(Dispatchers.IO) {
        val endpoint = apiRoot(config.baseUrl) + "/audio/voices"
        val headers = authHeaders(config)
        HttpClient.guardCleartextCredentials(endpoint, headers)
        val response = HttpClient.getTextResponse(endpoint, headers)
        if (!response.isSuccessful) {
            throw TtsError(friendlyError(response.code, response.body.toByteArray()), response.code)
        }
        val root = parseJson(response.body) ?: throw TtsError("Unexpected voice list response")
        val builtIn = root["voices"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive?.contentOrNull }
            ?: emptyList()
        val uploaded = root["uploaded_voices"]?.jsonArray
            ?.mapNotNull { element ->
                val obj = element.jsonObject
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                TtsVoice(name, obj["speaker_description"]?.jsonPrimitive?.contentOrNull)
            }
            ?: emptyList()
        (builtIn.map { TtsVoice(it) } + uploaded).distinctBy { it.name }
    }
}
