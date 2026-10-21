package com.you.gamespace

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import kotlin.math.roundToInt

// Thanh bên nổi trên game: theo dõi FPS/nhiệt độ/CPU/RAM + chỉnh hiệu năng, độ phân giải, FPS...
class OverlayService : Service() {

    companion object {
        const val ACTION_STOP = "com.you.gamespace.STOP_OVERLAY"
        private const val CH = "gamespace_overlay"
        private const val NID = 7
        private const val D = "$"
        private val PKG_REGEX = Regex("^[A-Za-z0-9._]+$")

        fun start(ctx: Context, pkg: String, label: String, systemGame: Boolean) {
            val i = Intent(ctx, OverlayService::class.java)
                .putExtra("pkg", pkg).putExtra("label", label).putExtra("sys", systemGame)
            ctx.startForegroundService(i)
        }
    }

    private val cCyan = 0xFF00E5FF.toInt()
    private val cMagenta = 0xFFFF2BD6.toInt()
    private val cText = 0xFFE8E8F8.toInt()
    private val cMuted = 0xFF8B8BAA.toInt()
    private val cLine = 0xFF2A2A48.toInt()
    private val cPanel = 0xF20B0B16.toInt()

    private lateinit var wm: WindowManager
    private val ui = Handler(Looper.getMainLooper())

    private var pkg = ""
    private var label = ""
    private var systemGame = true
    private var profile = DEFAULT_PROFILE

    private var handleView: TextView? = null
    private var handleLp: WindowManager.LayoutParams? = null
    private var panelView: View? = null
    private var panelLp: WindowManager.LayoutParams? = null
    private var handleAttached = false
    private var panelAttached = false
    private var built = false

    private var fpsBig: TextView? = null
    private var metricsTv: TextView? = null
    private var statusTv: TextView? = null
    private var graph: Graph? = null

    @Volatile private var alive = false
    @Volatile private var monitorOn = true
    @Volatile private var targetPkg = ""
    @Volatile private var sessionStart = 0L
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        Store.init(this)
        startFg()
        val p = intent?.getStringExtra("pkg") ?: ""
        if (p.isEmpty() || !Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        pkg = p
        label = intent?.getStringExtra("label") ?: p
        systemGame = intent?.getBooleanExtra("sys", true) ?: true
        profile = Store.profileOf(p)
        targetPkg = p
        sessionStart = System.currentTimeMillis()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (!built) { buildHandle(); built = true }
        removePanel()
        panelView = null
        buildPanel()
        if (!alive) {
            alive = true
            worker = Thread { loop() }.also { it.start() }
        }
        return START_NOT_STICKY
    }

