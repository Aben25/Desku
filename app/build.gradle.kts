import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Debug builds may carry a default Anthropic API key from local.properties
// (anthropic.apiKey=sk-ant-...), so a freshly installed desk phone works without typing a
// key on its keyboard. Release builds never embed one: the key is pasted in Settings and
// kept in the app's private storage.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}
val debugApiKey = localProps.getProperty("anthropic.apiKey", "")
// Debug builds can also preload the Desku engine address and device token
// (desku.serverUrl=ws://…:8787, desku.deviceToken=…), so the kiosk connects on first launch.
val debugServerUrl = localProps.getProperty("desku.serverUrl", "")
val debugDeviceToken = localProps.getProperty("desku.deviceToken", "")
// Admin PIN to leave the locked kiosk (device-owner mode). Kept in local.properties, never in git.
val kioskPin = localProps.getProperty("desku.kioskPin", "")

android {
    namespace = "com.deskbuddy"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.deskbuddy"
        // Android 8.0. The Anthropic Java SDK's Jackson needs API 26; that still covers
        // phones from 2017 onward, which is most of what sits in drawers.
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "DEFAULT_API_KEY", "\"\"")
        buildConfigField("String", "DEFAULT_SERVER_URL", "\"\"")
        buildConfigField("String", "DEFAULT_DEVICE_TOKEN", "\"\"")
        buildConfigField("String", "KIOSK_PIN", "\"$kioskPin\"")
    }

    buildTypes {
        debug {
            buildConfigField("String", "DEFAULT_API_KEY", "\"$debugApiKey\"")
            buildConfigField("String", "DEFAULT_SERVER_URL", "\"$debugServerUrl\"")
            buildConfigField("String", "DEFAULT_DEVICE_TOKEN", "\"$debugDeviceToken\"")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        // android.util.Log and friends return defaults in JVM tests instead of throwing.
        unitTests.isReturnDefaultValues = true
    }
    packaging {
        resources.excludes += listOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
            "/META-INF/INDEX.LIST",
            "/META-INF/versions/9/OSGI-INF/MANIFEST.MF",
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)

    // The brain: Claude through the official Java SDK (Kotlin uses the Java SDK).
    implementation(libs.anthropic.java)

    // The Desku engine link (WebSocket to server/).
    implementation(libs.okhttp)

    // Video loops of the user's own face (LivePortrait renders) in place of the drawn Desku.
    implementation(libs.media3.exoplayer)

    // On-device wake phrase. Vosk ships its natives as an AAR and needs JNA's AAR, not the jar.
    implementation("com.alphacephei:vosk-android:${libs.versions.vosk.get()}@aar")
    implementation("net.java.dev.jna:jna:5.18.1@aar")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    // Android's org.json is a stub in JVM unit tests; this is the real one.
    testImplementation(libs.org.json)
}
