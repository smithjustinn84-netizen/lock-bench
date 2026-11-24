package com.lockpicking.bench.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lockpicking.bench.R
import com.lockpicking.bench.ui.theme.BenchColors
import com.lockpicking.bench.ui.theme.BenchShapes
import com.lockpicking.bench.ui.theme.BenchType

private val MinTouchHeight = 48.dp

/** A flat toggle. Filled brass when on. */
@Composable
fun RowScope.BenchToggle(label: String, on: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
  val state = stringResource(if (on) R.string.state_on else R.string.state_off)
  Box(
    Modifier
      .weight(1f)
      .heightIn(min = MinTouchHeight)
      .clip(BenchShapes.Control)
      .background(if (on) BenchColors.Brass else Color.Transparent)
      .border(1.dp, if (on) BenchColors.Brass else BenchColors.Hairline, BenchShapes.Control)
      .clickable(enabled = enabled, role = Role.Switch, onClick = onClick)
      .semantics { stateDescription = state },
    contentAlignment = Alignment.Center,
  ) {
    Text(
      label,
      style = BenchType.Label,
      color = when {
        !enabled -> BenchColors.Hairline
        on -> BenchColors.Ink
        else -> BenchColors.Text
      },
      textAlign = TextAlign.Center,
    )
  }
}

/** A momentary button, or one of a set of exclusive choices when [selected] is used. */
@Composable
fun RowScope.BenchButton(label: String, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
  Box(
    Modifier
      .weight(1f)
      .heightIn(min = MinTouchHeight)
      .clip(BenchShapes.Control)
      .background(if (selected) BenchColors.BrassWash else Color.Transparent)
      .border(1.dp, if (selected) BenchColors.Brass else BenchColors.Hairline, BenchShapes.Control)
      .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      label,
      style = BenchType.Label,
      color = when {
        !enabled -> BenchColors.Hairline
        selected -> BenchColors.Brass
        else -> BenchColors.Text
      },
      textAlign = TextAlign.Center,
    )
  }
}
