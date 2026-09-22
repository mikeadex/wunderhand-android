/*
 * The baseline profile: which classes and methods to compile ahead of time,
 * so the store build starts and scrolls the diary without waiting for the
 * JIT. Generated, not written:
 *
 *   ./gradlew :app:generateReleaseBaselineProfile
 *
 * on a running emulator (API 33 or newer; no root needed). It installs the
 * release build under the app's real package name, signs in as the demo
 * shop, walks the diary and the client list, and writes what it saw to
 * app/src/release/generated/baselineProfiles/, which is committed. Regenerate
 * it when a screen on that walk changes shape; the app works without it,
 * only slower for the first minute.
 */
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "com.wunderhand.baselineprofile"
    compileSdk = libs.versions.compileSdk.get().toInt()

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        minSdk = 28
        targetSdk = libs.versions.targetSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"
}

kotlin {
    compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 }
}

baselineProfile {
    // Whatever emulator is running, rather than a Gradle-managed one it would have to download.
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
