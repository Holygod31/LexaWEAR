plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.lexawear"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.lexawear"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    // Required for TFLite model file not to be compressed
    aaptOptions {
        noCompress += "tflite"
    }
}

dependencies {
    // Existing
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")
    implementation("androidx.gridlayout:gridlayout:1.0.0")

    // CameraX — live preview and image capture
    implementation("androidx.camera:camera-core:1.3.1")
    implementation("androidx.camera:camera-camera2:1.3.1")
    implementation("androidx.camera:camera-lifecycle:1.3.1")
    implementation("androidx.camera:camera-view:1.3.1")

    // ML Kit — image labeling (clothing type detection)
    implementation("com.google.mlkit:image-labeling:17.0.7")

    // ML Kit — text recognition (care label OCR)
    implementation("com.google.mlkit:text-recognition:16.0.0")

    // ML Kit — object detection (isolate clothing item in frame)
    implementation("com.google.mlkit:object-detection:17.0.1")

    // TensorFlow Lite — care symbol classifier (model dropped in later)
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
    implementation("org.tensorflow:tensorflow-lite-metadata:0.4.4")
}