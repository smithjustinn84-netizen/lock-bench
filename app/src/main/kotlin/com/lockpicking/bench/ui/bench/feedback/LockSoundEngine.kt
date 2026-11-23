package com.lockpicking.bench.ui.bench.feedback

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import androidx.annotation.RawRes
import com.lockpicking.bench.R
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * The recorded sounds of the lock. Each is one or more takes in `res/raw`, rendered by
 * `scripts/generate_lock_sfx.py`; sounds that repeat quickly have several so that no two
 * plays in a row are the same file.
 */
enum class LockSfx(@RawRes internal vararg val takes: Int) {
  /** The pick's tip touching a key pin. */
  PinTouch(R.raw.pin_touch_1, R.raw.pin_touch_2, R.raw.pin_touch_3),

  /** A pin scraping up its chamber. */
  PinSlide(R.raw.pin_slide_1, R.raw.pin_slide_2, R.raw.pin_slide_3),

  /** A driver clearing the shear line. */
  PinSet(R.raw.pin_set_1, R.raw.pin_set_2, R.raw.pin_set_3),

  /** The plug turning back as a spool's lip clears. */
  SpoolCounterRotate(R.raw.spool_counter_rotate),

  /** A serration catching: a false set. */
  SerratedClick(R.raw.serrated_click_1, R.raw.serrated_click_2),

  /** A key pin pushed past the shear line and jammed. */
  PinOvershoot(R.raw.pin_overshoot),

  /** The tension wrench loading the plug. */
  CoreTensionCreak(R.raw.core_tension_creak),

  /** The plug turning once every pin is set. */
  PlugTurnOpen(R.raw.plug_turn_open),

  /** The shackle springing up. */
  ShacklePop(R.raw.shackle_pop),

  /** The shackle pushed home and the pins dropping. */
  PadlockReset(R.raw.padlock_reset),
}

/**
 * Plays [LockSfx] through a [SoundPool]. The files are decoded once, in the background, when
 * the engine is made; a sound asked for before its file is ready is silently skipped.
 *
 * Every play picks a take other than the last one used and shifts its pitch by a few percent,
 * so a run of quick pin clicks sounds like a mechanism and not like a sample.
 *
 * Call [release] when the screen goes.
 */
class LockSoundEngine(
  context: Context,
  maxStreams: Int = 8,
  private val random: Random = Random.Default,
) {
  private val pool: SoundPool = SoundPool.Builder()
    .setMaxStreams(maxStreams)
    .setAudioAttributes(
      AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build(),
    )
    .build()

  /** Sample ids the pool has finished decoding. Filled from the pool's own callback thread. */
  private val ready: MutableSet<Int> = ConcurrentHashMap.newKeySet()

  /** The pool's sample ids for each sound's takes, indexed by [LockSfx.ordinal]. */
  private val samples: Array<IntArray>

  /** The take each sound played last, so the next play can avoid it. */
  private val lastTake = IntArray(LockSfx.entries.size) { -1 }

  private var released = false

  /** Runs the later strokes of a sound that is more than one file. */
  private val later = Handler(Looper.getMainLooper())

  init {
    pool.setOnLoadCompleteListener { _, sampleId, status -> if (status == 0) ready += sampleId }
    val app = context.applicationContext
    samples = Array(LockSfx.entries.size) { i ->
      val takes = LockSfx.entries[i].takes
      IntArray(takes.size) { pool.load(app, takes[it], 1) }
    }
  }

  /**
   * Plays [sfx] at [volume], 0..1. [jitter] turns the random pitch shift on; pass false for a
   * sound that has to line up with another.
   *
   * @return the stream id, for [stop], or 0 if nothing played.
   */
  fun play(sfx: LockSfx, volume: Float = 1f, jitter: Boolean = true): Int {
    val takes = samples[sfx.ordinal]
    val take = nextTake(sfx.ordinal, takes.size)
    val sampleId = takes[take]
    if (sampleId !in ready) return 0
    val level = volume.coerceIn(0f, 1f)
    val rate = if (jitter) MIN_RATE + random.nextFloat() * (MAX_RATE - MIN_RATE) else 1f
    return pool.play(sampleId, level, level, PRIORITY, NO_LOOP, rate)
  }

  /**
   * Plays the recording, or the short run of recordings, that stands for [sound], at
   * [strength], 0..1. Call on the main thread.
   */
  fun play(sound: LockSound, strength: Float) {
    when (sound) {
      LockSound.SetClick -> play(LockSfx.PinSet, strength)
      LockSound.FalseSetClunk -> play(LockSfx.SerratedClick, strength)
      LockSound.CounterTick -> play(LockSfx.SpoolCounterRotate, strength)
      LockSound.Blocked -> play(LockSfx.CoreTensionCreak, strength)
      LockSound.Grit -> play(LockSfx.PinSlide, strength)
      LockSound.Jam -> play(LockSfx.PinOvershoot, strength)
      LockSound.SlipBack -> play(LockSfx.PinTouch, strength)
      LockSound.Rattle -> {
        // The drivers land one after another, each a little quieter than the last.
        for ((k, at) in RATTLE_MS.withIndex()) {
          after(at) { play(LockSfx.PinTouch, strength * (1f - 0.15f * k)) }
        }
      }
      LockSound.Open -> {
        // The shackle lets go as the plug reaches its stop, near the end of the turn.
        play(LockSfx.PlugTurnOpen, strength, jitter = false)
        after(SHACKLE_AFTER_TURN_MS) { play(LockSfx.ShacklePop, strength, jitter = false) }
      }
      LockSound.Relock -> play(LockSfx.PadlockReset, strength)
    }
  }

  /** Cuts off a stream that [play] started. */
  fun stop(streamId: Int) {
    if (streamId != 0) pool.stop(streamId)
  }

  /** Pauses everything, for when the app leaves the foreground. */
  fun pause() = pool.autoPause()

  fun resume() = pool.autoResume()

  fun release() {
    released = true
    later.removeCallbacksAndMessages(null)
    ready.clear()
    pool.release()
  }

  private fun after(ms: Long, stroke: () -> Unit) {
    if (ms == 0L) stroke() else later.postDelayed({ if (!released) stroke() }, ms)
  }

  /** A random take that is not the one played last, when there is more than one to choose from. */
  private fun nextTake(sound: Int, count: Int): Int {
    if (count == 1) return 0
    val last = lastTake[sound]
    val take = if (last < 0) random.nextInt(count) else (last + 1 + random.nextInt(count - 1)) % count
    lastTake[sound] = take
    return take
  }

  private companion object {
    const val MIN_RATE = 0.97f
    const val MAX_RATE = 1.03f
    const val PRIORITY = 1
    const val NO_LOOP = 0

    /** When each driver lands after the torque comes off. */
    val RATTLE_MS = longArrayOf(0, 24, 55, 97)

    /** The stop at the end of `plug_turn_open` falls about here. */
    const val SHACKLE_AFTER_TURN_MS = 195L
  }
}
