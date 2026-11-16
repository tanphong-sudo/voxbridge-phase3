package com.voxbridge.runtime

import java.io.File

/** Same-filesystem directory promotion. A crash between renames leaves a recoverable backup. */
internal object ModelInstallTransaction {
    fun recover(root: File) {
        val backup = File(root.parentFile, "${root.name}.backup")
        if (!root.exists() && backup.exists()) {
            check(backup.renameTo(root)) { "Cannot restore previous model set: $backup" }
        }
    }

    fun replace(root: File, populate: (File) -> Unit, validate: (File) -> Unit) {
        recover(root)
        val stage = File(root.parentFile, "${root.name}.staging")
        val backup = File(root.parentFile, "${root.name}.backup")
        check(!stage.exists() || stage.deleteRecursively()) { "Cannot clear interrupted staging" }
        check(stage.mkdirs()) { "Cannot create model staging" }
        try {
            populate(stage)
            validate(stage)
            check(!backup.exists() || backup.deleteRecursively()) { "Cannot clear previous backup" }
            if (root.exists()) check(root.renameTo(backup)) { "Cannot preserve previous models" }
            if (!stage.renameTo(root)) {
                recover(root)
                error("Cannot promote verified model staging")
            }
            // Keep backup until the next successful update; recovery never deletes the only copy.
        } catch (e: Exception) {
            recover(root)
            throw e
        }
    }
}
