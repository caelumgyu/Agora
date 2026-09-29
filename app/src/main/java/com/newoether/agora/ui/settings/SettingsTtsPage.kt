package com.newoether.agora.ui.settings

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.api.tts.TtsClient
import com.newoether.agora.api.tts.TtsError
import com.newoether.agora.api.tts.TtsProviderKind
import com.newoether.agora.api.tts.TtsProviders
import com.newoether.agora.api.tts.TtsVoice
import com.newoether.agora.api.tts.TtsRequest
import com.newoether.agora.api.tts.TtsServerConfig
import com.newoether.agora.data.DEFAULT_TTS_MODEL_NAME
import com.newoether.agora.data.repository.setTtsApiKey
import com.newoether.agora.data.repository.setTtsBaseUrl
import com.newoether.agora.data.repository.setTtsEmotionAlpha
import com.newoether.agora.data.repository.setTtsEmotionAuto
import com.newoether.agora.data.repository.setTtsEmotionRandom
import com.newoether.agora.data.repository.setTtsEnabled
import com.newoether.agora.data.repository.setTtsLanguage
import com.newoether.agora.data.repository.setTtsModelName
import com.newoether.agora.data.repository.setTtsRefAudioUrl
import com.newoether.agora.data.repository.setTtsSpeakPrompt
import com.newoether.agora.data.repository.setTtsSpeed
import com.newoether.agora.data.repository.setTtsVoiceName
import com.newoether.agora.ui.common.PersistedSliderFeedbackGate
import com.newoether.agora.ui.components.AgoraDropdownMenuItem
import com.newoether.agora.ui.components.AgoraExposedDropdownMenu
import com.newoether.agora.util.noOpBringIntoView
import com.newoether.agora.viewmodel.ChatViewModel
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Outcome of the "synthesize sample" action on the TTS settings page. */
private sealed interface TtsTestState {
    data object Idle : TtsTestState
    data object Running : TtsTestState
    data class Failed(val message: String) : TtsTestState
    data class Ok(val file: File) : TtsTestState
}

