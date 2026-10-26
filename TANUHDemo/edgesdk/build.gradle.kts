plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// LiteRT is optional so the ONNX path always builds. Turn it on with `edgesdk.litert=true` in
// gradle.properties (or -Pedgesdk.litert=true). EdgeSession finds LiteRtEngine by reflection.
val withLiteRt = (findProperty("edgesdk.litert") as String?)?.toBoolean() ?: false

android {
    namespace = "com.tanuh.edgesdk"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    sourceSets {
        getByName("main") {
            if (withLiteRt) java.srcDir("src/litert/java")
        }
    }
}

dependencies {
    // Inference runtimes. ONNX Runtime runs the current .onnx MViT2 folds; LiteRT (CompiledModel)
    // is here to test the "load by path / mmap" recommendation once .tflite exports exist.
    implementation(libs.onnxruntime.android)
    if (withLiteRt) implementation(libs.litert)

    // OpenCV is compileOnly: a library module cannot embed a local .aar. The host app supplies it
    // at runtime (app/build.gradle.kts -> implementation(files("libs/opencv-4140-android-trimmed.aar"))).
    compileOnly(files("../app/libs/opencv-4140-android-trimmed.aar"))

    testImplementation(libs.junit)
    testImplementation(libs.json)
}
