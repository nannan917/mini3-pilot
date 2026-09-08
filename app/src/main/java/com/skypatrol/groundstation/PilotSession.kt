package com.skypatrol.groundstation

import android.content.Context
import android.view.View

data class PilotState(
    val registered: Boolean = false,
    val connected: Boolean = false,
    val supported: Boolean = false,
    val product: String = "DJI Mini 3",
    val status: String = "准备连接",
    val battery: Int? = null,
    val satellites: Int? = null,
    val altitude: Double? = null,
    val distance: Double? = null,
    val speed: Double? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val yaw: Double? = null,
    val flying: Boolean? = null,
    val recording: Boolean? = null,
    val storageState: String = "UNKNOWN",
    val lastCommandError: String = "",
    val flightMode: String = "—",
    val videoMode: Boolean = false,
    val updatedAt: Long = 0,
    val connectedAt: Long = 0,
    val flightStateAt: Long = 0,
    val batteryAt: Long = 0,
    val satellitesAt: Long = 0,
    val altitudeAt: Long = 0,
    val positionAt: Long = 0,
    val homeAt: Long = 0,
    val telemetryDiagnostic: String = "",
    val busy: Boolean = false,
    val flightUncertain: Boolean = false,
    val homeKnown: Boolean = false,
    val message: String = "",
)

interface PilotSession {
    fun start(context: Context)
    fun retryRegistration()
    fun state(): PilotState
    fun preview(context: Context): View
    fun resumePreview()
    fun pausePreview()
    fun disposePreview()
    fun videoFresh(): Boolean
    fun camera(video: Boolean)
    fun shutter()
    fun flight(action: String)
}
