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
        versionCode = 25
        versionName = "2.4.9"
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
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
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

fun connectedDeviceSerials(adb: String): List<String> =
    runAdb(adb, "devices").lineSequence()
        .map { it.trim() }
        .filter { it.endsWith("\tdevice") }
        .map { it.substringBefore("\t") }
        .toList()

fun isEmulator(adb: String, serial: String): Boolean {
    if (serial.startsWith("emulator-")) return true
    return try {
        val qemu = runAdb(adb, "-s", serial, "shell", "getprop", "ro.kernel.qemu")
        if (qemu == "1") return true
        val hardware = runAdb(adb, "-s", serial, "shell", "getprop", "ro.hardware")
        hardware.contains("goldfish", ignoreCase = true) || hardware.contains("ranchu", ignoreCase = true)
    } catch (_: GradleException) {
        false
    }
}

fun connectedPhysicalDevices(adb: String): List<String> =
    connectedDeviceSerials(adb).filterNot { isEmulator(adb, it) }

tasks.register("installDebugOnDevice") {
    group = "install"
    description = "Install debug APK on physical devices only (skips emulators)"
    doLast {
        val apk = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile
        if (!apk.exists()) throw GradleException("APK not found")
        val adb = resolveAdbPath()
        val physicalDevices = connectedPhysicalDevices(adb)
        if (physicalDevices.isEmpty()) {
            val allDevices = connectedDeviceSerials(adb)
            if (allDevices.isEmpty()) {
                logger.warn("No device connected — skipping install")
            } else {
                logger.warn("Only emulator(s) connected — skipping install (${allDevices.joinToString()})")
            }
            return@doLast
        }
        physicalDevices.forEach { serial ->
            runAdb(adb, "-s", serial, "install", "-r", apk.absolutePath)
            logger.lifecycle("Installed ${apk.name} on $serial")
        }
    }
}

afterEvaluate {
    tasks.named("assembleDebug").configure { finalizedBy("installDebugOnDevice") }
    tasks.named("installDebug").configure {
        enabled = false
        logger.lifecycle("installDebug is disabled — use assembleDebug (installs to physical devices only)")
    }
}