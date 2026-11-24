package com.lockpicking.bench.ui.bench.feedback

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.annotation.RequiresApi
import kotlin.math.roundToInt

/**
 * The lock in the hand: every [Haptic] played at the strength its cue asks for.
 *
 * Where the phone has the haptic primitives (API 30 and a capable actuator) each shape is
 * composed from them, which is what makes a set feel like a click and a false set like a
 * drop. Elsewhere it falls back to timed pulses, scaled by amplitude where the motor allows.
 */
class LockHaptics(context: Context) {
  private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    context.getSystemService(VibratorManager::class.java)?.defaultVibrator
  } else {
    @Suppress("DEPRECATION")
    context.getSystemService(Vibrator::class.java)
  }?.takeIf { it.hasVibrator() }

  private val primitives = vibrator != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
    vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK, VibrationEffect.Composition.PRIMITIVE_TICK)
  private val deepPrimitives = primitives && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
    vibrator!!.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_THUD, VibrationEffect.Composition.PRIMITIVE_LOW_TICK)
  private val amplitude = vibrator?.hasAmplitudeControl() == true

  private var lastTexture = 0L

  fun play(haptic: Haptic, strength: Float) {
    val vibrator = vibrator ?: return
    if (haptic.isTexture) {
      // A texture, not a string of events: closer together than this it turns into a buzz.
      val now = SystemClock.uptimeMillis()
      if (now - lastTexture < TEXTURE_GAP_MILLIS) return
      lastTexture = now
    }
    val level = strength.coerceIn(MIN_LEVEL, 1f)
    val effect = if (primitives) composed(haptic, level) else pulsed(haptic, level)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
    } else {
      @Suppress("DEPRECATION")
      vibrator.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build())
    }
  }

  @RequiresApi(Build.VERSION_CODES.R)
  private fun composed(haptic: Haptic, level: Float): VibrationEffect {
    val click = VibrationEffect.Composition.PRIMITIVE_CLICK
    val tick = VibrationEffect.Composition.PRIMITIVE_TICK
    val thud = if (deepPrimitives) VibrationEffect.Composition.PRIMITIVE_THUD else click
    val low = if (deepPrimitives) VibrationEffect.Composition.PRIMITIVE_LOW_TICK else tick
    val composition = VibrationEffect.startComposition()
    when (haptic) {
      Haptic.Click -> composition.addPrimitive(click, level)

      Haptic.Thud -> composition.addPrimitive(thud, level)

      Haptic.Tick -> composition.addPrimitive(tick, level)

      Haptic.Grain -> composition.addPrimitive(low, level)

      Haptic.Refuse -> composition.addPrimitive(thud, level).addPrimitive(thud, level * REFUSE_ECHO_SCALE, REFUSE_ECHO_DELAY_MILLIS)

      Haptic.Rattle -> composition.addRattle(tick, level)

      Haptic.Release -> composition.addPrimitive(click, level * RELEASE_CLICK_SCALE).addPrimitive(thud, level, RELEASE_THUD_DELAY_MILLIS)

      Haptic.Bump -> composition.addPrimitive(low, level)

      // The tick sharpens the front of the click: metal on metal, with no give in it.
      Haptic.Knock -> composition.addPrimitive(tick, level).addPrimitive(click, level, 0)

      Haptic.Scrape -> composition.addPrimitive(tick, level)

      Haptic.Spring -> composition.addPrimitive(low, level)
    }
    return composition.compose()
  }

  /** A tick and then [RATTLE_TAIL]: each fainter and further apart. */
  @RequiresApi(Build.VERSION_CODES.R)
  private fun VibrationEffect.Composition.addRattle(tick: Int, level: Float) {
    addPrimitive(tick, level)
    for ((scale, delay) in RATTLE_TAIL) addPrimitive(tick, level * scale, delay)
  }

  private fun pulsed(haptic: Haptic, level: Float): VibrationEffect {
    val strength = if (amplitude) (level * MAX_AMPLITUDE).roundToInt().coerceIn(1, MAX_AMPLITUDE) else VibrationEffect.DEFAULT_AMPLITUDE
    fun pattern(millis: LongArray): VibrationEffect {
      // Timings alternate off, on, off, on…; an amplitude of 0 is the off stretch.
      val amplitudes = IntArray(millis.size) { if (it % 2 == 1) strength else 0 }
      return if (amplitude) VibrationEffect.createWaveform(millis, amplitudes, -1) else VibrationEffect.createWaveform(millis, -1)
    }
    return when (haptic) {
      Haptic.Click -> VibrationEffect.createOneShot(CLICK_MILLIS, strength)
      Haptic.Thud -> VibrationEffect.createOneShot(THUD_MILLIS, strength)
      Haptic.Tick -> VibrationEffect.createOneShot(TICK_MILLIS, strength)
      Haptic.Grain -> VibrationEffect.createOneShot(GRAIN_MILLIS, strength)
      Haptic.Refuse -> pattern(REFUSE_PULSES)
      Haptic.Rattle -> pattern(RATTLE_PULSES)
      Haptic.Release -> pattern(RELEASE_PULSES)
      Haptic.Bump -> VibrationEffect.createOneShot(BUMP_MILLIS, strength)
      Haptic.Knock -> VibrationEffect.createOneShot(KNOCK_MILLIS, strength)
      Haptic.Scrape -> VibrationEffect.createOneShot(SCRAPE_MILLIS, strength)
      Haptic.Spring -> VibrationEffect.createOneShot(SPRING_MILLIS, strength)
    }
  }

  private companion object {
    const val TEXTURE_GAP_MILLIS = 30L

    /** Below this a primitive is too faint to feel at all. */
    const val MIN_LEVEL = 0.05f
    const val MAX_AMPLITUDE = 255

    // Composed shapes: how much softer, and how many milliseconds later, each follow-up lands.
    const val REFUSE_ECHO_SCALE = 0.7f
    const val REFUSE_ECHO_DELAY_MILLIS = 70
    const val RELEASE_CLICK_SCALE = 0.7f
    const val RELEASE_THUD_DELAY_MILLIS = 110

    /** The ticks after the first in a rattle: (scale, delay in ms), each fainter and further apart. */
    val RATTLE_TAIL = listOf(0.8f to 22, 0.6f to 33, 0.45f to 44)

    // Pulsed fallbacks: one-shot lengths in milliseconds.
    const val CLICK_MILLIS = 12L
    const val THUD_MILLIS = 28L
    const val TICK_MILLIS = 7L
    const val GRAIN_MILLIS = 5L
    const val BUMP_MILLIS = 9L
    const val KNOCK_MILLIS = 16L
    const val SCRAPE_MILLIS = 6L
    const val SPRING_MILLIS = 4L

    // Pulsed fallbacks: off, on, off, on… in milliseconds.
    val REFUSE_PULSES = longArrayOf(0, 22, 60, 18)
    val RATTLE_PULSES = longArrayOf(0, 7, 20, 7, 30, 6, 40, 5)
    val RELEASE_PULSES = longArrayOf(0, 12, 100, 30)
  }
}
