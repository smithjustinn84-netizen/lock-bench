package com.lockpicking.bench.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.lockpicking.bench.R
import com.lockpicking.bench.ui.bench.BenchStatus
import com.lockpicking.bench.ui.theme.BenchColors
import com.lockpicking.bench.ui.theme.BenchType

/** One line telling the user what the lock is doing and what to do next. */
@Composable
fun StatusBlock(status: BenchStatus, modifier: Modifier = Modifier) {
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
      hint = stringResource(R.string.status_locked_hint)
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
    is BenchStatus.FalseSet -> {
      headline = stringResource(R.string.status_false_set)
      hint = stringResource(R.string.status_false_set_hint, status.pinNumber)
      tint = BenchColors.Brass
    }
    is BenchStatus.Picking -> {
      headline = stringResource(R.string.status_picking, status.setCount, status.pinCount)
      hint = status.bindingPinNumber?.let { stringResource(R.string.status_binding_hint, it) }.orEmpty()
      tint = BenchColors.Brass
    }
  }
  Column(modifier) {
    Text(stringResource(R.string.bench_title), style = BenchType.Label, color = BenchColors.Dim)
    Text(headline, style = BenchType.Headline, color = tint)
    Text(hint, style = BenchType.Readout, color = BenchColors.Dim)
  }
}
