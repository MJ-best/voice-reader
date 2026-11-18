import java.util.Properties
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
        versionCode = 7
        versionName = "0.1.6"
        // sherpa-onnx ships ~30MB of native code per ABI; phones and the Apple-silicon emulator are arm64.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildFeatures { buildConfig = true }

    // JVM tests have no libvoicereader_core.so: VoiceReaderNative logs and falls back to Kotlin, so Log must not throw.
    testOptions { unitTests.isReturnDefaultValues = true }

    // github: the sideloaded APK (GitHub Releases), may ask for "all files access" to find every EPUB on the device.
    // play: the Google Play build. Play only allows MANAGE_EXTERNAL_STORAGE for file managers and the like,
    // so this build drops it and adds books through the system file picker instead.
    flavorDimensions += "store"
    productFlavors {
        create("github") {
            dimension = "store"
            buildConfigField("boolean", "ALL_FILES_ACCESS", "true")
        }
        create("play") {
            dimension = "store"
            buildConfigField("boolean", "ALL_FILES_ACCESS", "false")
        }
    }

    // Upload key for Google Play, read from keystore.properties (git-ignored). Without it, release builds stay unsigned.
    val keystoreProps = rootProject.file("keystore.properties")
    signingConfigs {
        if (keystoreProps.exists()) {
            val props = Properties().apply { keystoreProps.inputStream().use { load(it) } }
            create("upload") {
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        getByName("release") {
            if (keystoreProps.exists()) signingConfig = signingConfigs.getByName("upload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true // required by Readium
    }

    sourceSets {
        getByName("main").jniLibs.srcDir("src/main/jniLibs")
    }
}

val buildRustCore = tasks.register("buildRustCore") {
    val rustDir = rootProject.file("rust")
    val jniOutDir = project.file("src/main/jniLibs")
    val prebuilt = project.file("src/main/jniLibs/arm64-v8a/libvoicereader_core.so")
    inputs.dir(rustDir.resolve("voicereader-core/src"))
    inputs.file(rustDir.resolve("Cargo.toml"))
    outputs.file(prebuilt)
    doLast {
        val hasNdk = !System.getenv("ANDROID_NDK_HOME").isNullOrBlank() ||
            !System.getenv("ANDROID_HOME").isNullOrBlank()
        val cargoNdkOk = try {
            ProcessBuilder("cargo", "ndk", "--version")
                .redirectErrorStream(true)
                .start()
                .waitFor() == 0
        } catch (_: Exception) {
            false
        }
        if (hasNdk && cargoNdkOk) {
            project.exec {
                workingDir = rustDir
                commandLine(
                    "cargo", "ndk", "-t", "arm64-v8a",
                    "-o", jniOutDir.absolutePath,
                    "build", "--release", "-p", "voicereader-core",
                )
            }
        } else if (!prebuilt.exists()) {
            logger.warn(
                "Skipping buildRustCore (install cargo-ndk and Android NDK, or run: " +
                    "cd rust && cargo ndk -t arm64-v8a -o ../app/src/main/jniLibs build --release -p voicereader-core). " +
                    "Kotlin fallbacks will be used unless libvoicereader_core.so is packaged.",
            )
        }
    }
}

tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("JniLibFolders")) {
        dependsOn(buildRustCore)
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
