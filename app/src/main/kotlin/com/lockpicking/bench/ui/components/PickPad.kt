package com.lockpicking.bench.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.IntState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.lockpicking.bench.R
import com.lockpicking.bench.domain.bench.Keyway
import com.lockpicking.bench.domain.bench.PickTool
import com.lockpicking.bench.domain.bench.RakeProfile
import com.lockpicking.bench.domain.lock.LockMechanics
import com.lockpicking.bench.domain.lock.LockReadout
import com.lockpicking.bench.domain.lock.PinSpec
import com.lockpicking.bench.domain.lock.PinState
import com.lockpicking.bench.domain.model.LockGeometry
import com.lockpicking.bench.ui.bench.reportBounds
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
 * wrench can be down together. The one exception is the hook and rake switch in its header,
 * which is a pair of buttons the picking thumb can reach without the other letting go of the
 * wrench; [onToolSwitchPlaced] says where it is, in window pixels, so the router leaves it alone.
 *
 * It reads pin travel straight from [pins], and the tip from [pickZ] and [pickY], in the draw
 * phase, keyed on [motionTick], so a moving pin repaints this pad without recomposing
 * anything.
 */
@Composable
fun PickPad(
  geometry: LockGeometry,
  pins: LockReadout,
  motionTick: IntState,
  setPulse: Int,
  setCount: Int,
  opened: Boolean,
  bindingPin: Int,
  selectedPin: Int,
  pickZ: () -> Float,
  pickY: () -> Float,
  modifier: Modifier = Modifier,
  /** Picking blind: draw the keyway, the chambers and the pick, and nothing of the pins in them. */
  blind: Boolean = false,
  /** Whether the header counts sets. */
  showSetCount: Boolean = true,
  /** The pick in hand, drawn in the keyway. */
  tool: PickTool = PickTool.Hook,
  /** Swaps the pick, or null if this lock has only a hook: then there is no switch. */
  onTool: ((PickTool) -> Unit)? = null,
  onToolSwitchPlaced: (Rect) -> Unit = {},
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
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        if (onTool != null) {
          Row(
            Modifier.width(ToolSwitchWidth).reportBounds(onToolSwitchPlaced),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            PickTool.entries.forEach { option ->
              BenchButton(stringResource(option.label), selected = option == tool) { onTool(option) }
            }
          }
        } else {
          Text(stringResource(R.string.pick_title), style = BenchType.Label, color = BenchColors.Dim)
        }
        if (showSetCount) {
          Text(
            stringResource(R.string.shear_line_count, setCount, count),
            style = BenchType.Label,
            color = if (opened) BenchColors.Set else BenchColors.Brass,
          )
        }
      }
      // This repaints on every frame a pin moves. Everything it draws with is made here, once
      // per size; each frame only rewinds and redraws the paths.
      Spacer(
        Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp).drawWithCache {
          val ink = PadInk(this, geometry, keyway, size.height - 14.dp.toPx(), count)
          val scale = ink.scale
          onDrawBehind {
            // Read here, not above, so pin and pick motion repaints without rebuilding the cache.
            motionTick.intValue
            drawLands(geometry, scale)
            if (blind) {
              for (i in 0 until count) drawBlindChamber(i, ink, isSelected = i == selectedPin)
            } else {
              for (i in 0 until count) drawPinStack(i, pins, ink, isBinding = i == bindingPin, isSelected = i == selectedPin)
              drawShearLine(scale.y(geometry.shearY), opened, pulse.value, ink)
            }
            val z = pickZ()
            val rake = geometry.rake
            if (!keyway.contains(z)) return@onDrawBehind
            if (tool == PickTool.Rake && rake != null) {
              drawRake(z, pickY(), rake, ink)
            } else {
              drawPick(size.width * keyway.depthAt(z), scale.y(pickY()), scale)
            }
          }
        },
      )
    }
  }
}

/** The padding either side of the pad's drawing, which the thumb's travel is measured across. */
val PickPadInset = 10.dp

/**
 * What the pad draws with, made once per size and reused every frame: the lock and its scale,
 * a path per key pin and per spring, rewound and rebuilt as the pins move, and the strokes and
 * dash that never change.
 */
