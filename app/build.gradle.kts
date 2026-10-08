plugins { id("com.android.application") }

android {
    namespace = "com.dennis.pixelrepair"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.dennis.pixelrepair.labv3"
        minSdk = 29
        targetSdk = 34
        versionCode = 3
        versionName = "0.3.0"
    }
    buildTypes {
        getByName("release") { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
}
