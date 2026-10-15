import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.voicereader"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.voicereader"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "0.1.4"
        // sherpa-onnx ships ~30MB of native code per ABI; phones and the Apple-silicon emulator are arm64.
        ndk { abiFilters += "arm64-v8a" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true // required by Readium
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        optIn.addAll(
            "org.readium.r2.shared.ExperimentalReadiumApi",
            "kotlinx.coroutines.ExperimentalCoroutinesApi",
            "kotlinx.coroutines.FlowPreview",
        )
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation(project(":korean"))
    implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v1.13.8")

    // 3.4.0 needs compileSdk 37 + AGP 9; 3.3.0 fits the installed SDK 36 / AGP 8.13.
    val readium = "3.3.0"
    implementation("org.readium.kotlin-toolkit:readium-shared:$readium")
    implementation("org.readium.kotlin-toolkit:readium-streamer:$readium")
    implementation("org.readium.kotlin-toolkit:readium-navigator:$readium")
    implementation("org.readium.kotlin-toolkit:readium-navigator-media-tts:$readium")

    // Readium declares these as runtime-only; we call them directly.
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.media3:media3-session:1.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
}
