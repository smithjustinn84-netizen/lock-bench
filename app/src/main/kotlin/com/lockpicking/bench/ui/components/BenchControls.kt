package com.lockpicking.bench.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lockpicking.bench.R
import com.lockpicking.bench.ui.theme.BenchBrushes
import com.lockpicking.bench.ui.theme.BenchColors
import com.lockpicking.bench.ui.theme.BenchShapes
import com.lockpicking.bench.ui.theme.BenchType

private val MinTouchHeight = 48.dp

/** How far a held button sinks. */
private const val PRESSED_SCALE = 0.92f
private const val DISABLED_ALPHA = 0.38f

/** A flat toggle. A solid brass key when on. */
@Composable
fun RowScope.BenchToggle(label: String, on: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
  val state = stringResource(if (on) R.string.toggle_on else R.string.toggle_off)
  BenchKey(
    label = label,
    style = if (on) KeyStyle.Lit else KeyStyle.Plain,
    enabled = enabled,
    role = Role.Switch,
    onClick = onClick,
    modifier = Modifier.semantics { stateDescription = state },
  )
}

/** A momentary button, or one of a set of exclusive choices when [selected] is used. */
@Composable
fun RowScope.BenchButton(label: String, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
  BenchKey(
    label = label,
    style = if (selected) KeyStyle.Chosen else KeyStyle.Plain,
    enabled = enabled,
    role = Role.Button,
    onClick = onClick,
  )
}

private enum class KeyStyle {
  /** Gunmetal with a hairline edge. */
  Plain,

  /** A brass wash and a brass edge: the one chosen of a set. */
  Chosen,

  /** Solid brass: a switch that is on. */
  Lit,
}

/**
 * One key of the HUD: it sinks under the thumb and springs back, and is lit from above like
 * the panels it sits on.
 */
@Composable
private fun RowScope.BenchKey(
  label: String,
  style: KeyStyle,
  enabled: Boolean,
  role: Role,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val interaction = remember { MutableInteractionSource() }
  val pressed by interaction.collectIsPressedAsState()
  val scale by animateFloatAsState(
    targetValue = if (pressed) PRESSED_SCALE else 1f,
    animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessHigh),
    label = "keyPress",
  )
  val fill = when (style) {
    KeyStyle.Plain -> BenchBrushes.Key
    KeyStyle.Chosen -> BenchBrushes.BrassWash
    KeyStyle.Lit -> BenchBrushes.Brass
  }
  val edge = when (style) {
    KeyStyle.Plain -> BenchBrushes.PanelEdge
    KeyStyle.Chosen, KeyStyle.Lit -> BenchBrushes.BrassEdge
  }
  val textColor = when (style) {
    KeyStyle.Plain -> BenchColors.Text
    KeyStyle.Chosen -> BenchColors.BrassLight
    KeyStyle.Lit -> BenchColors.Ink
  }
  Box(
    modifier
      .weight(1f)
      .heightIn(min = MinTouchHeight)
      .graphicsLayer {
        scaleX = scale
        scaleY = scale
        alpha = if (enabled) 1f else DISABLED_ALPHA
      }
      .clip(BenchShapes.Control)
      .background(fill)
      .border(1.dp, edge, BenchShapes.Control)
      .drawWithContent {
        drawContent()
        // A lit lip along the top edge.
        val inset = 8.dp.toPx()
        val y = 1.5.dp.toPx()
        drawLine(BenchColors.Bevel, Offset(inset, y), Offset(size.width - inset, y), strokeWidth = 1.dp.toPx())
      }
      .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = role, onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      label,
      style = BenchType.Button,
      color = textColor,
      textAlign = TextAlign.Center,
      modifier = Modifier.padding(horizontal = 4.dp),
    )
  }
}
