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
        versionCode = 2
        versionName = "0.2.0-glb"
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

    packaging {
        resources.excludes += "META-INF/*"
    }
}

dependencies {
    implementation("com.google.android.filament:filament-android:1.9.3")
    implementation("com.google.android.filament:gltfio-android:1.9.3")
    implementation("com.google.android.filament:filament-utils-android:1.9.3")
}

kotlin {
    jvmToolchain(17)
}
