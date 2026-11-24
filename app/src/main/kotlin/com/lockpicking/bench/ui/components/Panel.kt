package com.lockpicking.bench.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lockpicking.bench.ui.theme.BenchBrushes
import com.lockpicking.bench.ui.theme.BenchColors
import com.lockpicking.bench.ui.theme.BenchShapes
import kotlinx.coroutines.launch

/** How far a panel rises as it arrives, and how long its fade takes. */
private val ArriveRise = 18.dp
private const val ARRIVE_FADE_MILLIS = 180

/**
 * A bevelled gunmetal panel: lit from above, a gradient edge, and a highlight along its top
 * like light catching a machined lip.
 */
@Composable
fun Panel(
  modifier: Modifier = Modifier,
  contentAlignment: Alignment = Alignment.TopStart,
  content: @Composable () -> Unit,
) {
  Box(
    modifier
      .clip(BenchShapes.Panel)
      .background(BenchBrushes.Panel)
      .border(1.dp, BenchBrushes.PanelEdge, BenchShapes.Panel)
      .drawWithContent {
        drawContent()
        val inset = 14.dp.toPx()
        val y = 1.5.dp.toPx()
        drawLine(BenchColors.Bevel, Offset(inset, y), Offset(size.width - inset, y), strokeWidth = 1.dp.toPx())
      },
    contentAlignment = contentAlignment,
  ) { content() }
}

/**
 * Springs the node up into place and fades it in the first time it is composed: an entrance,
 * with no exit, so the node is laid out where it lands from the first frame and anything that
 * measures it sees its final bounds. The rise is a layer transform inside this modifier.
 */
fun Modifier.arrive(rise: Dp = ArriveRise): Modifier = composed {
  val progress = remember { Animatable(0f) }
  val fade = remember { Animatable(0f) }
  LaunchedEffect(Unit) {
    launch { fade.animateTo(1f, tween(ARRIVE_FADE_MILLIS)) }
    progress.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow))
  }
  graphicsLayer {
    alpha = fade.value
    translationY = (1f - progress.value) * rise.toPx()
    val s = ARRIVE_SCALE + (1f - ARRIVE_SCALE) * progress.value
    scaleX = s
    scaleY = s
  }
}

private const val ARRIVE_SCALE = 0.94f