    private fun startFg() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH, "Thanh bên game", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat_gs)
            .setContentTitle("GameSpace đang hoạt động")
            .setContentText("Thanh bên chờ game")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat_gs), "Dừng", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NID, n)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!built) return
        val pl = panelLp
        if (pl != null) {
            pl.width = panelW()
            pl.height = panelH()
            if (panelAttached) try { wm.updateViewLayout(panelView, pl) } catch (e: Throwable) {}
        }
        val hl = handleLp
        if (hl != null) {
            hl.y = hl.y.coerceIn(0, maxOf(0, screenH() - dp(56)))
            updateHandle()
        }
    }

    override fun onDestroy() {
        alive = false
        worker?.interrupt()
        if (built) {
            removePanel()
            try { if (handleAttached) wm.removeView(handleView) } catch (e: Throwable) {}
            handleAttached = false
        }
        val app = applicationContext
        Thread {
            if (ShizukuManager.hasPermission()) {
                ShizukuManager.exec("dumpsys SurfaceFlinger --timestats -disable")
                Boost.sweep(app)
            }
        }.start()
        super.onDestroy()
    }

    // ---------- Helpers giao diện ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun screenH() = resources.displayMetrics.heightPixels
    private fun screenW() = resources.displayMetrics.widthPixels
    private fun panelW() = minOf(dp(300), (screenW() * 0.9f).toInt())
    private fun panelH() = (screenH() * 0.88f).toInt()
    private fun sideGravity() = if (Store.prefs.getString("ov_side", "right") == "left") Gravity.START else Gravity.END

    private fun bg(fill: Int, stroke: Int, radiusDp: Int, strokeDp: Int = 1) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
        setStroke(dp(strokeDp), stroke)
    }

    private fun tv(text: String, sp: Float, color: Int, bold: Boolean = false, mono: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = sp
            setTextColor(color)
            if (bold || mono) {
                typeface = Typeface.create(
                    if (mono) Typeface.MONOSPACE else Typeface.DEFAULT,
                    if (bold) Typeface.BOLD else Typeface.NORMAL
                )
            }
        }

    private fun lp(w: Int, h: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or sideGravity() }

    private class ChipRow(val view: View, val select: (String) -> Unit)

    private fun chipRow(options: List<Pair<String, String>>, initial: String, onSelect: (String) -> Unit): ChipRow {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val views = ArrayList<Pair<String, TextView>>()
        var cur = initial
        fun restyle() {
            for ((v, t) in views) {
                val sel = v == cur
                t.setTextColor(if (sel) cCyan else cMuted)
                t.background = bg(if (sel) 0x2200E5FF else 0x00000000, if (sel) cCyan else cLine, 16)
            }
        }
        for ((value, text) in options) {
            val t = tv(text, 12f, cMuted).apply { setPadding(dp(11), dp(6), dp(11), dp(6)) }
            t.setOnClickListener { cur = value; restyle(); onSelect(value) }
            views.add(Pair(value, t))
            row.addView(
                t,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { rightMargin = dp(6) }
            )
        }
        restyle()
        val sv = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }
        return ChipRow(sv) { v -> cur = v; restyle() }
    }

    private fun btn(text: String, onClick: () -> Unit): TextView =
        tv(text, 13f, cCyan, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(9), dp(8), dp(9))
            background = bg(0x1A00E5FF, cCyan, 12)
            setOnClickListener { onClick() }
        }

    private fun section(title: String): TextView =
        tv(title.uppercase(), 11f, cCyan, bold = true).apply {
            letterSpacing = 0.15f
            setPadding(0, dp(12), 0, dp(4))
        }

    private fun tint(sb: SeekBar) {
        sb.progressTintList = ColorStateList.valueOf(cCyan)
        sb.thumbTintList = ColorStateList.valueOf(cMagenta)
    }

    private inner class Graph(c: Context) : View(c) {
        val data = FloatArray(40)
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = cCyan
            style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
            strokeCap = Paint.Cap.ROUND
        }
        private val grid = Paint().apply { color = cLine; strokeWidth = 1f }

        fun push(v: Float) {
            System.arraycopy(data, 1, data, 0, data.size - 1)
            data[data.size - 1] = v
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            canvas.drawLine(0f, h / 2, w, h / 2, grid)
            var top = 60f
            for (x in data) if (x > top) top = x
            val path = Path()
            for (i in data.indices) {
                val x = w * i / (data.size - 1)
                val y = h - (data[i] / top) * (h - 4f) - 2f
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, line)
        }
    }

    // ---------- Handle (nút tròn nhỏ ở mép màn hình) ----------

    private fun updateHandle() {
        if (handleAttached) try { wm.updateViewLayout(handleView, handleLp) } catch (e: Throwable) {}
    }

    private fun buildHandle() {
        val h = tv("--\nFPS", 11f, cCyan, bold = true).apply {
            gravity = Gravity.CENTER
            background = bg(0xCC07070D.toInt(), cCyan, 14, 2)
        }
        val hl = lp(dp(40), dp(56)).apply { y = Store.prefs.getInt("ov_y", dp(120)) }
        h.setOnTouchListener(object : View.OnTouchListener {
            var startY = 0
            var downY = 0f
            var moved = false
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { startY = hl.y; downY = e.rawY; moved = false }
                    MotionEvent.ACTION_MOVE -> {
                        val dy = (e.rawY - downY).toInt()
                        if (Math.abs(dy) > dp(6)) moved = true
                        if (moved) {
                            hl.y = (startY + dy).coerceIn(0, maxOf(0, screenH() - dp(56)))
                            updateHandle()
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) togglePanel() else Store.prefs.edit().putInt("ov_y", hl.y).apply()
                    }
                }
                return true
            }
        })
        handleView = h
        handleLp = hl
    }

    private fun setOverlayVisible(v: Boolean) {
        if (v == handleAttached) return
        if (v) {
            try { wm.addView(handleView, handleLp); handleAttached = true } catch (e: Throwable) {}
        } else {
            removePanel()
            try { wm.removeView(handleView) } catch (e: Throwable) {}
            handleAttached = false
        }
    }

    private fun togglePanel() { if (panelAttached) removePanel() else showPanel() }

    private fun showPanel() {
        val pv = panelView ?: return
        val pl = panelLp ?: return
        if (panelAttached) return
        pl.width = panelW()
        pl.height = panelH()
        pl.gravity = Gravity.CENTER_VERTICAL or sideGravity()
        pl.x = 0
        pl.y = 0
        try {
            // panel trước, handle sau để handle nằm trên và đứng cạnh panel
            if (handleAttached) wm.removeView(handleView)
            wm.addView(pv, pl)
            panelAttached = true
            val hl = handleLp
            if (hl != null) { hl.gravity = Gravity.TOP or sideGravity(); hl.x = panelW() }
            wm.addView(handleView, handleLp)
            handleAttached = true
        } catch (e: Throwable) {}
    }

    private fun removePanel() {
        if (!panelAttached) return
        try { wm.removeView(panelView) } catch (e: Throwable) {}
        panelAttached = false
        val hl = handleLp
        if (hl != null) { hl.gravity = Gravity.TOP or sideGravity(); hl.x = 0 }
        updateHandle()
    }

    // ---------- Panel ----------

    private fun setStatus(s: String) { statusTv?.text = s }

    private fun shell(title: String, block: () -> String) {
        if (!ShizukuManager.hasPermission()) { setStatus("Chưa có quyền Shizuku"); return }
        setStatus("$title ...")
        Thread {
            val r = try { block() } catch (e: Throwable) { "Lỗi: ${e.message}" }
            ui.post { setStatus("$title: ${r.take(140)}") }
        }.start()
    }

    private fun applyProfile(np: Profile) {
        profile = np
        Store.saveProfile(pkg, np)
        val p = pkg
        val sg = systemGame
        shell("Áp dụng") { Boost.applyProfile(p, np, sg) }
    }

    private fun scaleLabel(idx: Int): String {
        val s = ShizukuManager.SCALES
        return if (idx >= s.lastIndex) "Gốc 100%" else "${(s[idx].toFloat() * 100).roundToInt()}%"
    }

    private fun buildPanel() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(14))
        }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            background = bg(cPanel, cCyan and 0x66FFFFFF, 18)
            addView(content)
        }

        // Tiêu đề
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(
            tv(label, 15f, cText, bold = true).apply { maxLines = 1 },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        head.addView(tv("✕", 16f, cMuted).apply {
            setPadding(dp(10), 0, 0, 0)
            setOnClickListener { removePanel() }
        })
        content.addView(head)

        // FPS + số liệu
        val big = tv("--", 34f, cCyan, bold = true, mono = true)
        fpsBig = big
        val metrics = tv("Đang đo…", 12f, cText, mono = true)
        metricsTv = metrics
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val fpsCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fpsCol.addView(big)
        fpsCol.addView(tv("FPS", 10f, cMuted))
        top.addView(fpsCol, LinearLayout.LayoutParams(dp(78), LinearLayout.LayoutParams.WRAP_CONTENT))
        top.addView(metrics, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(top)
        val g = Graph(this)
        graph = g
        content.addView(g, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))

        // Hiệu năng
        content.addView(section("Hiệu năng"))
        val modeRow = chipRow(
            listOf("standard" to "Chuẩn", "performance" to "Hiệu năng", "battery" to "Tiết kiệm", "custom" to "Tuỳ chỉnh"),
            profile.mode
        ) { applyProfile(profile.copy(mode = it)) }
        content.addView(modeRow.view)

        content.addView(section("Giới hạn FPS"))
        val fpsRow = chipRow(
            ShizukuManager.FPS.map { it to (if (it == "disable") "Mặc định" else it) },
            profile.fps
        ) {
            applyProfile(profile.copy(mode = "custom", fps = it))
            modeRow.select("custom")
        }
        content.addView(fpsRow.view)

        content.addView(section("Độ phân giải"))
        val scales = ShizukuManager.SCALES
        val found = scales.indexOf(profile.scale)
        val startIdx = if (found < 0) scales.lastIndex else found
        val scaleTv = tv(scaleLabel(startIdx), 13f, cText, bold = true)
        content.addView(scaleTv)
        val scaleBar = SeekBar(this).apply {
            max = scales.lastIndex
            progress = startIdx
            tint(this)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { scaleTv.text = scaleLabel(p) }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {
                    applyProfile(profile.copy(mode = "custom", scale = scales[sb.progress]))
                    modeRow.select("custom")
                    setStatus("Độ phân giải có hiệu lực khi mở lại game")
                }
            })
        }
        content.addView(scaleBar)

        // Hệ thống
        content.addView(section("Hệ thống"))
        val rowBtns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val lpBtn = { LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) } }
        rowBtns.addView(btn("Dọn RAM") { shell("Dọn RAM") { ShizukuManager.killBackground() } }, lpBtn())
        rowBtns.addView(btn("Chụp màn hình") {
            removePanel()
            shell("Chụp màn hình") {
                Thread.sleep(500)
                val f = "/sdcard/Pictures/GameSpace/shot_${System.currentTimeMillis()}.png"
                ShizukuManager.exec("mkdir -p /sdcard/Pictures/GameSpace && screencap -p $f && echo $f")
            }
        }, lpBtn())
        content.addView(rowBtns)

        content.addView(Switch(this).apply {
            text = "Xung ổn định"
            setTextColor(cText)
            isChecked = Store.prefs.getBoolean("fixedPerf", false)
            setOnCheckedChangeListener { _, on ->
                Store.prefs.edit().putBoolean("fixedPerf", on).apply()
                shell("Xung ổn định") { ShizukuManager.fixedPerformance(on) }
            }
        })
        content.addView(Switch(this).apply {
            text = "Đo FPS / nhiệt độ"
            setTextColor(cText)
            isChecked = monitorOn
            setOnCheckedChangeListener { _, on -> monitorOn = on }
        })

        content.addView(tv("Độ sáng", 12f, cMuted).apply { setPadding(0, dp(8), 0, 0) })
        val cur = try { Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) } catch (e: Throwable) { 128 }
        content.addView(SeekBar(this).apply {
            max = 255
            progress = cur.coerceIn(0, 255)
            tint(this)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {}
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {
                    val v = sb.progress.coerceAtLeast(10)
                    shell("Độ sáng") {
                        ShizukuManager.exec("settings put system screen_brightness_mode 0")
                        ShizukuManager.exec("settings put system screen_brightness $v").ifBlank { "OK ($v)" }
                    }
                }
            })
        })

        // Thanh bên
        content.addView(section("Thanh bên"))
        content.addView(chipRow(
            listOf("left" to "Bên trái", "right" to "Bên phải"),
            Store.prefs.getString("ov_side", "right") ?: "right"
        ) { s ->
            Store.prefs.edit().putString("ov_side", s).apply()
            val hl = handleLp
            if (hl != null) { hl.gravity = Gravity.TOP or sideGravity(); updateHandle() }
            setStatus("Đã đổi bên — mở lại panel để áp dụng")
        }.view)

        val st = tv("", 11f, cMuted).apply { setPadding(0, dp(10), 0, dp(6)) }
        statusTv = st
        content.addView(st)
        content.addView(btn("Thoát thanh bên") { stopSelf() })

        panelView = scroll
        panelLp = lp(panelW(), panelH())
    }

    private fun updateUi(fps: Float?, cpu: Int?, cpuT: Float?, gpuT: Float?) {
        val (bat, batT) = batteryInfo()
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val gb = 1024.0 * 1024.0 * 1024.0
        fun f(v: Float?, unit: String) = if (v == null) "--" else String.format("%.0f%s", v, unit)
        val txt = StringBuilder()
        txt.append("CPU ").append(if (cpu == null) "--" else "$cpu%").append("  ").append(f(cpuT, "°C")).append('\n')
        txt.append("GPU --    ").append(f(gpuT, "°C")).append('\n')
        txt.append("PIN ").append(if (bat < 0) "--" else "$bat%").append("  ").append(String.format("%.1f°C", batT)).append('\n')
        txt.append(String.format("RAM %.1f/%.1fG", mi.availMem / gb, mi.totalMem / gb))
        metricsTv?.text = txt.toString()
        val label = if (fps == null) "--" else fps.roundToInt().toString()
        fpsBig?.text = label
        handleView?.text = "$label\nFPS"
        if (fps != null) graph?.push(fps)
    }

    private fun batteryInfo(): Pair<Int, Float> {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val temp = (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        return Pair(if (level >= 0 && scale > 0) level * 100 / scale else -1, temp)
    }

    // ---------- Vòng đo ----------

    private fun hasUsageAccess(): Boolean = try {
        val ao = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        ao.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName) ==
            AppOpsManager.MODE_ALLOWED
    } catch (e: Throwable) { false }

    // Gói app vừa được mở gần nhất (sự kiện MOVE_TO_FOREGROUND/ACTIVITY_RESUMED = 1)
    private var fgPkg: String? = null
    private var lastEvt = 0L

    private fun pollForeground() {
        try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            if (lastEvt == 0L) lastEvt = now - 10 * 60 * 1000
            val ev = usm.queryEvents(lastEvt, now)
            val e = UsageEvents.Event()
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e)
                if (e.eventType == 1) fgPkg = e.packageName
                if (e.timeStamp > lastEvt) lastEvt = e.timeStamp
            }
        } catch (e: Throwable) {}
    }

    private fun fullCmd(p: String): String =
        "echo '@@S'; head -n 1 /proc/stat; echo '@@T'; " +
            "for z in /sys/class/thermal/thermal_zone*; do echo \"${D}(cat ${D}z/type 2>/dev/null) ${D}(cat ${D}z/temp 2>/dev/null)\"; done; " +
            "echo '@@F'; dumpsys SurfaceFlinger --timestats -dump | grep -E 'layerName|averageFPS'; " +
            "dumpsys SurfaceFlinger --timestats -clear; echo '@@P'; pidof $p"

    private fun slice(out: String, tag: String, next: List<String>): String {
        val s = out.indexOf("@@$tag")
        if (s < 0) return ""
        val from = s + 3
        var end = out.length
        for (n in next) {
            val i = out.indexOf("@@$n", from)
            if (i in 0 until end) end = i
        }
        return out.substring(from, end).trim()
    }

    private var lastTotal = 0L
    private var lastIdle = 0L

    private fun parseCpu(s: String): Int? {
        val t = s.trim().split(Regex("\\s+"))
        if (t.isEmpty() || t[0] != "cpu" || t.size < 5) return null
        val v = t.drop(1).take(8).map { it.toLongOrNull() ?: return null }
        val total = v.sum()
        val idle = v[3] + (if (v.size > 4) v[4] else 0L)
        val dt = total - lastTotal
        val di = idle - lastIdle
        val had = lastTotal != 0L
        lastTotal = total
        lastIdle = idle
        if (!had || dt <= 0) return null
        return (((dt - di) * 100.0) / dt).roundToInt().coerceIn(0, 100)
    }

    private fun parseTemps(s: String): Pair<Float?, Float?> {
        var cpu: Float? = null
        var gpu: Float? = null
        for (line in s.lines()) {
            val t = line.trim().split(Regex("\\s+"))
            if (t.size < 2) continue
            val raw = t.last().toFloatOrNull() ?: continue
            val c = if (Math.abs(raw) >= 1000f) raw / 1000f else raw
            if (c < 5f || c > 130f) continue
            val type = t.dropLast(1).joinToString(" ").lowercase()
            if (type.contains("gpu")) { if (gpu == null || c > gpu) gpu = c }
            else if (type.contains("cpu") || type.contains("soc") || type.contains("tsens")) {
                if (cpu == null || c > cpu) cpu = c
            }
        }
        return Pair(cpu, gpu)
    }

    // Layer của game: layerName chứa package; lấy averageFPS lớn nhất
    private fun parseFps(s: String, p: String): Float? {
        var inLayer = false
        var best: Float? = null
        for (line in s.lines()) {
            val l = line.trim()
            if (l.startsWith("layerName")) inLayer = l.contains(p)
            else if (inLayer && l.startsWith("averageFPS")) {
                val v = l.substringAfter("=").trim().toFloatOrNull()
                if (v != null && (best == null || v > best)) best = v
            }
        }
        return best
    }

    private fun loop() {
        val p = targetPkg
        val safePkg = if (PKG_REGEX.matches(p)) p else "none"
        if (ShizukuManager.hasPermission()) {
            ShizukuManager.exec("dumpsys SurfaceFlinger --timestats -clear -enable")
        }
        var seenFg = false
        while (alive) {
            val usage = hasUsageAccess()
            if (usage) pollForeground()
            val inGame = !usage || fgPkg == targetPkg
            if (inGame) seenFg = true
            ui.post { setOverlayVisible(inGame) }

            val full = inGame && monitorOn
            var running = true
            if (ShizukuManager.hasPermission()) {
                val cmd = if (full) fullCmd(safePkg) else "echo '@@P'; pidof $safePkg"
                val out = ShizukuManager.exec(cmd)
                val pid = slice(out, "P", emptyList())
                // rỗng = không chạy; số = đang chạy; lỗi lạ = coi như đang chạy (an toàn)
                running = pid.isNotBlank()
                if (full) {
                    val cpu = parseCpu(slice(out, "S", listOf("T", "F", "P")))
                    val (cpuT, gpuT) = parseTemps(slice(out, "T", listOf("F", "P")))
                    val fps = parseFps(slice(out, "F", listOf("P")), safePkg)
                    ui.post { updateUi(fps, cpu, cpuT, gpuT) }
                }
            }
            // Hết phiên: game không còn trên màn hình và không còn chạy
            val elapsed = System.currentTimeMillis() - sessionStart
            if (!inGame && !running && (seenFg || elapsed > 60_000)) {
                ui.post { stopSelf() }
                break
            }
            try { Thread.sleep(if (full) 2000 else 4000) } catch (e: InterruptedException) { break }
        }
    }
}
