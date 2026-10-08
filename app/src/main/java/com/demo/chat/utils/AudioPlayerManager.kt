package com.demo.chat.utils

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AudioPlaybackState(
    val url: String? = null,
    val isPlaying: Boolean = false,
    val currentPositionMs: Int = 0,
    val durationMs: Int = 0,
    val isCompleted: Boolean = false,
    val error: String? = null
)

class AudioPlayerManager {

    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var progressRunnable: Runnable? = null

    private val _playbackState = MutableStateFlow(AudioPlaybackState())
    val playbackState: StateFlow<AudioPlaybackState> = _playbackState.asStateFlow()

    fun play(url: String) {
        if (url.isBlank()) return

        // If the same URL is already playing, pause it
        if (_playbackState.value.url == url && _playbackState.value.isPlaying) {
            pause()
            return
        }

        // If the same URL was paused, resume it
        if (_playbackState.value.url == url && mediaPlayer != null && !_playbackState.value.isPlaying) {
            resume()
            return
        }

        // Otherwise, stop previous audio and play new audio
        stopInternal()

        try {
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(url)
            }
            mediaPlayer = player

            _playbackState.value = AudioPlaybackState(url = url, isPlaying = false, currentPositionMs = 0, durationMs = 0)

            player.setOnPreparedListener { mp ->
                try {
                    val duration = mp.duration
                    mp.start()
                    _playbackState.value = AudioPlaybackState(
                        url = url,
                        isPlaying = true,
                        currentPositionMs = 0,
                        durationMs = duration
                    )
                    startProgressTracker()
                } catch (e: Exception) {
                    ChatLogger.e(TAG, "Error starting playback after prepare", e)
                    handleError(url, e.message ?: "Failed to start audio")
                }
            }

            player.setOnCompletionListener {
                stopProgressTracker()
                val duration = try { it.duration } catch (_: Exception) { 0 }
                _playbackState.value = AudioPlaybackState(
                    url = url,
                    isPlaying = false,
                    currentPositionMs = 0,
                    durationMs = duration,
                    isCompleted = true
                )
            }

            player.setOnErrorListener { _, what, extra ->
                stopProgressTracker()
                handleError(url, "MediaPlayer error (what=$what, extra=$extra)")
                true
            }

            player.prepareAsync()
        } catch (e: Exception) {
            ChatLogger.e(TAG, "Error initializing MediaPlayer for url=$url", e)
            handleError(url, e.message ?: "Unable to play audio")
        }
    }

    fun pause() {
        try {
            mediaPlayer?.let { player ->
                if (player.isPlaying) {
                    player.pause()
                }
                stopProgressTracker()
                val current = _playbackState.value
                _playbackState.value = current.copy(
                    isPlaying = false,
                    currentPositionMs = player.currentPosition
                )
            }
        } catch (e: Exception) {
            ChatLogger.e(TAG, "Error pausing audio", e)
        }
    }

    fun resume() {
        try {
            mediaPlayer?.let { player ->
                player.start()
                val current = _playbackState.value
                _playbackState.value = current.copy(isPlaying = true)
                startProgressTracker()
            }
        } catch (e: Exception) {
            ChatLogger.e(TAG, "Error resuming audio", e)
        }
    }

    fun stop() {
        stopInternal()
        _playbackState.value = AudioPlaybackState()
    }

    private fun stopInternal() {
        stopProgressTracker()
        try {
            mediaPlayer?.let { player ->
                if (player.isPlaying) {
                    player.stop()
                }
                player.reset()
                player.release()
            }
        } catch (e: Exception) {
            ChatLogger.e(TAG, "Error releasing MediaPlayer", e)
        } finally {
            mediaPlayer = null
        }
    }

    fun release() {
        stopInternal()
        _playbackState.value = AudioPlaybackState()
    }

    private fun handleError(url: String, errorMsg: String) {
        stopInternal()
        _playbackState.value = AudioPlaybackState(url = url, isPlaying = false, error = errorMsg)
    }

    private fun startProgressTracker() {
        stopProgressTracker()
        progressRunnable = object : Runnable {
            override fun run() {
                val player = mediaPlayer
                if (player != null && try { player.isPlaying } catch (_: Exception) { false }) {
                    val pos = try { player.currentPosition } catch (_: Exception) { 0 }
                    val dur = try { player.duration } catch (_: Exception) { 0 }
                    val current = _playbackState.value
                    _playbackState.value = current.copy(
                        currentPositionMs = pos,
                        durationMs = dur
                    )
                    handler.postDelayed(this, 250)
                }
            }
        }
        progressRunnable?.let { handler.post(it) }
    }

    private fun stopProgressTracker() {
        progressRunnable?.let { handler.removeCallbacks(it) }
        progressRunnable = null
    }

    companion object {
        private const val TAG = "AudioPlayerManager"
    }
}
