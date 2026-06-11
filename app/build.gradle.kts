plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.shelfie"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.shelfie"
        minSdk = 26
        targetSdk = 35
        versionCode = 14
        versionName = "0.8.4"
    }

    // Shared, committed keystore so every CI build signs identically and
    // installed builds update in place. Not a secret — it only provides
    // update continuity for sideloaded APKs.
    signingConfigs {
        create("shared") {
            storeFile = rootProject.file("signing/shelfie.keystore")
            storePassword = "shelfie"
            keyAlias = "shelfie"
            keyPassword = "shelfie"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.mediarouter)
    implementation(libs.play.services.cast.framework)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.common)
    implementation(libs.media3.cast)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.coil.compose)
}
