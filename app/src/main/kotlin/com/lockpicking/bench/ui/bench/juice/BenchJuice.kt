package com.lockpicking.bench.ui.bench.juice

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.lockpicking.bench.domain.lock.LockEvent
import com.lockpicking.bench.ui.theme.BenchColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.sin

private const val SHAKE_MILLIS = 420
private const val FLASH_MILLIS = 520

/** Swings of the shake over its whole decay. */
private const val SHAKE_SWINGS = 34f
private val ShakeReach = 7.dp

/** Flash strengths, 0..1, by what happened. */
private const val FLASH_SET = 0.32f
private const val FLASH_FAULT = 0.75f
private const val FLASH_OPEN = 1f

/** Shake strengths, 0..1. */
private const val SHAKE_FAULT = 1f
private const val SHAKE_SEIZE = 0.55f

/**
 * What the HUD may make a show of. Every flag is something the bench already lets you see:
 * juice never gives away what a blind tier or hints-off hides.
 */
data class JuiceRules(
  /** Sets are counted on the HUD, so a set may flash. */
  val showSets: Boolean,
  /** The pins are drawn on the pad, so a set may burst at its column. */
  val showPins: Boolean,
)

/**
 * The HUD's reactions to the lock: a shake and a red flash for a mistake, a green flash and a
 * burst on the pad for a set, gold and confetti when the shackle lets go.
 *
 * Fed from the one place the view model's events are collected, so haptics and sound lose
 * nothing to it. Every value here is read in a draw or layer block, never in composition: a
 * reaction repaints the HUD without recomposing the bench.
 */
@Stable
class BenchJuice(private val scope: CoroutineScope) {
  /** 1 at the hit, decaying to 0. */
  val shake = Animatable(0f)

  /** Strength of the edge flash, 1 at the hit, decaying to 0, in [flashColor]. */
  val flash = Animatable(0f)
  var flashColor: Color = BenchColors.Set
    private set

  /** Bumped on each set that may burst on the pad; [burstPin] is which column. */
  val padBurst = mutableIntStateOf(0)
  var burstPin: Int = -1
    private set

  companion object {
    /** [burstPin] when every column bursts at once. */
    const val BURST_ALL = -2
  }

  /** Bumped when the shackle lets go. */
  val opened = mutableIntStateOf(0)

  fun onEvent(event: LockEvent, rules: JuiceRules) {
    when (event) {
      is LockEvent.PinSet -> if (rules.showSets) {
        flash(BenchColors.Set, FLASH_SET)
        if (rules.showPins) {
          burstPin = event.pin
          padBurst.intValue++
        }
      }

      is LockEvent.PinOverset, LockEvent.Dropped -> fault(SHAKE_FAULT)

      is LockEvent.TensionBite -> if (event.heavy) fault(SHAKE_SEIZE)

      LockEvent.Opened -> {
        flash(BenchColors.Brass, FLASH_OPEN)
        opened.intValue++
        // The last pin to set opens the lock rather than counting as a set: every column goes up.
        if (rules.showPins) {
          burstPin = BURST_ALL
          padBurst.intValue++
        }
      }

      else -> Unit
    }
  }

  private fun fault(strength: Float) {
    flash(BenchColors.Fault, FLASH_FAULT * strength)
    scope.launch {
      shake.snapTo(strength)
      shake.animateTo(0f, tween(SHAKE_MILLIS, easing = LinearEasing))
    }
  }

  private fun flash(color: Color, strength: Float) {
    // A weaker flash does not cut a stronger one short.
    if (flash.value > strength && flashColor != color) return
    flashColor = color
    scope.launch {
      flash.snapTo(strength)
      flash.animateTo(0f, tween(FLASH_MILLIS, easing = FastOutSlowInEasing))
    }
  }
}

@Composable
fun rememberBenchJuice(): BenchJuice {
  val scope = rememberCoroutineScope()
  return remember(scope) { BenchJuice(scope) }
}

/**
 * Shakes this node side to side as [juice] decays. A layer transform: put it after anything in
 * the chain that reports bounds, so a thumb zone never moves under the touch router.
 */
fun Modifier.juiceShake(juice: BenchJuice): Modifier = graphicsLayer {
  val a = juice.shake.value
  translationX = if (a > 0f) sin(a * SHAKE_SWINGS) * a * ShakeReach.toPx() else 0f
}
