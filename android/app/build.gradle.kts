import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.util.zip.ZipFile
import java.security.MessageDigest

plugins {
    id("com.android.application")
    kotlin("android")
    // KSP for Room's annotation processing in Kotlin (annotationProcessor does NOT process Kotlin
    // sources — Room @Entity/@Dao would silently fail to generate). Version pinned to Kotlin 1.9.24.
    id("com.google.devtools.ksp")
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

val prepareBundledAssets = tasks.register<Sync>("prepareBundledAssets") {
    from("src/main/assets")
    into(layout.buildDirectory.dir("bundledAssets"))
    doLast {
        val root = destinationDir.resolve("models")
        if (root.isDirectory) {
            val rows = root.walkTopDown().filter { file ->
                file.isFile && file.relativeTo(root).invariantSeparatorsPath.split('/').none { it.startsWith('.') } &&
                    file.name !in setOf("version.txt", "integrity.sha256")
            }.sortedBy { it.relativeTo(root).invariantSeparatorsPath }.map { file ->
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().buffered().use { input ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                "$hash ${file.length()} ${file.relativeTo(root).invariantSeparatorsPath}\n"
            }.joinToString("")
            root.resolve("integrity.sha256").writeText(rows)
        }
    }
}

// VoxBridge — Android app.
//
// STATUS: fully implemented against the documented runtime contracts (MODEL_MANIFEST.md). All
// tensor names are resolved by session introspection at runtime, so nothing here depends on a
// specific export's naming. Versions are pinned to known-good majors; re-pin exact patch versions
// after a first successful Gradle sync (the Colab build notebook does this on a real toolchain).

android {
    namespace = "com.voxbridge"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.voxbridge"
        minSdk = 29              // Snapdragon 8-series targets; NNAPI/QNN EP available
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
        // Fully offline: no INTERNET permission. ASR, MT (EnViT5/VinAI QNN) and TTS run on-device.
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }

    val requestedDevProfile = providers.gradleProperty("voxbridgeModelProfile")
        .orElse("vi-en-qnn-envit5")
        .get()

    buildTypes {
        create("qdcPipeline") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            isDebuggable = false
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("boolean", "USE_FAKE_PIPELINE", "false")
            buildConfigField("String", "MODEL_SOURCE", "\"EXTERNAL_DEV\"")
            buildConfigField("String", "MODEL_PROFILE", "\"${providers.gradleProperty("voxbridgeQdcProfile").orElse("vi-en-qnn-envit5").get()}\"")
        }
        create("debugUi") {
            initWith(getByName("debug"))
            matchingFallbacks += listOf("debug")
            isDebuggable = true
            buildConfigField("boolean", "USE_FAKE_PIPELINE", "true")
            buildConfigField("String", "MODEL_SOURCE", "\"EXTERNAL_DEV\"")
            buildConfigField("String", "MODEL_PROFILE", "\"$requestedDevProfile\"")
        }
        create("debugCpu") {
            initWith(getByName("debug"))
            matchingFallbacks += listOf("debug")
            isDebuggable = true
            buildConfigField("boolean", "USE_FAKE_PIPELINE", "false")
            buildConfigField("String", "MODEL_SOURCE", "\"EXTERNAL_DEV\"")
            buildConfigField("String", "MODEL_PROFILE", "\"$requestedDevProfile\"")
        }
        create("competition") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            isDebuggable = false
            isMinifyEnabled = false
            buildConfigField("boolean", "USE_FAKE_PIPELINE", "false")
            buildConfigField("String", "MODEL_SOURCE", "\"BUNDLED_RELEASE\"")
            buildConfigField("String", "MODEL_PROFILE", "\"vi-en-qnn-envit5\"")
        }
        getByName("debug") {
            buildConfigField("boolean", "USE_FAKE_PIPELINE", "false")
            buildConfigField("String", "MODEL_SOURCE", "\"EXTERNAL_DEV\"")
            buildConfigField("String", "MODEL_PROFILE", "\"$requestedDevProfile\"")
        }
        getByName("release") {
            buildConfigField("boolean", "USE_FAKE_PIPELINE", "false")
            buildConfigField("String", "MODEL_SOURCE", "\"BUNDLED_RELEASE\"")
            buildConfigField("String", "MODEL_PROFILE", "\"vi-en-qnn-envit5\"")
        }
    }
    buildTypes.configureEach {
        buildConfigField("boolean", "CAPTURE_ARTIFACTS",
            if (isDebuggable) providers.gradleProperty("voxbridgeCaptureArtifacts").orElse("false").get().toBoolean().toString() else "false")
        buildConfigField("boolean", "RETRY_DRAFTS",
            if (isDebuggable || name == "qdcPipeline")
                providers.gradleProperty("voxbridgeRetryDrafts").orElse("true").get().toBoolean().toString()
            else "false")
        // Guidance-only prototype; production variants keep the existing capture/UI behavior.
        // Live mic guidance is part of the worker UX (noise handling), so it ships in every build.
        buildConfigField("boolean", "LIVE_AUDIO_STATUS",
            providers.gradleProperty("voxbridgeLiveAudioStatus").orElse("true").get().toBoolean().toString())
    }

    // Normal development never sees the Colab-staged src/main/assets/models tree. Common assets
    // belong in assets-common; only competition/release variants opt into the legacy assets dir.
    sourceSets {
        getByName("main").assets.setSrcDirs(listOf("src/main/assets-common"))
        getByName("competition").assets.srcDir(layout.buildDirectory.dir("bundledAssets"))
        getByName("release").assets.srcDir(layout.buildDirectory.dir("bundledAssets"))
    }

    // Model artifacts live under src/main/assets/models/ when built via colab/02_build_apk.ipynb
    // (which copies the exported models/ tree there) — src/main/assets is the default assets
    // source dir, so no config is needed and the APK is self-contained (ModelInstaller unpacks
    // it on first launch). An adb push to the external files dir remains an alternative path;
    // the assets dir is simply absent when models were never bundled (e.g. plain repo builds).
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // This application is arm64-only. Remove unsupported ABIs before merge (not pickFirst):
        // sherpa's x86 compatibility ORT must never collide with Microsoft's matched JNI/core.
        jniLibs.excludes += setOf("lib/x86/**", "lib/x86_64/**", "lib/armeabi-v7a/**")
        // QNN HTP skel libraries are loaded by the DSP from nativeLibraryDir (ADSP_LIBRARY_PATH),
        // so they must be extracted to disk, not left inside the APK.
        jniLibs.useLegacyPackaging = true
    }

    // Keep the bundled model weights UNCOMPRESSED inside the APK: aapt2's deflate pass over
    // hundreds of MB of dense fp32/int8 weights costs build time and slows first-launch
    // extraction (ModelInstaller streams them out), while saving almost nothing — ONNX weights
    // barely compress. .bin covers the mel filterbank, .pcm the safety-phrase cache.
    androidResources {
        noCompress += listOf("onnx", "bin", "pcm")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents {
    onVariants(selector().withBuildType("debugUi")) { variant ->
        // FakePipeline never enters ORT/sherpa/SQLCipher native code. Keep Java/Kotlin APIs on the
        // compile classpath but strip every native .so from this emulator/UI-only APK.
        variant.packaging.jniLibs.excludes.add("**/*.so")
    }
}

tasks.withType<KotlinCompile> { kotlinOptions { jvmTarget = "17" } }
tasks.matching { it.name == "mergeReleaseAssets" || it.name == "mergeCompetitionAssets" }.configureEach {
    dependsOn(prepareBundledAssets)
}

dependencies {
    implementation(project(":core"))   // the verified pure-Kotlin decision engine

    // On-device inference. The QNN Execution Provider for the Snapdragon NPU is in a SEPARATE
    // artifact (onnxruntime-android-qnn) — the plain onnxruntime-android AAR is CPU/NNAPI only.
    // CONFIRMED on Maven Central: onnxruntime-android-qnn lowest version is 1.20.0 (no 1.18).
    add("debugCpuImplementation", "com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    // AI Hub precompiled EPContext artifacts in the integrated bundle require the matching
    // ORT QNN loader. They were compiled/tested against ORT 1.30.0; 1.29 rejects QNNContext
    // before graph execution with ORT_NOT_IMPLEMENTED.
    // Same QNN stack for every shipping variant (qdcPipeline, competition, release).
    for (variant in listOf("qdcPipeline", "competition", "release")) {
        add("${variant}Implementation", "com.microsoft.onnxruntime:onnxruntime-android-qnn:1.29.0") {
            exclude(group="com.qualcomm.qti", module="qnn-runtime")
        }
        add("${variant}Implementation", "com.qualcomm.qti:qnn-runtime:2.50.0")
    }
    for (variant in listOf("debug", "debugUi")) {
        add("${variant}Implementation", "com.microsoft.onnxruntime:onnxruntime-android-qnn:1.20.0")
    }

    // Offline TTS — sherpa-onnx runs Piper (VITS) voices (MODEL_MANIFEST §4).
    // CONFIRMED: sherpa-onnx is NOT a Maven artifact — it ships as prebuilt AARs in its GitHub
    // releases. Use the static-ORT variant deliberately. The regular 1.13.3 AAR bundles
    // libonnxruntime.so@VERS_1.24.3, while onnxruntime-android-qnn:1.20.0 bundles
    // libonnxruntime.so + libonnxruntime4j_jni.so@VERS_1.20.0. Allowing both copies and resolving
    // them with pickFirst can pair Microsoft's 1.20 JNI with sherpa's 1.24 core (startup linker
    // crash) or pair sherpa JNI with Microsoft's 1.20 core (sherpa load failure). The static-link
    // AAR embeds sherpa's ORT into the arm64 libsherpa-onnx-jni.so and contributes no arm64
    // libonnxruntime.so, so the only dynamic ORT pair in this arm64-only APK comes from Microsoft
    // QNN 1.20.0. (The upstream AAR has an x86-only compatibility libonnxruntime.so, excluded by
    // defaultConfig.ndk.abiFilters above.)
    // See docs/development/onnx-integration.md §4. (No `implementation("com.k2-fsa...")` — that coordinate does not exist.)
    implementation(files("libs/sherpa-onnx-static-link-onnxruntime-1.13.3.aar"))

    // SentencePiece tokenizer for OPUS-MT (MODEL_MANIFEST §1). Keep this pure Java: DJL's
    // sentencepiece artifact ships desktop natives and fails on Android arm64 at first MT use.
    implementation("io.github.eix128:sentencepiece4j:1.0.2")

    // JSON for the tokenizer/model side files (vocab.json, whisper_tokens.json) — tensor names are
    // resolved by session introspection at runtime, not from export metadata. Explicit artifact
    // (not just the platform's shadowed copy) so the JVM mock harness and lint see the real API.
    implementation("org.json:json:20240303")

    // Encrypted on-device learning store (MODEL_MANIFEST §5).
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")          // KSP, not annotationProcessor (Kotlin sources)
    implementation("net.zetetic:android-database-sqlcipher:4.5.4")
    implementation("androidx.sqlite:sqlite-ktx:2.4.0")
    // Android Keystore-backed DB passphrase (EncryptedSharedPreferences) — confirmed stable 1.1.0.
    implementation("androidx.security:security-crypto:1.1.0")

    // Compose UI.
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    // collectAsStateWithLifecycle lives in lifecycle-runtime-compose (separate artifact, NOT
    // pulled transitively by viewmodel-compose). CONFIRMED 2.8.7 on Google Maven.
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    // Explicit (not transitive): MainActivity uses lifecycleScope — guaranteed present with the
    // runtime-ktx artifact rather than relying on the 2.8.0 ktx-merge chain.
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Material Components — supplies the XML Theme.Material3.* the Activity theme references
    // (androidx.compose.material3 does NOT provide XML themes). CONFIRMED 1.12.0 on Google Maven.
    implementation("com.google.android.material:material:1.12.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

val verifyDebugCpuNativeLibs = tasks.register("verifyDebugCpuNativeLibs") {
    group = "verification"
    description = "Fail if debugCpu does not contain exactly one compatible dynamic ORT JNI/core pair."
    doLast {
        val apk = layout.buildDirectory.file("outputs/apk/debugCpu/app-debugCpu.apk").get().asFile
        check(apk.isFile) { "debugCpu APK not found: $apk" }
        ZipFile(apk).use { zip ->
            val entries = zip.entries().asSequence().map { it.name }.toList()
            fun count(name: String) = entries.count { it == "lib/arm64-v8a/$name" }
            check(count("libonnxruntime.so") == 1) {
                "Expected exactly one arm64 libonnxruntime.so, found ${count("libonnxruntime.so")}"
            }
            check(count("libonnxruntime4j_jni.so") == 1) {
                "Expected exactly one arm64 libonnxruntime4j_jni.so, found ${count("libonnxruntime4j_jni.so")}"
            }
            check(entries.none { it.startsWith("lib/") && it.substringAfterLast('/').startsWith("libQnn") }) {
                "CPU APK must not contain QNN native libraries"
            }
        }
    }
}

tasks.matching { it.name == "assembleDebugCpu" }.configureEach {
    finalizedBy(verifyDebugCpuNativeLibs)
}
