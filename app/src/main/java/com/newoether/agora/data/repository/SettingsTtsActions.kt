package com.newoether.agora.data.repository

import kotlinx.coroutines.launch

/**
 * TTS write actions for [SettingsRepository].
 *
 * Extensions so the repository facade keeps a single flat settings surface while staying within
 * the 800-line source budget; the read-side StateFlows remain members of the repository.
 */
internal fun SettingsRepository.setTtsEnabled(enabled: Boolean) =
    scope.launch { settingsManager.ttsPreferenceStore.saveEnabled(enabled) }

internal fun SettingsRepository.setTtsBaseUrl(url: String) =
    scope.launch { settingsManager.ttsPreferenceStore.saveBaseUrl(url) }

internal fun SettingsRepository.setTtsApiKey(key: String) =
    scope.launch { settingsManager.ttsPreferenceStore.saveApiKey(key) }

internal fun SettingsRepository.setTtsModelName(name: String) =
    scope.launch { settingsManager.ttsPreferenceStore.saveModelName(name) }

internal fun SettingsRepository.setTtsVoiceName(name: String) =
    scope.launch { settingsManager.ttsPreferenceStore.saveVoiceName(name) }

internal fun SettingsRepository.setTtsRefAudioUrl(url: String) =
    scope.launch { settingsManager.ttsPreferenceStore.saveRefAudioUrl(url) }

internal fun SettingsRepository.setTtsLanguage(language: String) =
    scope.launch { settingsManager.ttsPreferenceStore.saveLanguage(language) }

internal fun SettingsRepository.setTtsSpeed(speed: Float) =
    scope.launch { settingsManager.ttsPreferenceStore.saveSpeed(speed) }

internal fun SettingsRepository.setTtsSpeakPrompt(prompt: String) =
    scope.launch { settingsManager.ttsPreferenceStore.saveSpeakPrompt(prompt) }

internal fun SettingsRepository.setTtsEmotionAuto(enabled: Boolean) =
    scope.launch { settingsManager.ttsPreferenceStore.saveEmotionAuto(enabled) }

internal fun SettingsRepository.setTtsEmotionAlpha(alpha: Float) =
    scope.launch { settingsManager.ttsPreferenceStore.saveEmotionAlpha(alpha) }

internal fun SettingsRepository.setTtsEmotionRandom(enabled: Boolean) =
    scope.launch { settingsManager.ttsPreferenceStore.saveEmotionRandom(enabled) }
