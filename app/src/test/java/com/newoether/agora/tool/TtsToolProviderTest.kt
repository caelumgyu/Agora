package com.newoether.agora.tool

import com.newoether.agora.viewmodel.GenerationContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsToolProviderTest {

    private fun configured(
        voice: String = "demo_voice",
        refAudio: String = "",
    ) = GenerationContext(
        ttsEnabled = true,
        ttsBaseUrl = "http://192.168.1.4:8092",
        ttsVoiceName = voice,
        ttsRefAudioUrl = refAudio,
    )

    @Test
    fun `speak tool is offered only when TTS is enabled and usable`() {
        assertFalse(TtsToolProvider.isConfigured(GenerationContext()))
        assertFalse(TtsToolProvider.isConfigured(configured().copy(ttsEnabled = false)))
        assertFalse(TtsToolProvider.isConfigured(configured().copy(ttsBaseUrl = "   ")))
        assertFalse(TtsToolProvider.isConfigured(configured(voice = "")))
        assertTrue(TtsToolProvider.isConfigured(configured()))
        assertTrue(
            TtsToolProvider.isConfigured(
                configured(voice = "", refAudio = "file:///tmp/reference.wav"),
            ),
        )
    }

    @Test
    fun `dashscope needs a named voice while the openai path also accepts a reference clip`() {
        val dashScope = configured().copy(ttsBaseUrl = "https://dashscope.aliyuncs.com")

        assertFalse(TtsToolProvider.isConfigured(dashScope.copy(ttsVoiceName = "")))
        assertFalse(
            TtsToolProvider.isConfigured(
                dashScope.copy(ttsVoiceName = "", ttsRefAudioUrl = "file:///tmp/reference.wav"),
            ),
        )
        assertTrue(TtsToolProvider.isConfigured(dashScope.copy(ttsVoiceName = "Cherry")))
    }

    @Test
    fun `default model follows the detected transport`() {
        assertEquals("qwen3-tts-flash", TtsToolProvider.defaultModel("https://dashscope.aliyuncs.com"))
        assertEquals(
            com.newoether.agora.data.DEFAULT_TTS_MODEL_NAME,
            TtsToolProvider.defaultModel("http://192.168.1.4:8092"),
        )
    }

    @Test
    fun `speak schema requires the spoken text and offers tone and rate where supported`() {
        val function = TtsToolProvider.definition(emotionSupported = true, speedSupported = true).function

        assertEquals("speak", function.name)
        assertEquals(listOf("text"), function.parameters.required)
        assertEquals("string", function.parameters.properties.getValue("text").type)
        assertEquals("string", function.parameters.properties.getValue("emotion").type)
        assertEquals("number", function.parameters.properties.getValue("speed").type)
        assertTrue(function.description.contains("emotion"))
        assertTrue(function.description.contains("speed"))

        val plain = TtsToolProvider.definition(emotionSupported = false, speedSupported = false)
            .function.parameters.properties
        assertFalse(plain.containsKey("emotion"))
        assertFalse(plain.containsKey("speed"))
    }

    @Test
    fun `spoken text and tone are parsed and trimmed from the arguments`() {
        assertEquals("你好世界", TtsToolProvider.parseText("""{"text":"  你好世界  "}"""))
        assertNull(TtsToolProvider.parseText("""{"text":"   "}"""))
        assertNull(TtsToolProvider.parseText("not json"))
        assertEquals("开心", TtsToolProvider.parseEmotion("""{"text":"hi","emotion":" 开心 "}"""))
        assertNull(TtsToolProvider.parseEmotion("""{"text":"hi"}"""))
        assertNull(TtsToolProvider.parseEmotion("""{"emotion":"  "}"""))
    }

    @Test
    fun `per-line rate is parsed and clamped to the supported range`() {
        assertEquals(1.5f, TtsToolProvider.parseSpeed("""{"text":"hi","speed":1.5}"""))
        assertEquals(0.5f, TtsToolProvider.parseSpeed("""{"text":"hi","speed":0.2}"""))
        assertEquals(2.0f, TtsToolProvider.parseSpeed("""{"text":"hi","speed":"3"}"""))
        assertNull(TtsToolProvider.parseSpeed("""{"text":"hi"}"""))
        assertNull(TtsToolProvider.parseSpeed("""{"text":"hi","speed":"fast"}"""))
    }
}
