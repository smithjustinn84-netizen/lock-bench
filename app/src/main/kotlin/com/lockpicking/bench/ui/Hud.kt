package com.lockpicking.bench.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lockpicking.bench.lock.BenchUi
import com.lockpicking.bench.lock.LockMechanics
import com.lockpicking.bench.lock.LockRig
import com.lockpicking.bench.lock.PinState

/** A dark instrument panel: ink, hairlines, and one brass accent. */
object Bench {
  val Ink = Color(0xFF0B0D10)
  val Panel = Color(0xE6101318)
  val Hairline = Color(0xFF2B3038)
  val Text = Color(0xFFDCDFE4)
  val Dim = Color(0xFF858B96)
  val Brass = Color(0xFFE3B45A)
  val Nickel = Color(0xFFB9BDC4)
  val Bronze = Color(0xFFB8733A)
  val Set = Color(0xFF62D391)
  val Fault = Color(0xFFEC6A55)

  val Label = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Medium)
  val Readout = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp)
  val Shape = RoundedCornerShape(10.dp)
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
  Box(
    modifier
      .clip(Bench.Shape)
      .background(Bench.Panel)
      .border(1.dp, Bench.Hairline, Bench.Shape),
  ) { content() }
}

/** One line telling the user what the lock is doing and what to do next. */
@Composable
fun StatusBlock(ui: BenchUi, pinCount: Int, modifier: Modifier = Modifier) {
  val (headline, tint) = when {
    ui.opened -> "OPEN" to Bench.Set
    ui.oversetPin >= 0 -> "PIN ${ui.oversetPin + 1} OVERSET" to Bench.Fault
    !ui.tension -> "LOCKED" to Bench.Text
    else -> "${ui.setCount} / $pinCount SET" to Bench.Brass
  }
  val hint = when {
    ui.opened -> "Shackle released. Reset to relock."
    ui.oversetPin >= 0 -> "Key pin is past the shear line. Release tension to drop it."
    !ui.tension -> "Turn tension on, then lift pins to the shear line."
    ui.bindingPin >= 0 -> "Pin ${ui.bindingPin + 1} is binding. Tap it, or drag it up."
    else -> ""
  }
  Column(modifier) {
    Text("LOCK BENCH", style = Bench.Label, color = Bench.Dim)
    Text(headline, style = Bench.Readout.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold), color = tint)
    Text(hint, style = Bench.Readout, color = Bench.Dim)
  }
}

/** Mesh and frame telemetry. [lines] is refreshed a few times a second by the caller. */
@Composable
fun StatsPill(lines: List<String>, modifier: Modifier = Modifier) {
  Panel(modifier.semantics { contentDescription = "Render stats. " + lines.joinToString(". ") }) {
    Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), horizontalAlignment = Alignment.End) {
      lines.forEachIndexed { i, line ->
        Text(line, style = Bench.Readout.copy(fontSize = 11.sp, lineHeight = 15.sp), color = if (i == 0) Bench.Text else Bench.Dim)
      }
    }
  }
}

/**
 * A to-scale section through the four pin stacks with the shear line drawn across it.
 *
 * It reads pin travel straight from [lock] in the draw phase, keyed on [BenchUi.motionTick],
 * so a moving pin repaints this canvas without recomposing anything. Tapping a column nudges
 * that pin, which keeps the lock pickable from camera angles where the pins are hidden.
 */
