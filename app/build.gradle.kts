plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.dennis.aichat"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.dennis.aichat"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    packaging { jniLibs { useLegacyPackaging = true } }
}
dependencies {
    implementation("com.google.ai.edge.litertlm:litertlm-android:latest.release")
}
