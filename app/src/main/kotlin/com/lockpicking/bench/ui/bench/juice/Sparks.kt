package com.lockpicking.bench.ui.bench.juice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.channels.Channel
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private const val CAPACITY = 220
private const val NANOS_PER_SECOND = 1_000_000_000f

/** A frame longer than this (a hitch, a resume) is stepped as this, so nothing teleports. */
private const val MAX_STEP_SECONDS = 1f / 20f

/** Streaks are drawn this many seconds of travel long. */
private const val STREAK_SECONDS = 0.035f

/** Confetti flutters: its fall is damped much harder than a spark's flight. */
private const val SPARK_DRAG = 2.2f
private const val CONFETTI_DRAG = 3.6f
private const val FULL_TURN = 360f
private const val ASPECT_CONFETTI = 0.45f

/** How a particle looks and moves. */
enum class SparkKind {
  /** A hot streak along its velocity that falls fast and fades: a pin setting. */
  Spark,

  /** A tumbling paper rectangle that drifts down: the shackle letting go. */
  Confetti,
}

/**
 * A small pool of particles, stepped and drawn without allocating. Bursts are fired with
 * [burst] in the coordinates of whatever canvas draws them; [frame] ticks while any are alive,
 * so a canvas that reads it in its draw phase repaints, and nothing recomposes. When the last
 * one dies the frame loop stops: a quiet bench draws nothing.
 */
@Stable
class Sparks(private val random: Random = Random.Default) {
  private val x = FloatArray(CAPACITY)
  private val y = FloatArray(CAPACITY)
  private val vx = FloatArray(CAPACITY)
  private val vy = FloatArray(CAPACITY)
  private val age = FloatArray(CAPACITY)
  private val life = FloatArray(CAPACITY)
  private val sizes = FloatArray(CAPACITY)
  private val spin = FloatArray(CAPACITY)
  private val turn = FloatArray(CAPACITY)
  private val argb = IntArray(CAPACITY)
  private val kind = arrayOfNulls<SparkKind>(CAPACITY)
  private var count = 0
  private var gravity = 0f
  private val wake = Channel<Unit>(Channel.CONFLATED)

  /** Read this in a draw phase to repaint while particles fly. */
  val frame = mutableIntStateOf(0)

  val alive: Boolean get() = count > 0

  /**
   * Fires [n] particles from ([cx], [cy]) at up to [speed] pixels a second, in a cone of
   * [spread] radians either side of [heading] (radians, 0 is right, -π/2 is up), coloured from
   * [palette]. [gravityPx] pulls everything alive down, in pixels a second squared.
   */
  @Suppress("LongParameterList")
  fun burst(
    cx: Float,
    cy: Float,
    n: Int,
    palette: List<Color>,
    speed: Float,
    sizePx: Float,
    kind: SparkKind = SparkKind.Spark,
    heading: Float = -HALF_PI,
    spread: Float = PI_F,
    lifeSeconds: Float = 0.6f,
    gravityPx: Float = speed * 2f,
  ) {
    gravity = gravityPx
    repeat(n) {
      if (count == CAPACITY) return@repeat
      val i = count++
      val angle = heading + (random.nextFloat() * 2f - 1f) * spread
      val v = speed * (MIN_SPEED_SHARE + (1f - MIN_SPEED_SHARE) * random.nextFloat())
      x[i] = cx
      y[i] = cy
      vx[i] = cos(angle) * v
      vy[i] = sin(angle) * v
      age[i] = 0f
      life[i] = lifeSeconds * (MIN_LIFE_SHARE + (1f - MIN_LIFE_SHARE) * random.nextFloat())
      sizes[i] = sizePx * (MIN_SIZE_SHARE + (1f - MIN_SIZE_SHARE) * random.nextFloat())
      turn[i] = random.nextFloat() * FULL_TURN
      spin[i] = (random.nextFloat() * 2f - 1f) * MAX_SPIN
      argb[i] = palette[random.nextInt(palette.size)].toArgb()
      this.kind[i] = kind
    }
    wake.trySend(Unit)
  }

  /** Steps every particle [dt] seconds and drops the dead. */
  private fun step(dt: Float) {
    var i = 0
    while (i < count) {
      age[i] += dt
      if (age[i] >= life[i]) {
        moveLastTo(i)
        continue
      }
      val drag = if (kind[i] == SparkKind.Confetti) CONFETTI_DRAG else SPARK_DRAG
      val keep = (1f - drag * dt).coerceAtLeast(0f)
      vx[i] *= keep
      vy[i] = vy[i] * keep + gravity * dt * (if (kind[i] == SparkKind.Confetti) CONFETTI_GRAVITY_SHARE else 1f)
      x[i] += vx[i] * dt
      y[i] += vy[i] * dt
      turn[i] += spin[i] * dt
      i++
    }
  }

  private fun moveLastTo(i: Int) {
    val last = --count
    x[i] = x[last]
    y[i] = y[last]
    vx[i] = vx[last]
    vy[i] = vy[last]
    age[i] = age[last]
    life[i] = life[last]
    sizes[i] = sizes[last]
    spin[i] = spin[last]
    turn[i] = turn[last]
    argb[i] = argb[last]
    kind[i] = kind[last]
  }

  /** Runs the frame loop for as long as the composition keeps it: idle until a burst. */
  suspend fun run() {
    while (true) {
      wake.receive()
      var last = withFrameNanos { it }
      while (count > 0) {
        withFrameNanos { now ->
          step(((now - last) / NANOS_PER_SECOND).coerceIn(0f, MAX_STEP_SECONDS))
          last = now
        }
        frame.intValue++
      }
      // One more frame so the last of them is wiped.
      frame.intValue++
    }
  }

  /** Draws every live particle. Call from a draw phase that also reads [frame]. */
  fun draw(scope: DrawScope) = with(scope) {
    for (i in 0 until count) {
      val fade = 1f - age[i] / life[i]
      val color = Color(argb[i]).copy(alpha = fade.coerceIn(0f, 1f))
      when (kind[i]) {
        SparkKind.Confetti -> {
          val w = sizes[i]
          val h = w * ASPECT_CONFETTI
          // A tumbling card shows its edge as it turns.
          val flip = abs(cos(turn[i] * FLIP_RATE)).coerceAtLeast(MIN_FLIP)
          rotate(turn[i], Offset(x[i], y[i])) {
            drawRect(color, Offset(x[i] - w / 2f, y[i] - h * flip / 2f), Size(w, h * flip))
          }
        }

        else -> {
          val tail = Offset(x[i] - vx[i] * STREAK_SECONDS, y[i] - vy[i] * STREAK_SECONDS)
          drawLine(color, tail, Offset(x[i], y[i]), strokeWidth = sizes[i], cap = StrokeCap.Round)
        }
      }
    }
  }

  private companion object {
    const val PI_F = Math.PI.toFloat()
    const val HALF_PI = PI_F / 2f
    const val MIN_SPEED_SHARE = 0.35f
    const val MIN_LIFE_SHARE = 0.6f
    const val MIN_SIZE_SHARE = 0.6f
    const val MAX_SPIN = 540f
    const val FLIP_RATE = 0.05f
    const val MIN_FLIP = 0.15f
    const val CONFETTI_GRAVITY_SHARE = 0.35f
  }
}

/** A [Sparks] pool whose frame loop lives as long as this composition does. */
@Composable
fun rememberSparks(): Sparks {
  val sparks = remember { Sparks() }
  LaunchedEffect(sparks) { sparks.run() }
  return sparks
}
