package com.lockpicking.bench.ui.components

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lockpicking.bench.R
import com.lockpicking.bench.ui.theme.BenchColors
import com.lockpicking.bench.ui.theme.BenchType

/** The guide's sections, in reading order: a heading and its paragraph. */
private val GuideSections = listOf(
  R.string.guide_rules_title to R.string.guide_rules_body,
  R.string.guide_parts_title to R.string.guide_parts_body,
  R.string.guide_shear_title to R.string.guide_shear_body,
  R.string.guide_binding_title to R.string.guide_binding_body,
  R.string.guide_tension_title to R.string.guide_tension_body,
  R.string.guide_spool_title to R.string.guide_spool_body,
  R.string.guide_serrated_title to R.string.guide_serrated_body,
)

/**
 * A short locksport primer: what the parts in the cutaway are and why picking works. The long
 * version is docs/LOCKSPORT.md. Back closes it, as does [onClose].
 */
@Composable
fun GuidePanel(onClose: () -> Unit, modifier: Modifier = Modifier) {
  BackHandler(onBack = onClose)
  Panel(modifier) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          stringResource(R.string.guide_title),
          style = BenchType.Headline,
          color = BenchColors.Text,
          modifier = Modifier.weight(1f).semantics { heading() },
        )
        Row(Modifier.width(96.dp)) {
          BenchButton(stringResource(R.string.control_close), onClick = onClose)
        }
      }
      Column(
        Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        GuideSections.forEach { (title, body) -> GuideSection(title, body) }
        Text(stringResource(R.string.guide_more), style = BenchType.Small, color = BenchColors.Dim)
      }
    }
  }
}

@Composable
private fun GuideSection(@StringRes title: Int, @StringRes body: Int) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(
      stringResource(title),
      style = BenchType.Label,
      color = BenchColors.Brass,
      modifier = Modifier.semantics { heading() },
    )
    Text(stringResource(body), style = BenchType.Readout, color = BenchColors.Text)
  }
}
