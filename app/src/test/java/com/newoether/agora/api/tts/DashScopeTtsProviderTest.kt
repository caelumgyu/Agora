package com.newoether.agora.api.tts

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class DashScopeTtsProviderTest {

    @Test
    fun `endpoint accepts host roots api bases and the full path`() {
        val expected =
            "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation"

        assertEquals(expected, DashScopeTtsProvider.speechEndpoint("https://dashscope.aliyuncs.com"))
        assertEquals(expected, DashScopeTtsProvider.speechEndpoint("https://dashscope.aliyuncs.com/"))
        assertEquals(expected, DashScopeTtsProvider.speechEndpoint("https://dashscope.aliyuncs.com/api/v1"))
        assertEquals(expected, DashScopeTtsProvider.speechEndpoint(expected))
        assertEquals(
            "https://dashscope-intl.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation",
            DashScopeTtsProvider.speechEndpoint("https://dashscope-intl.aliyuncs.com"),
        )
    }

    @Test
    fun `language codes map onto the dashscope enum`() {
        assertEquals("Chinese", DashScopeTtsProvider.languageType("zh"))
        assertEquals("English", DashScopeTtsProvider.languageType("EN"))
        assertEquals("Japanese", DashScopeTtsProvider.languageType("ja"))
        assertEquals("Spanish", DashScopeTtsProvider.languageType("es"))
        // DashScope has no Arabic type; mixed zh/en and unknown codes use Auto.
        assertEquals("Auto", DashScopeTtsProvider.languageType("ar"))
        assertEquals("Auto", DashScopeTtsProvider.languageType("zhen"))
        assertEquals("Auto", DashScopeTtsProvider.languageType(""))
    }

    @Test
    fun `request body nests text voice and language`() {
        val body = DashScopeTtsProvider.requestBody(
            TtsRequest(text = "你好", model = "qwen3-tts-flash", voiceName = "Cherry", language = "zh"),
        )

        val root = Json.parseToJsonElement(body).jsonObject
        assertEquals("qwen3-tts-flash", root["model"]?.jsonPrimitive?.content)
        val input = root["input"]!!.jsonObject
        assertEquals("你好", input["text"]?.jsonPrimitive?.content)
        assertEquals("Cherry", input["voice"]?.jsonPrimitive?.content)
        assertEquals("Chinese", input["language_type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a named voice is required even with a reference clip`() {
        try {
            DashScopeTtsProvider.requestBody(
                TtsRequest(
                    text = "hi",
                    model = "qwen3-tts-flash",
                    refAudioUrl = "file:///tmp/reference.wav",
                    language = "en",
                ),
            )
            fail("Expected a missing voice to be rejected")
        } catch (error: TtsError) {
            assertTrue(error.message.orEmpty().contains("voice"))
        }
    }

    @Test
    fun `non-stream responses expose base64 audio url or an error envelope`() {
        val base64 = java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))

        assertEquals(
            listOf<Byte>(1, 2, 3),
            DashScopeTtsProvider.audioBase64("""{"output":{"audio":{"data":"$base64","url":""}}}""")?.toList(),
        )
        assertEquals(
            "https://example.com/audio.wav",
            DashScopeTtsProvider.audioUrl(
                """{"output":{"audio":{"data":"","url":"https://example.com/audio.wav"}}}""",
            ),
        )
        assertNull(DashScopeTtsProvider.audioBase64("""{"output":{"audio":{"data":"","url":"https://x/a.wav"}}}"""))
        assertNull(DashScopeTtsProvider.audioUrl("""{"output":{"audio":{"data":"","url":""}}}"""))
        assertEquals(
            "Invalid API-key provided",
            DashScopeTtsProvider.providerError("""{"code":"InvalidApiKey","message":"Invalid API-key provided"}"""),
        )
        assertNull(DashScopeTtsProvider.providerError("""{"output":{"audio":{"url":"https://x/a.wav"}}}"""))
    }

    @Test
    fun `voice upload is rejected for dashscope`() = runTest {
        try {
            DashScopeTtsProvider().uploadVoice(
                TtsServerConfig(baseUrl = "https://dashscope.aliyuncs.com"),
                File("voice.wav"),
                "Cherry",
            )
            fail("Expected DashScope voice upload to be rejected")
        } catch (error: TtsError) {
            assertTrue(error.message.orEmpty().contains("does not support"))
        }
    }
}
