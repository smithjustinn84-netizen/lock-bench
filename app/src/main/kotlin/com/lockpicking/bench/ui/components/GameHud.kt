package com.lockpicking.bench.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lockpicking.bench.R
import com.lockpicking.bench.domain.game.GameResult
import com.lockpicking.bench.domain.model.LockModel
import com.lockpicking.bench.ui.bench.GameUiState
import com.lockpicking.bench.ui.theme.BenchBrushes
import com.lockpicking.bench.ui.theme.BenchColors
import com.lockpicking.bench.ui.theme.BenchType
import kotlinx.coroutines.delay
import java.util.Locale

private const val TIMER_REFRESH_MILLIS = 100L
private const val MILLIS_PER_TENTH = 100
private const val TENTHS_PER_SECOND = 10
private const val TENTHS_PER_MINUTE = 600
private const val SECONDS_PER_MINUTE = 60

/** 83_400 ms as "1:23.4". */
fun formatGameTime(millis: Long): String {
  val tenths = millis / MILLIS_PER_TENTH
  return "%d:%02d.%d".format(
    Locale.US,
    tenths / TENTHS_PER_MINUTE,
    (tenths / TENTHS_PER_SECOND) % SECONDS_PER_MINUTE,
    tenths % TENTHS_PER_SECOND,
  )
}

/**
 * The status line during a blind round: which lock, the clock, and only what the tier allows.
 * It never names a pin.
 *
 * The clock is polled here, in its own restart scope, so the running time recomposes this
 * text and nothing else: hoisted, it would recompose the 3D scene ten times a second.
 */
@Composable
fun GameStatusBlock(
  game: GameUiState,
  lock: LockModel,
  setCount: Int,
  pinCount: Int,
  elapsedMillis: () -> Long,
  modifier: Modifier = Modifier,
) {
  val readElapsed by rememberUpdatedState(elapsedMillis)
  var elapsed by remember { mutableLongStateOf(readElapsed()) }
  val finished = game.result != null
  LaunchedEffect(finished) {
    elapsed = readElapsed()
    while (!finished) {
      delay(TIMER_REFRESH_MILLIS)
      elapsed = readElapsed()
    }
  }
  val tier = stringResource(game.tier.label)
  val details = buildList {
    if (game.tier.showSetCount) add(stringResource(R.string.game_sets, setCount, pinCount))
    game.bestMillis?.let { add(stringResource(R.string.game_best, formatGameTime(it))) }
  }.joinToString("  ·  ")
  val accent = if (finished) BenchColors.Set else BenchColors.Brass
  Row(modifier.height(IntrinsicSize.Min)) {
    Box(
      Modifier
        .width(3.dp)
        .fillMaxHeight()
        .clip(RoundedCornerShape(2.dp))
        .background(Brush.verticalGradient(listOf(accent, accent.copy(alpha = 0.1f)))),
    )
    Column(Modifier.padding(start = 10.dp)) {
      Text(
        stringResource(R.string.game_title, stringResource(lock.label), tier, game.lockNumber),
        style = BenchType.Label,
        color = BenchColors.Dim,
      )
      Row(verticalAlignment = Alignment.Bottom) {
        Text(formatGameTime(elapsed), style = BenchType.Clock, color = accent)
        MistakeCount(game.mistakes, Modifier.padding(start = 14.dp, bottom = 4.dp))
      }
      Text(details, style = BenchType.Readout, color = BenchColors.Dim)
    }
  }
}

private const val MISTAKE_POP_SCALE = 1.8f

/** The mistake tally, which jumps and flashes red each time it goes up. */
@Composable
private fun MistakeCount(mistakes: Int, modifier: Modifier = Modifier) {
  val pop = remember { Animatable(0f) }
  var seen by remember { mutableIntStateOf(mistakes) }
  LaunchedEffect(mistakes) {
    if (mistakes <= seen) {
      seen = mistakes
      return@LaunchedEffect
    }
    seen = mistakes
    pop.snapTo(1f)
    pop.animateTo(0f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow))
  }
  Text(
    pluralStringResource(R.plurals.game_mistakes, mistakes, mistakes),
    style = BenchType.Button,
    color = if (mistakes == 0) BenchColors.Dim else BenchColors.Fault,
    modifier = modifier.graphicsLayer {
      val s = 1f + (MISTAKE_POP_SCALE - 1f) * pop.value
      scaleX = s
      scaleY = s
      transformOrigin = TransformOrigin(0f, 1f)
    },
  )
}

