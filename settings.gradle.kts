pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "voxbridge"
include(":core")
include(":benchmark-cli")

// Android app module. Requires the Android SDK + AGP to configure. The kotlinc-based verification
// harnesses (tools/self-verify, tools/safety-benchmark) do NOT use Gradle and are unaffected; if
// you build only the JVM core without an Android SDK, comment this line out.
include(":android:app")

// Independent offline-model benchmark; does not launch or modify the product pipeline.
include(":android:qdcbench")
