package com.lockpicking.bench

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.graphics.Color
import android.os.Bundle
import android.view.animation.AccelerateInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.lockpicking.bench.ui.Bench
import com.lockpicking.bench.ui.LockBenchScreen

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    val splashScreen = installSplashScreen()
    super.onCreate(savedInstanceState)

    splashScreen.setOnExitAnimationListener { splashView ->
      splashView.view.animate()
        .alpha(0f)
        .setDuration(220L)
        .setInterpolator(AccelerateInterpolator())
        .setListener(object : AnimatorListenerAdapter() {
          override fun onAnimationEnd(animation: Animator) {
            splashView.remove()
          }
        })
        .start()
    }

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
