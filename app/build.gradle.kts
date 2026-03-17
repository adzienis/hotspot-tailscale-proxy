import org.gradle.api.GradleException
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val ANDROID_NDK_VERSION = "29.0.14206865"

data class GoAndroidAbi(
    val androidAbi: String,
    val goArch: String,
    val goArm: String? = null,
    val requiresNdkToolchain: Boolean = false,
) {
    fun clangExecutableName(minSdk: Int): String =
        when (androidAbi) {
            "arm64-v8a" -> "aarch64-linux-android${minSdk}-clang"
            "armeabi-v7a" -> "armv7a-linux-androideabi${minSdk}-clang"
            "x86" -> "i686-linux-android${minSdk}-clang"
            "x86_64" -> "x86_64-linux-android${minSdk}-clang"
            else -> error("Unsupported Android ABI: $androidAbi")
        }
}

fun Project.resolveAndroidSdkDir(): File {
    val localProperties = rootProject.file("local.properties")
    if (localProperties.isFile) {
        val properties = Properties().apply {
            localProperties.inputStream().use(::load)
        }
        properties.getProperty("sdk.dir")
            ?.takeIf { it.isNotBlank() }
            ?.let { return file(it) }
    }
    System.getenv("ANDROID_SDK_ROOT")
        ?.takeIf { it.isNotBlank() }
        ?.let { return file(it) }
    System.getenv("ANDROID_HOME")
        ?.takeIf { it.isNotBlank() }
        ?.let { return file(it) }
    throw GradleException(
        "Android SDK not found. Set ANDROID_SDK_ROOT/ANDROID_HOME or create local.properties with sdk.dir.",
    )
}

val supportedGoAbis = listOf(
    GoAndroidAbi(androidAbi = "arm64-v8a", goArch = "arm64"),
    GoAndroidAbi(androidAbi = "armeabi-v7a", goArch = "arm", goArm = "7", requiresNdkToolchain = true),
    GoAndroidAbi(androidAbi = "x86", goArch = "386", requiresNdkToolchain = true),
    GoAndroidAbi(androidAbi = "x86_64", goArch = "amd64", requiresNdkToolchain = true),
)

val releaseKeystorePath = providers.gradleProperty("android.release.keystore.path")
    .orElse(providers.environmentVariable("ANDROID_KEYSTORE_PATH"))
val releaseKeystorePassword = providers.gradleProperty("android.release.keystore.password")
    .orElse(providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD"))
val releaseKeyAlias = providers.gradleProperty("android.release.key.alias")
    .orElse(providers.environmentVariable("ANDROID_KEY_ALIAS"))
val releaseKeyPassword = providers.gradleProperty("android.release.key.password")
    .orElse(providers.environmentVariable("ANDROID_KEY_PASSWORD"))

val androidSdkDir = rootProject.resolveAndroidSdkDir()
val goSourceDir = layout.projectDirectory.dir("../upstream-proxyt")
val generatedGoJniLibsDir = layout.buildDirectory.dir("generated/jniLibs/proxyt")
val buildGoAndroidBinaries = tasks.register<BuildGoAndroidBinariesTask>("buildGoAndroidBinaries") {
    group = "build"
    description = "Builds the bundled Go proxy binary for all supported Android ABIs."
    sourceDir.set(goSourceDir)
    sdkDir.set(androidSdkDir)
    outputDir.set(generatedGoJniLibsDir)
    abiMatrix.set(
        supportedGoAbis.map { abi ->
            listOf(
                abi.androidAbi,
                abi.goArch,
                abi.goArm.orEmpty(),
                abi.requiresNdkToolchain.toString(),
            ).joinToString("|")
        },
    )
    ndkVersion.set(ANDROID_NDK_VERSION)
    minSdkApi.set(28)
}

android {
    namespace = "io.proxyt.hotspot"
    compileSdk = 35
    ndkVersion = ANDROID_NDK_VERSION

    defaultConfig {
        applicationId = "io.proxyt.hotspot"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += supportedGoAbis.map { it.androidAbi }
        }
    }

    sourceSets.getByName("main").jniLibs.srcDir(generatedGoJniLibsDir)

    signingConfigs {
        create("release") {
            val keystore = releaseKeystorePath.orNull
            if (!keystore.isNullOrBlank()) {
                storeFile = rootProject.file(keystore)
                storePassword = releaseKeystorePassword.orNull
                keyAlias = releaseKeyAlias.orNull
                keyPassword = releaseKeyPassword.orNull
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile != null) {
                signingConfig = releaseSigning
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    buildFeatures {
        buildConfig = true
    }
}

tasks.named("preBuild") {
    dependsOn(buildGoAndroidBinaries)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
    jvmToolchain(17)
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("com.google.android.material:material:1.12.0")

    testImplementation("junit:junit:4.13.2")
}
