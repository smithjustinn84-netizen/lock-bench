package com.lockpicking.bench.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A dark instrument panel: ink, hairlines, and one brass accent. */
object BenchColors {
  val Ink = Color(0xFF0B0D10)
  val Panel = Color(0xE6101318)
  val Hairline = Color(0xFF2B3038)
  val Text = Color(0xFFDCDFE4)
  val Dim = Color(0xFF858B96)
  val Brass = Color(0xFFE3B45A)
  val BrassWash = Color(0x24E3B45A)
  val Nickel = Color(0xFFB9BDC4)
  val Bronze = Color(0xFFB8733A)
  val Set = Color(0xFF62D391)
  val Fault = Color(0xFFEC6A55)
  val Chamber = Color(0x14FFFFFF)
  val VignetteEdge = Color(0x99000000)
}

object BenchType {
  val Label = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Medium)
  val Readout = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp)
  val Headline = Readout.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
  val Small = Readout.copy(fontSize = 11.sp, lineHeight = 15.sp)
}

object BenchShapes {
  val Panel = RoundedCornerShape(10.dp)
  val Control = RoundedCornerShape(8.dp)
}

/** The bench is always dark. */
@Composable
fun BenchTheme(content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = darkColorScheme(primary = BenchColors.Brass, background = BenchColors.Ink, surface = BenchColors.Ink),
    content = content,
  )
}
