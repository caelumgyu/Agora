package com.newoether.agora.api.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class OpenAiSpeechTtsProviderTest {

    @Test
    fun `endpoint appends the v1 root exactly once`() {
        assertEquals(
            "http://host:8092/v1/audio/speech",
            OpenAiSpeechTtsProvider.speechEndpoint("http://host:8092"),
        )
        assertEquals(
            "http://host:8092/v1/audio/speech",
            OpenAiSpeechTtsProvider.speechEndpoint("http://host:8092/"),
        )
        assertEquals(
            "http://host:8092/v1/audio/speech",
            OpenAiSpeechTtsProvider.speechEndpoint("http://host:8092/v1"),
        )
    }

    @Test
    fun `named voices go to voice and clips go to ref_audio`() {
        val named = OpenAiSpeechTtsProvider.buildBody(
            TtsRequest("hi", "m", voiceName = "demo", language = "zh"),
        )
        val clip = OpenAiSpeechTtsProvider.buildBody(
            TtsRequest("hi", "m", refAudioUrl = "file:///tmp/a.wav", language = "zh"),
        )

        assertTrue(named.contains(""""voice":"demo""""))
        assertTrue(clip.contains(""""ref_audio":"file:///tmp/a.wav""""))
        // Speed is omitted at 1.0 and serialized otherwise.
        assertFalse(named.contains("speed"))
        assertTrue(
            OpenAiSpeechTtsProvider.buildBody(
                TtsRequest("hi", "m", voiceName = "demo", language = "zh", speed = 1.5f),
            ).contains(""""speed":1.5"""),
        )
    }

    @Test
    fun `tone descriptions enable the server-side emotion classifier`() {
        val described = OpenAiSpeechTtsProvider.buildBody(
            TtsRequest("hi", "m", voiceName = "demo", language = "zh", emotion = " 难过、语速缓慢 "),
        )
        assertTrue(described.contains(""""emo_text":"难过、语速缓慢""""))
        assertTrue(described.contains(""""use_emo_text":true"""))

        val auto = OpenAiSpeechTtsProvider.buildBody(
            TtsRequest("hi", "m", voiceName = "demo", language = "zh", emotion = "auto"),
        )
        assertTrue(auto.contains(""""use_emo_text":true"""))
        assertFalse(auto.contains(""""emo_text":"""))

        val neutral = OpenAiSpeechTtsProvider.buildBody(
            TtsRequest("hi", "m", voiceName = "demo", language = "zh"),
        )
        assertFalse(neutral.contains("use_emo_text"))
    }

    @Test
    fun `voice upload response yields the registered name`() {
        val voice = OpenAiSpeechTtsProvider.parseUploadedVoice(
            """{"success":true,"voice":{"name":"甘城","consent":"user-authorized","created_at":1,"mime_type":"audio/wav","file_size":501156}}""",
        )

        assertEquals("甘城", voice.name)
    }

    @Test
    fun `failed voice uploads surface the server message`() {
        try {
            OpenAiSpeechTtsProvider.parseUploadedVoice(
                """{"success":false,"message":"Reference audio too short (0.5s)."}""",
            )
            fail("Expected a failed upload envelope to be rejected")
        } catch (error: TtsError) {
            assertTrue(error.message.orEmpty().contains("too short"))
        }

        try {
            OpenAiSpeechTtsProvider.parseUploadedVoice("not json")
            fail("Expected an unparseable body to be rejected")
        } catch (error: TtsError) {
            assertTrue(error.message.orEmpty().contains("Unexpected"))
        }
    }

    @Test
    fun `upload media type follows the picked file extension`() {
        assertEquals("audio/mpeg", OpenAiSpeechTtsProvider.uploadMediaType(File("voice.mp3")).toString())
        assertEquals("audio/wav", OpenAiSpeechTtsProvider.uploadMediaType(File("voice.wav")).toString())
        assertEquals("audio/wav", OpenAiSpeechTtsProvider.uploadMediaType(File("voice.unknown")).toString())
    }
}
