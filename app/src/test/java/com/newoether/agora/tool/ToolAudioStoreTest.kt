package com.newoether.agora.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolAudioStoreTest {

    private fun header(vararg parts: Pair<Int, String>): ByteArray =
        ByteArray(16).also { buffer ->
            parts.forEach { (offset, text) ->
                text.forEachIndexed { index, char -> buffer[offset + index] = char.code.toByte() }
            }
        }

    @Test
    fun `supported speech containers are sniffed from their magic bytes`() {
        val wav = header(0 to "RIFF", 8 to "WAVE")
        val ogg = header(0 to "OggS")
        val m4a = header(4 to "ftyp")
        val id3 = header(0 to "ID3")
        val frameSync = ByteArray(16).also {
            it[0] = 0xFF.toByte()
            it[1] = 0xFB.toByte()
        }

        assertEquals("audio/wav", ToolAudioStore.audioMimeType(wav, wav.size))
        assertEquals("audio/ogg", ToolAudioStore.audioMimeType(ogg, ogg.size))
        assertEquals("audio/mp4", ToolAudioStore.audioMimeType(m4a, m4a.size))
        assertEquals("audio/mpeg", ToolAudioStore.audioMimeType(id3, id3.size))
        assertEquals("audio/mpeg", ToolAudioStore.audioMimeType(frameSync, frameSync.size))
    }

    @Test
    fun `unknown or truncated payloads are rejected`() {
        val html = header(0 to "<html><body>")
        val riffOnly = header(0 to "RIFF")

        assertNull(ToolAudioStore.audioMimeType(html, html.size))
        assertNull(ToolAudioStore.audioMimeType(riffOnly, riffOnly.size))
        assertNull(ToolAudioStore.audioMimeType(ByteArray(16), 1))
    }
}
