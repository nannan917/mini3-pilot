package com.skypatrol.groundstation

object TelemetryValues {
    fun withAltitude(state: PilotState, altitude: Double?, at: Long): PilotState =
        if (altitude != null && altitude.isFinite())
            state.copy(altitude = altitude, altitudeAt = at, updatedAt = at)
        else state.copy(altitude = null, altitudeAt = 0)
}
