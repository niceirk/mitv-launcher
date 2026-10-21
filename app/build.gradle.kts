plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ru.nikita.tvhome"
    compileSdk = 34
    defaultConfig {
        applicationId = "ru.nikita.tvhome"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.2"
    }
    // ключ подписи хранится вне репозитория: все версии должны подписываться им, иначе обновление не встанет
    signingConfigs {
        create("tv") {
            storeFile = file(System.getProperty("user.home") + "/.config/mitv-launcher/sign.keystore")
            storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("tv")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