@Composable
fun ShearLineIndicator(
  ui: BenchUi,
  lock: LockMechanics,
  rig: LockRig,
  onNudge: (Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  val pulse = remember { Animatable(0f) }
  LaunchedEffect(ui.setPulse) {
    if (ui.setPulse > 0) {
      pulse.snapTo(1f)
      pulse.animateTo(0f, tween(520))
    }
  }
  val count = lock.pins.size
  Panel(modifier) {
    Column(Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 6.dp)) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("SHEAR LINE", style = Bench.Label, color = Bench.Dim)
        Text("${ui.setCount}/$count", style = Bench.Label, color = if (ui.opened) Bench.Set else Bench.Brass)
      }
      Canvas(
        Modifier
          .fillMaxWidth()
          .height(112.dp)
          .padding(top = 4.dp)
          .semantics { contentDescription = "Pin stacks. Tap a column to lift that pin to the shear line." }
          .pointerInput(count) {
            detectTapGestures { tap -> onNudge((tap.x / (size.width / count.toFloat())).toInt().coerceIn(0, count - 1)) }
          },
      ) {
        ui.motionTick // read here so pin motion invalidates the draw, not the composition
        val binding = ui.bindingPin
        val selected = ui.selectedPin

        // Model Y -> canvas Y. The stacks are drawn at their true proportions.
        val bottom = rig.pinRestY - 0.006f
        val top = rig.chamberTop + 0.004f
        val markerBand = 14.dp.toPx()
        val drawable = size.height - markerBand
        fun y(model: Float) = drawable * (1f - (model - bottom) / (top - bottom))
        val unit = drawable / (top - bottom)

        val column = size.width / count
        val pinWidth = 0.032f * unit
        val shearY = y(rig.shearY)

        for (i in 0 until count) {
          val spec = lock.pins[i]
          val state = lock.states[i]
          val cx = column * (i + 0.5f)
          val left = cx - pinWidth / 2f

          // Chamber: the plug's bore below the shear line, the bible above it.
          drawRoundRect(
            color = Color(0x14FFFFFF),
            topLeft = Offset(left - 2.dp.toPx(), y(rig.chamberTop)),
            size = Size(pinWidth + 4.dp.toPx(), y(rig.pinRestY) - y(rig.chamberTop)),
            cornerRadius = CornerRadius(2.dp.toPx()),
          )

          val keyBase = rig.pinRestY + lock.keyLift[i]
          val keyTop = keyBase + (rig.shearY - rig.pinRestY - spec.liftToShear)
          val driverBase = (rig.shearY - spec.liftToShear) + lock.driverLift[i]
          val driverTop = driverBase + rig.driverLength

          // Spring, from the roof of the bible down to the driver.
          drawSpring(cx, y(rig.chamberTop), y(driverTop), pinWidth * 0.36f)

          val driverColor = when (state) {
            PinState.Set -> Bench.Set
            PinState.Overset -> Bench.Fault
            PinState.Resting -> Bench.Nickel
          }
          drawRoundRect(driverColor, Offset(left, y(driverTop)), Size(pinWidth, y(driverBase) - y(driverTop) - 1f), CornerRadius(1.5.dp.toPx()))
          if (spec.spool) {
            // Waist of a spool driver, cut back out of the block.
            val waist = (y(driverBase) - y(driverTop)) * 0.34f
            val inset = pinWidth * 0.22f
            val mid = (y(driverBase) + y(driverTop)) / 2f
            drawRect(Bench.Ink, Offset(left, mid - waist / 2f), Size(inset, waist))
            drawRect(Bench.Ink, Offset(left + pinWidth - inset, mid - waist / 2f), Size(inset, waist))
          }

          val keyColor = if (state == PinState.Overset) Bench.Fault else Bench.Brass
          val keyPath = Path().apply {
            // Tapered tip at the bottom, as on the model.
            val taper = 0.014f * unit
            moveTo(cx - pinWidth * 0.18f, y(keyBase))
            lineTo(cx + pinWidth * 0.18f, y(keyBase))
            lineTo(left + pinWidth, y(keyBase) - taper)
            lineTo(left + pinWidth, y(keyTop) + 1f)
            lineTo(left, y(keyTop) + 1f)
            lineTo(left, y(keyBase) - taper)
            close()
          }
          drawPath(keyPath, keyColor)

          // Markers under each column: number, plus who is binding and who is selected.
          val markerY = size.height - markerBand / 2f
          val markerColor = when {
            state == PinState.Set -> Bench.Set
            state == PinState.Overset -> Bench.Fault
            i == binding -> Bench.Brass
            else -> Bench.Hairline
          }
          drawCircle(markerColor, radius = if (i == binding) 4.dp.toPx() else 3.dp.toPx(), center = Offset(cx, markerY))
          if (i == selected) {
            drawCircle(Bench.Text, radius = 6.5.dp.toPx(), center = Offset(cx, markerY), style = Stroke(1.dp.toPx()))
          }
        }

        // The shear line itself, drawn last so it reads over the pins. It flashes on a set.
        val glow = pulse.value
        if (glow > 0f) {
          drawLine(Bench.Set.copy(alpha = 0.35f * glow), Offset(0f, shearY), Offset(size.width, shearY), strokeWidth = (2f + 9f * glow).dp.toPx(), cap = StrokeCap.Round)
        }
        drawLine(
          color = if (ui.opened) Bench.Set else lerpColor(Bench.Text, Bench.Set, glow),
          start = Offset(0f, shearY),
          end = Offset(size.width, shearY),
          strokeWidth = 1.5.dp.toPx(),
          pathEffect = if (ui.opened) null else PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.dp.toPx())),
        )
      }
    }
  }
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
  drawPath(path, Bench.Bronze, style = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round))
}

