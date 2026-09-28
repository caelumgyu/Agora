package com.newoether.agora.tool

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ToolAudioStorePersistTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val wavBytes: ByteArray = (
        "RIFF".toByteArray(Charsets.US_ASCII) +
            ByteArray(4) +
            "WAVE".toByteArray(Charsets.US_ASCII) +
            ByteArray(32) { 7 }
        )

    private fun store(directory: File) = ToolAudioStore(
        ApplicationProvider.getApplicationContext<Context>(),
        directory = directory,
    )

    @Test
    fun `valid speech bytes are published atomically into app-private storage`() {
        val directory = temporaryFolder.newFolder("tool-media")
        val source = temporaryFolder.newFile("speech.wav").apply { writeBytes(wavBytes) }

        val stored = store(directory).persistFile(source)

        assertTrue(stored.isFile)
        assertEquals(directory, stored.parentFile)
        assertTrue(stored.name.startsWith("speech_"))
        assertEquals("wav", stored.extension)
        assertArrayEquals(wavBytes, stored.readBytes())
        assertFalse(directory.listFiles().orEmpty().any { it.name.endsWith(".tmp") })
    }

    @Test
    fun `payload without a supported container is rejected`() {
        val directory = temporaryFolder.newFolder("tool-media-reject")
        val source = temporaryFolder.newFile("speech.wav")
            .apply { writeBytes("<html><body>nope</body></html>".toByteArray()) }

        try {
            store(directory).persistFile(source)
            fail("Expected the unsupported payload to be rejected")
        } catch (error: IOException) {
            assertTrue(error.message.orEmpty().contains("unsupported"))
        }
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }
}
