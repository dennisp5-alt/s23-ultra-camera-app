plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.example.autophoto"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.example.autophoto.s23u"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.2-s23u-enhanced"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
