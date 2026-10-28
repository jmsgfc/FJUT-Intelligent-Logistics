plugins {
    id("com.android.application")
}

android {
    namespace = "com.local.smartlogistics"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.local.smartlogistics"
        minSdk = 23
        targetSdk = 36
        versionCode = 27
        versionName = "1.8.8"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}
