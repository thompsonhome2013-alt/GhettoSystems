import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.ghettosystems.v2"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.ghettosystems.v2"
        minSdk = 31
        targetSdk = 36
        versionCode = 2
        versionName = "2.0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.biometric)
    implementation(libs.security.crypto)
    implementation(libs.okhttp)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.runtime)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

fun resolveAdbPath(): String {
    val localProperties = Properties()
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { stream -> localProperties.load(stream) }
    }
    val sdkDir = localProperties.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME")
    return if (sdkDir != null) File(sdkDir, "platform-tools/adb.exe").absolutePath else "adb"
}

fun runAdb(adb: String, vararg args: String): String {
    val process = ProcessBuilder(listOf(adb) + args).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()
    if (exitCode != 0) throw GradleException("adb failed: $output")
    return output.trim()
}

fun hasConnectedDevice(adb: String): Boolean =
    runAdb(adb, "devices").lineSequence().any { it.endsWith("\tdevice") }

tasks.register("installDebugOnDevice") {
    group = "install"
    doLast {
        val apk = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile
        if (!apk.exists()) throw GradleException("APK not found")
        val adb = resolveAdbPath()
        if (!hasConnectedDevice(adb)) {
            logger.warn("No device connected — skipping install")
            return@doLast
        }
        runAdb(adb, "install", "-r", apk.absolutePath)
        logger.lifecycle("Installed ${apk.name}")
    }
}

afterEvaluate {
    tasks.named("assembleDebug").configure { finalizedBy("installDebugOnDevice") }
}