private class PadInk(density: Density, val geometry: LockGeometry, val keyway: Keyway, drawable: Float, pinCount: Int) {
  val scale = StackScale(geometry, drawable)

  /** Width of a pin, in canvas pixels. */
  val pinWidth = PIN_WIDTH * scale.unit
  val keyPaths = Array(pinCount) { Path() }
  val springPaths = Array(pinCount) { Path() }
  val rakePath = Path()
  val springStroke: Stroke
  val rakeStroke: Stroke
  val ringStroke: Stroke
  val shearDash: PathEffect

  init {
    with(density) {
      springStroke = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round)
      rakeStroke = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
      ringStroke = Stroke(1.dp.toPx())
      shearDash = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.dp.toPx()))
    }
  }
}

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

/**
 * The rake, in from the mouth on the left with its lead peak at [tipZ], [tipY]: the wave of
 * its blade and the shank behind it. It is drawn as the lever it is, pivoting on the mouth of
 * the keyway, so each hump sits where it really is under the pins.
 */
private fun DrawScope.drawRake(tipZ: Float, tipY: Float, rake: RakeProfile, ink: PadInk) {
  val geometry = ink.geometry
  val keyway = ink.keyway
  val scale = ink.scale
  val outward = if (keyway.mouthZ > keyway.backZ) 1f else -1f
  val span = (keyway.mouthZ - keyway.backZ) * outward
  val reach = (geometry.pickFulcrumZ - tipZ) * outward
  val contact = geometry.pinRestY - RAKE_GAP
  val lift = tipY - contact

  // Canvas X of a point [behind] the lead peak: the mouth is the left edge, the back wall the right.
  fun x(behind: Float) = size.width * ((keyway.mouthZ - tipZ) * outward - behind) / span
  fun y(behind: Float): Float {
    val lever = if (reach > 0.02f) (1f - behind / reach).coerceAtLeast(0f) else 1f
    return scale.y(contact + lift * lever + rake.heightAt(behind))
  }
  val blade = ink.rakePath
  blade.rewind()
  var behind = rake.front
  blade.moveTo(x(behind), y(behind))
  val shankFrom = (rake.peaks - 1) * rake.pitch + rake.pitch / 2f
  while (behind < shankFrom) {
    behind = (behind + rake.pitch / 12f).coerceAtMost(shankFrom)
    blade.lineTo(x(behind), y(behind))
  }
  // The shank runs out through the mouth and off the pad.
  val out = (keyway.mouthZ - tipZ) * outward + span * 0.1f
  if (out > shankFrom) {
    blade.lineTo(x(shankFrom + rake.pitch / 4f), y(shankFrom + rake.pitch / 4f))
    blade.lineTo(x(out), y(out))
  }
  drawPath(blade, BenchColors.Nickel, style = ink.rakeStroke)
  drawCircle(BenchColors.Text, radius = 2.dp.toPx(), center = Offset(x(0f), y(0f)))
}

/** A pin's diameter, in model units. */
private const val PIN_WIDTH = 0.032f

/** Daylight between the rake's lead peak and a pin at rest: PickMotion's PICK_GAP. */
private const val RAKE_GAP = 0.002f

/** Room for the two tool buttons in the header. */
private val ToolSwitchWidth = 148.dp

