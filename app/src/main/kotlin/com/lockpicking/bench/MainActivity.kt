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
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.lockpicking.bench.ui.bench.LockBenchRoute
import com.lockpicking.bench.ui.theme.BenchTheme

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
      BenchTheme {
        LockBenchRoute()
      }
    }
  }
}
