package com.lockpicking.bench

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import com.lockpicking.bench.ui.Bench
import com.lockpicking.bench.ui.LockBenchScreen

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // The bench is always dark: light system bar icons over the 3D surface.
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
    )
    setContent {
      MaterialTheme(colorScheme = darkColorScheme(primary = Bench.Brass, background = Bench.Ink, surface = Bench.Ink)) {
        LockBenchScreen()
      }
    }
  }
}