/** Debounced single-line setting field (URL, model name, voice name, …). */
@Composable
private fun TtsSettingField(
    title: String,
    initial: String,
    placeholder: String,
    description: String? = null,
    icon: ImageVector,
    onCommit: (String) -> Unit,
) {
    // Don't key on [initial] for state creation — the debounced save would recreate the
    // field on every write. External changes (e.g. import) are synced separately.
    val state = remember { TextFieldState(initial) }
    LaunchedEffect(initial) {
        val current = state.text.toString()
        if (initial.isNotEmpty() && initial != current) {
            state.edit { replace(0, length, initial) }
        }
    }
    // Save user input with 500ms debounce.
    LaunchedEffect(state.text) {
        delay(500)
        onCommit(state.text.toString().trim())
    }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Icon(
                icon,
                null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Box(modifier = Modifier.noOpBringIntoView().padding(top = 8.dp)) {
                    OutlinedTextField(
                        state = state,
                        placeholder = { Text(placeholder) },
                        lineLimits = TextFieldLineLimits.SingleLine,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                    )
                }
                description?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

/** [TtsSettingField] plus a dropdown of server-detected values; free text entry still works. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TtsSelectField(
    title: String,
    initial: String,
    options: List<String>,
    placeholder: String,
    description: String? = null,
    icon: ImageVector,
    onCommit: (String) -> Unit,
) {
    // Same state pattern as TtsSettingField: debounced commit + external-change sync.
    val state = remember { TextFieldState(initial) }
    var menuExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(initial) {
        val current = state.text.toString()
        if (initial.isNotEmpty() && initial != current) {
            state.edit { replace(0, length, initial) }
        }
    }
    LaunchedEffect(state.text) {
        delay(500)
        onCommit(state.text.toString().trim())
    }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Icon(
                icon,
                null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                ExposedDropdownMenuBox(
                    expanded = menuExpanded,
                    onExpandedChange = { menuExpanded = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    OutlinedTextField(
                        state = state,
                        placeholder = { Text(placeholder) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuExpanded) },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .menuAnchor(
                                type = ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                                enabled = true,
                            )
                            .fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                    )
                    AgoraExposedDropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        options.forEach { option ->
                            AgoraDropdownMenuItem(
                                text = { Text(option) },
                                onClick = {
                                    state.edit { replace(0, length, option) }
                                    menuExpanded = false
                                },
                            )
                        }
                    }
                }
                description?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTtsPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val settings = viewModel.settings
    val enabled by settings.ttsEnabled.collectAsState()
    val baseUrl by settings.ttsBaseUrl.collectAsState()
    val apiKey by settings.ttsApiKey.collectAsState()
    val modelName by settings.ttsModelName.collectAsState()
    val voiceName by settings.ttsVoiceName.collectAsState()
    val refAudioUrl by settings.ttsRefAudioUrl.collectAsState()
    val language by settings.ttsLanguage.collectAsState()
    val speed by settings.ttsSpeed.collectAsState()
    val speakPrompt by settings.ttsSpeakPrompt.collectAsState()
    val emotionAuto by settings.ttsEmotionAuto.collectAsState()
    val emotionAlpha by settings.ttsEmotionAlpha.collectAsState()
    val emotionRandom by settings.ttsEmotionRandom.collectAsState()

    var apiKeyText by remember { mutableStateOf(apiKey) }
    LaunchedEffect(apiKey) { if (apiKeyText != apiKey) apiKeyText = apiKey }

    var showLanguageDialog by remember { mutableStateOf(false) }
    var showSpeakPromptDialog by remember { mutableStateOf(false) }
    var testState by remember { mutableStateOf<TtsTestState>(TtsTestState.Idle) }

    val speedGate = remember {
        PersistedSliderFeedbackGate(
            initialPersisted = speed,
            toDisplay = Float::toFloat,
        )
    }
    LaunchedEffect(speed) { speedGate.reconcile(speed) }

    val emotionAlphaGate = remember {
        PersistedSliderFeedbackGate(
            initialPersisted = emotionAlpha,
            toDisplay = Float::toFloat,
        )
    }
    LaunchedEffect(emotionAlpha) { emotionAlphaGate.reconcile(emotionAlpha) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val showDocFab by settings.showDocumentationFab.collectAsState()

    // DashScope's native Qwen-TTS protocol is selected by its hosts; everything else is an
    // OpenAI-compatible /v1/audio/speech server.
    val dashScopeTts = TtsProviders.kindFor(baseUrl) == TtsProviderKind.DASHSCOPE_QWEN_TTS

    // Resolve the test-synthesis copy in composable scope so the plain function below
    // never invokes @Composable functions.
    val noUrlMessage = stringResource(R.string.tts_no_url)
    val needsVoiceMessage = stringResource(R.string.tts_needs_voice)
    val sampleText = stringResource(R.string.tts_test_text)

    var detecting by remember { mutableStateOf(false) }
    var detectStatus by remember { mutableStateOf<String?>(null) }
    var detectedModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var detectedVoices by remember { mutableStateOf<List<TtsVoice>>(emptyList()) }

    val detectingMessage = stringResource(R.string.tts_detecting)
    val detectResultFormat = stringResource(R.string.tts_detect_result)

    // Query the server for served models and selectable voices; results feed the
    // dropdowns below. Strings are resolved above so this plain function never
    // invokes @Composable functions.
    fun runDetection() {
        if (detecting) return
        val url = baseUrl.trim()
        if (url.isEmpty()) {
            detectStatus = noUrlMessage
            return
        }
        detecting = true
        detectStatus = detectingMessage
        scope.launch {
            try {
                val client = TtsClient()
                val config = TtsServerConfig(baseUrl = url, apiKey = apiKey)
                detectedModels = client.listModels(config)
                detectedVoices = client.listVoices(config)
                detectStatus = String.format(
                    Locale.US,
                    detectResultFormat,
                    detectedModels.size,
                    detectedVoices.size,
                )
            } catch (error: TtsError) {
                detectStatus = error.message ?: "HTTP ${error.code}"
            } catch (error: Exception) {
                detectStatus = error.message ?: "Network error"
            } finally {
                detecting = false
            }
        }
    }

    // Detect on enable and whenever the URL field commits a new value. DashScope's native API has
    // no model/voice listing, so its hint replaces the detection row.
    LaunchedEffect(enabled, baseUrl) {
        if (enabled && baseUrl.trim().isNotEmpty() && !dashScopeTts) runDetection()
    }

    fun runTestSynthesis() {
        if (testState is TtsTestState.Running) return
        val url = baseUrl.trim()
        if (url.isEmpty()) {
            testState = TtsTestState.Failed(noUrlMessage)
            return
        }
        if (voiceName.isBlank() && (dashScopeTts || refAudioUrl.isBlank())) {
            testState = TtsTestState.Failed(needsVoiceMessage)
            return
        }
        testState = TtsTestState.Running
        scope.launch {
            try {
                val file = TtsClient().synthesize(
                    context = context,
                    config = TtsServerConfig(baseUrl = url, apiKey = settings.ttsApiKey.value),
                    request = TtsRequest(
                        text = sampleText,
                        model = modelName.ifBlank {
                            if (dashScopeTts) TtsProviders.DASHSCOPE_DEFAULT_MODEL else DEFAULT_TTS_MODEL_NAME
                        },
                        voiceName = voiceName.takeIf { it.isNotBlank() },
                        refAudioUrl = refAudioUrl.takeIf { it.isNotBlank() },
                        language = language,
                        speed = speed,
                        emotion = if (emotionAuto) "auto" else null,
                        emotionAlpha = emotionAlpha,
                        useRandom = emotionRandom,
                    ),
                )
                testState = TtsTestState.Ok(file)
            } catch (error: TtsError) {
                testState = TtsTestState.Failed(error.message ?: "HTTP ${error.code}")
            } catch (error: Exception) {
                testState = TtsTestState.Failed(error.message ?: "Network error")
            }
        }
    }

    // Play back the freshly synthesized sample; releasing happens when a new sample arrives
    // or the page leaves composition.
    val audioFile = (testState as? TtsTestState.Ok)?.file
    DisposableEffect(audioFile) {
        val player = audioFile?.let { file ->
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                prepare()
                play()
            }
        }
        onDispose { player?.release() }
    }

    CollapsingSettingsScaffold(
        title = stringResource(R.string.settings_tts),
        onBack = onBack,
        floatingActionButton = { if (showDocFab) DocumentationFab("tts.md") },
    ) {
            SettingsGroupColumn {
                SettingsGroup(title = stringResource(R.string.settings_tts), items = listOf({
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.tts_enable)) },
                        supportingContent = { Text(stringResource(R.string.tts_enable_desc)) },
                        leadingContent = { Icon(Icons.Default.Podcasts, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = {
                            Switch(checked = enabled, onCheckedChange = { settings.setTtsEnabled(it) })
                        },
                        modifier = Modifier.clickable { settings.setTtsEnabled(!enabled) }
                    )
                }))

                if (enabled) {
                    SettingsGroup(title = stringResource(R.string.tts_server), items = listOf(
                        {
                            TtsSettingField(
                                title = stringResource(R.string.tts_base_url),
                                initial = baseUrl,
                                placeholder = stringResource(R.string.tts_base_url_hint),
                                icon = Icons.Default.Cloud,
                                onCommit = { settings.setTtsBaseUrl(it) },
                            )
                        },
                        {
                            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                                    Icon(Icons.Default.Key, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp))
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            stringResource(R.string.tts_api_key),
                                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        Box(modifier = Modifier.noOpBringIntoView().padding(top = 8.dp)) {
                                            OutlinedTextField(
                                                value = apiKeyText,
                                                onValueChange = {
                                                    apiKeyText = it
                                                    settings.setTtsApiKey(it)
                                                },
                                                placeholder = { Text(stringResource(R.string.tts_api_key_hint)) },
                                                singleLine = true,
                                                visualTransformation = PasswordVisualTransformation(),
                                                shape = RoundedCornerShape(16.dp),
                                                modifier = Modifier.fillMaxWidth(),
                                                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                                            )
                                        }
                                    }
                                }
                            }
                        },
                        {
                            if (dashScopeTts) {
                                SettingsItem(
                                    headlineContent = { Text("DashScope Qwen-TTS") },
                                    supportingContent = { Text(stringResource(R.string.tts_dashscope_hint)) },
                                    leadingContent = { Icon(Icons.Default.Cloud, null, tint = MaterialTheme.colorScheme.primary) },
                                )
                            } else {
                                SettingsItem(
                                    headlineContent = { Text(stringResource(R.string.tts_detect)) },
                                    supportingContent = { Text(detectStatus ?: "") },
                                    leadingContent = { Icon(Icons.Default.Refresh, null, tint = MaterialTheme.colorScheme.primary) },
                                    modifier = Modifier.clickable { runDetection() }
                                )
                            }
                        },
                        {
                            if (dashScopeTts) {
                                TtsSettingField(
                                    title = stringResource(R.string.tts_model_name),
                                    initial = modelName,
                                    placeholder = TtsProviders.DASHSCOPE_DEFAULT_MODEL,
                                    icon = Icons.Default.Chat,
                                    onCommit = { settings.setTtsModelName(it) },
                                )
                            } else {
                                TtsSelectField(
                                    title = stringResource(R.string.tts_model_name),
                                    initial = modelName,
                                    options = detectedModels.let { list ->
                                        if (modelName.isNotBlank() && modelName !in list) listOf(modelName) + list else list
                                    },
                                    placeholder = stringResource(R.string.tts_model_name_hint),
                                    icon = Icons.Default.Chat,
                                    onCommit = { settings.setTtsModelName(it) },
                                )
                            }
                        },
                    ))

                    SettingsGroup(title = stringResource(R.string.tts_voice), items = listOf(
                        {
                            if (dashScopeTts) {
                                TtsSettingField(
                                    title = stringResource(R.string.tts_voice_name),
                                    initial = voiceName,
                                    placeholder = TtsProviders.DASHSCOPE_VOICE_EXAMPLE,
                                    icon = Icons.Default.Mic,
                                    onCommit = { settings.setTtsVoiceName(it) },
                                )
                            } else {
                                TtsSelectField(
                                    title = stringResource(R.string.tts_voice_name),
                                    initial = voiceName,
                                    options = detectedVoices.map { it.name }.let { list ->
                                        if (voiceName.isNotBlank() && voiceName !in list) listOf(voiceName) + list else list
                                    },
                                    placeholder = "demo_voice",
                                    description = stringResource(R.string.tts_voice_name_desc),
                                    icon = Icons.Default.Mic,
                                    onCommit = { settings.setTtsVoiceName(it) },
                                )
                            }
                        },
                        {
                            TtsVoiceUploadItem(
                                baseUrl = baseUrl,
                                apiKey = apiKey,
                                note = if (dashScopeTts) {
                                    stringResource(R.string.tts_voice_upload_clone_note)
                                } else {
                                    null
                                },
                                onUploaded = { name ->
                                    settings.setTtsVoiceName(name)
                                    if (dashScopeTts) {
                                        settings.setTtsModelName(TtsProviders.DASHSCOPE_CLONE_MODEL)
                                    }
                                    runDetection()
                                },
                            )
                        },
                        {
                            if (!dashScopeTts) {
                                TtsSettingField(
                                    title = stringResource(R.string.tts_ref_audio),
                                    initial = refAudioUrl,
                                    placeholder = "http://192.168.1.50/voice_01.wav",
                                    description = stringResource(R.string.tts_ref_audio_desc),
                                    icon = Icons.Default.Link,
                                    onCommit = { settings.setTtsRefAudioUrl(it) },
                                )
                            }
                        },
                    ))

                    SettingsGroup(title = stringResource(R.string.tts_synthesis), items = listOf(
                        {
                            SettingsItem(
                                headlineContent = { Text(stringResource(R.string.tts_language)) },
                                supportingContent = { Text(language, style = MaterialTheme.typography.bodySmall) },
                                leadingContent = { Icon(Icons.Default.Language, null, tint = MaterialTheme.colorScheme.primary) },
                                modifier = Modifier.clickable { showLanguageDialog = true }
                            )
                        },
                        {
                            // DashScope Qwen-TTS has no speed control.
                            if (!dashScopeTts) {
                                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)) {
                                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                                        Icon(Icons.Default.Speed, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp))
                                        Spacer(modifier = Modifier.width(16.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    stringResource(R.string.tts_speed),
                                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    modifier = Modifier.weight(1f),
                                                )
                                                Text(
                                                    String.format(Locale.US, "%.1f×", speedGate.displayed),
                                                    style = MaterialTheme.typography.labelLarge,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(end = 8.dp),
                                                )
                                            }
                                            Slider(
                                                value = speedGate.displayed,
                                                onValueChange = speedGate::updateFromGesture,
                                                onValueChangeFinished = {
                                                    val committed = (speedGate.displayed * 10).roundToInt() / 10f
                                                    if (committed == speed) {
                                                        speedGate.settleWithoutWrite(speed, committed)
                                                    } else {
                                                        speedGate.expectPersisted(committed, committed)
                                                        settings.setTtsSpeed(committed)
                                                    }
                                                },
                                                valueRange = 0.5f..2.0f,
                                                steps = 14,
                                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        },
                    ))

                    if (!dashScopeTts) {
                        SettingsGroup(title = stringResource(R.string.tts_emotion), items = listOf(
                            {
                                SettingsItem(
                                    headlineContent = { Text(stringResource(R.string.tts_emotion_auto)) },
                                    supportingContent = { Text(stringResource(R.string.tts_emotion_auto_desc)) },
                                    leadingContent = { Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary) },
                                    trailingContent = {
                                        Switch(checked = emotionAuto, onCheckedChange = { settings.setTtsEmotionAuto(it) })
                                    },
                                    modifier = Modifier.clickable { settings.setTtsEmotionAuto(!emotionAuto) },
                                )
                            },
                            {
                                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)) {
                                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                                        Icon(Icons.Default.Tune, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp))
                                        Spacer(modifier = Modifier.width(16.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    stringResource(R.string.tts_emotion_alpha),
                                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    modifier = Modifier.weight(1f),
                                                )
                                                Text(
                                                    String.format(Locale.US, "%d%%", (emotionAlphaGate.displayed * 100).roundToInt()),
                                                    style = MaterialTheme.typography.labelLarge,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(end = 8.dp),
                                                )
                                            }
                                            Slider(
                                                value = emotionAlphaGate.displayed,
                                                onValueChange = emotionAlphaGate::updateFromGesture,
                                                onValueChangeFinished = {
                                                    val committed = (emotionAlphaGate.displayed * 10).roundToInt() / 10f
                                                    if (committed == emotionAlpha) {
                                                        emotionAlphaGate.settleWithoutWrite(emotionAlpha, committed)
                                                    } else {
                                                        emotionAlphaGate.expectPersisted(committed, committed)
                                                        settings.setTtsEmotionAlpha(committed)
                                                    }
                                                },
                                                valueRange = 0.1f..1.0f,
                                                steps = 8,
                                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                            )
                                            Text(
                                                stringResource(R.string.tts_emotion_alpha_desc),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                                modifier = Modifier.padding(top = 6.dp),
                                            )
                                        }
                                    }
                                }
                            },
                            {
                                SettingsItem(
                                    headlineContent = { Text(stringResource(R.string.tts_emotion_random)) },
                                    supportingContent = { Text(stringResource(R.string.tts_emotion_random_desc)) },
                                    leadingContent = { Icon(Icons.Default.Casino, null, tint = MaterialTheme.colorScheme.primary) },
                                    trailingContent = {
                                        Switch(checked = emotionRandom, onCheckedChange = { settings.setTtsEmotionRandom(it) })
                                    },
                                    modifier = Modifier.clickable { settings.setTtsEmotionRandom(!emotionRandom) },
                                )
                            },
                        ))
                    }

                    SettingsGroup(title = stringResource(R.string.tts_test), items = listOf({
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedButton(
                                    onClick = { runTestSynthesis() },
                                    enabled = testState !is TtsTestState.Running,
                                ) {
                                    if (testState is TtsTestState.Running) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                    } else {
                                        Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                    }
                                    Text(
                                        stringResource(
                                            if (testState is TtsTestState.Running) R.string.tts_testing else R.string.tts_run_test
                                        ),
                                    )
                                }
                            }
                            when (val result = testState) {
                                is TtsTestState.Failed -> Text(
                                    stringResource(R.string.tts_test_error, result.message),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                                is TtsTestState.Ok -> Text(
                                    stringResource(R.string.tts_test_ok),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                                else -> Unit
                            }
                        }
                    }))

                    SettingsGroup(title = stringResource(R.string.advanced_title), items = listOf({
                        PromptSettingItem(
                            title = stringResource(R.string.tts_speak_prompt),
                            description = stringResource(R.string.tts_speak_prompt_desc),
                            prompt = speakPrompt,
                            onClick = { showSpeakPromptDialog = true },
                        )
                    }))
                }
            }

            if (showDocFab) { Spacer(modifier = Modifier.height(80.dp)) }
    }

    if (showSpeakPromptDialog) {
        PromptEditDialog(
            title = stringResource(R.string.tts_speak_prompt),
            initialPrompt = speakPrompt,
            onDismiss = { showSpeakPromptDialog = false },
            onSave = { settings.setTtsSpeakPrompt(it) },
        )
    }

    if (showLanguageDialog) {
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { showLanguageDialog = false },
            title = { Text(stringResource(R.string.tts_language), fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    listOf("zh", "zhen", "en", "ja", "es", "ar").forEach { code ->
                        SettingsItem(
                            headlineContent = {
                                Text(
                                    stringResource(
                                        when (code) {
                                            "zh" -> R.string.tts_lang_zh
                                            "zhen" -> R.string.tts_lang_zhen
                                            "en" -> R.string.tts_lang_en
                                            "ja" -> R.string.tts_lang_ja
                                            "es" -> R.string.tts_lang_es
                                            else -> R.string.tts_lang_ar
                                        }
                                    ),
                                    fontWeight = if (language == code) FontWeight.Bold else FontWeight.Normal,
                                )
                            },
                            leadingContent = {
                                RadioButton(selected = language == code, onClick = {
                                    settings.setTtsLanguage(code); showLanguageDialog = false
                                })
                            },
                            modifier = Modifier.clickable {
                                settings.setTtsLanguage(code); showLanguageDialog = false
                            }
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showLanguageDialog = false }) { Text(stringResource(R.string.provider_cancel)) } }
        )
    }
}
