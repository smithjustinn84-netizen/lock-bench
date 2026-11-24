package com.lockpicking.bench.baselineprofile

import android.graphics.Rect
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

/** The app under test. The plugin's benchmarkRelease and nonMinifiedRelease keep its id. */
internal const val TARGET_PACKAGE = "com.lockpicking.bench"

// The zones' content descriptions (cd_pick_pad, cd_wrench) open with these.
private val PICK_PAD = By.descStartsWith("Pick pad")
private val TENSION_ZONE = By.descStartsWith("Tension zone")

private const val BENCH_TIMEOUT_MS = 15_000L

// A swipe is ~5 ms a step: these are deliberate thumb strokes, not flings.
private const val STROKE_STEPS = 40
private const val PICK_ROUNDS = 3

// Keep strokes inside the zones, clear of the screen edges and their back gestures.
private const val NEAR = 0.2f
private const val FAR = 0.8f
private const val MIDDLE = 0.5f

/**
 * Cold start as the player sees it: the splash holds until the view model has restored the
 * saved lock, then the bench draws with both thumb zones. Waits for the pick pad, which is
 * only there once the bench is.
 */
internal fun MacrobenchmarkScope.waitForBench() {
  check(device.wait(Until.hasObject(PICK_PAD), BENCH_TIMEOUT_MS)) { "The bench never drew its pick pad" }
  device.waitForIdle()
}

/**
 * A short picking session: pull torque on the tension zone, then work the pick pad, along the
 * keyway and up into the pins. The zones are found by description, not by side, so a swapped
 * hands preference does not move the strokes off them.
 */
internal fun MacrobenchmarkScope.pickTheLock() {
  repeat(PICK_ROUNDS) {
    zone(TENSION_ZONE).stroke(fromX = MIDDLE, fromY = NEAR, toX = MIDDLE, toY = FAR)
    val pad = zone(PICK_PAD)
    pad.stroke(fromX = NEAR, fromY = FAR, toX = FAR, toY = FAR)
    pad.stroke(fromX = MIDDLE, fromY = FAR, toX = MIDDLE, toY = NEAR)
    pad.stroke(fromX = FAR, fromY = MIDDLE, toX = NEAR, toY = MIDDLE)
  }
  device.waitForIdle()
}

private fun MacrobenchmarkScope.zone(selector: BySelector): ZoneStroke {
  val zone: UiObject2 = checkNotNull(device.findObject(selector)) { "No zone matches $selector" }
  return ZoneStroke(this, zone.visibleBounds)
}

private class ZoneStroke(private val scope: MacrobenchmarkScope, private val bounds: Rect) {
  fun stroke(fromX: Float, fromY: Float, toX: Float, toY: Float) {
    scope.device.swipe(x(fromX), y(fromY), x(toX), y(toY), STROKE_STEPS)
  }

  private fun x(fraction: Float) = bounds.left + (bounds.width() * fraction).toInt()

  private fun y(fraction: Float) = bounds.top + (bounds.height() * fraction).toInt()
}
