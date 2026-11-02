plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val signingFile = System.getenv("SIGNING_KEYSTORE_FILE")

android {
    namespace = "com.dennis.colourprofiles"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dennis.colourprofiles"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    signingConfigs {
        if (signingFile != null) {
            create("release") {
                storeFile = file(signingFile!!)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Left off on purpose: Shizuku starts ShellService by class name.
            isMinifyEnabled = false
            if (signingFile != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    lint {
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
