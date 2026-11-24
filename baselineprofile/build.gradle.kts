plugins {
  alias(libs.plugins.android.test)
  alias(libs.plugins.androidx.baselineprofile)
  alias(libs.plugins.detekt)
}

android {
  namespace = "com.lockpicking.bench.baselineprofile"
  compileSdk = 37

  defaultConfig {
    minSdk = 28
    targetSdk = 37
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  targetProjectPath = ":app"

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

kotlin {
  jvmToolchain(17)
}

// The plugin mirrors every non-debuggable app build type, :app's own `benchmark` included,
// but has no app variant to pair that copy with and falls back to unsigned `release`. The
// benchmarks run on the plugin's benchmarkRelease: R8, debug signing, not debuggable, the
// same build `benchmark` describes.
androidComponents {
  beforeVariants { variant ->
    if (variant.buildType?.endsWith("Benchmark") == true) variant.enable = false
  }
}

baselineProfile {
  // The attached emulator or phone; no managed device.
  useConnectedDevices = true
}

detekt {
  buildUponDefaultConfig = true
  config.setFrom(rootProject.file("config/detekt/detekt.yml"))
  source.setFrom("src/main/kotlin")
}

configurations.matching { it.name == "detekt" }.configureEach {
  resolutionStrategy.eachDependency {
    if (requested.group == "org.jetbrains.kotlin") useVersion("2.0.21")
  }
}

tasks.named("check") {
  dependsOn("detekt")
}

dependencies {
  implementation(libs.androidx.test.ext.junit)
  implementation(libs.androidx.uiautomator)
  implementation(libs.androidx.benchmark.macro.junit4)
}
