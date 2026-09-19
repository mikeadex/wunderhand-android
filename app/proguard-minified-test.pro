# The minified *test* build only (app/build.gradle.kts). Never part of the store build.
#
# The emulator test drives the app through libraries that live in the app's APK, by name:
# the test runner, Compose's semantics tree, coroutines, the lifecycle. R8 removes whatever
# the app itself does not use, and the test then cannot start. So those libraries are kept
# whole here.
#
# What is being checked is everything else, and none of it is kept: every class under
# com.wunderhand — the API types, their generated serializers, the client, the view models —
# and kotlinx.serialization and OkHttp are minified exactly as the store build minifies them.
-keep class androidx.tracing.** { *; }
-keep class androidx.test.** { *; }
-keep class androidx.compose.** { *; }
-keep class androidx.lifecycle.** { *; }
-keep class androidx.activity.** { *; }
-keep class androidx.savedstate.** { *; }
-keep class androidx.core.** { *; }
-keep class androidx.concurrent.** { *; }
-keep class androidx.collection.** { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class com.google.common.util.concurrent.** { *; }
-dontwarn androidx.test.**
-dontwarn com.google.errorprone.annotations.**
