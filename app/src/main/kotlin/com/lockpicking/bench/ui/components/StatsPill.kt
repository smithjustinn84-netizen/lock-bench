package com.lockpicking.bench.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lockpicking.bench.R
import com.lockpicking.bench.ui.theme.BenchColors
import com.lockpicking.bench.ui.theme.BenchType

/** Mesh and frame telemetry. [lines] is refreshed a few times a second by the caller. */
@Composable
fun StatsPill(lines: List<String>, modifier: Modifier = Modifier) {
  val description = stringResource(R.string.cd_render_stats, lines.joinToString(". "))
  Panel(modifier.semantics { contentDescription = description }) {
    Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), horizontalAlignment = Alignment.End) {
      lines.forEachIndexed { i, line ->
        Text(line, style = BenchType.Small, color = if (i == 0) BenchColors.Text else BenchColors.Dim)
      }
    }
  }
}
