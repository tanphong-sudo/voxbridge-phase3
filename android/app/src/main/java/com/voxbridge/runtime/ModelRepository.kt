package com.voxbridge.runtime

import android.content.Context

enum class ModelSource { EXTERNAL_DEV, BUNDLED_RELEASE }

data class ModelRepositoryStatus(
    val source: ModelSource,
    val profile: String,
    val root: String,
    val components: List<ModelComponentStatus>,
) {
    val ready: Boolean get() = components.all { it.ready }
    val missing: List<String> get() = components.flatMap { it.missing }

    fun humanReadable(): String = buildString {
        appendLine("Model status (${source.name}, $profile)")
        for (c in components) appendLine("${if (c.ready) "✓" else "✗"} ${c.label}")
        if (missing.isNotEmpty()) {
            appendLine("Missing:")
            missing.forEach { appendLine(" - $it") }
        }
        append("Root: $root")
    }
}

interface ModelRepository {
    val source: ModelSource
    val paths: ModelPaths
    fun prepare(): ModelRepositoryStatus
}

class ExternalDevModelRepository(
    override val paths: ModelPaths,
) : ModelRepository {
    override val source: ModelSource = ModelSource.EXTERNAL_DEV

    override fun prepare(): ModelRepositoryStatus = status(source, paths)
}

class BundledReleaseModelRepository(
    private val context: Context,
    override val paths: ModelPaths,
) : ModelRepository {
    override val source: ModelSource = ModelSource.BUNDLED_RELEASE

    override fun prepare(): ModelRepositoryStatus {
        ModelInstaller.ensureInstalled(context, paths)
        return status(source, paths)
    }
}

private fun status(source: ModelSource, paths: ModelPaths): ModelRepositoryStatus =
    ModelRepositoryStatus(
        source = source,
        profile = paths.profile.id,
        root = paths.root.absolutePath,
        components = paths.componentStatus(),
    )
