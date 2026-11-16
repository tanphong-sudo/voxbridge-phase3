package com.voxbridge.runtime

import android.content.Context
import java.io.File

/**
 * Android-only wiring for [ModelPaths]: anchors the model root at the app's external files dir
 * (no storage permission needed, cleaned up on uninstall). Kept in its own file so that
 * ModelPaths itself stays android-free and runnable on a plain JVM (tools/e2e-jvm).
 */
object AndroidModelPaths {
    fun from(context: Context, profile: ModelProfile = ModelProfile.VI_EN_PHASE2): ModelPaths {
        // getExternalFilesDir returns null when external storage is unmounted — fall back to
        // internal filesDir rather than building a CWD-relative nonsense path.
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return ModelPaths(File(dir, "models"), profile = profile)
    }

    fun repository(context: Context, source: ModelSource, profile: ModelProfile): ModelRepository {
        val paths = from(context, profile)
        return when (source) {
            ModelSource.EXTERNAL_DEV -> ExternalDevModelRepository(paths)
            ModelSource.BUNDLED_RELEASE -> BundledReleaseModelRepository(context, paths)
        }
    }
}
