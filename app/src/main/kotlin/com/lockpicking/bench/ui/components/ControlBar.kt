package com.lockpicking.bench.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lockpicking.bench.R
import com.lockpicking.bench.domain.game.GameTier
import com.lockpicking.bench.ui.bench.CameraPreset
import com.lockpicking.bench.ui.theme.BenchColors
import com.lockpicking.bench.ui.theme.BenchType

/**
 * The bench drawer: explode slider, the bench toggles, the camera presets, which hand does
 * what, and the blind game. While a blind lock is being picked ([blind]) everything that would
 * let you see into it is switched off, and RESET gives the round up.
 */
@Composable
fun ControlBar(
  explode: Float,
  exploded: Boolean,
  hints: Boolean,
  ghost: Boolean,
  swapHands: Boolean,
  preset: CameraPreset,
  gameTier: GameTier?,
  blind: Boolean,
  onExplodeChange: (Float) -> Unit,
  onExplodedToggle: () -> Unit,
  onHintsToggle: () -> Unit,
  onGhostToggle: () -> Unit,
  onReset: () -> Unit,
  onSwapHands: () -> Unit,
  onPreset: (CameraPreset) -> Unit,
  onPlay: (GameTier) -> Unit,
  onQuit: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Panel(modifier) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      ExplodeSlider(explode, enabled = !blind, onExplodeChange)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BenchToggle(stringResource(R.string.control_hints), hints, enabled = gameTier == null, onClick = onHintsToggle)
        BenchToggle(stringResource(R.string.control_ghost), ghost, enabled = !blind, onClick = onGhostToggle)
        BenchToggle(stringResource(R.string.control_exploded), exploded, enabled = !blind, onClick = onExplodedToggle)
        if (gameTier != null) {
          BenchButton(stringResource(R.string.control_give_up), onClick = onQuit)
        } else {
          BenchButton(stringResource(R.string.control_reset), onClick = onReset)
        }
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        CameraPreset.entries.filter { it.inDrawer }.forEach { option ->
          BenchButton(stringResource(option.label), selected = option == preset, enabled = !blind) { onPreset(option) }
        }
        BenchToggle(stringResource(R.string.control_swap), swapHands, onClick = onSwapHands)
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.control_play), style = BenchType.Label, color = BenchColors.Dim, modifier = Modifier.width(66.dp))
        GameTier.entries.forEach { tier ->
          BenchButton(stringResource(tier.label), selected = tier == gameTier) { onPlay(tier) }
        }
      }
    }
  }
}

@Composable
private fun ExplodeSlider(explode: Float, enabled: Boolean, onExplodeChange: (Float) -> Unit) {
  val description = stringResource(R.string.cd_explode_slider)
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(stringResource(R.string.control_explode), style = BenchType.Label, color = BenchColors.Dim, modifier = Modifier.width(66.dp))
    Slider(
      value = explode,
      onValueChange = onExplodeChange,
      enabled = enabled,
      valueRange = 0f..1f,
      modifier = Modifier
        .weight(1f)
        .semantics { contentDescription = description },
      colors = SliderDefaults.colors(
        thumbColor = BenchColors.Brass,
        activeTrackColor = BenchColors.Brass,
        inactiveTrackColor = BenchColors.Hairline,
      ),
    )
    Text(
      stringResource(R.string.percent, (explode * 100).toInt()),
      style = BenchType.Readout,
      color = BenchColors.Text,
      textAlign = TextAlign.End,
      modifier = Modifier.width(44.dp),
    )
  }
}

/** The name a tier goes by on its button and in the HUD. */
val GameTier.label: Int
  get() = when (this) {
    GameTier.Apprentice -> R.string.tier_apprentice
    GameTier.Journeyman -> R.string.tier_journeyman
    GameTier.Master -> R.string.tier_master
  }
