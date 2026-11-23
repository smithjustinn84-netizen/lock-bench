package com.lockpicking.bench.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lockpicking.bench.R
import com.lockpicking.bench.domain.bench.Keyway
import com.lockpicking.bench.domain.lock.LockMechanics
import com.lockpicking.bench.domain.lock.LockReadout
import com.lockpicking.bench.domain.lock.PinState
import com.lockpicking.bench.domain.model.LockGeometry
import com.lockpicking.bench.ui.theme.BenchColors
import com.lockpicking.bench.ui.theme.BenchType

private const val PULSE_MILLIS = 520

/**
 * The pick pad: a to-scale section through the pin stacks with the shear line drawn across it
 * and the pick underneath, where the thumb that works it rests.
 *
 * The keyway runs across the pad, mouth on the left. Sliding a thumb across moves the pick
 * along it; sliding up lifts the tip. The pad takes no touches itself: they fall through to
 * the 3D view, whose router knows where the pad is, so that this thumb and the one on the
 * wrench can be down together.
 *
 * It reads pin travel straight from [pins], and the tip from [pickZ] and [pickY], in the draw
 * phase, keyed on [motionTick], so a moving pin repaints this canvas without recomposing
 * anything.
 */
@Composable
fun PickPad(
  geometry: LockGeometry,
  pins: LockReadout,
  motionTick: State<Int>,
  setPulse: Int,
  setCount: Int,
  opened: Boolean,
  bindingPin: Int,
  selectedPin: Int,
  pickZ: () -> Float,
  pickY: () -> Float,
  modifier: Modifier = Modifier,
) {
  val pulse = remember { Animatable(0f) }
  // Flash only on a pulse that arrives while shown: the count survives rotation, and a rebuilt
  // panel must not replay the last set.
  var seenPulse by remember { mutableIntStateOf(setPulse) }
  LaunchedEffect(setPulse) {
    if (setPulse != seenPulse) {
      seenPulse = setPulse
      pulse.snapTo(1f)
      pulse.animateTo(0f, tween(PULSE_MILLIS))
    }
  }
  val count = pins.pinCount
  val keyway = remember(geometry) { Keyway(geometry) }
  val description = stringResource(R.string.cd_pick_pad)
  Panel(modifier.systemGestureExclusion().semantics { contentDescription = description }) {
    Column(Modifier.padding(horizontal = PickPadInset, vertical = 8.dp)) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(R.string.pick_title), style = BenchType.Label, color = BenchColors.Dim)
        Text(
          stringResource(R.string.shear_line_count, setCount, count),
          style = BenchType.Label,
          color = if (opened) BenchColors.Set else BenchColors.Brass,
        )
      }
      Canvas(Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp)) {
        motionTick.value // read here so pin and pick motion invalidates the draw, not the composition
        val scale = StackScale(geometry, size.height - 14.dp.toPx())
        drawLands(geometry, scale)
        for (i in 0 until count) {
          val cx = size.width * keyway.depthOf(i)
          drawPinStack(i, cx, geometry, pins, scale, isBinding = i == bindingPin, isSelected = i == selectedPin)
        }
        drawShearLine(scale.y(geometry.shearY), opened, pulse.value)
        val z = pickZ()
        if (keyway.contains(z)) drawPick(size.width * keyway.depthAt(z), scale.y(pickY()), scale)
      }
    }
  }
}

/** The padding either side of the pad's drawing, which the thumb's travel is measured across. */
val PickPadInset = 10.dp

/** The plug's metal between the chambers: what the tip knocks on when it is lifted in the wrong place. */
private fun DrawScope.drawLands(geometry: LockGeometry, scale: StackScale) {
  val top = scale.y(geometry.shearY)
  val bottom = scale.y(geometry.pinRestY + Keyway.LAND_CLEARANCE)
  drawRect(BenchColors.Hairline.copy(alpha = 0.45f), Offset(0f, top), Size(size.width, bottom - top))
  drawLine(BenchColors.Dim, Offset(0f, bottom), Offset(size.width, bottom), strokeWidth = 1.dp.toPx())
}

/** The hook pick, in from the mouth on the left, with its tip at ([tipX], [tipY]). */
private fun DrawScope.drawPick(tipX: Float, tipY: Float, scale: StackScale) {
  val hook = 0.012f * scale.unit
  val shaftY = tipY + hook
  val stroke = 2.5.dp.toPx()
  drawLine(BenchColors.Nickel, Offset(-stroke, shaftY), Offset(tipX, shaftY), strokeWidth = stroke, cap = StrokeCap.Round)
  drawLine(BenchColors.Nickel, Offset(tipX, shaftY), Offset(tipX, tipY), strokeWidth = stroke, cap = StrokeCap.Round)
  drawCircle(BenchColors.Text, radius = 2.dp.toPx(), center = Offset(tipX, tipY))
}

/** Model Y to canvas Y. The stacks are drawn at their true proportions. */
private class StackScale(geometry: LockGeometry, private val drawable: Float) {
  // Room under the pin tips for the keyway and the pick in it.
  private val bottom = geometry.pinRestY - 0.034f
  private val top = geometry.chamberTop + 0.004f

  /** Canvas pixels per model unit. */
  val unit = drawable / (top - bottom)

  fun y(model: Float) = drawable * (1f - (model - bottom) / (top - bottom))
}

