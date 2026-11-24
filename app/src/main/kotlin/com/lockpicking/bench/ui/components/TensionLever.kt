package com.lockpicking.bench.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.IntState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
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

/** How far past the end of its scale the needle may still swing. */
private const val DIAL_OVERSWING_DEGREES = 8.0

/** The turned wedge of the dial, filled faintly behind the lit arc. */
private const val DIAL_WEDGE_ALPHA = 0.12f

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
 * [motionTick], so a moving wrench repaints this gauge without recomposing anything.
 */
@Composable
fun TensionLever(
  band: TorqueBand,
  motionTick: IntState,
  torque: () -> Float,
  plugDegrees: () -> Float,
  modifier: Modifier = Modifier,
  /**
   * Whether the track is marked and the band named. Off, it is a bare slot: how hard is too
   * hard is left to the hand. [seizeAt] is where the binding pin seizes on this lock.
   */
  showZones: Boolean = true,
  seizeAt: Float = LockMechanics.T_HEAVY,
  /** Whether the dial is ticked once per set: the needle against the ticks counts the sets. */
  showSetTicks: Boolean = true,
) {
  val description = stringResource(R.string.cd_wrench)
  Panel(modifier.systemGestureExclusion().semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
    Column(Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 8.dp).width(GaugeWidth)) {
      Text(stringResource(R.string.wrench_title), style = BenchType.Label, color = BenchColors.Dim)
      Spacer(
        Modifier
          .fillMaxWidth()
          .height(WrenchThrowLength + SlideHeight + 8.dp)
          .padding(top = 6.dp)
          .drawWithCache {
            val dialStroke = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round)
            onDrawBehind {
              // Read here so the wrench moving repaints the gauge, not the composition.
              motionTick.intValue
              drawTrack(torque(), showZones, seizeAt)
              drawDial(abs(plugDegrees()), showSetTicks, dialStroke)
            }
          },
      )
      val tint by animateColorAsState(band.tint, label = "bandTint")
      Text(
        if (showZones) stringResource(band.label) else "",
        style = BenchType.Button,
        color = tint,
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
private fun DrawScope.drawTrack(torque: Float, showZones: Boolean, seizeAt: Float) {
  val width = TrackWidth.toPx()
  val slide = SlideHeight.toPx()
  val top = slide / 2f
  val travel = WrenchThrowLength.toPx()
  fun y(t: Float) = top + WrenchThrow.fractionAt(t) * travel

  drawRoundRect(BenchColors.Chamber, Offset(0f, 0f), Size(width, travel + slide), CornerRadius(4.dp.toPx()))
  if (showZones) {
    zone(y(LockMechanics.T_BIND), y(LockMechanics.T_EASED), width, BenchColors.Set.copy(alpha = 0.22f))
    zone(y(LockMechanics.T_EASED), y(LockMechanics.T_SPOOL_BLOCK), width, BenchColors.Brass.copy(alpha = 0.14f))
    zone(y(seizeAt), y(1f) + slide / 2f, width, BenchColors.Fault.copy(alpha = 0.22f))
    fun mark(t: Float) = drawLine(BenchColors.Hairline, Offset(0f, y(t)), Offset(width + 5.dp.toPx(), y(t)), strokeWidth = 1.dp.toPx())
    mark(LockMechanics.T_BIND)
    mark(LockMechanics.T_EASED)
    mark(LockMechanics.T_SPOOL_BLOCK)
    mark(seizeAt)
  }

  val at = y(torque)
  val color = when {
    // A bare slot says nothing about where the torque sits.
    !showZones -> BenchColors.Nickel

    torque >= seizeAt -> BenchColors.Fault

    torque < LockMechanics.T_BIND -> BenchColors.Nickel

    torque <= LockMechanics.T_EASED -> BenchColors.Set

    else -> BenchColors.Brass
  }
  // A stem from the top of the slot, so the pull reads as a length and not just a position.
  val stem = 4.dp.toPx()
  drawLine(color.copy(alpha = STEM_ALPHA), Offset(width / 2f, top), Offset(width / 2f, at), strokeWidth = stem, cap = StrokeCap.Round)
  // The slide glows in its colour, harder the further into the seize it is pulled.
  val strain = if (showZones && torque >= seizeAt) 1f else torque.coerceIn(0f, 1f) * GLOW_PER_TORQUE
  if (torque > 0f) {
    drawRoundRect(
      color.copy(alpha = GLOW_ALPHA_BASE + GLOW_ALPHA_STRAIN * strain),
      Offset(-3.dp.toPx(), at - slide / 2f - 5.dp.toPx()),
      Size(width + 6.dp.toPx(), slide + 10.dp.toPx()),
      CornerRadius(8.dp.toPx()),
    )
  }
  drawGrip(color, Offset(2.dp.toPx(), at - slide / 2f), Size(width - 4.dp.toPx(), slide))
}

/** The slide the thumb holds: a coloured grip with a lit top edge and knurling across it. */
private fun DrawScope.drawGrip(color: Color, at: Offset, size: Size) {
  drawRoundRect(color, at, size, CornerRadius(3.dp.toPx()))
  val hair = 1.dp.toPx()
  val lip = at.y + hair
  val inset = 3.dp.toPx()
  drawLine(Color.White.copy(alpha = SHEEN_ALPHA), Offset(at.x + inset, lip), Offset(at.x + size.width - inset, lip), strokeWidth = hair)
  val step = size.width / (KNURLS + 1)
  for (k in 1..KNURLS) {
    val kx = at.x + step * k
    drawLine(Color.Black.copy(alpha = KNURL_ALPHA), Offset(kx, at.y + inset), Offset(kx, at.y + size.height - inset), strokeWidth = hair)
  }
}

private const val STEM_ALPHA = 0.55f
private const val GLOW_PER_TORQUE = 0.5f
private const val GLOW_ALPHA_BASE = 0.12f
private const val GLOW_ALPHA_STRAIN = 0.3f
private const val SHEEN_ALPHA = 0.55f
private const val KNURL_ALPHA = 0.35f
private const val KNURLS = 5

private fun DrawScope.zone(from: Float, to: Float, width: Float, color: Color) {
  drawRect(color, Offset(0f, from), Size(width, to - from))
}

/** The plug as a needle: straight up at rest, round to the right as the plug turns. */
private fun DrawScope.drawDial(plugDegrees: Float, showTicks: Boolean, arcStroke: Stroke) {
  val radius = 34.dp.toPx()
  val pivot = Offset(TrackWidth.toPx() + 14.dp.toPx(), radius + 6.dp.toPx())
  fun at(degrees: Float, r: Float): Offset {
    val sweep = (degrees / DIAL_FULL_DEGREES * DIAL_SWEEP_DEGREES).toDouble()
    val swing = Math.toRadians(sweep.coerceIn(0.0, DIAL_SWEEP_DEGREES + DIAL_OVERSWING_DEGREES))
    return Offset(pivot.x + r * sin(swing).toFloat(), pivot.y - r * cos(swing).toFloat())
  }

  drawArc(
    color = BenchColors.Hairline,
    startAngle = -90f,
    sweepAngle = DIAL_SWEEP_DEGREES,
    useCenter = false,
    topLeft = Offset(pivot.x - radius, pivot.y - radius),
    size = Size(radius * 2f, radius * 2f),
    style = arcStroke,
  )
  // The arc the plug has turned through, lit, so a set reads as a step forward.
  val swept = (plugDegrees / DIAL_FULL_DEGREES * DIAL_SWEEP_DEGREES).coerceIn(0f, DIAL_SWEEP_DEGREES + DIAL_OVERSWING_DEGREES.toFloat())
  if (swept > 0f) {
    drawArc(
      color = BenchColors.Brass,
      startAngle = -90f,
      sweepAngle = swept,
      useCenter = false,
      topLeft = Offset(pivot.x - radius, pivot.y - radius),
      size = Size(radius * 2f, radius * 2f),
      style = arcStroke,
    )
    drawArc(
      color = BenchColors.Brass.copy(alpha = DIAL_WEDGE_ALPHA),
      startAngle = -90f,
      sweepAngle = swept,
      useCenter = true,
      topLeft = Offset(pivot.x - radius, pivot.y - radius),
      size = Size(radius * 2f, radius * 2f),
    )
  }
  // One tick per set; anything the needle shows beyond the last of them is not a real set.
  var tick = if (showTicks) 0f else Float.MAX_VALUE
  while (tick <= DIAL_FULL_DEGREES) {
    drawLine(BenchColors.Dim, at(tick, radius - 5.dp.toPx()), at(tick, radius), strokeWidth = 1.dp.toPx())
    tick += LockMechanics.SET_DEGREES
  }
  drawLine(BenchColors.NickelLight, pivot, at(plugDegrees, radius - 3.dp.toPx()), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
  drawCircle(BenchColors.BrassDeep, radius = 5.dp.toPx(), center = pivot)
  drawCircle(BenchColors.BrassLight, radius = 2.5.dp.toPx(), center = pivot)
}
