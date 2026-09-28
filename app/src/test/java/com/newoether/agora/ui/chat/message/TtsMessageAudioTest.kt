package com.newoether.agora.ui.chat.message

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.Participant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsMessageAudioTest {

    private fun speakSegment(
        callId: String,
        audioPath: String? = "/data/tool-media/speech.wav",
        text: String = "你好",
    ) = MessageSegment(
        type = "tool",
        toolName = "speak",
        toolCallId = callId,
        toolArgs = """{"text":"$text"}""",
        toolResult = """{"type":"tts_speech","status":"ok"}""",
        toolStructuredResult = audioPath
            ?.let { """{"type":"tts_speech","audio_path":"$it","text":"$text"}""" }
            ?: """{"type":"tts_speech","error":"speech_failed"}""",
    )

    private fun message(
        id: String,
        runId: String?,
        segments: List<MessageSegment>,
    ) = ChatMessage(
        id = id,
        text = "",
        participant = Participant.MODEL,
        runId = runId,
        segments = segments,
    )

    @Test
    fun `successful speech segments become clips`() {
        val clips = ttsAudioClips(
            listOf(
                message("a", "run-1", listOf(speakSegment("call-1", text = "早上好"))),
                message("b", "run-1", listOf(MessageSegment(type = "answer", content = "hi"))),
            ),
        )

        assertEquals(1, clips.size)
        assertEquals("call-1", clips.single().callId)
        assertEquals("/data/tool-media/speech.wav", clips.single().path)
        assertEquals("早上好", clips.single().text)
    }

    @Test
    fun `failed speech and unrelated tools never produce clips`() {
        val clips = ttsAudioClips(
            listOf(
                message("a", "run-1", listOf(speakSegment("call-1", audioPath = null))),
                message(
                    "b",
                    "run-1",
                    listOf(
                        MessageSegment(
                            type = "tool",
                            toolName = "generate_image",
                            toolCallId = "call-2",
                            toolStructuredResult = """{"audio_path":"/tmp/not-speech.wav"}""",
                        ),
                    ),
                ),
            ),
        )

        assertTrue(clips.isEmpty())
    }

    @Test
    fun `clips without a run stay out of the per-run lookup`() {
        val clips = latestTtsClipsByRun(
            listOf(message("a", null, listOf(speakSegment("call-1")))),
        )

        assertTrue(clips.isEmpty())
    }

    @Test
    fun `latest clips are grouped per run`() {
        val clips = latestTtsClipsByRun(
            listOf(
                message("a", "run-1", listOf(speakSegment("call-1", text = "first"))),
                message("b", "run-2", listOf(speakSegment("call-2", text = "other run"))),
                message("c", "run-1", listOf(speakSegment("call-3", text = "second"))),
            ),
        )

        assertEquals(setOf("run-1", "run-2"), clips.keys)
        assertEquals("call-3", clips.getValue("run-1").callId)
        assertEquals("second", clips.getValue("run-1").text)
        assertEquals("call-2", clips.getValue("run-2").callId)
    }

    @Test
    fun `clip for a run without speech is absent`() {
        assertNull(latestTtsClipsByRun(listOf(message("a", "run-1", emptyList())))["run-1"])
    }
}
