package com.decibelevidence.app.model
import org.junit.Assert.assertEquals
import org.junit.Test
class WallTimeMappingTest {
    @Test fun delayedSnapshotKeepsItsOwnWallTime() {
        assertEquals(101000L, wallTimeAt(1_000_000_000L,3_113_000_000L,103113L))
    }
    @Test fun changedWallClockKeepsElapsedOffset() {
        assertEquals(3701000L, wallTimeAt(1_000_000_000L,3_113_000_000L,3703113L))
    }
}
