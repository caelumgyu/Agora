package com.newoether.agora.api.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class TtsProvidersTest {

    @Test
    fun `dashscope hosts select the native qwen-tts transport`() {
        assertEquals(TtsProviderKind.DASHSCOPE_QWEN_TTS, TtsProviders.kindFor("https://dashscope.aliyuncs.com"))
        assertEquals(
            TtsProviderKind.DASHSCOPE_QWEN_TTS,
            TtsProviders.kindFor("https://dashscope.aliyuncs.com/api/v1"),
        )
        assertEquals(
            TtsProviderKind.DASHSCOPE_QWEN_TTS,
            TtsProviders.kindFor("https://dashscope-intl.aliyuncs.com/"),
        )
        assertEquals(TtsProviderKind.DASHSCOPE_QWEN_TTS, TtsProviders.kindFor("dashscope.aliyuncs.com"))
    }

    @Test
    fun `every other endpoint stays openai-compatible`() {
        assertEquals(TtsProviderKind.OPENAI_SPEECH, TtsProviders.kindFor(""))
        assertEquals(TtsProviderKind.OPENAI_SPEECH, TtsProviders.kindFor("http://192.168.1.4:8092"))
        assertEquals(TtsProviderKind.OPENAI_SPEECH, TtsProviders.kindFor("https://api.openai.com/v1"))
        assertEquals(
            TtsProviderKind.OPENAI_SPEECH,
            TtsProviders.kindFor("https://tts.example.com/dashscope.aliyuncs.com"),
        )
    }
}
