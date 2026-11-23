package com.lockpicking.bench.ui.bench

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.lockpicking.bench.R
import com.lockpicking.bench.data.glb.GlbInfo
import com.lockpicking.bench.ui.components.StatsPill
import kotlinx.coroutines.delay
import java.util.Locale

private const val REFRESH_MILLIS = 500L

/** SceneView renders on demand: this long without a frame means parked, not slow. */
private const val IDLE_AFTER_MILLIS = 600L

/** Presented-frame counter fed from SceneView's onFrame. Plain fields: read on a slow tick. */
class FrameMeter {
  var frames = 0
    private set
  var lastFrameAt = 0L
    private set

  fun onFrame() {
    frames++
    lastFrameAt = SystemClock.uptimeMillis()
  }
}

/**
 * Three lines of telemetry, refreshed twice a second.
 *
 * The state lives in here, in its own restart scope, on purpose: a changing fps number must
 * recompose this pill and nothing else. Hoisted into the screen it would recompose the
 * SceneView call twice a second, and the readout would be keeping alive the very render loop
 * it is measuring.
 */
@Composable
fun LiveStatsPill(
  modelInfo: GlbInfo?,
  meter: FrameMeter,
  telemetry: () -> BenchTelemetry?,
  modifier: Modifier = Modifier,
) {
  val pending = stringResource(R.string.stats_fps_pending)
  val idle = stringResource(R.string.stats_idle)
  val loading = stringResource(R.string.stats_loading)
  val readTelemetry by rememberUpdatedState(telemetry)
  val meshLine = modelInfo?.let { "%,d tris · %d draws".format(Locale.US, it.triangles, it.primitives) } ?: loading
  var liveLines by remember { mutableStateOf(listOf(pending, "")) }

  LaunchedEffect(meter) {
    var lastFrames = meter.frames
    var lastAt = SystemClock.uptimeMillis()
    while (true) {
      delay(REFRESH_MILLIS)
      val now = SystemClock.uptimeMillis()
      val fps = (meter.frames - lastFrames) * 1000f / (now - lastAt).coerceAtLeast(1)
      lastFrames = meter.frames
      lastAt = now
      val rate = if (now - meter.lastFrameAt > IDLE_AFTER_MILLIS) idle else "%.0f fps".format(Locale.US, fps)
      liveLines = listOf(rate, readTelemetry()?.describe() ?: loading)
    }
  }
  StatsPill(listOf(meshLine) + liveLines, modifier)
}

private fun BenchTelemetry.describe(): String =
  if (selectedPinNode != null) {
    "%s Δy %+.1f mm".format(Locale.US, selectedPinNode, selectedLiftMm)
  } else {
    "Core_Plug rz %+.1f°".format(Locale.US, plugDegrees)
  }
