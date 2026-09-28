package com.newoether.agora.tool

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * One binary-safe persistence boundary for audio returned by any tool transport.
 *
 * Mirrors [ToolImageStore]: server responses are untrusted inputs, so the store bounds the byte
 * payload, verifies that the bytes really are a supported audio container, writes into
 * app-private storage, fsyncs, and atomically publishes the final file. The published file is
 * what the chat player opens; nothing is ever played from an unverified download.
 */
class ToolAudioStore(
    context: Context,
    private val directory: File = File(context.applicationContext.filesDir, "tool-media"),
) {
    companion object {
        const val MAX_AUDIO_BYTES = 25L * 1024L * 1024L

        private const val HEADER_BYTES = 16

        /** Container sniffing for the formats a speech endpoint realistically returns. */
        internal fun audioMimeType(header: ByteArray, length: Int): String? {
            fun has(offset: Int, text: String): Boolean {
                if (offset + text.length > length) return false
                for (index in text.indices) {
                    if (header[offset + index] != text[index].code.toByte()) return false
                }
                return true
            }
            return when {
                length >= 12 && has(0, "RIFF") && has(8, "WAVE") -> "audio/wav"
                length >= 3 && has(0, "ID3") -> "audio/mpeg"
                length >= 2 && header[0] == 0xFF.toByte() &&
                    (header[1].toInt() and 0xE0) == 0xE0 -> "audio/mpeg"
                length >= 4 && has(0, "OggS") -> "audio/ogg"
                length >= 12 && has(4, "ftyp") -> "audio/mp4"
                else -> null
            }
        }

        private fun extensionFor(mime: String): String = when (mime) {
            "audio/wav" -> "wav"
            "audio/mpeg" -> "mp3"
            "audio/ogg" -> "ogg"
            "audio/mp4" -> "m4a"
            else -> "audio"
        }
    }

    /**
     * Copy [source] into app-private storage as a validated, atomically published audio file.
     *
     * @throws IOException when the source is missing, too large, or not a supported container
     */
    fun persistFile(source: File, filePrefix: String = "speech"): File {
        if (!source.isFile) throw IOException("Audio source is missing")
        val header = ByteArray(HEADER_BYTES)
        val headerLength = source.inputStream().use { it.read(header) }
        val mime = audioMimeType(header, headerLength)
            ?: throw IOException("Tool returned an unsupported or invalid audio file")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Could not create tool media directory")
        }
        val safePrefix = filePrefix
            .map { char -> if (char.isLetterOrDigit() || char == '_') char else '_' }
            .joinToString("")
            .trim('_')
            .ifBlank { "speech" }
            .take(32)
        val destination = File(
            directory,
            "${safePrefix}_${UUID.randomUUID()}.${extensionFor(mime)}",
        )
        val temporary = File(directory, ".${destination.name}.tmp")
        var size = 0L
        try {
            source.inputStream().use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count
                        if (size > MAX_AUDIO_BYTES) {
                            throw IOException("Tool audio exceeds its byte bound")
                        }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            if (size == 0L) throw IOException("Tool returned an empty audio file")
            if (!temporary.renameTo(destination)) {
                throw IOException("Could not finalize tool audio")
            }
            return destination
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }
}
