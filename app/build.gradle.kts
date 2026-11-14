import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.cwtrainer.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.cwtrainer.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "2.1"
    }

    signingConfigs {
        // A fixed sideload key committed to the repo, so every build (local or CI) has the same
        // signature and installs over the previous one without wiping your progress.
        // It is NOT secret. Replace it before publishing anywhere.
        create("sideload") {
            storeFile = rootProject.file("keystore/sideload.jks")
            storePassword = "cwtrainer"
            keyAlias = "sideload"
            keyPassword = "cwtrainer"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sideload")
        }
        debug {
            signingConfig = signingConfigs.getByName("sideload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    lint {
        // Release builds should not fail on lint; run ./gradlew :app:lint when you want the report.
        checkReleaseBuilds = false
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}


dependencies {
    implementation(project(":core"))

    implementation(platform("androidx.compose:compose-bom:2025.12.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    implementation("androidx.room:room-runtime:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    implementation("androidx.datastore:datastore-preferences:1.1.7")

    // Home-screen widget
    implementation("androidx.glance:glance-appwidget:1.1.1")
}
