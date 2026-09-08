package com.skypatrol.groundstation

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import com.skypatrol.groundstation.ui.DjiVideoPreviewView
import dji.sdk.keyvalue.key.*
import dji.sdk.keyvalue.value.camera.CameraMode
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.product.ProductType
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.common.register.DJISDKInitEvent
import dji.v5.manager.KeyManager
import dji.v5.manager.SDKManager
import dji.v5.manager.interfaces.SDKManagerCallback
import kotlin.math.*

/** Loaded reflectively only after DJI's protected classes have been installed. */
class DjiMini3Session : PilotSession {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var current = PilotState()
    private var diagnostics: SharedPreferences? = null
    private val cameraIndex = ComponentIndexType.LEFT_OR_MAIN
    private var started = false
    private var initialized = false
    private var registering = false
    private var validKey = false
    private var owner: Any? = null
    private var epoch = 0L
    private var previewView: DjiVideoPreviewView? = null
    private var homeLat: Double? = null
    private var homeLon: Double? = null
    private var commandId = 0L
    private val reads = linkedMapOf<String, () -> Unit>()
    private val inFlight = mutableMapOf<String, Long>()
    private val readStarted = mutableMapOf<String, Long>()
    private val lastAccepted = mutableMapOf<String, Long>()
    private val readErrors = linkedMapOf<String, String>()
    private var readSequence = 0L
    private val refresh = object : Runnable {
        override fun run() {
            if (!current.connected || !current.registered || owner == null) return
            reads.values.toList().forEach { it() }
            main.postDelayed(this, 1000)
        }
    }

