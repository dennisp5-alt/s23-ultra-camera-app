plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.example.autophoto"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.example.autophoto.studioclean.v049"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.4.9-studio-clean"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    androidResources { noCompress += "tflite" }
}
dependencies {
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
