package com.decibelevidence.app.location

import com.decibelevidence.app.model.LocationSnapshot
import org.junit.Assert.*
import org.junit.Test

class LocationStateTest {
    private fun fix(time: Long) = LocationSnapshot(31.2, 121.5, 5f, time, 0)
    @Test fun oldProviderFixCannotReplaceNewerCoordinates() {
        val state = LocationState(); state.start()
        assertTrue(state.accept(fix(20)))
        assertFalse(state.accept(fix(10)))
        assertEquals(20L, state.current!!.timeNs)
    }
    @Test fun lateAddressDoesNotOverwriteANewerFix() {
        val state = LocationState(); state.start()
        val old = fix(10); state.accept(old); state.accept(fix(20))
        assertNull(state.resolve(state.generation, old, "old address"))
        assertNull(state.current!!.placeName)
    }
    @Test fun previousSessionAddressCannotEnterRestartedSession() {
        val state = LocationState(); state.start()
        val sample = fix(10); state.accept(sample); val generation = state.generation
        state.stop(); state.start(); state.accept(sample)
        assertNull(state.resolve(generation, sample, "old session"))
        assertEquals("new session", state.resolve(state.generation, sample, "new session")!!.placeName)
        state.stop(); assertFalse(state.accept(fix(30)))
    }
}
