plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
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

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))

  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.core.splashscreen)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.activity.compose)

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
