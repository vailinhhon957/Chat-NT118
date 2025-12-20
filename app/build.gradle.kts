plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.google.gms.google-services")
}

android {
    namespace = "com.example.chat_compose"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.chat_compose"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(platform("com.google.firebase:firebase-bom:33.4.0"))
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-firestore-ktx")

    // Coil load ảnh (avatar)
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Navigation Compose
    implementation("androidx.navigation:navigation-compose:2.8.0")

    // ViewModel + livedata/ktx nếu cần
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.0")
    implementation("androidx.compose.material:material-icons-extended:1.7.5")
    implementation("io.github.webrtc-sdk:android:137.7151.05")
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("androidx.lifecycle:lifecycle-process:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))

    // Auth + Firestore (đang bị unresolved)
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-firestore-ktx")

    // Messaging (app bạn có)
    implementation("com.google.firebase:firebase-messaging-ktx")

    // Gemini (Firebase AI Logic)
    implementation("com.google.firebase:firebase-vertexai")
    implementation("com.google.firebase:firebase-storage-ktx")
// Dùng thư viện icon mở rộng để có hình cái Micro và Loa
    implementation("androidx.compose.material:material-icons-extended:1.5.4")
    implementation("com.google.mlkit:image-labeling:17.0.7")
    implementation("com.google.mlkit:smart-reply:17.0.4")
    implementation("com.google.android.gms:play-services-auth:20.7.0")
}