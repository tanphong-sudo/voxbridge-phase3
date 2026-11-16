package com.voxbridge.runtime

import org.junit.Assert.*
import org.junit.Test

class ResourceScopeTest {
    @Test fun failureAtEveryConstructionStepClosesEarlierResourcesInReverse() {
        for (failAt in 0..4) {
            val closed = mutableListOf<Int>()
            assertThrows(IllegalStateException::class.java) {
                ResourceScope().use { scope ->
                    for (step in 0..4) {
                        if (step == failAt) error("injected")
                        scope.own(AutoCloseable { closed.add(step) })
                    }
                }
            }
            assertEquals((0 until failAt).reversed().toList(), closed)
        }
    }
    @Test fun committedResourcesRemainOwnedByCaller() {
        var closed = false
        ResourceScope().use { it.own(AutoCloseable { closed = true }); it.commit() }
        assertFalse(closed)
    }
    @Test fun failingCloseDoesNotSkipOtherResources() {
        var closed = false
        assertThrows(IllegalStateException::class.java) {
            ResourceScope().use {
                it.own(AutoCloseable { closed = true })
                it.own(AutoCloseable { error("close") })
            }
        }
        assertTrue(closed)
    }
}
