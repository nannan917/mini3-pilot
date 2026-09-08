package com.skypatrol.groundstation

object TelemetryFreshness {
    fun fresh(at: Long, now: Long) = at > 0 && now - at in 0..3000

    fun notice(s: PilotState, now: Long): String? {
        if (!s.connected || !s.registered || !s.supported) return null
        val missing = buildList {
            if (s.flying == null || !fresh(s.flightStateAt, now)) add("飞行状态")
            if (s.battery == null || !fresh(s.batteryAt, now)) add("电量")
            if (s.altitude == null || !fresh(s.altitudeAt, now)) add("高度")
        }
        if (missing.isEmpty()) return null
        val prefix = if (now - s.connectedAt < 5000) "正在读取" else "部分遥测暂不可用"
        return "已连接 · $prefix：${missing.joinToString("、")}"
    }
}
