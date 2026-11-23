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
import com.lockpicking.bench.ui.bench.CameraPreset
import com.lockpicking.bench.ui.theme.BenchColors
import com.lockpicking.bench.ui.theme.BenchType

/** The bench drawer: explode slider, the bench toggles, the camera presets, and which hand does what. */
@Composable
fun ControlBar(
  explode: Float,
  exploded: Boolean,
  hints: Boolean,
  ghost: Boolean,
  swapHands: Boolean,
  preset: CameraPreset,
  onExplodeChange: (Float) -> Unit,
  onExplodedToggle: () -> Unit,
  onHintsToggle: () -> Unit,
  onGhostToggle: () -> Unit,
  onReset: () -> Unit,
  onSwapHands: () -> Unit,
  onPreset: (CameraPreset) -> Unit,
  modifier: Modifier = Modifier,
) {
  Panel(modifier) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      ExplodeSlider(explode, onExplodeChange)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BenchToggle(stringResource(R.string.control_hints), hints, onClick = onHintsToggle)
        BenchToggle(stringResource(R.string.control_ghost), ghost, onClick = onGhostToggle)
        BenchToggle(stringResource(R.string.control_exploded), exploded, onClick = onExplodedToggle)
        BenchButton(stringResource(R.string.control_reset), onClick = onReset)
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        CameraPreset.entries.forEach { option ->
          BenchButton(stringResource(option.label), selected = option == preset) { onPreset(option) }
        }
        BenchToggle(stringResource(R.string.control_swap), swapHands, onClick = onSwapHands)
      }
    }
  }
}

@Composable
private fun ExplodeSlider(explode: Float, onExplodeChange: (Float) -> Unit) {
  val description = stringResource(R.string.cd_explode_slider)
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(stringResource(R.string.control_explode), style = BenchType.Label, color = BenchColors.Dim, modifier = Modifier.width(66.dp))
    Slider(
      value = explode,
      onValueChange = onExplodeChange,
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
