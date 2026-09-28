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
    fun `speak schema requires the spoken text`() {
        val function = TtsToolProvider.definition().function

        assertEquals("speak", function.name)
        assertEquals(listOf("text"), function.parameters.required)
        assertEquals("string", function.parameters.properties.getValue("text").type)
    }

    @Test
    fun `spoken text is parsed and trimmed from the arguments`() {
        assertEquals("你好世界", TtsToolProvider.parseText("""{"text":"  你好世界  "}"""))
        assertNull(TtsToolProvider.parseText("""{"text":"   "}"""))
        assertNull(TtsToolProvider.parseText("{}"))
        assertNull(TtsToolProvider.parseText("not json"))
    }
}
