package com.newoether.agora.ui.chat.message

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.R

/**
 * Message action bar toggle for the run's speech clip: replay when idle, stop while playing.
 */
@Composable
internal fun TtsReplayButton(
    clip: TtsAudioClip,
    playing: Boolean,
    enabled: Boolean,
    contentAlpha: Float,
    tint: Color,
    onToggle: (TtsAudioClip) -> Unit,
) {
    IconButton(
        onClick = { onToggle(clip) },
        enabled = enabled,
        modifier = Modifier
            .size(32.dp)
            .graphicsLayer { alpha = contentAlpha },
    ) {
        Icon(
            if (playing) Icons.Default.Stop else Icons.AutoMirrored.Filled.VolumeUp,
            contentDescription = stringResource(
                if (playing) R.string.tts_stop_audio else R.string.tts_replay_audio,
            ),
            modifier = Modifier.size(16.dp),
            tint = tint,
        )
    }
}
