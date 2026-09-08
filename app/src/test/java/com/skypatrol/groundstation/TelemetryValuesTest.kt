package com.skypatrol.groundstation

import org.junit.Assert.*
import org.junit.Test

class TelemetryValuesTest {
    @Test fun groundHeightDoesNotRequireGpsFix() {
        val state = TelemetryValues.withAltitude(PilotState(), 0.0, 1000)
        assertEquals(0.0, state.altitude!!, 0.0)
        assertEquals(1000L, state.altitudeAt)
        assertNull(state.latitude)
        assertEquals(0L, state.positionAt)
    }
    @Test fun negativeRelativeHeightIsValid() {
        assertEquals(-2.3, TelemetryValues.withAltitude(PilotState(), -2.3, 1000).altitude!!, 0.0)
    }
    @Test fun invalidHeightNeverBecomesZeroOrFresh() {
        for (value in listOf(null, Double.NaN, Double.POSITIVE_INFINITY)) {
            val state = TelemetryValues.withAltitude(PilotState(altitude = 5.0, altitudeAt = 500), value, 1000)
            assertNull(state.altitude)
            assertEquals(0L, state.altitudeAt)
        }
    }
}
