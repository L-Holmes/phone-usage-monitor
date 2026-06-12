plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.example.webtrafficmonitor"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.webtrafficmonitor"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Runs instrumented (androidTest) tests on a connected device.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
    }

    androidResources {
        // The ONNX model must stay uncompressed in the APK so ONNX Runtime can
        // mmap it from disk (otherwise it would inflate into the Java heap).
        noCompress += "onnx"
    }

    buildTypes {
        debug {
            // Testing builds keep only recent data and seed nothing.
            buildConfigField("boolean", "IS_TESTING", "true")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("boolean", "IS_TESTING", "false")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Local on-device storage for the monitored list.
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Loads screenshot thumbnails into the list efficiently.
    implementation("io.coil-kt:coil:2.7.0")

    // On-device NSFW image scoring. Same engine + exact version (1.22.0) as the
    // IMAGE_ANALYSER Rust harness, so on-phone scores match the desktop reference.
    // Bundles libonnxruntime.so for every phone ABI automatically. (MIT licensed.)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")

    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}

// ---------------------------------------------------------------------------
// Stage the NSFW classifier model into app assets at build time.
//
// The model (adamcodd-vit-nsfw, ~329 MB) lives in the IMAGE_ANALYSER Rust
// project and is git-ignored (large + regenerable). Rather than duplicate it
// into version control, we copy it — plus its preproc.json sidecar and the
// shared thresholds.json — into src/main/assets/nsfw/ so they ship in the APK.
// The staged copy is also git-ignored. Single source of truth, clean repo.
//
// Gradle's up-to-date checks mean the 329 MB file is only copied when it
// actually changes; normal rebuilds skip it.
// ---------------------------------------------------------------------------
val stageNsfwModel = tasks.register<Copy>("stageNsfwModel") {
    description = "Copy the NSFW ONNX model + sidecars into src/main/assets/nsfw."
    // Ship the quantized INT8 model (small + fast on-device). The full-precision
    // model is NOT shipped — on-device it was 3-6x slower and couldn't keep up,
    // while INT8 matched it on the test set. Falls back to the full-precision model
    // only if the INT8 one hasn't been generated (scripts/quantize_int8.py).
    val int8Dir = rootProject.file("IMAGE_ANALYSER/models/adamcodd-vit-nsfw-int8")
    val fp32Dir = rootProject.file("IMAGE_ANALYSER/models/adamcodd-vit-nsfw")
    val modelDir = if (int8Dir.resolve("model.onnx").exists()) int8Dir else fp32Dir
    from(modelDir) { include("model.onnx", "preproc.json") }
    from(rootProject.file("IMAGE_ANALYSER/models")) { include("thresholds.json") }
    into(layout.projectDirectory.dir("src/main/assets/nsfw"))
    doFirst { logger.lifecycle("Staging NSFW model from ${modelDir.name}") }
    onlyIf {
        val present = modelDir.resolve("model.onnx").exists()
        if (!present) {
            logger.warn(
                "⚠ NSFW model not found at $modelDir/model.onnx — run " +
                "IMAGE_ANALYSER/setup.sh (and scripts/quantize_int8.py). The app will " +
                "still build, but on-device image scoring will be disabled (scores null)."
            )
        }
        present
    }
}

// Make sure staging happens before assets are merged into the APK.
tasks.named("preBuild") { dependsOn(stageNsfwModel) }
