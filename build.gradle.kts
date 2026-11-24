// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.android.test) apply false
  alias(libs.plugins.androidx.baselineprofile) apply false
  alias(libs.plugins.compose.compiler) apply false
  alias(libs.plugins.detekt) apply false
  alias(libs.plugins.spotless)
}

// Mirrors .editorconfig (IDE formatting) so Spotless and the IDE agree.
val ktlintRules =
  mapOf(
    "indent_size" to 2,
    "max_line_length" to 140,
    "ktlint_code_style" to "intellij_idea",
    // Composables are PascalCase by convention.
    "ktlint_function_naming_ignore_when_annotated_with" to "Composable",
    // The codebase lays out signatures and short if/else by hand; keep that.
    "ktlint_standard_class-signature" to "disabled",
    "ktlint_standard_function-signature" to "disabled",
    "ktlint_standard_multiline-if-else" to "disabled",
    "ktlint_standard_max-line-length" to "disabled",
  )

spotless {
  kotlin {
    target("app/src/**/*.kt", "baselineprofile/src/**/*.kt")
    ktlint(libs.versions.ktlint.get()).editorConfigOverride(ktlintRules)
  }
  kotlinGradle {
    target("*.gradle.kts", "app/*.gradle.kts", "baselineprofile/*.gradle.kts")
    ktlint(libs.versions.ktlint.get()).editorConfigOverride(ktlintRules)
  }
}
