package com.skypatrol.groundstation

import org.junit.Assert.*
import org.junit.Test

class TelemetryFreshnessTest {
    private val received = PilotState(registered = true, connected = true, supported = true,
        flying = false, battery = 90, altitude = 0.0, connectedAt = 1000,
        flightStateAt = 9000, batteryAt = 9000, altitudeAt = 9000)
    @Test fun unchangedValuesRemainValidAfterSuccessfulReads() {
        assertNull(TelemetryFreshness.notice(received, 11000))
        val unchanged = received.copy(flightStateAt = 12000, batteryAt = 12000, altitudeAt = 12000)
        assertNull(TelemetryFreshness.notice(unchanged, 14000))
    }
    @Test fun missingHeightDoesNotMislabelTheConnectionAsLost() {
        val notice = TelemetryFreshness.notice(received.copy(altitudeAt = 0, altitude = null), 11000)!!
        assertTrue(notice.startsWith("已连接"))
        assertTrue(notice.contains("高度"))
        assertFalse(notice.contains("电量"))
    }
    @Test fun onlyReceivedAndRecentDataIsFresh() {
        assertFalse(TelemetryFreshness.fresh(0, 100))
        assertFalse(TelemetryFreshness.fresh(1000, 5000))
        assertFalse(TelemetryFreshness.fresh(2000, 1000))
        assertTrue(TelemetryFreshness.fresh(1000, 4000))
        assertNull(TelemetryFreshness.notice(received.copy(connected = false), 11000))
    }
}
