package com.lockpicking.bench.ui.bench.feedback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Plays the lock's sounds. Each one is synthesised once, off the main thread, into its own
 * static [AudioTrack], so playing it later is a rewind and a start with nothing to decode.
 *
 * The sounds go out as game audio, on the media volume. Call [release] when the screen goes.
 */
class LockSounds(scope: CoroutineScope) {
  private val tracks = arrayOfNulls<AudioTrack>(LockSound.entries.size)
  private var released = false

  init {
    scope.launch {
      for (sound in LockSound.entries) {
        val pcm = withContext(Dispatchers.Default) { SoundSynth.render(sound) }
        if (released) return@launch
        tracks[sound.ordinal] = runCatching { track(pcm) }
          .onFailure { Log.w(TAG, "no audio track for $sound", it) }
          .getOrNull()
      }
    }
  }

  /** Plays [sound] at [volume], 0..1, cutting off its own previous play if that is still going. */
  fun play(sound: LockSound, volume: Float) {
    val track = tracks[sound.ordinal] ?: return
    runCatching {
      if (track.playState != AudioTrack.PLAYSTATE_STOPPED) track.stop()
      track.reloadStaticData()
      track.setVolume(volume.coerceIn(0f, 1f))
      track.play()
    }.onFailure { Log.w(TAG, "could not play $sound", it) }
  }

  fun release() {
    released = true
    for (i in tracks.indices) {
      tracks[i]?.release()
      tracks[i] = null
    }
  }

  private fun track(pcm: ShortArray): AudioTrack {
    val track = AudioTrack.Builder()
      .setAudioAttributes(
        AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_GAME)
          .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
          .build(),
      )
      .setAudioFormat(
        AudioFormat.Builder()
          .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
          .setSampleRate(SoundSynth.SAMPLE_RATE)
          .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
          .build(),
      )
      .setTransferMode(AudioTrack.MODE_STATIC)
      .setBufferSizeInBytes(pcm.size * Short.SIZE_BYTES)
      .build()
    track.write(pcm, 0, pcm.size)
    return track
  }

  private companion object {
    const val TAG = "LockSounds"
  }
}
