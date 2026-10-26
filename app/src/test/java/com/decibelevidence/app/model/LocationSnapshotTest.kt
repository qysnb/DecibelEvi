package com.decibelevidence.app.model

import org.junit.Assert.*
import org.junit.Test

class LocationSnapshotTest {
    @Test fun missingLocationIsNeverTreatedAsFresh() {
        val sample = LocationSnapshot(null, null, null, 0, 0)
        assertEquals("未定位", sample.status(100))
    }
    @Test fun coordinatesBecomeStaleAfterThirtySeconds() {
        val sample = LocationSnapshot(31.2, 121.5, 20f, 1_000_000_000, 0, approximate = true)
        assertEquals("大致位置", sample.status(31_000_000_000))
        assertEquals("旧定位", sample.status(31_000_000_001))
        assertTrue(sample.approximate)
    }
}
