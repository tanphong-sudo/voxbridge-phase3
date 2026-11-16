plugins { id("com.android.application") }
android {
    namespace = "com.voxbridge.qdcbench"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.voxbridge.qdcbench"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += "arm64-v8a" }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    packaging { jniLibs.excludes += setOf("lib/x86/**", "lib/x86_64/**", "lib/armeabi-v7a/**") }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation(files("../app/libs/sherpa-onnx-static-link-onnxruntime-1.13.3.aar"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")

    implementation("com.microsoft.onnxruntime:onnxruntime-android-qnn:1.29.0") {
        exclude(group="com.qualcomm.qti", module="qnn-runtime")
    }
    implementation("com.qualcomm.qti:qnn-runtime:2.50.0")
}
