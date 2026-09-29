package com.newoether.agora.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.newoether.agora.util.SecretCrypto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Self-hosted speech preferences (IndexTTS via vLLM-Omni, DashScope Qwen-TTS, and any
 * OpenAI-compatible `/v1/audio/speech` server).
 *
 * Own category store so [SettingsManager] stays within the source-size budget; mirrors the
 * one-store-per-category shape of the model and backup preference stores.
 */
internal class SettingsTtsPreferenceStore(
    private val dataStore: DataStore<Preferences>,
) {
    val enabled: Flow<Boolean> = dataStore.data.map { it[TTS_ENABLED] ?: false }
    val baseUrl: Flow<String> = dataStore.data.map { it[TTS_BASE_URL] ?: "" }
    val apiKey: Flow<String> = dataStore.data.map { pref -> SecretCrypto.decrypt(pref[TTS_API_KEY] ?: "") }
    val modelName: Flow<String> = dataStore.data.map { it[TTS_MODEL_NAME] ?: "" }
    val voiceName: Flow<String> = dataStore.data.map { it[TTS_VOICE_NAME] ?: "" }
    val refAudioUrl: Flow<String> = dataStore.data.map { it[TTS_REF_AUDIO_URL] ?: "" }
    val language: Flow<String> = dataStore.data.map { pref -> normalizeTtsLanguage(pref[TTS_LANGUAGE]) }
    val speed: Flow<Float> = dataStore.data.map { pref ->
        normalizeTtsSpeed(pref[TTS_SPEED]?.toFloatOrNull() ?: DEFAULT_TTS_SPEED)
    }

    /** Tool description shown to the model; a blank stored value falls back to the built-in one. */
    val speakPrompt: Flow<String> = dataStore.data.map { pref ->
        pref[TTS_SPEAK_PROMPT]?.takeIf { it.isNotBlank() } ?: DEFAULT_TTS_SPEAK_PROMPT
    }

    /** Infer the emotion from the spoken text (`use_emo_text`). */
    val emotionAuto: Flow<Boolean> = dataStore.data.map { it[TTS_EMOTION_AUTO] ?: DEFAULT_TTS_EMOTION_AUTO }

    /** Emotion vector strength (`emo_alpha`). */
    val emotionAlpha: Flow<Float> = dataStore.data.map { pref ->
        normalizeTtsEmotionAlpha(pref[TTS_EMOTION_ALPHA]?.toFloatOrNull() ?: DEFAULT_TTS_EMOTION_ALPHA)
    }

    /** Draw a random emotion per synthesis (`use_random`). */
    val emotionRandom: Flow<Boolean> =
        dataStore.data.map { it[TTS_EMOTION_RANDOM] ?: DEFAULT_TTS_EMOTION_RANDOM }

    suspend fun saveEnabled(enabled: Boolean) { dataStore.edit { it[TTS_ENABLED] = enabled } }
    suspend fun saveBaseUrl(url: String) { dataStore.edit { it[TTS_BASE_URL] = url.trim() } }
    suspend fun saveApiKey(key: String) {
        dataStore.edit { prefs ->
            if (key.isBlank()) prefs.remove(TTS_API_KEY) else prefs[TTS_API_KEY] = SecretCrypto.encrypt(key.trim())
        }
    }
    suspend fun saveModelName(name: String) { dataStore.edit { it[TTS_MODEL_NAME] = name.trim() } }
    suspend fun saveVoiceName(name: String) { dataStore.edit { it[TTS_VOICE_NAME] = name.trim() } }
    suspend fun saveRefAudioUrl(url: String) { dataStore.edit { it[TTS_REF_AUDIO_URL] = url.trim() } }
    suspend fun saveLanguage(language: String) {
        dataStore.edit { it[TTS_LANGUAGE] = normalizeTtsLanguage(language) }
    }
    suspend fun saveSpeed(speed: Float) {
        dataStore.edit { it[TTS_SPEED] = normalizeTtsSpeed(speed).toString() }
    }

    /** A blank prompt removes the override so the built-in default applies again. */
    suspend fun saveSpeakPrompt(prompt: String) {
        dataStore.edit { prefs ->
            val trimmed = prompt.trim()
            if (trimmed.isBlank()) prefs.remove(TTS_SPEAK_PROMPT) else prefs[TTS_SPEAK_PROMPT] = trimmed
        }
    }

    suspend fun saveEmotionAuto(enabled: Boolean) { dataStore.edit { it[TTS_EMOTION_AUTO] = enabled } }

    suspend fun saveEmotionAlpha(alpha: Float) {
        dataStore.edit { it[TTS_EMOTION_ALPHA] = normalizeTtsEmotionAlpha(alpha).toString() }
    }

    suspend fun saveEmotionRandom(enabled: Boolean) { dataStore.edit { it[TTS_EMOTION_RANDOM] = enabled } }
}
