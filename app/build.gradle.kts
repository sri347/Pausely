import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
val ks = Properties().apply { file("../keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
android {
    namespace = "app.pausely"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.pausely"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "1.4"
    }
    signingConfigs {
        create("release") {
            if (ks.isNotEmpty()) {
                storeFile = file("../" + ks.getProperty("storeFile"))
                storePassword = ks.getProperty("storePassword")
                keyAlias = ks.getProperty("keyAlias")
                keyPassword = ks.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
