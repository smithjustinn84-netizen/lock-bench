package com.lockpicking.bench.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records the code the two journeys run into the app's baseline profile.
 *
 * `./gradlew :app:generateReleaseBaselineProfile` runs this on a connected device (API 33+, or
 * a rooted one such as a userdebug emulator) and writes the result to
 * `app/src/main/generated/baselineProfiles/`.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
  @get:Rule
  val rule = BaselineProfileRule()

  @Test
  fun coldStart() = rule.collect(packageName = TARGET_PACKAGE, includeInStartupProfile = true) {
    pressHome()
    startActivityAndWait()
    waitForBench()
  }

  @Test
  fun picking() = rule.collect(packageName = TARGET_PACKAGE) {
    pressHome()
    startActivityAndWait()
    waitForBench()
    pickTheLock()
  }
}
