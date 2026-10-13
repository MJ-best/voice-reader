pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // sherpa-onnx (on-device TTS) is only published on JitPack; limit the repo to that group.
        maven("https://jitpack.io") { content { includeGroup("com.github.k2-fsa.sherpa-onnx") } }
    }
}
rootProject.name = "voice-reader"
include(":app", ":korean")
