package com.skypatrol.groundstation

/** Fail closed on unknown telemetry. A command timeout never triggers a retry. */
object FlightGate {
    fun rejection(s: PilotState, action: String, now: Long): String? {
        if (action !in setOf("takeoff", "land", "rth", "cancelRth", "cancelLand")) return "未知飞行操作"
        if (!s.registered || !s.connected || !s.supported) return "请先连接已注册的 Mini 3"
        if (s.busy) return "上一条指令尚未结束"
        if (!TelemetryFreshness.fresh(s.flightStateAt, now) || s.flying == null)
            return "飞行状态未就绪或已过期，暂不能发送飞行指令"
        if (s.flightUncertain && action in setOf("takeoff", "land", "rth"))
            return "上一条飞行指令状态尚未确认，请通过遥控器接管"
        if (action == "takeoff") {
            if (!TelemetryFreshness.fresh(s.batteryAt, now) || !TelemetryFreshness.fresh(s.satellitesAt, now) ||
                !TelemetryFreshness.fresh(s.positionAt, now)) return "起飞所需的电量或定位数据未更新"
            if (s.flying != false || s.battery == null || s.battery < 25 ||
                s.satellites == null || s.satellites < 8 || s.latitude == null || s.longitude == null ||
                !s.latitude.isFinite() || !s.longitude.isFinite())
                return "起飞需要：飞机在地面、电量至少 25%、GNSS 至少 8 颗且定位有效"
        } else if (s.flying != true) return "该操作需要飞行器处于飞行中"
        if (action == "rth" && (!s.homeKnown || !TelemetryFreshness.fresh(s.homeAt, now)))
            return "返航点尚未确认，请通过遥控器操作"
        return null
    }
}