private fun DrawScope.drawPinStack(
  i: Int,
  cx: Float,
  geometry: LockGeometry,
  pins: LockReadout,
  scale: StackScale,
  isBinding: Boolean,
  isSelected: Boolean,
) {
  val spec = pins.pin(i)
  val state = pins.state(i)
  val pinWidth = 0.032f * scale.unit
  val left = cx - pinWidth / 2f
  // In a false set the turned plug has carried the foot of the driver sideways.
  val cocked = pins.driverTilt(i) * pinWidth * 0.16f

  // Chamber: the plug's bore below the shear line, the bible above it.
  drawRoundRect(
    color = BenchColors.Chamber,
    topLeft = Offset(left - 2.dp.toPx(), scale.y(geometry.chamberTop)),
    size = Size(pinWidth + 4.dp.toPx(), scale.y(geometry.pinRestY) - scale.y(geometry.chamberTop)),
    cornerRadius = CornerRadius(2.dp.toPx()),
  )

  val keyBase = geometry.pinRestY + pins.keyLift(i)
  val keyTop = keyBase + (geometry.shearY - geometry.pinRestY - spec.liftToShear)
  val driverBase = (geometry.shearY - spec.liftToShear) + pins.driverLift(i)
  val driverTop = driverBase + geometry.driverLength

  // Spring, from the roof of the bible down to the driver.
  drawSpring(cx, scale.y(geometry.chamberTop), scale.y(driverTop), pinWidth * 0.36f)

  val driverColor = when (state) {
    PinState.Set -> BenchColors.Set
    PinState.Overset -> BenchColors.Fault
    PinState.Resting -> BenchColors.Nickel
  }
  val driverHeight = scale.y(driverBase) - scale.y(driverTop)
  drawRoundRect(driverColor, Offset(left + cocked, scale.y(driverTop)), Size(pinWidth, driverHeight - 1f), CornerRadius(1.5.dp.toPx()))
  if (spec.spool) {
    // Waist of a spool driver, cut back out of the block: everything between its two flanges.
    val flange = LockMechanics.SPOOL_FLANGE * scale.unit
    val inset = pinWidth * 0.22f
    val waistTop = scale.y(driverTop) + flange
    val waist = driverHeight - 2f * flange
    drawRect(BenchColors.Ink, Offset(left + cocked, waistTop), Size(inset, waist))
    drawRect(BenchColors.Ink, Offset(left + cocked + pinWidth - inset, waistTop), Size(inset, waist))
  }

  val keyColor = if (state == PinState.Overset) BenchColors.Fault else BenchColors.Brass
  val keyPath = Path().apply {
    // Tapered tip at the bottom, as on the model.
    val taper = 0.014f * scale.unit
    moveTo(cx - pinWidth * 0.18f, scale.y(keyBase))
    lineTo(cx + pinWidth * 0.18f, scale.y(keyBase))
    lineTo(left + pinWidth, scale.y(keyBase) - taper)
    lineTo(left + pinWidth, scale.y(keyTop) + 1f)
    lineTo(left, scale.y(keyTop) + 1f)
    lineTo(left, scale.y(keyBase) - taper)
    close()
  }
  drawPath(keyPath, keyColor)

  // Markers under each column: who is binding and who is selected.
  val markerY = size.height - 7.dp.toPx()
  val markerColor = when {
    state == PinState.Set -> BenchColors.Set
    state == PinState.Overset -> BenchColors.Fault
    isBinding -> BenchColors.Brass
    else -> BenchColors.Hairline
  }
  drawCircle(markerColor, radius = if (isBinding) 4.dp.toPx() else 3.dp.toPx(), center = Offset(cx, markerY))
  if (isSelected) {
    drawCircle(BenchColors.Text, radius = 6.5.dp.toPx(), center = Offset(cx, markerY), style = Stroke(1.dp.toPx()))
  }
}

/** The shear line itself, drawn last so it reads over the pins. It flashes on a set. */
private fun DrawScope.drawShearLine(y: Float, opened: Boolean, glow: Float) {
  if (glow > 0f) {
    drawLine(
      BenchColors.Set.copy(alpha = 0.35f * glow),
      Offset(0f, y),
      Offset(size.width, y),
      strokeWidth = (2f + 9f * glow).dp.toPx(),
      cap = StrokeCap.Round,
    )
  }
  drawLine(
    color = if (opened) BenchColors.Set else lerpColor(BenchColors.Text, BenchColors.Set, glow),
    start = Offset(0f, y),
    end = Offset(size.width, y),
    strokeWidth = 1.5.dp.toPx(),
    pathEffect = if (opened) null else PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.dp.toPx())),
  )
}

private fun DrawScope.drawSpring(cx: Float, top: Float, bottom: Float, halfWidth: Float) {
  val turns = 6
  val path = Path().apply {
    moveTo(cx, top)
    val step = (bottom - top) / (turns * 2)
    for (k in 1..turns * 2) {
      lineTo(cx + if (k % 2 == 1) halfWidth else -halfWidth, top + step * (k - 0.5f))
    }
    lineTo(cx, bottom)
  }
  drawPath(path, BenchColors.Bronze, style = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round))
}

private fun lerpColor(a: Color, b: Color, t: Float): Color = lerp(a, b, t.coerceIn(0f, 1f))