    override fun state() = current
    @Synchronized private fun update(block: (PilotState) -> PilotState) { current = block(current) }
    private fun error(label: String, error: Throwable) {
        Log.e("Mini3Session", label, error)
        update { it.copy(message = "$label：${error.javaClass.simpleName} ${error.message}") }
    }
    override fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        diagnostics = app.getSharedPreferences("mini3_diagnostics", Context.MODE_PRIVATE)
        update { it.copy(lastCommandError = diagnostics?.getString("last_error", "").orEmpty()) }
        val key = app.packageManager.getApplicationInfo(app.packageName, PackageManager.GET_META_DATA)
            .metaData?.getString("com.dji.sdk.API_KEY").orEmpty()
        validKey = key.isNotBlank()
        update { it.copy(status = "正在初始化 DJI SDK") }
        try {
            SDKManager.getInstance().init(app, object : SDKManagerCallback {
                override fun onInitProcess(event: DJISDKInitEvent?, totalProcess: Int) {
                    main.post {
                        if (event == DJISDKInitEvent.INITIALIZE_COMPLETE) {
                            initialized = true
                            retryRegistration()
                        }
                    }
                }
                override fun onRegisterSuccess() { main.post {
                    registering = false
                    update { it.copy(registered = true, status = "注册成功 · 请连接 RC-N1", message = "") }
                    if (current.connected) observe()
                } }
                override fun onRegisterFailure(error: IDJIError?) { main.post {
                    registering = false
                    update { it.copy(registered = false, status = "DJI 注册失败", message = error.toString()) }
                    Log.e("Mini3Session", "Registration failed: $error")
                } }
                override fun onProductConnect(productId: Int) { main.post { connected(productId) } }
                override fun onProductChanged(productId: Int) { main.post { connected(productId) } }
                override fun onProductDisconnect(productId: Int) { main.post {
                    clearProduct()
                    update { PilotState(lastCommandError = it.lastCommandError, registered = it.registered, status = "连接已断开 · 检查数据线和遥控器") }
                } }
                override fun onDatabaseDownloadProgress(current: Long, total: Long) { }
            })
        } catch (error: Throwable) {
            update { it.copy(status = "SDK 初始化失败") }
            error("初始化", error)
        }
    }
    override fun retryRegistration() {
        if (!initialized || registering || current.registered) return
        if (!validKey) {
            update { it.copy(status = "尚未配置 App Key", message = "请在 local.properties 配置匹配包名的 DJI_APP_KEY 后重新构建。") }
            return
        }
        registering = true
        update { it.copy(status = "正在联网注册 DJI SDK", message = "") }
        try { SDKManager.getInstance().registerApp() }
        catch (error: Throwable) { registering = false; error("注册", error) }
    }
    private fun clearProduct() {
        epoch++
        commandId++
        main.removeCallbacks(refresh)
        reads.clear(); inFlight.clear(); readStarted.clear(); lastAccepted.clear(); readErrors.clear()
        owner?.let { runCatching { KeyManager.getInstance().cancelListen(it) } }
        owner = null
        pausePreview()
        homeLat = null; homeLon = null
    }
    private fun connected(productId: Int) {
        clearProduct()
        update { PilotState(lastCommandError = it.lastCommandError, registered = it.registered, connected = true,
            connectedAt = SystemClock.elapsedRealtime(), status = "正在识别飞行器") }
        Log.i("Mini3Session", "Product connected: $productId")
        if (current.registered) observe()
    }
    private fun recordReadError(label: String, detail: String) {
        if (readErrors[label] != detail) Log.w("Mini3Telemetry", "$label: $detail")
        readErrors[label] = detail
        publishReadDiagnostics()
    }
    private fun publishReadDiagnostics() {
        update { it.copy(telemetryDiagnostic = readErrors.entries.joinToString("\n") { e -> "${e.key}：${e.value}" }) }
    }
    private fun <T> watch(label: String, key: DJIKey<T>, poll: Boolean = false, callback: (T, Long) -> Unit) {
        val token = epoch
        val listenerOwner = owner ?: return
        fun validSession() = token == epoch && owner === listenerOwner && current.connected
        fun accept(value: T, at: Long) {
            if (!validSession()) return
            readErrors.remove(label); publishReadDiagnostics()
            lastAccepted[label] = at
            callback(value, at)
        }
        // Each subscription is independent: an unsupported optional key cannot suppress the others.
        try {
            KeyManager.getInstance().listen(key, listenerOwner, !poll) { _, value: T? ->
                val at = SystemClock.elapsedRealtime()
                main.post { if (value != null) accept(value, at) }
            }
        } catch (error: Throwable) { recordReadError(label, "订阅失败 ${error.message}") }
        if (!poll) return
        reads[label] = read@{
            if (!validSession()) return@read
            val startedAt = SystemClock.elapsedRealtime()
            if (inFlight.containsKey(label) && startedAt - (readStarted[label] ?: 0L) < 2500) return@read
            if (inFlight.containsKey(label)) recordReadError(label, "读取超时")
            val requestId = ++readSequence
            inFlight[label] = requestId; readStarted[label] = startedAt
            try {
                // The asynchronous overload reads hardware; repeatedly reading the SDK cache
                // would not prove that an unchanged value is still current.
                KeyManager.getInstance().getValue(key, object : CommonCallbacks.CompletionCallbackWithParam<T> {
                    override fun onSuccess(result: T) {
                        val at = SystemClock.elapsedRealtime()
                        main.post {
                            if (!validSession() || inFlight[label] != requestId) return@post
                            inFlight.remove(label); readStarted.remove(label)
                            if (at - startedAt > 2500 || result == null) {
                                recordReadError(label, "未及时收到有效数据"); return@post
                            }
                            readErrors.remove(label); publishReadDiagnostics()
                            // A more recent change notification takes precedence over a pending read.
                            if ((lastAccepted[label] ?: 0L) <= startedAt) accept(result, at)
                        }
                    }
                    override fun onFailure(error: IDJIError) { main.post {
                        if (!validSession() || inFlight[label] != requestId) return@post
                        inFlight.remove(label); readStarted.remove(label)
                        recordReadError(label, error.toString())
                    } }
                })
            } catch (error: Throwable) {
                inFlight.remove(label); readStarted.remove(label)
                recordReadError(label, "读取失败 ${error.message}")
            }
        }
    }
    private fun observe() {
        owner?.let { runCatching { KeyManager.getInstance().cancelListen(it) } }
        main.removeCallbacks(refresh)
        reads.clear(); inFlight.clear(); readStarted.clear(); lastAccepted.clear(); readErrors.clear()
        owner = Any()
        try {
            watch("机型", KeyTools.createKey(ProductKey.KeyProductType)) { product, _ ->
                val name = product.toString()
                val mini3 = product == ProductType.DJI_MINI_3
                update { it.copy(supported = mini3, product = if (mini3) "DJI Mini 3" else name,
                    status = if (mini3) "已连接 DJI Mini 3" else "当前机型尚未适配：$name") }
            }
            watch("电量", KeyTools.createKey(BatteryKey.KeyChargeRemainingInPercent), true) { value, at ->
                if (value in 0..100) update { it.copy(battery = value, batteryAt = at, updatedAt = at) }
            }
            watch("GNSS", KeyTools.createKey(FlightControllerKey.KeyGPSSatelliteCount), true) { value, at ->
                if (value >= 0) update { it.copy(satellites = value, satellitesAt = at, updatedAt = at) }
            }
            watch("飞行状态", KeyTools.createKey(FlightControllerKey.KeyIsFlying), true) { value, at ->
                update { it.copy(flying = value, flightStateAt = at, updatedAt = at) }
            }
            watch("姿态", KeyTools.createKey(FlightControllerKey.KeyAircraftAttitude)) { value, _ ->
                update { it.copy(yaw = value.yaw) }
            }
            watch("速度", KeyTools.createKey(FlightControllerKey.KeyAircraftVelocity)) { value, _ ->
                update { it.copy(speed = hypot(value.x, value.y)) }
            }
            watch("飞行模式", KeyTools.createKey(FlightControllerKey.KeyFlightMode)) { value, _ ->
                update { it.copy(flightMode = value.toString().replace('_', ' ')) }
            }
            watch("返航点", KeyTools.createKey(FlightControllerKey.KeyHomeLocation), true) { value, at ->
                homeLat = value.latitude; homeLon = value.longitude
                val a = homeLat; val b = homeLon
                update { it.copy(homeAt = at, homeKnown = a != null && b != null && a.isFinite() && b.isFinite() &&
                    a in -90.0..90.0 && b in -180.0..180.0 && (abs(a) > 0.000001 || abs(b) > 0.000001)) }
            }
            watch("三维位置", KeyTools.createKey(FlightControllerKey.KeyAircraftLocation3D), true) { value, at ->
                // Height remains usable indoors even when GNSS coordinates are invalid.
                update { TelemetryValues.withAltitude(it, value.altitude, at) }
                val lat = value.latitude; val lon = value.longitude
                if (lat != null && lon != null && lat.isFinite() && lon.isFinite() &&
                    lat in -90.0..90.0 && lon in -180.0..180.0 && (abs(lat) > 0.000001 || abs(lon) > 0.000001)) {
                    val distance = homeLat?.let { a -> homeLon?.let { b ->
                        val result = FloatArray(1)
                        android.location.Location.distanceBetween(lat, lon, a, b, result)
                        result[0].toDouble()
                    } }
                    update { it.copy(latitude = lat, longitude = lon, distance = distance, positionAt = at, updatedAt = at) }
                } else {
                    update { it.copy(latitude = null, longitude = null, distance = null, positionAt = 0) }
                }
            }
            watch("SD 卡", KeyTools.createKey(CameraKey.KeyCameraSDCardState, cameraIndex), true) { value, _ ->
                update { it.copy(storageState = value.toString()) }
            }
            watch("录像状态", KeyTools.createKey(CameraKey.KeyIsRecording, cameraIndex), true) { value, _ -> update { it.copy(recording = value) } }
            watch("相机模式", KeyTools.createKey(CameraKey.KeyCameraMode, cameraIndex), true) { value, _ ->
                update { it.copy(videoMode = value == CameraMode.VIDEO_NORMAL) }
            }
        } catch (error: Throwable) { error("读取飞行器状态", error) }
        main.post(refresh)
    }
    override fun preview(context: Context): View = DjiVideoPreviewView(context).also { previewView = it }
    override fun resumePreview() {
        if (current.connected && current.registered) previewView?.resume()
    }
    override fun pausePreview() { previewView?.release() }
    override fun disposePreview() { previewView?.release(); previewView = null }
    override fun videoFresh() = previewView?.streamHealth()?.hasFreshFrames() == true
    private fun available(): Boolean {
        val s = current
        if (!s.registered || !s.connected || !s.supported || s.busy) {
            update { it.copy(message = "请先连接 Mini 3，并等待当前操作结束。") }
            return false
        }
        return true
    }
    private fun begin(flightCommand: Boolean = false): Pair<Long, Long> {
        val token = ++commandId
        val generation = epoch
        update { it.copy(busy = true, message = "正在发送指令…") }
        main.postDelayed({
            if (commandId == token && epoch == generation && current.busy) {
                // Do not re-send a timed out flight command: aircraft outcome is unknown.
                update { it.copy(busy = false, flightUncertain = it.flightUncertain || flightCommand,
                    message = "指令确认超时，请检查飞机状态；不要重复发送。") }
                commandId++
            }
        }, 12000)
        return token to generation
    }
    private fun finish(token: Pair<Long, Long>, failure: String?) { main.post {
        if (commandId != token.first || epoch != token.second) return@post
        if (failure != null) {
            Log.e("Mini3Command", failure)
            diagnostics?.edit()?.putString("last_error", failure)?.apply()
        }
        update { it.copy(busy = false, message = failure?.let(CameraFeedback::error) ?: "指令已由飞行器确认",
            lastCommandError = failure ?: it.lastCommandError) }
    } }
    private fun active(token: Pair<Long, Long>) = commandId == token.first && epoch == token.second && current.connected && current.busy

    private fun readRecording(token: Pair<Long, Long>, next: (Boolean) -> Unit) {
        try {
            KeyManager.getInstance().getValue(KeyTools.createKey(CameraKey.KeyIsRecording, cameraIndex),
                object : CommonCallbacks.CompletionCallbackWithParam<Boolean> {
                    override fun onSuccess(result: Boolean) { main.post {
                        if (!active(token)) return@post
                        update { it.copy(recording = result) }
                        next(result)
                    } }
                    override fun onFailure(error: IDJIError) = finish(token, "读取录像状态：$error")
                })
        } catch (error: Throwable) { finish(token, "读取录像状态：$error") }
    }
    private fun setCameraMode(token: Pair<Long, Long>, video: Boolean, next: () -> Unit) {
        try {
            KeyManager.getInstance().setValue(KeyTools.createKey(CameraKey.KeyCameraMode, cameraIndex),
                if (video) CameraMode.VIDEO_NORMAL else CameraMode.PHOTO_NORMAL,
                object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() { main.post {
                        if (!active(token)) return@post
                        update { it.copy(videoMode = video) }
                        next()
                    } }
                    override fun onFailure(error: IDJIError) = finish(token, "切换相机模式：$error")
                })
        } catch (error: Throwable) { finish(token, "切换相机模式：$error") }
    }
    override fun camera(video: Boolean) {
        if (!available()) return
        val token = begin()
        readRecording(token) { recording ->
            if (recording) finish(token, "请先停止录像，再切换模式。")
            else setCameraMode(token, video) { finish(token, null) }
        }
    }
    override fun shutter() {
        if (!available()) return
        val video = current.videoMode
        val token = begin()
        readRecording(token) { recording ->
            if (recording) {
                perform(token, KeyTools.createKey(CameraKey.KeyStopRecord, cameraIndex), "停止录像")
            } else {
                // Set the actual aircraft mode before capture, including after a cold connection.
                setCameraMode(token, video) {
                    val key = if (video) CameraKey.KeyStartRecord else CameraKey.KeyStartShootPhoto
                    perform(token, KeyTools.createKey(key, cameraIndex), if (video) "开始录像" else "拍照")
                }
            }
        }
    }
    override fun flight(action: String) {
        if (!available()) return
        FlightGate.rejection(current, action, SystemClock.elapsedRealtime())?.let { reason ->
            update { it.copy(message = reason) }; return
        }
        val key = when (action) {
            "takeoff" -> FlightControllerKey.KeyStartTakeoff
            "land" -> FlightControllerKey.KeyStartAutoLanding
            "rth" -> FlightControllerKey.KeyStartGoHome
            "cancelRth" -> FlightControllerKey.KeyStopGoHome
            "cancelLand" -> FlightControllerKey.KeyStopAutoLanding
            else -> return
        }
        this.action(key, true)
    }
    private fun action(key: DJIActionKeyInfo<EmptyMsg, EmptyMsg>, flightCommand: Boolean = false) {
        val token = begin(flightCommand)
        perform(token, KeyTools.createKey(key), "飞行指令")
    }
    private fun perform(token: Pair<Long, Long>, key: DJIKey.ActionKey<EmptyMsg, EmptyMsg>, label: String) {
        if (!active(token)) return
        Log.i("Mini3Command", "Sending $label")
        try {
            KeyManager.getInstance().performAction(key,
                object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                    override fun onSuccess(result: EmptyMsg) = finish(token, null)
                    override fun onFailure(error: IDJIError) = finish(token, "$label：$error")
                })
        } catch (error: Throwable) { finish(token, "指令失败：${error.message}") }
    }
}
