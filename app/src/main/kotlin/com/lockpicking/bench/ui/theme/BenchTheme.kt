package com.lockpicking.bench.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A dark instrument panel: ink, bevelled gunmetal, and one brass accent. */
object BenchColors {
  val Ink = Color(0xFF0B0D10)
  val Panel = Color(0xE6101318)

  /** The two ends of a panel's fill: lit from above, falling off to ink. */
  val PanelTop = Color(0xEE1B2028)
  val PanelBottom = Color(0xEE0C0F13)

  /** The lit top edge of a bevel. */
  val Bevel = Color(0x33FFFFFF)
  val Hairline = Color(0xFF2B3038)
  val Text = Color(0xFFDCDFE4)
  val Dim = Color(0xFF858B96)
  val Brass = Color(0xFFE3B45A)
  val BrassLight = Color(0xFFF6D58E)
  val BrassDeep = Color(0xFFA8792C)
  val BrassWash = Color(0x24E3B45A)
  val Nickel = Color(0xFFB9BDC4)
  val NickelLight = Color(0xFFE6E9EE)
  val Bronze = Color(0xFFB8733A)
  val Set = Color(0xFF62D391)
  val Fault = Color(0xFFEC6A55)
  val Chamber = Color(0x14FFFFFF)
  val VignetteEdge = Color(0x99000000)
}

object BenchType {
  val Label = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Medium)
  val Readout = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp)

  /** The status headline: a heavy sans, the one loud voice on the HUD. */
  val Headline = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontSize = 22.sp,
    lineHeight = 26.sp,
    fontWeight = FontWeight.Black,
    letterSpacing = 1.sp,
  )
  val Small = Readout.copy(fontSize = 11.sp, lineHeight = 15.sp)

  /** Buttons: the label face, a touch heavier. */
  val Button = Label.copy(fontWeight = FontWeight.Bold)

  /** The running clock and the time on the results card. */
  val Clock = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 26.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold)

  /** The banner that goes up when the shackle releases. */
  val Banner = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 54.sp, fontWeight = FontWeight.Black, letterSpacing = 6.sp)
}

object BenchShapes {
  val Panel = RoundedCornerShape(14.dp)
  val Control = RoundedCornerShape(10.dp)
}

/** Fills and edges made once and shared by every panel and button. */
object BenchBrushes {
  val Panel = Brush.verticalGradient(listOf(BenchColors.PanelTop, BenchColors.PanelBottom))
  val PanelEdge = Brush.verticalGradient(listOf(Color(0xFF3A414C), BenchColors.Hairline, Color(0xFF15181D)))
  val Brass = Brush.verticalGradient(listOf(BenchColors.BrassLight, BenchColors.Brass, BenchColors.BrassDeep))
  val BrassEdge = Brush.verticalGradient(listOf(BenchColors.BrassLight, BenchColors.BrassDeep))
  val BrassWash = Brush.verticalGradient(listOf(Color(0x40E3B45A), Color(0x12E3B45A)))
  val Key = Brush.verticalGradient(listOf(Color(0x14FFFFFF), Color(0x05FFFFFF)))
}

/** The bench is always dark. */
@Composable
fun BenchTheme(content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = darkColorScheme(primary = BenchColors.Brass, background = BenchColors.Ink, surface = BenchColors.Ink),
    content = content,
  )
}
