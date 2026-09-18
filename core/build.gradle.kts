import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin on the JVM: the API's types, money, the shop's clock and the
// words. No Android import belongs here, which is what lets its tests run
// without an emulator.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget = JvmTarget.JVM_17 }
}

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
