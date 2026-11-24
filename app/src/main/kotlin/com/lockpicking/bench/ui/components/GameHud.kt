package com.lockpicking.bench.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lockpicking.bench.R
import com.lockpicking.bench.domain.game.GameResult
import com.lockpicking.bench.domain.model.LockModel
import com.lockpicking.bench.ui.bench.GameUiState
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
    add(pluralStringResource(R.plurals.game_mistakes, game.mistakes, game.mistakes))
    game.bestMillis?.let { add(stringResource(R.string.game_best, formatGameTime(it))) }
  }.joinToString("  ·  ")
  Column(modifier) {
    Text(
      stringResource(R.string.game_title, stringResource(lock.label), tier, game.lockNumber),
      style = BenchType.Label,
      color = BenchColors.Dim,
    )
    Text(
      formatGameTime(elapsed),
      style = BenchType.Headline,
      color = if (finished) BenchColors.Set else BenchColors.Brass,
    )
    Text(details, style = BenchType.Readout, color = BenchColors.Dim)
  }
}

/** The payoff once the shackle releases: how it went, and the way on to the next lock. */
@Composable
fun ResultsCard(result: GameResult, onNext: () -> Unit, onQuit: () -> Unit, modifier: Modifier = Modifier) {
  Panel(modifier) {
    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.game_opened), style = BenchType.Headline, color = BenchColors.Set, modifier = Modifier.weight(1f))
        Text(
          "★".repeat(result.stars) + "☆".repeat(3 - result.stars),
          style = BenchType.Headline,
          color = BenchColors.Brass,
        )
      }
      Text(
        stringResource(
          if (result.newBest) R.string.game_time_best else R.string.game_time,
          formatGameTime(result.timeMillis),
        ),
        style = BenchType.Readout,
        color = if (result.newBest) BenchColors.Brass else BenchColors.Text,
      )
      Text(
        if (result.mistakes == 0) {
          stringResource(R.string.game_clean)
        } else {
          stringResource(R.string.game_breakdown, result.drops, result.oversets, result.seizes)
        },
        style = BenchType.Readout,
        color = BenchColors.Dim,
      )
      result.previousBestMillis?.takeIf { result.newBest }?.let {
        Text(stringResource(R.string.game_previous_best, formatGameTime(it)), style = BenchType.Small, color = BenchColors.Dim)
      }
      if (result.raked) {
        Text(stringResource(R.string.game_raked), style = BenchType.Small, color = BenchColors.Dim)
      }
      if (result.stars < 3) {
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
