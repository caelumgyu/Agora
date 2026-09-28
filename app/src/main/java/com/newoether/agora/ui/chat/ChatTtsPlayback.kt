package com.newoether.agora.ui.chat

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.newoether.agora.ui.chat.message.TtsAudioClip
import com.newoether.agora.ui.chat.message.ttsAudioClips
import com.newoether.agora.viewmodel.ChatViewModel
import java.io.File
import kotlinx.coroutines.flow.combine

/**
 * The single active speech playback of the open conversation.
 *
 * A model may speak several lines in one turn (different tone or rate per sentence), so playback
 * is a queue: auto-play appends each new line behind whatever still sounds, while the message
 * action bar replays a whole run from the start. One player serves every action bar, and
 * [playingClipId] drives the replay/stop icons. The instance is owned by the chat screen and
 * released with it.
 */
@Stable
internal class ChatTtsPlayback(context: Context) {

    private val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext).build().apply {
        setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .build(),
            /* handleAudioFocus = */ true,
        )
        addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) startNext()
            }
        })
    }

    private val queue = ArrayDeque<TtsAudioClip>()

    /** Clip currently producing sound, if any. */
    var playingClipId: String? by mutableStateOf(null)
        private set

    /** Replays [clips] from the start, or stops when any of them is already sounding. */
    fun toggle(clips: List<TtsAudioClip>) {
        if (clips.isEmpty()) return
        if (playingClipId != null && clips.any { it.callId == playingClipId }) {
            stop()
        } else {
            playAll(clips)
        }
    }

    /** Replaces any current playback with [clips], played in order. */
    fun playAll(clips: List<TtsAudioClip>) {
        queue.clear()
        queue.addAll(clips)
        startNext()
    }

    /** Appends newly arrived clips behind whatever still plays or waits. */
    fun enqueue(clips: List<TtsAudioClip>) {
        val pending = clips.filter { clip ->
            clip.callId != playingClipId && queue.none { it.callId == clip.callId }
        }
        if (pending.isEmpty()) return
        queue.addAll(pending)
        if (playingClipId == null) startNext()
    }

    fun stop() {
        queue.clear()
        player.stop()
        player.clearMediaItems()
        playingClipId = null
    }

    fun release() {
        queue.clear()
        playingClipId = null
        player.release()
    }

    private fun startNext() {
        val next = queue.removeFirstOrNull()
        if (next == null) {
            playingClipId = null
            return
        }
        val file = File(next.path)
        if (!file.isFile) {
            startNext()
            return
        }
        player.stop()
        player.clearMediaItems()
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        player.prepare()
        player.play()
        playingClipId = next.callId
    }
}

@Composable
internal fun rememberChatTtsPlayback(viewModel: ChatViewModel): ChatTtsPlayback {
    val context = LocalContext.current
    val playback = remember(context) { ChatTtsPlayback(context) }
    DisposableEffect(playback) {
        onDispose { playback.release() }
    }
    LaunchedEffect(viewModel, playback) {
        var activeConversationId: String? = null
        var initialized = false
        val seenClipIds = mutableSetOf<String>()
        combine(
            viewModel.currentConversationId,
            viewModel.loadedMessagesConversationId,
            viewModel.allMessages,
        ) { conversationId, loadedConversationId, messages -> Triple(conversationId, loadedConversationId, messages) }
            .collect { (conversationId, loadedConversationId, messages) ->
                if (conversationId != activeConversationId) {
                    // Never let a background conversation keep talking over the newly opened one.
                    playback.stop()
                    activeConversationId = conversationId
                    initialized = false
                    seenClipIds.clear()
                }
                if (conversationId == null || loadedConversationId != conversationId) return@collect
                val clips = ttsAudioClips(messages)
                if (!initialized) {
                    // Everything already loaded when the conversation opens is history; those
                    // clips are replayed from the message action bar, never spoken unprompted.
                    seenClipIds += clips.map(TtsAudioClip::callId)
                    initialized = true
                    return@collect
                }
                val fresh = clips.filter { it.callId !in seenClipIds }
                seenClipIds += clips.map(TtsAudioClip::callId)
                if (fresh.isNotEmpty()) playback.enqueue(fresh)
            }
    }
    return playback
}
