package com.skypatrol.groundstation

import org.junit.Assert.*
import org.junit.Test

class FlightGateTest {
    private val ground = PilotState(registered = true, connected = true, supported = true,
        flying = false, battery = 80, satellites = 16, latitude = 31.2, longitude = 121.4,
        updatedAt = 10000, homeKnown = true, flightStateAt = 10000, batteryAt = 10000,
        satellitesAt = 10000, positionAt = 10000, homeAt = 10000)
    @Test fun takeoffRequiresConfirmedGroundState() {
        assertNull(FlightGate.rejection(ground, "takeoff", 11000))
        listOf(ground.copy(flying = null), ground.copy(flying = true), ground.copy(battery = null),
            ground.copy(battery = 24), ground.copy(satellites = 7), ground.copy(latitude = null),
            ground.copy(latitude = Double.NaN)).forEach { assertNotNull(FlightGate.rejection(it, "takeoff", 11000)) }
    }
    @Test fun staleDisconnectedAndUncertainStateBlockNewFlightCommands() {
        assertNotNull(FlightGate.rejection(ground, "takeoff", 14000))
        assertNotNull(FlightGate.rejection(ground, "takeoff", 9000))
        listOf(ground.copy(connected = false), ground.copy(registered = false), ground.copy(supported = false),
            ground.copy(busy = true), ground.copy(flightUncertain = true)).forEach {
            assertNotNull(FlightGate.rejection(it, "takeoff", 11000))
        }
    }
    @Test fun returnHomeNeedsHomeAndFlyingButCancellationCanResolveUncertainty() {
        val air = ground.copy(flying = true)
        assertNotNull(FlightGate.rejection(ground, "land", 11000))
        assertNotNull(FlightGate.rejection(air.copy(homeKnown = false), "rth", 11000))
        assertNull(FlightGate.rejection(air, "rth", 11000))
        assertNull(FlightGate.rejection(air.copy(flightUncertain = true), "cancelRth", 11000))
        assertNotNull(FlightGate.rejection(air, "unknown", 11000))
    }
    @Test fun freshHeightCannotAuthorizeStaleCriticalState() {
        assertNotNull(FlightGate.rejection(ground.copy(updatedAt = 14000, flightStateAt = 10000), "takeoff", 14000))
        assertNotNull(FlightGate.rejection(ground.copy(batteryAt = 1), "takeoff", 11000))
        assertNotNull(FlightGate.rejection(ground.copy(positionAt = 1), "takeoff", 11000))
        assertNotNull(FlightGate.rejection(ground.copy(flying = true, homeAt = 1), "rth", 11000))
    }
}
