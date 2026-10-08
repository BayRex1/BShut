plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bayrex.bgame"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bayrex.bgame"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }
}

kotlin {
    jvmToolchain(17)
}
