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
import com.lockpicking.bench.ui.bench.LockBenchViewModel
import com.lockpicking.bench.ui.theme.BenchTheme
import org.koin.androidx.viewmodel.ext.android.viewModel

private const val SPLASH_FADE_MILLIS = 220L

class MainActivity : ComponentActivity() {
  /** The same view model the bench screen gets: the activity is its owner. */
  private val bench: LockBenchViewModel by viewModel()

  override fun onCreate(savedInstanceState: Bundle?) {
    val splashScreen = installSplashScreen()
    super.onCreate(savedInstanceState)
    // Hold the splash until the bench knows which lock to open on.
    splashScreen.setKeepOnScreenCondition { !bench.uiState.value.restored }

    splashScreen.setOnExitAnimationListener { splashView ->
      splashView.view.animate()
        .alpha(0f)
        .setDuration(SPLASH_FADE_MILLIS)
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
