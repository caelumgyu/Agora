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
    fun `the tool row and its result row only yield one clip per call`() {
        val clips = ttsAudioClips(
            listOf(
                message("a", "run-1", listOf(speakSegment("call-1", text = "第一句"))),
                message("b", "run-1", listOf(speakSegment("call-1", text = "第一句"))),
            ),
        )

        assertEquals(1, clips.size)
        assertEquals("call-1", clips.single().callId)
    }

    @Test
    fun `multiple calls keep the model's order`() {
        val clips = ttsAudioClips(
            listOf(
                message(
                    "a",
                    "run-1",
                    listOf(
                        speakSegment("call-1", text = "第一句"),
                        speakSegment("call-2", text = "第二句"),
                        speakSegment("call-3", text = "第三句"),
                    ),
                ),
            ),
        )

        assertEquals(listOf("call-1", "call-2", "call-3"), clips.map(TtsAudioClip::callId))
        assertEquals(listOf("第一句", "第二句", "第三句"), clips.map(TtsAudioClip::text))
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
    fun `rendered payloads expose their clips and merge keeps call order`() {
        val toolRow = message(
            "a",
            "run-1",
            listOf(speakSegment("call-1", text = "第一句"), speakSegment("call-2", text = "第二句")),
        )
        val resultRow = message("b", "run-1", listOf(speakSegment("call-2", text = "第二句")))
        val answerRow = message("c", "run-1", listOf(MessageSegment(type = "answer", content = "hi")))

        assertNull(renderedTtsClips(answerRow))
        val first = renderedTtsClips(toolRow)!!
        assertEquals(listOf("call-1", "call-2"), first.map(TtsAudioClip::callId))

        // The result row repeats the same clip; merging must not duplicate or reorder it.
        val merged = mergeTtsClips(first, renderedTtsClips(resultRow)!!)
        assertEquals(listOf("call-1", "call-2"), merged.map(TtsAudioClip::callId))
        assertEquals(first, merged)
        assertEquals(first, mergeTtsClips(null, first))
        assertEquals(first, mergeTtsClips(first, emptyList()))
    }

    @Test
    fun `later rows append only clips the index does not know`() {
        val known = renderedTtsClips(message("a", "run-1", listOf(speakSegment("call-1"))))!!
        val later = renderedTtsClips(
            message("b", "run-1", listOf(speakSegment("call-1"), speakSegment("call-2"))),
        )!!

        assertEquals(
            listOf("call-1", "call-2"),
            mergeTtsClips(known, later).map(TtsAudioClip::callId),
        )
    }
}