private fun lerpColor(a: Color, b: Color, t: Float): Color = androidx.compose.ui.graphics.lerp(a, b, t.coerceIn(0f, 1f))

/** A flat toggle. Filled brass when on. */
@Composable
fun RowScope.BenchToggle(label: String, on: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
  val shape = RoundedCornerShape(8.dp)
  Box(
    Modifier
      .weight(1f)
      .heightIn(min = 48.dp)
      .clip(shape)
      .background(if (on) Bench.Brass else Color.Transparent)
      .border(1.dp, if (on) Bench.Brass else Bench.Hairline, shape)
      .clickable(enabled = enabled, role = Role.Switch, onClick = onClick)
      .semantics { stateDescription = if (on) "On" else "Off" },
    contentAlignment = Alignment.Center,
  ) {
    Text(
      label,
      style = Bench.Label,
      color = when {
        !enabled -> Bench.Hairline
        on -> Bench.Ink
        else -> Bench.Text
      },
      textAlign = TextAlign.Center,
    )
  }
}

/** A momentary button, or one of a set of exclusive choices when [selected] is used. */
@Composable
fun RowScope.BenchButton(label: String, selected: Boolean = false, onClick: () -> Unit) {
  val shape = RoundedCornerShape(8.dp)
  Box(
    Modifier
      .weight(1f)
      .heightIn(min = 48.dp)
      .clip(shape)
      .background(if (selected) Color(0x24E3B45A) else Color.Transparent)
      .border(1.dp, if (selected) Bench.Brass else Bench.Hairline, shape)
      .clickable(role = Role.Button, onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Text(label, style = Bench.Label, color = if (selected) Bench.Brass else Bench.Text, textAlign = TextAlign.Center)
  }
}

@Composable
fun ControlBar(
  ui: BenchUi,
  preset: CameraPreset,
  onPreset: (CameraPreset) -> Unit,
  onTension: (Boolean) -> Unit,
  onReset: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Panel(modifier) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text("EXPLODE", style = Bench.Label, color = Bench.Dim, modifier = Modifier.width(66.dp))
        Slider(
          value = ui.explode,
          onValueChange = { ui.explode = it },
          valueRange = 0f..1f,
          modifier = Modifier
            .weight(1f)
            .semantics { contentDescription = "Exploded view amount" },
          colors = SliderDefaults.colors(
            thumbColor = Bench.Brass,
            activeTrackColor = Bench.Brass,
            inactiveTrackColor = Bench.Hairline,
          ),
        )
        Text(
          "${(ui.explode * 100).toInt()}%",
          style = Bench.Readout,
          color = Bench.Text,
          textAlign = TextAlign.End,
          modifier = Modifier.width(44.dp),
        )
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BenchToggle("TENSION", ui.tension, enabled = !ui.opened) { onTension(!ui.tension) }
        BenchToggle("GHOST", ui.ghost) { ui.ghost = !ui.ghost }
        BenchToggle("EXPLODED", ui.explode > 0.5f) { ui.explode = if (ui.explode > 0.5f) 0f else 1f }
        BenchButton("RESET", onClick = onReset)
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        CameraPreset.entries.forEach { option ->
          BenchButton(option.label, selected = option == preset) { onPreset(option) }
        }
      }
    }
  }
}

/** Darkens the corners over the 3D surface so the lit lock sits in a pool of light. */
@Composable
fun Vignette(modifier: Modifier = Modifier) {
  Canvas(modifier.fillMaxSize()) {
    drawRect(
      androidx.compose.ui.graphics.Brush.radialGradient(
        colors = listOf(Color.Transparent, Color(0x99000000)),
        center = Offset(size.width / 2f, size.height * 0.42f),
        radius = size.maxDimension * 0.72f,
      ),
    )
  }
}

@Composable
fun Gap(height: Int) = Spacer(Modifier.height(height.dp))
