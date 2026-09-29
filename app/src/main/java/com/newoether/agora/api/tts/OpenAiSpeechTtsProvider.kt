package com.newoether.agora.api.tts

import android.content.Context
import com.newoether.agora.api.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Speech transport for OpenAI-compatible `/v1/audio/speech` servers — in particular
 * [IndexTTS-2.5 served through vLLM-Omni](https://recipes.vllm.ai/IndexTeam/IndexTTS-2.5), plus
 * any gateway or cloud endpoint that implements the same request/response shape.
 */
internal class OpenAiSpeechTtsProvider : TtsProvider {

    companion object {
        /** GPU inference for a short sentence can take a while; allow up to 10 minutes. */
        private const val SYNTH_READ_TIMEOUT_MS = 10 * 60 * 1000L

        internal fun apiRoot(baseUrl: String): String {
            val base = baseUrl.trim().removeSuffix("/")
            require(base.isNotBlank()) { "TTS server URL must not be blank" }
            return if (base.endsWith("/v1")) base else "$base/v1"
        }

        internal fun speechEndpoint(baseUrl: String): String = apiRoot(baseUrl) + "/audio/speech"

        internal fun buildBody(request: TtsRequest): String {
            val voice = request.voiceName?.trim()?.takeIf { it.isNotBlank() }
                ?: request.refAudioUrl?.trim()?.takeIf { it.isNotBlank() }
                ?: throw TtsError("voice or ref_audio is required")
            val emotion = request.emotion?.trim()?.takeIf { it.isNotBlank() }
            val autoEmotion = emotion?.equals("auto", ignoreCase = true) == true
            val emotionActive = autoEmotion || emotion != null
            val emotionAlpha = request.emotionAlpha
                .takeIf { emotionActive && it.isFinite() && it != 1.0f }
                ?.coerceIn(0f, 1f)
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
                        if (request.useRandom) put("use_random", true)
                        when {
                            autoEmotion -> put("use_emo_text", true)
                            emotion != null -> {
                                put("use_emo_text", true)
                                put("emo_text", emotion)
                            }
                        }
                        emotionAlpha?.let { put("emo_alpha", it.toDouble()) }
                    },
                )
            }.toString()
        }

        /** Consent token the voice-registration API records for app uploads. */
        internal const val VOICE_UPLOAD_CONSENT = "user-authorized"

        /** MIME type the upload API validates, derived from the picked file's extension. */
        internal fun uploadMediaType(file: File): okhttp3.MediaType =
            when (file.extension.lowercase()) {
                "mp3", "mpeg" -> "audio/mpeg"
                "ogg" -> "audio/ogg"
                "flac" -> "audio/flac"
                "m4a", "mp4" -> "audio/mp4"
                "aac" -> "audio/aac"
                "webm" -> "audio/webm"
                else -> "audio/wav"
            }.toMediaType()

        /** `{"success":true,"voice":{...}}`; anything else is an error envelope. */
        internal fun parseUploadedVoice(body: String): TtsVoice {
            val root = TtsWire.parseJson(body)
            val voice = root?.get("voice")?.jsonObject
            val name = voice?.get("name")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val failed = root?.get("success")?.jsonPrimitive?.booleanOrNull == false
            if (failed || name == null) {
                val message = (root?.get("message") as? JsonPrimitive)?.contentOrNull
                    ?: (root?.get("error")?.jsonObject?.get("message") as? JsonPrimitive)?.contentOrNull
                throw TtsError(message ?: "Unexpected voice upload response from the server")
            }
            return TtsVoice(name, voice["speaker_description"]?.jsonPrimitive?.contentOrNull)
        }

        /** Some servers answer with JSON (an audio URL, base64, or an error) even on success. */
        private fun resolveJsonAudio(code: Int, bytes: ByteArray, authHeaders: Map<String, String>): ByteArray {
            val text = String(bytes, Charsets.UTF_8)
            val obj = TtsWire.parseJson(text)
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
                .post(buildBody(request).toRequestBody("application/json; charset=utf-8".toMediaType()))
                .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
                .build()

            client.newCall(httpRequest).execute().use { response ->
                val bytes = response.body?.bytes() ?: ByteArray(0)
                if (!response.isSuccessful) {
                    throw TtsError(TtsWire.friendlyError(response.code, bytes), response.code)
                }
                val contentType = (response.header("Content-Type") ?: "").lowercase()
                val audioBytes: ByteArray = when {
                    bytes.isEmpty() -> throw TtsError("Empty response from TTS server", response.code)
                    contentType.contains("json") -> resolveJsonAudio(response.code, bytes, headers)
                    contentType.contains("html") ->
                        throw TtsError("Unexpected HTML response — wrong endpoint or a proxy page?", response.code)
                    else -> bytes
                }
                TtsWire.writeCacheAudio(context, audioBytes)
            }
        }

    /** Model names served by the endpoint (`GET /v1/models`), in server order. */
    override suspend fun listModels(config: TtsServerConfig): List<String> = withContext(Dispatchers.IO) {
        val endpoint = apiRoot(config.baseUrl) + "/models"
        val headers = TtsWire.authHeaders(config)
        TtsWire.guardCleartext(endpoint, headers)
        val response = HttpClient.getTextResponse(endpoint, headers)
        if (!response.isSuccessful) {
            throw TtsError(TtsWire.friendlyError(response.code, response.body.toByteArray()), response.code)
        }
        val root = TtsWire.parseJson(response.body) ?: throw TtsError("Unexpected model list response")
        root["data"]?.jsonArray
            ?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
            ?.distinct()
            ?: emptyList()
    }

    /** Voices selectable by name (`GET /v1/audio/voices`): built-in speakers plus uploaded ones. */
    override suspend fun listVoices(config: TtsServerConfig): List<TtsVoice> = withContext(Dispatchers.IO) {
        val endpoint = apiRoot(config.baseUrl) + "/audio/voices"
        val headers = TtsWire.authHeaders(config)
        TtsWire.guardCleartext(endpoint, headers)
        val response = HttpClient.getTextResponse(endpoint, headers)
        if (!response.isSuccessful) {
            throw TtsError(TtsWire.friendlyError(response.code, response.body.toByteArray()), response.code)
        }
        val root = TtsWire.parseJson(response.body) ?: throw TtsError("Unexpected voice list response")
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

    /**
     * Registers [sample] through `POST /v1/audio/voices` (the IndexTTS/vLLM-Omni voice library).
     * The server stores the clip, and the chosen [name] becomes selectable right away.
     */
    override suspend fun uploadVoice(
        config: TtsServerConfig,
        sample: File,
        name: String,
    ): TtsVoice = withContext(Dispatchers.IO) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) throw TtsError("Voice name must not be blank")
        if (!sample.isFile) throw TtsError("The selected audio file is missing")
        if (sample.length() > TtsProviders.MAX_VOICE_SAMPLE_BYTES) {
            throw TtsError("Audio clip exceeds the 10 MB upload limit")
        }
        val endpoint = apiRoot(config.baseUrl) + "/audio/voices"
        val headers = TtsWire.authHeaders(config)
        TtsWire.guardCleartext(endpoint, headers)

        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("consent", VOICE_UPLOAD_CONSENT)
            .addFormDataPart("name", trimmed)
            .addFormDataPart("audio_sample", sample.name, sample.asRequestBody(uploadMediaType(sample)))
            .build()
        val httpRequest = Request.Builder()
            .url(endpoint)
            .post(multipart)
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
                parseUploadedVoice(text)
            }
    }
}
