package com.newoether.agora.ui.chat.message

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * One successfully synthesized speech clip carried by a tool segment.
 *
 * [path] is the app-private file the player opens; [callId] is the stable identity used to dedupe
 * auto-play and to reflect the current playback state in the message action bar.
 */
internal data class TtsAudioClip(
    val callId: String,
    val path: String,
    val text: String? = null,
)

/** Tool name owned by `TtsToolProvider`; kept as a literal so the UI never depends on the executor. */
private const val SPEAK_TOOL_NAME = "speak"

private val ttsClipJson = Json { ignoreUnknownKeys = true }

/**
 * Every speech clip in [messages], in message order.
 *
 * A tool round repeats the same structured result on the model call row and on the result row, so
 * clips are deduplicated by `toolCallId` — the first occurrence (the call row, which preserves the
 * model's call order) wins. Used by auto-play, which only ever sees the live payloads.
 */
internal fun ttsAudioClips(messages: List<ChatMessage>): List<TtsAudioClip> {
    val clips = LinkedHashMap<String, TtsAudioClip>()
    for (message in messages) {
        for (segment in message.segments.orEmpty()) {
            val clip = clipForSegment(segment) ?: continue
            clips.putIfAbsent(clip.callId, clip)
        }
    }
    return clips.values.toList()
}

/**
 * Speech clips this rendered message carries, in call order; null when it has none.
 *
 * History rows are payload-free stubs until they are hydrated for rendering, so the clip index is
 * fed from every message the list actually composes instead of from `allMessages` — that keeps
 * the replay button working for earlier turns (within the context window) as soon as their turn
 * has been shown once.
 */
internal fun renderedTtsClips(message: ChatMessage): List<TtsAudioClip>? {
    val segments = message.segments ?: return null
    if (segments.none { it.type == "tool" && it.toolName == SPEAK_TOOL_NAME }) return null
    val clips = LinkedHashMap<String, TtsAudioClip>()
    for (segment in segments) {
        val clip = clipForSegment(segment) ?: continue
        clips.putIfAbsent(clip.callId, clip)
    }
    return clips.values.toList().takeIf { it.isNotEmpty() }
}

/** Appends [incoming] clips that [existing] does not know yet, preserving first-seen call order. */
internal fun mergeTtsClips(
    existing: List<TtsAudioClip>?,
    incoming: List<TtsAudioClip>,
): List<TtsAudioClip> {
    if (existing.isNullOrEmpty()) return incoming
    val known = existing.mapTo(hashSetOf(), TtsAudioClip::callId)
    val additions = incoming.filter { it.callId !in known }
    return if (additions.isEmpty()) existing else existing + additions
}

/**
 * The provider-declared structured result is authoritative: a segment carries an audio path only
 * after synthesis succeeded and the private file was published. Wire states are never trusted for
 * this, so a run stopped after the clip completed is still replayable.
 */
private fun clipForSegment(segment: MessageSegment): TtsAudioClip? {
    if (segment.type != "tool" || segment.toolName != SPEAK_TOOL_NAME) return null
    val callId = segment.toolCallId?.takeIf { it.isNotBlank() } ?: return null
    val structured = parseObject(segment.toolStructuredResult) ?: return null
    val path = structured.string("audio_path")?.takeIf { it.isNotBlank() } ?: return null
    return TtsAudioClip(callId = callId, path = path, text = structured.string("text"))
}

private fun parseObject(value: String?): JsonObject? {
    if (value.isNullOrBlank()) return null
    return runCatching { ttsClipJson.parseToJsonElement(value) as? JsonObject }.getOrNull()
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull
