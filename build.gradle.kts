plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}

tasks.register("buildAndroidGoBinaries") {
    group = "build"
    description = "Build the bundled Go proxy binaries for the Android app."
    dependsOn(":app:buildGoAndroidBinaries")
}

tasks.register("assembleAndroidDebug") {
    group = "build"
    description = "Assemble the Android debug APK."
    dependsOn(":app:assembleDebug")
}

tasks.register("assembleAndroidRelease") {
    group = "build"
    description = "Assemble the Android release APK."
    dependsOn(":app:assembleRelease")
}

tasks.register("verifyAndroidPr") {
    group = "verification"
    description = "Run the Android verification tasks expected on pull requests."
    dependsOn(":app:testDebugUnitTest", ":app:lintDebug", ":app:assembleDebug")
}
