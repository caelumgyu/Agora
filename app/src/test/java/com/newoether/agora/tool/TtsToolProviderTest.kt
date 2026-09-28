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
