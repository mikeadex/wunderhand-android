import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.baselineprofile)
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

/*
 * The upload key: `keystore.properties` beside this project, never in git (nor is the .jks). Google
 * holds the key that signs what phones install (Play App Signing); this one only proves an upload
 * is yours, and can be replaced if it is ever lost. With no such file the release build is simply
 * unsigned, as it is on any machine but the one that publishes. See PLAYSTORE.md.
 */
val keystore = Properties().also { props ->
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { props.load(it) }
}

android {
    namespace = "com.wunderhand.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        // The same as the iPhone app's bundle id, on purpose.
        applicationId = "com.wunderhand.app"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 2
        versionName = "1.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "FIREBASE_PROJECT_ID", firebaseField("projectId"))
        buildConfigField("String", "FIREBASE_API_KEY", firebaseField("apiKey"))
        buildConfigField("String", "FIREBASE_SENDER_ID", firebaseField("senderId"))
    }

    signingConfigs {
        if (keystore.getProperty("storeFile") != null) create("upload") {
            storeFile = rootProject.file(keystore.getProperty("storeFile"))
            storePassword = keystore.getProperty("storePassword")
            keyAlias = keystore.getProperty("keyAlias")
            keyPassword = keystore.getProperty("keyPassword")
        }
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
            signingConfig = signingConfigs.findByName("upload")
            buildConfigField("String", "FIREBASE_APP_ID", firebaseField("release.appId"))
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        /*
         * The store build in every way that can break it — R8, resource shrinking, not debuggable —
         * but aimed at chairtime's dev server on this Mac and signed with the debug key, so the
         * emulator test can be run against it:
         *
         *   ./gradlew :app:connectedMinifiedAndroidTest -Pminified
         *
         * "It decoded in debug" is the classic way an Android release breaks: R8 renames or removes
         * what a serializer needed, and the first anybody hears of it is a blank screen in the store
         * build. The emulator test signs in and walks every screen, so every reply chairtime sends is
         * decoded by minified code. Never uploaded anywhere: its own application id, and no way to be
         * pointed at production.
         */
        create("minified") {
            initWith(getByName("release"))
            applicationIdSuffix = ".minified"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            // The libraries the test harness drives by name; the app's own code is kept by nothing.
            proguardFile("proguard-minified-test.pro")
            buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2:3100\"")
            buildConfigField("String", "FIREBASE_APP_ID", "\"\"")
        }
    }
    // The emulator test runs against the debug build, unless asked for the minified one.
    testBuildType = if (project.hasProperty("minified")) "minified" else "debug"

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
    // Installs the baseline profile in app/src/release/generated/baselineProfiles/ on first run.
    implementation(libs.androidx.profileinstaller)
    "baselineProfile"(project(":baselineprofile"))
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
