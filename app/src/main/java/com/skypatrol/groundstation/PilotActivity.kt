package com.skypatrol.groundstation

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.view.*
import android.widget.*
import java.util.Locale

/** Compact landscape cockpit inspired by the information hierarchy of DJI Fly. */
class PilotActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var session: PilotSession? = null
    private lateinit var root: FrameLayout
    private lateinit var feed: FrameLayout
    private lateinit var empty: LinearLayout
    private lateinit var connection: TextView
    private lateinit var mode: TextView
    private lateinit var battery: TextView
    private lateinit var satellites: TextView
    private lateinit var height: TextView
    private lateinit var distance: TextView
    private lateinit var speed: TextView
    private lateinit var shutter: TextView
    private lateinit var cameraMode: TextView
    private lateinit var notification: TextView
    private lateinit var centerStatus: TextView
    private lateinit var connect: TextView
    private lateinit var compass: CompassView
    private var resumed = false
    private var previewRunning = false
    private var hasPreview = false
    private val poll = object : Runnable {
        override fun run() { render(); handler.postDelayed(this, 500) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        buildUi()
        root.setOnApplyWindowInsetsListener { view, insets ->
            val cutout = if (Build.VERSION.SDK_INT >= 28) insets.displayCutout else null
            view.setPadding(maxOf(dp(12), cutout?.safeInsetLeft ?: 0),
                maxOf(dp(6), cutout?.safeInsetTop ?: 0), maxOf(dp(12), cutout?.safeInsetRight ?: 0),
                maxOf(dp(6), cutout?.safeInsetBottom ?: 0))
            insets
        }
        render()
    }
    override fun onResume() { super.onResume(); resumed = true; handler.post(poll) }
    override fun onPause() {
        resumed = false
        handler.removeCallbacks(poll)
        session?.pausePreview(); previewRunning = false
        super.onPause()
    }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); session?.disposePreview(); super.onDestroy() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); render() }

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(INK) }
        feed = FrameLayout(this)
        root.addView(feed, FrameLayout.LayoutParams(-1, -1))
        feed.addView(HorizonBackdrop(this), FrameLayout.LayoutParams(-1, -1))

        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            background = rounded(PANEL, 16)
        }
        top.addView(button("⌂", 40) { showConnection() }, LinearLayout.LayoutParams(dp(44), dp(40)))
        mode = label("N", 21, WHITE).apply { typeface = Typeface.DEFAULT_BOLD }
        top.addView(mode, LinearLayout.LayoutParams(dp(42), -2))
        connection = label("未连接飞行器", 14, WHITE)
        top.addView(connection, LinearLayout.LayoutParams(0, -2, 1f))
        satellites = label("GNSS  —", 12, MUTED)
        top.addView(satellites, LinearLayout.LayoutParams(dp(91), -2))
        battery = label("电量  —", 13, GREEN)
        top.addView(battery, LinearLayout.LayoutParams(dp(86), -2))
        top.addView(button("•••", 40) { showSettings() }, LinearLayout.LayoutParams(dp(46), dp(40)))
        root.addView(top, FrameLayout.LayoutParams(-1, dp(52), Gravity.TOP).apply {
            leftMargin = dp(6); rightMargin = dp(6); topMargin = dp(5)
        })

        empty = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(18), dp(24), dp(18))
            background = rounded(0xEE171F28.toInt(), 22)
        }
        empty.addView(label("MINI 3  /  PILOT", 11, GREEN).apply { letterSpacing = .22f }, row(-1, 24))
        empty.addView(label("让视野，延伸至天空", 24, WHITE).apply { typeface = Typeface.DEFAULT_BOLD }, row(-1, 40))
        centerStatus = label("连接 RC-N1，开启实时飞行视图", 13, MUTED)
        empty.addView(centerStatus, row(-1, 30))
        connect = button("连接飞行器", 46, GREEN, INK) { connectFlow() }
        empty.addView(connect, LinearLayout.LayoutParams(dp(190), dp(46)).apply { topMargin = dp(12) })
        empty.addView(label("手机 ↔ RC-N1 ↔ DJI Mini 3", 11, MUTED), row(-1, 30))
        root.addView(empty, FrameLayout.LayoutParams(dp(385), -2, Gravity.CENTER).apply { rightMargin = dp(40) })

        val camera = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(7), dp(10), dp(7), dp(10))
            background = rounded(PANEL, 24)
        }
        cameraMode = button("拍照", 40) {
            val s = session?.state() ?: return@button
            session?.camera(!s.videoMode)
        }
        camera.addView(cameraMode, row(72, 40))
        shutter = button("●", 76, WHITE, INK) { session?.shutter() }.apply { textSize = 42f }
        camera.addView(shutter, LinearLayout.LayoutParams(dp(66), dp(66)).apply {
            topMargin = dp(10); bottomMargin = dp(10)
        })
        camera.addView(button("设置", 38) { showSettings() }, row(72, 38))
        root.addView(camera, FrameLayout.LayoutParams(dp(88), -2, Gravity.END or Gravity.CENTER_VERTICAL).apply {
            rightMargin = dp(6)
        })

        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(button("起飞", 44) { confirmFlight("takeoff", "起飞", "确认飞机周围空旷、桨叶完好且人员远离。飞机将自动起飞，请握持遥控器。") }, row(62, 44))
        left.addView(button("返航", 44) { confirmFlight("rth", "开始返航", "确认返航点与返航高度合适。飞机将按自身返航逻辑飞行，请持续观察。") },
            row(62, 44).apply { topMargin = dp(10) })
        left.addView(button("降落", 44) { confirmFlight("land", "自动降落", "确认飞机下方可安全降落。降落过程中请持续观察，可通过更多操作取消降落。") },
            row(62, 44).apply { topMargin = dp(10) })
        root.addView(left, FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.CENTER_VERTICAL).apply { leftMargin = dp(6) })

        val bottom = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(PANEL, 16)
            setPadding(dp(14), dp(7), dp(14), dp(7))
        }
        compass = CompassView(this)
        bottom.addView(compass, LinearLayout.LayoutParams(dp(48), dp(48)))
        bottom.addView(label("方位", 10, MUTED), row(40, 45))
        height = metric(bottom, "H · 相对高度")
        distance = metric(bottom, "D · 距返航点")
        speed = metric(bottom, "水平速度")
        bottom.addView(button("连接诊断", 38) { showConnection() }, LinearLayout.LayoutParams(dp(90), dp(38)))
        root.addView(bottom, FrameLayout.LayoutParams(-1, dp(66), Gravity.BOTTOM).apply {
            leftMargin = dp(6); rightMargin = dp(6); bottomMargin = dp(5)
        })
        notification = label("", 12, WHITE).apply {
            maxLines = 2; setPadding(dp(12), dp(6), dp(12), dp(6)); background = rounded(PANEL, 8)
        }
        root.addView(notification, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(82); leftMargin = dp(80); rightMargin = dp(105)
        })
        setContentView(root)
    }
    private fun metric(parent: LinearLayout, title: String): TextView {
        val group = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        val value = label("—", 20, WHITE)
        group.addView(value); group.addView(label(title, 10, MUTED))
        parent.addView(group, LinearLayout.LayoutParams(0, -2, 1f))
        return value
    }
    private fun connectFlow() {
        val prefs = getSharedPreferences("pilot", MODE_PRIVATE)
        if (!prefs.getBoolean("sdkConsent", false)) {
            AlertDialog.Builder(this).setTitle("连接 DJI 服务")
                .setMessage("连接功能使用 DJI Mobile SDK，会联网验证应用并获取飞行器状态，需要定位权限。飞行中请保持遥控器连接。本应用是第三方 Mini 3 控制端。")
                .setNegativeButton("稍后", null)
                .setPositiveButton("同意并继续") { _, _ -> prefs.edit().putBoolean("sdkConsent", true).apply(); permissions() }.show()
        } else permissions()
    }
    private fun permissions() {
        val needed = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT <= 32) needed += Manifest.permission.READ_EXTERNAL_STORAGE
        if (Build.VERSION.SDK_INT <= 28) needed += Manifest.permission.WRITE_EXTERNAL_STORAGE
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 31) else startSdk()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode != 31) return
        if (results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) startSdk()
        else AlertDialog.Builder(this).setTitle("尚未获得连接权限")
            .setMessage("请授予定位和 SDK 所需权限后，再点击连接飞行器。")
            .setPositiveButton("知道了", null).show()
    }
    private fun startSdk() {
        val app = application as PilotApplication
        session = app.session()
        val active = session ?: run { render(); return }
        try {
            active.start(this)
            active.retryRegistration()
            if (!hasPreview) {
                feed.addView(active.preview(this), FrameLayout.LayoutParams(-1, -1))
                hasPreview = true
            }
        } catch (error: Throwable) {
            notification.text = "连接模块异常：${error.message}"
            AlertDialog.Builder(this).setTitle("连接模块异常").setMessage(error.toString()).setPositiveButton("知道了", null).show()
        }
        render()
    }
    private fun render() {
        if (!::root.isInitialized) return
        val s = session?.state() ?: PilotState()
        val failure = (application as PilotApplication).bootstrapError
        val ready = s.connected && s.registered && s.supported
        connection.text = if (failure != null) "运行库加载失败" else s.status
        connection.setTextColor(if (ready) GREEN else WHITE)
        satellites.text = "GNSS  ${s.satellites ?: "—"}"
        battery.text = "电量  ${s.battery?.let { "$it%" } ?: "—"}"
        battery.setTextColor(if ((s.battery ?: 100) < 25) RED else GREEN)
        mode.text = if (s.flying == true) "飞行" else if (s.flying == false) "地面" else "—"
        height.text = value(s.altitude, "m")
        distance.text = value(s.distance, "m")
        speed.text = value(s.speed, "m/s")
        compass.yaw = s.yaw
        shutter.isEnabled = ready && !s.busy
        cameraMode.isEnabled = ready && !s.busy && s.recording != true
        shutter.alpha = if (shutter.isEnabled) 1f else .4f
        cameraMode.text = if (s.videoMode) "录像" else "拍照"
        shutter.text = if (s.recording == true) "■" else "●"
        shutter.setTextColor(if (s.videoMode || s.recording == true) RED else INK)
        empty.visibility = if (ready) View.GONE else View.VISIBLE
        centerStatus.text = failure ?: if (session != null) s.status else "连接 RC-N1，开启实时飞行视图"
        connect.text = if (session != null && !s.registered) "重试注册" else "连接飞行器"
        val telemetryNotice = TelemetryFreshness.notice(s, SystemClock.elapsedRealtime())
        notification.text = failure ?: when {
            s.message.isNotEmpty() -> s.message
            telemetryNotice != null -> telemetryNotice
            ready && session?.videoFresh() != true -> "已连接，等待实时图传…"
            else -> ""
        }
        notification.visibility = if (notification.text.isEmpty()) View.GONE else View.VISIBLE
        val wantPreview = resumed && ready
        if (wantPreview != previewRunning) {
            if (wantPreview) session?.resumePreview() else session?.pausePreview()
            previewRunning = wantPreview
        }
    }
    private fun confirmFlight(action: String, title: String, explanation: String) {
        val s = session?.state()
        if (s == null || !s.connected || !s.supported || !s.registered) {
            showConnection(); return
        }
        AlertDialog.Builder(this).setTitle(title).setMessage(explanation)
            .setNegativeButton("取消", null).setPositiveButton("确认$title") { _, _ -> session?.flight(action) }.show()
    }
    private fun showConnection() {
        val s = session?.state() ?: PilotState()
        val usb = getSystemService(android.hardware.usb.UsbManager::class.java).accessoryList
        AlertDialog.Builder(this).setTitle("连接 DJI Mini 3")
            .setMessage("1. 开启飞机与 RC-N1，确认已对频。\n2. 用遥控器顶部手机接口连接手机。\n3. 关闭 DJI Fly，USB 应用选择 Mini 3 Pilot。\n4. 首次注册请保持手机联网。\n\nSDK：${if (s.registered) "已注册" else "未注册"}\nUSB 配件：${usb?.size ?: 0}\n飞机：${if (s.connected) s.product else "未连接"}\nSD 卡：${CameraFeedback.storage(s.storageState)}\n${s.message}\n${s.telemetryDiagnostic}\n上次指令错误：${s.lastCommandError}")
            .setNegativeButton("关闭", null).setPositiveButton("连接 / 重试") { _, _ -> connectFlow() }.show()
    }
    private fun showSettings() {
        val s = session?.state() ?: PilotState()
        val version = packageManager.getPackageInfo(packageName, 0)
        AlertDialog.Builder(this).setTitle("Mini 3 Pilot")
            .setItems(arrayOf("飞行状态与版本", "取消返航", "取消自动降落", "连接帮助")) { _, which -> when (which) {
                0 -> AlertDialog.Builder(this).setTitle("飞行状态与版本")
                    .setMessage("机型：${s.product}\n飞行模式：${s.flightMode}\n相对高度：${value(s.altitude, "m")}\n经纬度：${s.latitude ?: "—"}, ${s.longitude ?: "—"}\n\n版本：${version.versionName}\nDJI MSDK：5.18.0\nAndroid：${Build.VERSION.RELEASE}\n包名：$packageName\n\n第三方应用，界面参考 DJI Fly；不包含官方账号、固件升级或自动航线功能。")
                    .setPositiveButton("关闭", null).show()
                1 -> confirmFlight("cancelRth", "取消返航", "取消后请使用遥控器接管飞行。")
                2 -> confirmFlight("cancelLand", "取消降落", "取消后请使用遥控器接管飞行。")
                3 -> showConnection()
            } }.show()
    }
    private fun value(number: Double?, unit: String) = if (number == null || !number.isFinite()) "—" else
        String.format(Locale.US, "%.1f %s", number, unit)
    private fun label(text: String, size: Int, color: Int) = TextView(this).apply {
        this.text = text; textSize = size.toFloat(); setTextColor(color); gravity = Gravity.CENTER
        includeFontPadding = false
    }
    private fun button(text: String, height: Int, background: Int = PANEL, foreground: Int = WHITE, action: () -> Unit) =
        label(text, 13, foreground).apply {
            minHeight = dp(height); this.background = rounded(background, if (height >= 70) 50 else 14)
            isClickable = true; isFocusable = true; contentDescription = text
            setOnClickListener { action() }
        }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun row(width: Int, height: Int) = LinearLayout.LayoutParams(if (width < 0) width else dp(width), dp(height))
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private class HorizonBackdrop(context: android.content.Context) : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            p.shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), intArrayOf(0xFF1A2A35.toInt(), INK), null, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p); p.shader = null
            p.color = 0x163C7790; p.strokeWidth = 1f
            val step = 48 * resources.displayMetrics.density
            var x = 0f; while (x < width) { c.drawLine(x, 0f, x, height.toFloat(), p); x += step }
            var y = 0f; while (y < height) { c.drawLine(0f, y, width.toFloat(), y, p); y += step }
        }
    }
    private class CompassView(context: android.content.Context) : View(context) {
        var yaw: Double? = null
            set(value) { field = value; invalidate() }
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val r = width.coerceAtMost(height) / 2f - 4
            val x = width / 2f; val y = height / 2f
            p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = 0xFF536572.toInt()
            c.drawCircle(x, y, r, p); p.style = Paint.Style.FILL; p.textSize = r * .45f
            p.textAlign = Paint.Align.CENTER; p.color = MUTED; c.drawText("N", x, y - r * .5f, p)
            if (yaw == null) { c.drawText("—", x, y + r * .4f, p); return }
            c.save(); c.rotate(yaw!!.toFloat(), x, y)
            p.color = GREEN
            c.drawPath(Path().apply { moveTo(x, y - r * .35f); lineTo(x - r * .3f, y + r * .45f); lineTo(x, y + r * .3f); lineTo(x + r * .3f, y + r * .45f); close() }, p)
            c.restore()
        }
    }
    companion object {
        private val INK = 0xFF10151C.toInt()
        private val PANEL = 0xDA1B232D.toInt()
        private val WHITE = 0xFFF4F7FA.toInt()
        private val MUTED = 0xFFA4B2BF.toInt()
        private val GREEN = 0xFF52D9AC.toInt()
        private val RED = 0xFFFF6262.toInt()
    }
}
