package com.lockpicking.bench.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.lockpicking.bench.ui.theme.BenchColors

/** Darkens the corners over the 3D surface so the lit lock sits in a pool of light. */
@Composable
fun Vignette(modifier: Modifier = Modifier) {
  Spacer(
    modifier.fillMaxSize().drawWithCache {
      // Only a new size changes the gradient.
      val shade = Brush.radialGradient(
        colors = listOf(Color.Transparent, BenchColors.VignetteEdge),
        center = Offset(size.width / 2f, size.height * 0.42f),
        radius = size.maxDimension * 0.72f,
      )
      onDrawBehind { drawRect(shade) }
    },
  )
}
