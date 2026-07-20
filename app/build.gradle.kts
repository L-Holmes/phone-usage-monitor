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


    androidResources { noCompress += "gz" }

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

    // Plain JVM unit tests. BorderlineScorer is pure Kotlin (no Android), so the content
    // filter can be tested properly on the host - see BorderlineScorerTest.
    testImplementation("junit:junit:4.13.2")

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

// ---------------------------------------------------------------------------
// checkTranslations — every language must have EXACTLY the same string keys as
// the English master (res/values/strings.xml). English is the source of truth.
//
// For each res/values-<code>/strings.xml it reports the keys that are MISSING
// (in the master but not translated) and EXTRA (present but not in the master,
// usually a typo or a removed key). Any mismatch fails the build, so a
// half-translated or drifted language can't ship. Runs as part of preBuild, so
// it also gates every assembleDebug/assembleRelease (i.e. ./deploy). Run it on
// its own with:  ./gradlew checkTranslations
// ---------------------------------------------------------------------------
val checkTranslations = tasks.register("checkTranslations") {
    group = "verification"
    description = "Verify every res/values-*/strings.xml has the same <string> keys as the English master."
    val resDir = layout.projectDirectory.dir("src/main/res").asFile
    inputs.dir(resDir)
    doLast {
        val master = resDir.resolve("values/strings.xml")
        if (!master.exists()) throw GradleException("Missing English master: ${master.path}")
        val keyRe = Regex("""<string\s+name="([^"]+)"""")
        val commentRe = Regex("""(?s)<!--.*?-->""")   // strip comments so examples inside them don't count
        fun keysOf(f: java.io.File): Set<String> =
            keyRe.findAll(f.readText().replace(commentRe, "")).map { it.groupValues[1] }.toSet()

        val masterKeys = keysOf(master)
        val problems = StringBuilder()
        resDir.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("values-") }
            ?.sortedBy { it.name }
            ?.forEach { dir ->
                val f = dir.resolve("strings.xml")
                if (!f.exists()) return@forEach          // locale dir with no strings.xml: skip
                val keys = keysOf(f)
                val missing = (masterKeys - keys).sorted()
                val extra = (keys - masterKeys).sorted()
                if (missing.isNotEmpty() || extra.isNotEmpty()) {
                    problems.appendLine("• ${dir.name}/strings.xml")
                    if (missing.isNotEmpty())
                        problems.appendLine("    MISSING ${missing.size} (in English, not translated): ${missing.joinToString(", ")}")
                    if (extra.isNotEmpty())
                        problems.appendLine("    EXTRA ${extra.size} (not in the English master): ${extra.joinToString(", ")}")
                }
            }
        if (problems.isNotEmpty())
            throw GradleException(
                "Translation keys do not match the English master (src/main/res/values/strings.xml):\n$problems"
            )
        logger.lifecycle("checkTranslations: OK — all locales match the English master (${masterKeys.size} keys).")
    }
}
tasks.named("preBuild") { dependsOn(checkTranslations) }
