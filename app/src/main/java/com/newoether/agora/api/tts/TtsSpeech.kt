package com.newoether.agora.api.tts

import android.content.Context
import com.newoether.agora.api.HttpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File

/** TTS synthesis failure. [code] carries the HTTP status when the server answered. */
class TtsError(message: String, val code: Int? = null) : Exception(message)

/** Connection settings for a speech server. */
data class TtsServerConfig(
    /** Base address of the server, e.g. `http://192.168.1.50:8092` (no trailing slash needed). */
    val baseUrl: String,
    /** Optional Bearer token for servers that require authentication. */
    val apiKey: String = "",
)

/** One synthesis request against a speech endpoint. */
data class TtsRequest(
    val text: String,
    /** Model name the server expects (e.g. `IndexTeam/IndexTTS-2.5` or `qwen3-tts-flash`). */
    val model: String,
    /** Named voice; takes precedence over [refAudioUrl]. */
    val voiceName: String? = null,
    /**
     * Reference clip for zero-shot cloning: http(s) URL, `data:` URI or `file://` path.
     *
     * Only transports that support cloning (the OpenAI-compatible IndexTTS/vLLM-Omni path) read
     * this field; DashScope's native Qwen-TTS API has no reference-audio input and ignores it.
     */
    val refAudioUrl: String? = null,
    /** IndexTTS-2.5 language code (zh/en/ja/es/ar, plus the vLLM-Omni mixed mode "zhen"). */
    val language: String,
    /** Speech rate, 0.5–2.0. Ignored by transports without a speed control (DashScope). */
    val speed: Float = 1.0f,
)

/** A voice selectable by name: a built-in speaker or one uploaded to the server. */
data class TtsVoice(
    val name: String,
    val description: String? = null,
)

/** Shared HTTP/JSON/cache plumbing for every speech transport. */
internal object TtsWire {

    /** Best-effort human-readable failure from a non-2xx body. */
    fun friendlyError(code: Int, bytes: ByteArray): String {
        val text = String(bytes, Charsets.UTF_8).trim()
        val detail = parseJson(text)
            ?.let { obj ->
                (obj["detail"] as? JsonPrimitive)?.contentOrNull
                    ?: (obj["error"]?.jsonObject?.get("message") as? JsonPrimitive)?.contentOrNull
                    ?: (obj["message"] as? JsonPrimitive)?.contentOrNull
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

    fun parseJson(text: String): JsonObject? = try {
        Json.parseToJsonElement(text).jsonObject
    } catch (_: Exception) {
        null
    }

    fun authHeaders(config: TtsServerConfig): Map<String, String> = buildMap {
        config.apiKey.trim().takeIf { it.isNotBlank() }?.let { put("Authorization", "Bearer $it") }
    }

    fun audioExtension(bytes: ByteArray): String {
        if (bytes.size >= 12 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'A'.code.toByte()
        ) return "wav"
        if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && (bytes[1].toInt() and 0xE0) == 0xE0) return "mp3"
        if (bytes.size >= 4 && String(bytes, 0, 4) == "OggS") return "ogg"
        if (bytes.size >= 12 && String(bytes, 4, 8) == "ftyp") return "m4a"
        return "wav" // default; ExoPlayer sniffs the container anyway
    }

    /** Persist synthesized bytes in the app cache and return the playable file. */
    fun writeCacheAudio(context: Context, bytes: ByteArray): File {
        val dir = File(context.cacheDir, "tts").apply { mkdirs() }
        return File(dir, "tts_${System.currentTimeMillis()}.${audioExtension(bytes)}")
            .also { it.writeBytes(bytes) }
    }

    /** Shared guard used by every transport before a request carries credentials. */
    fun guardCleartext(endpoint: String, headers: Map<String, String>) {
        HttpClient.guardCleartextCredentials(endpoint, headers)
    }
}
