plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.detekt)
}

android {
  namespace = "com.lockpicking.bench"
  compileSdk = 37
  defaultConfig {
    applicationId = "com.lockpicking.bench"
    minSdk = 28
    targetSdk = 37
    versionCode = 1
    versionName = "1.0"
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    aidl = false
    buildConfig = false
    shaders = false
  }
  androidResources {
    // Both are already compact binary formats; storing them lets the loaders map them directly.
    noCompress += listOf("glb", "hdr")
  }

  lint {
    abortOnError = true
    checkDependencies = false
    // Version bumps are a separate chore; these go stale and need the network.
    disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    baseline = file("lint-baseline.xml")
  }

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }
}

kotlin {
  jvmToolchain(17)
}

detekt {
  buildUponDefaultConfig = true
  config.setFrom(rootProject.file("config/detekt/detekt.yml"))
  baseline = file("detekt-baseline.xml")
  source.setFrom("src/main/kotlin", "src/test/kotlin")
}

// detekt 1.23 is built against Kotlin 2.0; keep its own compiler off the project's newer one.
configurations.matching { it.name == "detekt" }.configureEach {
  resolutionStrategy.eachDependency {
    if (requested.group == "org.jetbrains.kotlin") useVersion("2.0.21")
  }
}

tasks.named("check") {
  dependsOn("detekt", ":spotlessCheck")
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))

  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.core.splashscreen)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.datastore.preferences)

  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  debugImplementation(libs.androidx.compose.ui.tooling)

  // Dependency injection
  implementation(platform(libs.koin.bom))
  implementation(libs.koin.android)
  implementation(libs.koin.androidx.compose)

  // 3D: SceneView on Google Filament
  implementation(libs.sceneview)

  testImplementation(libs.junit)
  testImplementation(platform(libs.koin.bom))
  testImplementation(libs.koin.test)
  testImplementation(libs.koin.test.junit4)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.org.json)
}