private const val STAR_FIRST_DELAY_MILLIS = 350L
private const val STAR_STAGGER_MILLIS = 220L
private const val TIME_ROLL_MILLIS = 900
private const val STAR_FROM_SCALE = 2.6f
private const val MAX_STARS = 3

/** The payoff once the shackle releases: how it went, and the way on to the next lock. */
@Composable
fun ResultsCard(result: GameResult, onNext: () -> Unit, onQuit: () -> Unit, modifier: Modifier = Modifier) {
  val shown = remember { Animatable(0f) }
  LaunchedEffect(result) {
    shown.snapTo(0f)
    shown.animateTo(1f, tween(TIME_ROLL_MILLIS, easing = FastOutSlowInEasing))
  }
  Panel(modifier.arrive()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.game_opened), style = BenchType.Headline, color = BenchColors.Set, modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
          repeat(MAX_STARS) { i -> Star(earned = i < result.stars, index = i) }
        }
      }
      Row(verticalAlignment = Alignment.CenterVertically) {
        // The time rolls up to what it was, like a score being counted in.
        val rolled = (result.timeMillis * shown.value).toLong()
        Text(
          formatGameTime(rolled),
          style = BenchType.Clock,
          color = if (result.newBest) BenchColors.Brass else BenchColors.Text,
          modifier = Modifier.weight(1f),
        )
        if (result.newBest) NewBestBadge(Modifier.graphicsLayer { alpha = shown.value })
      }
      Text(
        if (result.mistakes == 0) {
          stringResource(R.string.game_clean)
        } else {
          stringResource(R.string.game_breakdown, result.drops, result.oversets, result.seizes)
        },
        style = BenchType.Readout,
        color = if (result.mistakes == 0) BenchColors.Set else BenchColors.Dim,
      )
      result.previousBestMillis?.takeIf { result.newBest }?.let {
        Text(stringResource(R.string.game_previous_best, formatGameTime(it)), style = BenchType.Small, color = BenchColors.Dim)
      }
      if (result.raked) {
        Text(stringResource(R.string.game_raked), style = BenchType.Small, color = BenchColors.Dim)
      }
      if (result.stars < MAX_STARS) {
        Text(stringResource(R.string.game_par, result.parSeconds), style = BenchType.Small, color = BenchColors.Dim)
      }
      Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BenchButton(stringResource(R.string.game_next), selected = true, onClick = onNext)
        BenchButton(stringResource(R.string.game_quit), onClick = onQuit)
      }
      Text(stringResource(R.string.game_tap_shackle), style = BenchType.Small, color = BenchColors.Dim)
    }
  }
}

/** One star of the rating. Earned stars slam in one after another; the rest just appear. */
@Composable
private fun Star(earned: Boolean, index: Int) {
  val scale = remember { Animatable(if (earned) 0f else 1f) }
  LaunchedEffect(earned) {
    if (!earned) return@LaunchedEffect
    delay(STAR_FIRST_DELAY_MILLIS + STAR_STAGGER_MILLIS * index)
    scale.snapTo(STAR_FROM_SCALE)
    scale.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium))
  }
  Text(
    if (earned) "★" else "☆",
    style = BenchType.Headline.copy(
      fontSize = BenchType.Headline.fontSize * STAR_SIZE,
      shadow = if (earned) Shadow(BenchColors.Brass.copy(alpha = 0.7f), blurRadius = 18f) else null,
    ),
    color = if (earned) BenchColors.Brass else BenchColors.Hairline,
    modifier = Modifier.graphicsLayer {
      scaleX = scale.value
      scaleY = scale.value
      alpha = if (earned) (scale.value * 2f).coerceAtMost(1f) else 1f
    },
  )
}

private const val STAR_SIZE = 1.25f

/** A solid brass tag for a new best time. */
@Composable
private fun NewBestBadge(modifier: Modifier = Modifier) {
  Box(
    modifier
      .clip(RoundedCornerShape(6.dp))
      .background(BenchBrushes.Brass)
      .padding(horizontal = 8.dp, vertical = 3.dp),
  ) {
    Text(stringResource(R.string.game_new_best), style = BenchType.Button, color = BenchColors.Ink)
  }
}
