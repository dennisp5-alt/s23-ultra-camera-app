plugins {
 id("com.android.application")
 id("org.jetbrains.kotlin.android")
 id("org.jetbrains.kotlin.plugin.compose")
}
android {
 namespace = "com.dennisp.s23lab"
 compileSdk = 36
 defaultConfig {
  // One stable id from v0.4 on, so later versions upgrade in place and keep the imported model.
  // (v0.1 to v0.3 used .v1/.v2/.v3; this installs alongside them.)
  applicationId = "com.dennisp.s23lab"
  minSdk = 26
  targetSdk = 35
  versionCode = 5
  versionName = "0.4.1"
 }
 // A fixed key lets builds upgrade in place. CI supplies it from repository secrets when they exist;
 // without them the build falls back to the runner's random debug key (uninstall needed between builds).
 val labStore = System.getenv("LAB_SIGNING_STORE_FILE")
 signingConfigs {
  if (!labStore.isNullOrBlank()) create("lab") {
   storeFile = file(labStore)
   storePassword = System.getenv("LAB_SIGNING_STORE_PASSWORD")
   keyAlias = System.getenv("LAB_SIGNING_KEY_ALIAS")
   keyPassword = System.getenv("LAB_SIGNING_KEY_PASSWORD")
  }
 }
 buildTypes {
  getByName("debug") { if (!labStore.isNullOrBlank()) signingConfig = signingConfigs.getByName("lab") }
 }
 compileOptions {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
 }
 kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
 buildFeatures { compose = true; buildConfig = true }
 packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}
dependencies {
 implementation(platform("androidx.compose:compose-bom:2025.09.00"))
 implementation("androidx.compose.ui:ui")
 implementation("androidx.compose.foundation:foundation")
 implementation("androidx.compose.material3:material3")
 implementation("androidx.activity:activity-compose:1.13.0")
 implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
 implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
 implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
 implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
 implementation("com.google.ai.edge.litertlm:litertlm-android:0.19.0")
 testImplementation("junit:junit:4.13.2")
 // Real JSON parser for checking exported files in JVM unit tests (Android's org.json is a stub there).
 testImplementation("org.json:json:20240303")
}
