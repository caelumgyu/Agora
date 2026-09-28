package com.newoether.agora.api.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
