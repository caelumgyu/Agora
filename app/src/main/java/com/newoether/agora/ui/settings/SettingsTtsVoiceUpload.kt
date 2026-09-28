package com.newoether.agora.ui.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.api.tts.TtsClient
import com.newoether.agora.api.tts.TtsError
import com.newoether.agora.api.tts.TtsProviders
import com.newoether.agora.api.tts.TtsServerConfig
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Voice-group item that registers a local recording as a named voice through the server's
 * `/v1/audio/voices` upload API (IndexTTS/vLLM-Omni). On success the uploaded name becomes the
 * selected voice and the server's voice list is refreshed.
 */
@Composable
internal fun TtsVoiceUploadItem(
    baseUrl: String,
    apiKey: String,
    onUploaded: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uploadingLabel = stringResource(R.string.tts_voice_upload_status)
    val okTemplate = stringResource(R.string.tts_voice_upload_ok)
    val failedTemplate = stringResource(R.string.tts_voice_upload_failed)

    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var uploading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingUri = uri
    }

    pendingUri?.let { picked ->
        var voiceName by remember(picked) { mutableStateOf("") }
        var consent by remember(picked) { mutableStateOf(false) }
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { if (!uploading) pendingUri = null },
            title = { Text(stringResource(R.string.tts_voice_upload), fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = voiceName,
                        onValueChange = { voiceName = it },
                        label = { Text(stringResource(R.string.tts_voice_upload_name)) },
                        singleLine = true,
                        enabled = !uploading,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable(enabled = !uploading) { consent = !consent },
                    ) {
                        Checkbox(
                            checked = consent,
                            onCheckedChange = { consent = it },
                            enabled = !uploading,
                        )
                        Text(
                            stringResource(R.string.tts_voice_upload_consent),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    status?.let {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !uploading && voiceName.isNotBlank() && consent,
                    onClick = {
                        uploading = true
                        status = uploadingLabel
                        scope.launch {
                            try {
                                val sample = copyPickedAudio(context, picked)
                                val voice = try {
                                    TtsClient().uploadVoice(
                                        TtsServerConfig(baseUrl = baseUrl.trim(), apiKey = apiKey),
                                        sample,
                                        voiceName.trim(),
                                    )
                                } finally {
                                    sample.delete()
                                }
                                status = String.format(Locale.US, okTemplate, voice.name)
                                uploading = false
                                pendingUri = null
                                onUploaded(voice.name)
                            } catch (error: TtsError) {
                                status = String.format(
                                    Locale.US,
                                    failedTemplate,
                                    error.message ?: "HTTP ${error.code}",
                                )
                                uploading = false
                            } catch (error: Exception) {
                                status = String.format(
                                    Locale.US,
                                    failedTemplate,
                                    error.message ?: "Network error",
                                )
                                uploading = false
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.tts_voice_upload_action))
                }
            },
            dismissButton = {
                TextButton(enabled = !uploading, onClick = { pendingUri = null }) {
                    Text(stringResource(R.string.provider_cancel))
                }
            },
        )
    }

    SettingsItem(
        headlineContent = { Text(stringResource(R.string.tts_voice_upload)) },
        supportingContent = { Text(status ?: stringResource(R.string.tts_voice_upload_desc)) },
        leadingContent = { Icon(Icons.Default.UploadFile, null, tint = MaterialTheme.colorScheme.primary) },
        modifier = Modifier.clickable(enabled = !uploading) { picker.launch(arrayOf("audio/*")) },
    )
}

/** Copies the picked clip into the cache within the server's upload bound. */
private suspend fun copyPickedAudio(context: Context, uri: Uri): File = withContext(Dispatchers.IO) {
    val destination = File(context.cacheDir, "voice_upload_${UUID.randomUUID()}")
    try {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw TtsError("Could not read the selected audio file")
        input.use { source ->
            destination.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > TtsProviders.MAX_VOICE_SAMPLE_BYTES) {
                        throw TtsError("Audio clip exceeds the 10 MB upload limit")
                    }
                    output.write(buffer, 0, read)
                }
            }
        }
        if (destination.length() == 0L) throw TtsError("The selected audio file is empty")
        destination
    } catch (error: Exception) {
        destination.delete()
        throw error
    }
}
