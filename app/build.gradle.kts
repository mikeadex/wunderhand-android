import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/*
 * Where Firebase is, for push: `firebase.properties` beside this project, never in git, written
 * from the console's google-services.json by scripts/firebase-config.sh. With no such file every
 * field is empty, push is off, and nothing else notices — the same app, on a machine that has
 * never heard of the Firebase project.
 */
val firebase = Properties().also { props ->
    rootProject.file("firebase.properties").takeIf { it.exists() }?.inputStream()?.use { props.load(it) }
}
fun firebaseField(name: String): String = "\"" + (firebase.getProperty(name) ?: "").replace("\\", "").replace("\"", "") + "\""

android {
    namespace = "com.wunderhand.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        // The same as the iPhone app's bundle id, on purpose.
        applicationId = "com.wunderhand.app"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "FIREBASE_PROJECT_ID", firebaseField("projectId"))
        buildConfigField("String", "FIREBASE_API_KEY", firebaseField("apiKey"))
        buildConfigField("String", "FIREBASE_SENDER_ID", firebaseField("senderId"))
    }

    buildTypes {
        debug {
            // Beside the store build on one phone, not instead of it.
            applicationIdSuffix = ".debug"
            // 10.0.2.2 is the emulator's name for this Mac's localhost, where
            // chairtime's dev server listens. The sign-in screen can point a
            // real phone somewhere else.
            buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2:3100\"")
            // Firebase knows the debug build as its own app, because it has its own package name.
            buildConfigField("String", "FIREBASE_APP_ID", firebaseField("debug.appId"))
        }
        release {
            buildConfigField("String", "API_BASE_URL", "\"https://wunderhand.com\"")
            buildConfigField("String", "FIREBASE_APP_ID", firebaseField("release.appId"))
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }
}

kotlin {
    compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":network"))
    implementation(project(":design"))
    implementation(libs.firebase.messaging)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment)
    implementation(libs.compose.material3.adaptive.navigation.suite)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    debugImplementation(libs.compose.ui.test.manifest)
}
