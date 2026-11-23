package com.lockpicking.bench.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lockpicking.bench.R
import com.lockpicking.bench.domain.lock.LockMechanics
import com.lockpicking.bench.domain.lock.TorqueBand
import com.lockpicking.bench.ui.bench.input.WrenchThrow
import com.lockpicking.bench.ui.theme.BenchColors
import com.lockpicking.bench.ui.theme.BenchType
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** How far the slide travels from nothing to full torque: the finger and the slide move together. */
val WrenchThrowLength: Dp = 132.dp

private val SlideHeight = 12.dp
private val TrackWidth = 30.dp

/** Room for the track and, beside it, the plug dial. */
private val GaugeWidth = 84.dp

/** Plug rotation that swings the dial's needle right across: every set, a false set, and wind-up. */
private const val DIAL_FULL_DEGREES = 12.5f
private const val DIAL_SWEEP_DEGREES = 110f

/**
 * The tension wrench: a slide you hold and pull down, and the two things a hand on a real
 * wrench feels — how hard it is pulling, and how far the plug has turned.
 *
 * The track is marked where the torque starts to hold the plug, where it is light enough for
 * a spool to turn the plug back, where a spool is stuck fast, and where the binding pin
 * seizes. The dial's needle is the plug: it steps round on each set, swings well over on a
 * false set, and kicks back as a spool is worked out of one.
 *
 * The whole panel is the tension zone: a thumb anywhere on it is a hand on the wrench, and the
 * pull is measured from where it lands. This draws the lever and takes no touches. Touches
 * fall through to the 3D view, whose router knows where the zone is: that is the only way a
 * thumb here and a thumb on the pick pad can be down at the same time. [torque] and [plugDegrees] are read in the draw phase, keyed on
 * [motionTick], so a moving wrench repaints this canvas without recomposing anything.
 */
@Composable
fun TensionLever(
  band: TorqueBand,
  motionTick: State<Int>,
  torque: () -> Float,
  plugDegrees: () -> Float,
  modifier: Modifier = Modifier,
) {
  val description = stringResource(R.string.cd_wrench)
  Panel(modifier.systemGestureExclusion().semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
    Column(Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 8.dp).width(GaugeWidth)) {
      Text(stringResource(R.string.wrench_title), style = BenchType.Label, color = BenchColors.Dim)
      Canvas(
        Modifier
          .fillMaxWidth()
          .height(WrenchThrowLength + SlideHeight + 8.dp)
          .padding(top = 6.dp),
      ) {
        motionTick.value // read here so the wrench moving invalidates the draw, not the composition
        drawTrack(torque())
        drawDial(abs(plugDegrees()))
      }
      Text(
        stringResource(band.label),
        style = BenchType.Label,
        color = band.tint,
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
      )
    }
  }
}

private val TorqueBand.label: Int
  get() = when (this) {
    TorqueBand.Slack -> R.string.wrench_slack
    TorqueBand.Eased -> R.string.wrench_eased
    TorqueBand.Firm -> R.string.wrench_firm
    TorqueBand.Heavy -> R.string.wrench_heavy
  }

private val TorqueBand.tint: Color
  get() = when (this) {
    TorqueBand.Slack -> BenchColors.Dim
    TorqueBand.Eased -> BenchColors.Set
    TorqueBand.Firm -> BenchColors.Brass
    TorqueBand.Heavy -> BenchColors.Fault
  }

/** The slot the slide runs in, shaded by what the torque does there, and the slide itself. */
private fun DrawScope.drawTrack(torque: Float) {
  val width = TrackWidth.toPx()
  val slide = SlideHeight.toPx()
  val top = slide / 2f
  val travel = WrenchThrowLength.toPx()
  fun y(t: Float) = top + WrenchThrow.fractionAt(t) * travel

  drawRoundRect(BenchColors.Chamber, Offset(0f, 0f), Size(width, travel + slide), CornerRadius(4.dp.toPx()))
  zone(y(LockMechanics.T_BIND), y(LockMechanics.T_EASED), width, BenchColors.Set.copy(alpha = 0.22f))
  zone(y(LockMechanics.T_EASED), y(LockMechanics.T_SPOOL_BLOCK), width, BenchColors.Brass.copy(alpha = 0.14f))
  zone(y(LockMechanics.T_HEAVY), y(1f) + slide / 2f, width, BenchColors.Fault.copy(alpha = 0.22f))
  for (mark in floatArrayOf(LockMechanics.T_BIND, LockMechanics.T_EASED, LockMechanics.T_SPOOL_BLOCK, LockMechanics.T_HEAVY)) {
    drawLine(BenchColors.Hairline, Offset(0f, y(mark)), Offset(width + 5.dp.toPx(), y(mark)), strokeWidth = 1.dp.toPx())
  }

  val at = y(torque)
  val color = when {
    torque >= LockMechanics.T_HEAVY -> BenchColors.Fault
    torque < LockMechanics.T_BIND -> BenchColors.Nickel
    torque <= LockMechanics.T_EASED -> BenchColors.Set
    else -> BenchColors.Brass
  }
  // A stem from the top of the slot, so the pull reads as a length and not just a position.
  drawLine(color.copy(alpha = 0.55f), Offset(width / 2f, top), Offset(width / 2f, at), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
  drawRoundRect(color, Offset(2.dp.toPx(), at - slide / 2f), Size(width - 4.dp.toPx(), slide), CornerRadius(3.dp.toPx()))
}

private fun DrawScope.zone(from: Float, to: Float, width: Float, color: Color) {
  drawRect(color, Offset(0f, from), Size(width, to - from))
}

/** The plug as a needle: straight up at rest, round to the right as the plug turns. */
private fun DrawScope.drawDial(plugDegrees: Float) {
  val radius = 34.dp.toPx()
  val pivot = Offset(TrackWidth.toPx() + 14.dp.toPx(), radius + 6.dp.toPx())
  fun at(degrees: Float, r: Float): Offset {
    val swing = Math.toRadians((degrees / DIAL_FULL_DEGREES * DIAL_SWEEP_DEGREES).toDouble().coerceIn(0.0, DIAL_SWEEP_DEGREES + 8.0))
    return Offset(pivot.x + r * sin(swing).toFloat(), pivot.y - r * cos(swing).toFloat())
  }

  drawArc(
    color = BenchColors.Hairline,
    startAngle = -90f,
    sweepAngle = DIAL_SWEEP_DEGREES,
    useCenter = false,
    topLeft = Offset(pivot.x - radius, pivot.y - radius),
    size = Size(radius * 2f, radius * 2f),
    style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round),
  )
  // One tick per set; anything the needle shows beyond the last of them is not a real set.
  var tick = 0f
  while (tick <= DIAL_FULL_DEGREES) {
    drawLine(BenchColors.Dim, at(tick, radius - 5.dp.toPx()), at(tick, radius), strokeWidth = 1.dp.toPx())
    tick += LockMechanics.SET_DEGREES
  }
  drawLine(BenchColors.Text, pivot, at(plugDegrees, radius - 3.dp.toPx()), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
  drawCircle(BenchColors.Text, radius = 3.dp.toPx(), center = pivot)
}
