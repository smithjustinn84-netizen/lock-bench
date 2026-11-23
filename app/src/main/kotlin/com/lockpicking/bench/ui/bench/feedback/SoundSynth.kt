package com.lockpicking.bench.ui.bench.feedback

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * The lock's sounds, built from arithmetic: there are no audio files in the app.
 *
 * Small metal parts knocking together make a broadband snap followed by a few ringing
 * partials that die away in milliseconds. Each sound here is that recipe with different
 * numbers: high, short partials for a pin clicking over the shear line; low, longer ones for
 * the plug dropping into a false set or the shackle letting go.
 *
 * Pure Kotlin, so the recipes can be checked in a unit test.
 */
object SoundSynth {
  const val SAMPLE_RATE = 44_100

  /** 16-bit mono PCM for [sound] at [sampleRate]. The same input always gives the same samples. */
  fun render(sound: LockSound, sampleRate: Int = SAMPLE_RATE): ShortArray {
    val mix = Mix(sampleRate, seed = sound.ordinal + 1)
    when (sound) {
      LockSound.SetClick -> {
        mix.snap(at = 0f, level = 0.8f, decay = 0.0015f)
        mix.ring(at = 0f, level = 0.6f, decay = 0.009f, 2400f, 3900f, 6100f)
      }
      LockSound.FalseSetClunk -> {
        mix.snap(at = 0f, level = 0.6f, decay = 0.003f)
        mix.ring(at = 0f, level = 0.9f, decay = 0.026f, 520f, 910f, 1500f)
        // The plug fetches up on the waist a moment after it lets go.
        mix.ring(at = 0.030f, level = 0.4f, decay = 0.016f, 700f, 1300f)
      }
      LockSound.CounterTick -> {
        mix.snap(at = 0f, level = 0.35f, decay = 0.001f)
        mix.ring(at = 0f, level = 0.5f, decay = 0.004f, 3200f, 5100f)
      }
      LockSound.Blocked -> {
        mix.ring(at = 0f, level = 0.9f, decay = 0.018f, 180f, 320f)
        mix.snap(at = 0f, level = 0.2f, decay = 0.004f)
      }
      LockSound.Grit -> mix.scrape(at = 0f, level = 0.7f, length = 0.020f)
      LockSound.Rattle -> {
        // Four drivers and their springs, not quite together.
        var at = 0f
        for (k in 0 until 5) {
          val level = 0.8f - 0.12f * k
          mix.snap(at, level * 0.5f, decay = 0.0015f)
          mix.ring(at, level * 0.6f, decay = 0.007f, 1800f + 260f * k, 3000f + 410f * k)
          at += 0.022f + 0.011f * k
        }
      }
      LockSound.Jam -> {
        mix.snap(at = 0f, level = 0.7f, decay = 0.002f)
        mix.ring(at = 0f, level = 0.8f, decay = 0.020f, 260f, 1150f, 1900f)
      }
      LockSound.SlipBack -> {
        mix.snap(at = 0f, level = 0.3f, decay = 0.002f)
        mix.ring(at = 0f, level = 0.5f, decay = 0.010f, 1500f, 2300f)
      }
      LockSound.Open -> {
        // The plug goes round against its stop, then the shackle springs.
        mix.snap(at = 0f, level = 0.7f, decay = 0.003f)
        mix.ring(at = 0f, level = 0.9f, decay = 0.035f, 300f, 640f, 1250f)
        mix.snap(at = 0.120f, level = 0.6f, decay = 0.002f)
        mix.ring(at = 0.120f, level = 0.8f, decay = 0.060f, 900f, 1700f, 2600f)
      }
      LockSound.Relock -> {
        mix.snap(at = 0f, level = 0.6f, decay = 0.003f)
        mix.ring(at = 0f, level = 0.8f, decay = 0.030f, 450f, 1100f, 2300f)
      }
    }
    return mix.toPcm()
  }

  /** A growing float buffer that sounds are layered into. Times are in seconds. */
  private class Mix(private val sampleRate: Int, seed: Int) {
    private var samples = FloatArray(0)
    private val random = Random(seed)

    /** A burst of noise dying away with time constant [decay]: the impact itself. */
    fun snap(at: Float, level: Float, decay: Float) {
      val start = index(at)
      val count = index(decay * TAIL)
      reserve(start + count)
      var last = 0f
      for (i in 0 until count) {
        val white = random.nextFloat() * 2f - 1f
        // The difference of neighbours leans the noise bright, as a hard tap is.
        val bright = white - 0.6f * last
        last = white
        samples[start + i] += level * bright * envelope(i, decay)
      }
    }

    /** Sine partials at [frequencies] dying away with time constant [decay]: the part ringing. */
    fun ring(at: Float, level: Float, decay: Float, vararg frequencies: Float) {
      val start = index(at)
      val count = index(decay * TAIL)
      reserve(start + count)
      for ((n, frequency) in frequencies.withIndex()) {
        // Higher partials are quieter and die sooner.
        val partial = level / (n + 1)
        val partialDecay = decay / (1f + 0.5f * n)
        val step = 2.0 * PI * frequency / sampleRate
        for (i in 0 until count) {
          samples[start + i] += partial * sin(step * i).toFloat() * envelope(i, partialDecay)
        }
      }
    }

    /** A short rasp of band-limited noise, swelling and fading: metal dragging on metal. */
    fun scrape(at: Float, level: Float, length: Float) {
      val start = index(at)
      val count = index(length)
      reserve(start + count)
      var low = 0f
      var last = 0f
      for (i in 0 until count) {
        val white = random.nextFloat() * 2f - 1f
        low += 0.35f * (white - low)
        val band = low - last
        last = low
        val swell = sin(PI * i / count).toFloat()
        samples[start + i] += level * 2.5f * band * swell
      }
    }

    fun toPcm(): ShortArray {
      val peak = max(samples.maxOfOrNull { abs(it) } ?: 0f, 1e-6f)
      val gain = PEAK / peak
      // A few samples of fade at the end, so a sound cut by the next one never clicks.
      val fade = minOf(FADE_SAMPLES, samples.size)
      return ShortArray(samples.size) { i ->
        val tail = if (i >= samples.size - fade) (samples.size - 1 - i) / fade.toFloat() else 1f
        (samples[i] * gain * tail * Short.MAX_VALUE).roundToInt().coerceIn(-Short.MAX_VALUE, Short.MAX_VALUE.toInt()).toShort()
      }
    }

    private fun envelope(i: Int, decay: Float): Float = exp(-i / (decay * sampleRate))

    private fun index(seconds: Float): Int = (seconds * sampleRate).roundToInt()

    private fun reserve(size: Int) {
      if (size > samples.size) samples = samples.copyOf(size)
    }
  }

  /** Each layer runs this many time constants, by which point it is inaudible. */
  private const val TAIL = 6f
  private const val PEAK = 0.9f
  private const val FADE_SAMPLES = 64
}