/** The name a pick goes by on its button. */
val PickTool.label: Int
  get() = when (this) {
    PickTool.Hook -> R.string.tool_hook
    PickTool.Rake -> R.string.tool_rake
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

/**
 * Where a chamber opens into the keyway, and nothing more: the mouth of its bore above the
 * lands, and a mark under it, ringed while the tip is under it. The tip feels that much.
 */
private fun DrawScope.drawBlindChamber(i: Int, ink: PadInk, isSelected: Boolean) {
  val geometry = ink.geometry
  val scale = ink.scale
  val pinWidth = ink.pinWidth
  val cx = size.width * ink.keyway.depthOf(i)
  val mouth = scale.y(geometry.pinRestY + Keyway.LAND_CLEARANCE)
  val top = scale.y(geometry.shearY)
  drawRect(BenchColors.Ink, Offset(cx - pinWidth / 2f, top), Size(pinWidth, mouth - top))
  val markerY = size.height - 7.dp.toPx()
  drawCircle(BenchColors.Hairline, radius = 3.dp.toPx(), center = Offset(cx, markerY))
  if (isSelected) {
    drawCircle(BenchColors.Text, radius = 6.5.dp.toPx(), center = Offset(cx, markerY), style = ink.ringStroke)
  }
}

private fun DrawScope.drawPinStack(i: Int, pins: LockReadout, ink: PadInk, isBinding: Boolean, isSelected: Boolean) {
  val geometry = ink.geometry
  val scale = ink.scale
  val cx = size.width * ink.keyway.depthOf(i)
  val spec = pins.pin(i)
  val state = pins.state(i)
  val pinWidth = ink.pinWidth
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
  drawSpring(i, ink, cx, scale.y(geometry.chamberTop), scale.y(driverTop))

  val driverColor = when (state) {
    PinState.Set -> BenchColors.Set
    PinState.Overset -> BenchColors.Fault
    PinState.Resting -> BenchColors.Nickel
  }
  val driverHeight = scale.y(driverBase) - scale.y(driverTop)
  drawRoundRect(driverColor, Offset(left + cocked, scale.y(driverTop)), Size(pinWidth, driverHeight - 1f), CornerRadius(1.5.dp.toPx()))
  drawDriverCuts(spec, left + cocked, scale.y(driverBase), scale.y(driverTop), ink)

  val keyColor = if (state == PinState.Overset) BenchColors.Fault else BenchColors.Brass
  val keyPath = ink.keyPaths[i].apply {
    rewind()
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
    drawCircle(BenchColors.Text, radius = 6.5.dp.toPx(), center = Offset(cx, markerY), style = ink.ringStroke)
  }
}

/**
 * What is cut out of a security driver drawn between [bottom] and [top]: the waist of a spool,
 * everything between its two flanges, or the grooves of a serrated pin, nicked out of both
 * ends, of which the lower ladder is the one that catches.
 */
private fun DrawScope.drawDriverCuts(spec: PinSpec, left: Float, bottom: Float, top: Float, ink: PadInk) {
  val pinWidth = ink.pinWidth
  val scale = ink.scale
  if (spec.spool) {
    val flange = LockMechanics.SPOOL_FLANGE * scale.unit
    val inset = pinWidth * 0.22f
    val waist = bottom - top - 2f * flange
    drawRect(BenchColors.Ink, Offset(left, top + flange), Size(inset, waist))
    drawRect(BenchColors.Ink, Offset(left + pinWidth - inset, top + flange), Size(inset, waist))
  }
  if (!spec.serrated) return
  val nick = pinWidth * 0.2f
  val stroke = 1.5.dp.toPx()
  fun nicks(y: Float) {
    drawLine(BenchColors.Ink, Offset(left, y), Offset(left + nick, y), strokeWidth = stroke)
    drawLine(BenchColors.Ink, Offset(left + pinWidth - nick, y), Offset(left + pinWidth, y), strokeWidth = stroke)
  }
  for (k in 1..LockMechanics.SERRATIONS) {
    val rise = k * LockMechanics.SERRATION_PITCH * scale.unit
    nicks(bottom - rise)
    nicks(top + rise)
  }
}

/** The shear line itself, drawn last so it reads over the pins. It flashes on a set. */
private fun DrawScope.drawShearLine(y: Float, opened: Boolean, glow: Float, ink: PadInk) {
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
    pathEffect = if (opened) null else ink.shearDash,
  )
}

private fun DrawScope.drawSpring(i: Int, ink: PadInk, cx: Float, top: Float, bottom: Float) {
  val turns = 6
  val halfWidth = ink.pinWidth * 0.36f
  val path = ink.springPaths[i].apply {
    rewind()
    moveTo(cx, top)
    val step = (bottom - top) / (turns * 2)
    for (k in 1..turns * 2) {
      lineTo(cx + if (k % 2 == 1) halfWidth else -halfWidth, top + step * (k - 0.5f))
    }
    lineTo(cx, bottom)
  }
  drawPath(path, BenchColors.Bronze, style = ink.springStroke)
}

private fun lerpColor(a: Color, b: Color, t: Float): Color = lerp(a, b, t.coerceIn(0f, 1f))
