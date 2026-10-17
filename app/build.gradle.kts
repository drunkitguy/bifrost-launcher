plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.bifrost.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.bifrost.launcher"
        minSdk = 31
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }

    // Committed debug key: every CI build has the same signature, so updates install over each other.
    signingConfigs {
        getByName("debug") { storeFile = rootProject.file("debug.keystore") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
