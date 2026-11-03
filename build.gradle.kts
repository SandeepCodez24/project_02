// The Android Gradle Plugin goes on the root buildscript classpath (instead of the
// plugins block) so it can be skipped entirely in core-only builds, while still
// living in the same classloader as the Kotlin plugin, which KMP requires.
buildscript {
    val coreOnly = providers.gradleProperty("kharcha.coreOnly").orNull == "true"
    if (!coreOnly) {
        dependencies {
            classpath(libs.android.gradlePlugin)
        }
    }
}

plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.sqldelight) apply false
}
