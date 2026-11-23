package com.lockpicking.bench.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.lockpicking.bench.ui.theme.BenchColors
import com.lockpicking.bench.ui.theme.BenchShapes

/** A translucent instrument panel with a hairline border. */
@Composable
fun Panel(
  modifier: Modifier = Modifier,
  contentAlignment: Alignment = Alignment.TopStart,
  content: @Composable () -> Unit,
) {
  Box(
    modifier
      .clip(BenchShapes.Panel)
      .background(BenchColors.Panel)
      .border(1.dp, BenchColors.Hairline, BenchShapes.Panel),
    contentAlignment = contentAlignment,
  ) { content() }
}
