package com.lockpicking.bench.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Frame timing while picking: the canvases, the HUD and the 3D scene under both thumbs. */
@RunWith(AndroidJUnit4::class)
class PickingBenchmark {
  @get:Rule
  val rule = MacrobenchmarkRule()

  @Test
  fun pickingNoCompilation() = picking(CompilationMode.None())

  @Test
  fun pickingBaselineProfile() = picking(CompilationMode.Partial(BaselineProfileMode.Require))

  private fun picking(compilationMode: CompilationMode) = rule.measureRepeated(
    packageName = TARGET_PACKAGE,
    metrics = listOf(FrameTimingMetric()),
    compilationMode = compilationMode,
    // Not StartupMode.COLD: that kills the app after setupBlock, under the measured strokes.
    // The setup kills it itself instead, so each iteration starts from a fresh process.
    startupMode = null,
    iterations = PICKING_ITERATIONS,
    setupBlock = {
      killProcess()
      pressHome()
      startActivityAndWait()
      waitForBench()
    },
  ) {
    pickTheLock()
  }

  private companion object {
    const val PICKING_ITERATIONS = 5
  }
}
