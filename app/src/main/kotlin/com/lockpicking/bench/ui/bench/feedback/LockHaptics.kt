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

  private var lastGrain = 0L

  fun play(haptic: Haptic, strength: Float) {
    val vibrator = vibrator ?: return
    if (haptic == Haptic.Grain) {
      // A texture, not a string of events: closer together than this it turns into a buzz.
      val now = SystemClock.uptimeMillis()
      if (now - lastGrain < GRAIN_GAP_MILLIS) return
      lastGrain = now
    }
    val level = strength.coerceIn(0.05f, 1f)
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
      Haptic.Refuse -> composition.addPrimitive(thud, level).addPrimitive(thud, level * 0.7f, 70)
      Haptic.Rattle -> composition
        .addPrimitive(tick, level)
        .addPrimitive(tick, level * 0.8f, 22)
        .addPrimitive(tick, level * 0.6f, 33)
        .addPrimitive(tick, level * 0.45f, 44)
      Haptic.Release -> composition.addPrimitive(click, level * 0.7f).addPrimitive(thud, level, 110)
    }
    return composition.compose()
  }

  private fun pulsed(haptic: Haptic, level: Float): VibrationEffect {
    val strength = if (amplitude) (level * 255).roundToInt().coerceIn(1, 255) else VibrationEffect.DEFAULT_AMPLITUDE
    fun pattern(vararg millis: Long): VibrationEffect {
      // Timings alternate off, on, off, on…; an amplitude of 0 is the off stretch.
      val amplitudes = IntArray(millis.size) { if (it % 2 == 1) strength else 0 }
      return if (amplitude) VibrationEffect.createWaveform(millis, amplitudes, -1) else VibrationEffect.createWaveform(millis, -1)
    }
    return when (haptic) {
      Haptic.Click -> VibrationEffect.createOneShot(12, strength)
      Haptic.Thud -> VibrationEffect.createOneShot(28, strength)
      Haptic.Tick -> VibrationEffect.createOneShot(7, strength)
      Haptic.Grain -> VibrationEffect.createOneShot(5, strength)
      Haptic.Refuse -> pattern(0, 22, 60, 18)
      Haptic.Rattle -> pattern(0, 7, 20, 7, 30, 6, 40, 5)
      Haptic.Release -> pattern(0, 12, 100, 30)
    }
  }

  private companion object {
    const val GRAIN_GAP_MILLIS = 30L
  }
}
