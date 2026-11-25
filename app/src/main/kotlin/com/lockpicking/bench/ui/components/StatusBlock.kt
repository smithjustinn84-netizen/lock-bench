package com.lockpicking.bench.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.lockpicking.bench.R
import com.lockpicking.bench.ui.bench.BenchStatus
import com.lockpicking.bench.ui.theme.BenchColors
import com.lockpicking.bench.ui.theme.BenchType

/**
 * One line telling the user what the lock is doing and what to do next. [swapHands] says the
 * wrench is under the right thumb, which the hint has to name.
 */
@Composable
fun StatusBlock(status: BenchStatus, swapHands: Boolean, modifier: Modifier = Modifier) {
  val headline: String
  val hint: String
  val tint: Color
  when (status) {
    BenchStatus.Open -> {
      headline = stringResource(R.string.status_open)
      hint = stringResource(R.string.status_open_hint)
      tint = BenchColors.Set
    }

    is BenchStatus.Overset -> {
      headline = stringResource(R.string.status_overset, status.pinNumber)
      hint = stringResource(R.string.status_overset_hint)
      tint = BenchColors.Fault
    }

    BenchStatus.Locked -> {
      headline = stringResource(R.string.status_locked)
      hint = stringResource(if (swapHands) R.string.status_locked_hint_swapped else R.string.status_locked_hint)
      tint = BenchColors.Text
    }

    BenchStatus.Seized -> {
      headline = stringResource(R.string.status_seized)
      hint = stringResource(R.string.status_seized_hint)
      tint = BenchColors.Fault
    }

    is BenchStatus.EaseOff -> {
      headline = stringResource(R.string.status_ease_off)
      hint = stringResource(R.string.status_ease_off_hint, status.pinNumber)
      tint = BenchColors.Fault
    }

    is BenchStatus.Caught -> {
      headline = stringResource(R.string.status_caught)
      hint = stringResource(R.string.status_caught_hint, status.pinNumber)
      tint = BenchColors.Fault
    }

    is BenchStatus.FalseSet -> {
      headline = stringResource(R.string.status_false_set)
      hint = stringResource(
        if (status.rake) R.string.status_false_set_rake_hint else R.string.status_false_set_hint,
        status.pinNumber,
      )
      tint = BenchColors.Brass
    }

    is BenchStatus.Picking -> {
      headline = stringResource(R.string.status_picking, status.setCount, status.pinCount)
      hint = status.bindingPinNumber?.let { stringResource(R.string.status_binding_hint, it) }.orEmpty()
      tint = BenchColors.Brass
    }
  }
  HudHeadline(
    title = stringResource(R.string.bench_title),
    headline = headline,
    hint = hint,
    tint = tint,
    progress = (status as? BenchStatus.Picking)?.let { it.setCount to it.pinCount },
    modifier = modifier,
  )
}

private const val ACCENT_WIDTH_DP = 3
private const val HEADLINE_POP_SCALE = 1.35f
private const val PIP_GAP_DP = 5

/**
 * The HUD's headline: a small title, a loud line that pops in whenever it changes, and a hint
 * under it, behind a coloured edge that says how things stand. [progress] draws a pip per pin,
 * lit for each one set.
 */
@Composable
fun HudHeadline(
  title: String,
  headline: String,
  hint: String,
  tint: Color,
  modifier: Modifier = Modifier,
  progress: Pair<Int, Int>? = null,
  headlineStyle: TextStyle = BenchType.Headline,
) {
  val accent by animateColorAsState(tint, label = "headlineTint")
  Row(modifier.height(IntrinsicSize.Min)) {
    Box(
      Modifier
        .width(ACCENT_WIDTH_DP.dp)
        .fillMaxHeight()
        .clip(RoundedCornerShape(2.dp))
        .background(Brush.verticalGradient(listOf(accent, accent.copy(alpha = 0.1f)))),
    )
    Column(Modifier.padding(start = 10.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = BenchType.Label, color = BenchColors.Dim)
        if (progress != null) {
          Row(Modifier.padding(start = 10.dp), horizontalArrangement = Arrangement.spacedBy(PIP_GAP_DP.dp)) {
            repeat(progress.second) { i -> Pip(lit = i < progress.first) }
          }
        }
      }
      AnimatedContent(
        targetState = headline,
        transitionSpec = {
          (
            fadeIn(tween(durationMillis = 120)) +
              scaleIn(initialScale = HEADLINE_POP_SCALE, animationSpec = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium))
            ) togetherWith fadeOut(tween(durationMillis = 90))
        },
        contentAlignment = Alignment.CenterStart,
        label = "headline",
      ) { line ->
        Text(line, style = headlineStyle, color = accent)
      }
      Text(hint, style = BenchType.Readout, color = BenchColors.Dim)
    }
  }
}

/** One pin's pip in the headline: a ring, filled and glowing once set. */
@Composable
private fun Pip(lit: Boolean) {
  val fill by animateColorAsState(if (lit) BenchColors.Set else Color.Transparent, label = "pip")
  val scale by animateFloatAsState(
    if (lit) 1f else PIP_IDLE_SCALE,
    animationSpec = spring(Spring.DampingRatioHighBouncy, Spring.StiffnessMediumLow),
    label = "pipScale",
  )
  Box(
    Modifier
      .size(9.dp)
      .graphicsLayer {
        scaleX = scale
        scaleY = scale
      }
      .clip(CircleShape)
      .background(fill)
      .border(1.5.dp, if (lit) BenchColors.Set else BenchColors.Dim, CircleShape),
  )
}

private const val PIP_IDLE_SCALE = 0.8f
