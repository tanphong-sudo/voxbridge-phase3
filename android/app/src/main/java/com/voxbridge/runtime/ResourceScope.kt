package com.voxbridge.runtime

/** Own partially constructed native objects until their final owner is ready. */
internal class ResourceScope : AutoCloseable {
    private val resources = mutableListOf<AutoCloseable>()
    fun <T : AutoCloseable> own(resource: T): T = resource.also { resources.add(it) }
    fun commit() { resources.clear() }
    override fun close() {
        var failure: Throwable? = null
        for (resource in resources.asReversed()) {
            try { resource.close() } catch (t: Throwable) {
                if (failure == null) failure = t else failure.addSuppressed(t)
            }
        }
        resources.clear()
        failure?.let { throw it }
    }
}
