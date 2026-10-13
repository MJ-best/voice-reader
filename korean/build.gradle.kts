import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin (no Android) so it can become a Kotlin Multiplatform module for iPad/Mac later.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
