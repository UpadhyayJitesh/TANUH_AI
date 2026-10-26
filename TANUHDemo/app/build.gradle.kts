plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.tanuh.demo"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tanuh.demo"
        minSdk = 24
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
    packaging {
        jniLibs {
            // OpenCV's trimmed AAR bundles libc++_shared.so only for x86; app/src/main/jniLibs supplies all ABIs.
            pickFirsts += "**/libc++_shared.so"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.vosk.android)
    implementation(libs.mediapipe.tasks.text)

    // SDK exploration: the candidate SDK module + the trimmed OpenCV runtime it compiles against.
    // app/libs/opencv-4140-android-trimmed.aar is the same trimmed AAR the avni tanuh flavour uses.
    implementation(project(":edgesdk"))
    implementation(files("libs/opencv-4140-android-trimmed.aar"))
    testImplementation(libs.junit)
    testImplementation(libs.json)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
