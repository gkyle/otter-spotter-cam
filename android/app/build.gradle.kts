plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kylescholz.otterspotter"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kylescholz.otterspotter"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.12"

        resValue(
            "string",
            "default_web_client_id",
            "945689943529-fmoh0kv2h8fad8dmtkq51hugg7oc3o6p.apps.googleusercontent.com"
        )
    }

    buildTypes {
        debug {
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        viewBinding = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // CameraX
    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // ONNX Runtime Android
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    // TensorFlow Lite
    val tfliteVersion = "2.16.1"
    implementation("org.tensorflow:tensorflow-lite:$tfliteVersion")
    implementation("org.tensorflow:tensorflow-lite-gpu:$tfliteVersion")
    implementation("org.tensorflow:tensorflow-lite-gpu-api:$tfliteVersion")

    // Google Sign-In & Credential Manager
    implementation("com.google.android.gms:play-services-auth:21.2.0")
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    // WorkManager (Background Sync)
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // OkHttp (REST API & Multipart Uploads)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Google Play Services Location (High-Accuracy GNSS/GPS)
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // AndroidX ExifInterface (GPS Metadata Verification)
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // Unit Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

