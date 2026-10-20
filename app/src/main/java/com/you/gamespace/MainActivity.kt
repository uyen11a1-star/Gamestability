package com.you.gamespace

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import rikka.shizuku.Shizuku
import kotlin.math.roundToInt

private data class AppItem(
    val label: String,
    val pkg: String,
    val isGame: Boolean,
    val icon: ImageBitmap
)

private data class Profile(val mode: String, val scale: String, val fps: String) {
    fun encode() = "$mode|$scale|$fps"
}

private val DEFAULT_PROFILE = Profile("performance", "disable", "disable")

private fun parseProfile(s: String?): Profile? {
    val p = s?.split("|") ?: return null
    if (p.size != 3) return null
    if (p[0] !in ShizukuManager.GAME_MODES) return null
    if (p[1] !in ShizukuManager.SCALES) return null
    if (p[2] !in ShizukuManager.FPS) return null
    return Profile(p[0], p[1], p[2])
}

private fun modeNote(mode: String) = when (mode) {
    "standard" -> "Chế độ mặc định của hệ thống."
    "performance" -> "Android 12+ và game phải hỗ trợ. Cấu hình do hãng máy quyết định."
    "battery" -> "Giảm tải để mát hơn, tiết kiệm pin (nếu game hỗ trợ)."
    else -> "Tự đặt độ phân giải và giới hạn FPS (Android 13+). Độ phân giải chỉ có hiệu lực sau khi game khởi động lại."
}

class MainActivity : ComponentActivity() {

    private val prefs by lazy { getSharedPreferences("gamespace", MODE_PRIVATE) }

    private var shizukuState by mutableStateOf(0) // 0 tắt, 1 chưa cấp quyền, 2 sẵn sàng
    private var tab by mutableStateOf(0)
    private var query by mutableStateOf("")
    private var onlyGames by mutableStateOf(true)
    private var loading by mutableStateOf(true)
    private var apps by mutableStateOf<List<AppItem>>(emptyList())
    private var selected by mutableStateOf<AppItem?>(null)
    private var log by mutableStateOf("")
    private var busy by mutableStateOf(false)

    private var autoAnim by mutableStateOf(true)
    private var autoKill by mutableStateOf(true)
    private var fixedPerf by mutableStateOf(false)
    private var restartGame by mutableStateOf(true)
    private var hz by mutableStateOf(0)
    private var compileMode by mutableStateOf("speed-profile")
    private var defProfile by mutableStateOf(DEFAULT_PROFILE)

    private val permListener = Shizuku.OnRequestPermissionResultListener { _, _ ->
        runOnUiThread { refreshStatus() }
    }
    private val binderReceived = Shizuku.OnBinderReceivedListener { runOnUiThread { refreshStatus() } }
    private val binderDead = Shizuku.OnBinderDeadListener { runOnUiThread { refreshStatus() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            Shizuku.addRequestPermissionResultListener(permListener)
            Shizuku.addBinderReceivedListenerSticky(binderReceived)
            Shizuku.addBinderDeadListener(binderDead)
        } catch (e: Throwable) {
            log = "Lỗi Shizuku: ${e.message}"
        }
        loadSettings()
        loadApps()
        setContent { NeonTheme { Root() } }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onDestroy() {
        try {
            Shizuku.removeRequestPermissionResultListener(permListener)
            Shizuku.removeBinderReceivedListener(binderReceived)
            Shizuku.removeBinderDeadListener(binderDead)
        } catch (e: Throwable) {}
        super.onDestroy()
    }

    // ---------- Logic ----------

    private fun refreshStatus() {
        shizukuState = when {
            !ShizukuManager.isShizukuAvailable() -> 0
            !ShizukuManager.hasPermission() -> 1
            else -> 2
        }
    }

    private fun loadSettings() {
        autoAnim = prefs.getBoolean("autoAnim", true)
        autoKill = prefs.getBoolean("autoKill", true)
        fixedPerf = prefs.getBoolean("fixedPerf", false)
        restartGame = prefs.getBoolean("restartGame", true)
        hz = prefs.getInt("hz", 0)
        val cm = prefs.getString("compileMode", "speed-profile") ?: "speed-profile"
        compileMode = if (cm in ShizukuManager.COMPILE_MODES) cm else "speed-profile"
        defProfile = parseProfile(prefs.getString("d_profile", null)) ?: DEFAULT_PROFILE
    }

    private fun putBool(key: String, v: Boolean) { prefs.edit().putBoolean(key, v).apply() }

    private fun profileOf(pkg: String): Profile =
        parseProfile(prefs.getString("p_$pkg", null)) ?: defProfile

    private fun loadApps() {
        Thread {
            val list = try {
                val pm = packageManager
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                pm.queryIntentActivities(intent, 0)
                    .filter { it.activityInfo.packageName != packageName }
                    .distinctBy { it.activityInfo.packageName }
                    .map {
                        AppItem(
                            label = it.loadLabel(pm).toString(),
                            pkg = it.activityInfo.packageName,
                            isGame = it.activityInfo.applicationInfo.category == ApplicationInfo.CATEGORY_GAME,
                            icon = it.loadIcon(pm).toBitmap(96, 96).asImageBitmap()
                        )
                    }
                    .sortedBy { it.label.lowercase() }
            } catch (e: Throwable) { emptyList() }
            runOnUiThread {
                apps = list
                onlyGames = list.any { it.isGame }
                loading = false
            }
        }.start()
    }

    // Chạy lệnh Shizuku ở thread nền, không chặn UI
    private fun runShell(title: String, after: (() -> Unit)? = null, block: () -> String) {
        if (busy) return
        if (!ShizukuManager.hasPermission()) { log = "Chưa có quyền Shizuku — bấm nút trạng thái ở trên"; return }
        busy = true
        log = "$title ..."
        Thread {
            val res = try { block() } catch (e: Throwable) { "Lỗi: ${e.message}" }
            runOnUiThread {
                log = "$title\n$res"
                busy = false
                after?.invoke()
            }
        }.start()
    }

    private fun connect() {
        try {
            when {
                !ShizukuManager.isShizukuAvailable() -> log = "Shizuku chưa chạy — mở app Shizuku và Start trước"
                ShizukuManager.hasPermission() -> {
                    refreshStatus()
                    runShell("Kiểm tra kết nối") { ShizukuManager.exec("wm size") }
                }
                else -> ShizukuManager.requestPermission()
            }
        } catch (e: Throwable) {
            log = "Lỗi kết nối: ${e.message}"
        }
    }

    private fun launchApp(pkg: String) {
        val i = packageManager.getLaunchIntentForPackage(pkg)
        if (i != null) startActivity(i) else log += "\nKhông mở được $pkg"
    }

    private fun applyRefresh(rate: Int): String {
        val sb = StringBuilder()
        for (key in listOf("min_refresh_rate", "peak_refresh_rate")) {
            if (!prefs.contains("prev_$key")) {
                val prev = ShizukuManager.exec("settings get system $key")
                if (Regex("^(null|[0-9.]+)$").matches(prev)) {
                    prefs.edit().putString("prev_$key", prev).apply()
                }
            }
            sb.append(ShizukuManager.exec("settings put system $key $rate.0").ifBlank { "OK" }).append(' ')
        }
        return sb.toString().trim()
    }

    private fun restoreRefresh(): String {
        val sb = StringBuilder()
        for (key in listOf("min_refresh_rate", "peak_refresh_rate")) {
            val prev = prefs.getString("prev_$key", null) ?: continue
            val r = if (prev == "null") ShizukuManager.exec("settings delete system $key")
            else ShizukuManager.exec("settings put system $key $prev")
            sb.append(r.ifBlank { "OK" }).append(' ')
            prefs.edit().remove("prev_$key").apply()
        }
        return if (sb.isEmpty()) "không có gì để khôi phục" else sb.toString().trim()
    }

    private fun boost(app: AppItem, p: Profile) {
        runShell("Boost ${app.label}", after = { launchApp(app.pkg) }) {
            val sb = StringBuilder()
            if (autoKill) sb.append("Dọn RAM: ").append(ShizukuManager.killBackground()).append('\n')
            if (autoAnim) sb.append("Tắt animation: ").append(ShizukuManager.setAnimations("0")).append('\n')
            if (fixedPerf) sb.append("Xung ổn định: ").append(ShizukuManager.fixedPerformance(true)).append('\n')
            if (hz > 0) sb.append("Tần số quét ").append(hz).append("Hz: ").append(applyRefresh(hz)).append('\n')
            sb.append("Game mode: ").append(ShizukuManager.applyGameProfile(app.pkg, p.mode, p.scale, p.fps)).append('\n')
            if (restartGame) sb.append("Khởi động lại game: ").append(ShizukuManager.forceStop(app.pkg))
            sb.toString().trim()
        }
    }

    private fun restoreSystem() {
        runShell("Khôi phục hệ thống") {
            "Animation: " + ShizukuManager.setAnimations("1.0") +
                "\nXung ổn định: " + ShizukuManager.fixedPerformance(false) +
                "\nTần số quét: " + restoreRefresh()
        }
    }

    // ---------- UI ----------

    @Composable
    private fun Root() {
        Column(
            modifier = Modifier.fillMaxSize().background(Neon.Bg).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Header()
            val sel = selected
            if (sel != null) {
                BackHandler { selected = null }
                GameDetail(sel, Modifier.weight(1f))
            } else {
                Row(modifier = Modifier.fillMaxWidth()) {
                    NeonTab("GAME", tab == 0) { tab = 0 }
                    NeonTab("CÀI ĐẶT", tab == 1) { tab = 1 }
                }
                if (tab == 0) GameTab(Modifier.weight(1f)) else SettingsTab(Modifier.weight(1f))
            }
            if (log.isNotEmpty() || busy) LogCard()
        }
    }

    @Composable
    private fun Header() {
        val txt: String
        val col: androidx.compose.ui.graphics.Color
        when (shizukuState) {
            2 -> { txt = "SẴN SÀNG"; col = Neon.Green }
            1 -> { txt = "CẤP QUYỀN"; col = Neon.Amber }
            else -> { txt = "SHIZUKU TẮT"; col = Neon.Red }
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("GAMESPACE", color = Neon.Cyan, fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                Text("boost · stable · neon", color = Neon.Muted, fontSize = 11.sp)
            }
            StatusPill(txt, col) { connect() }
        }
    }

    @Composable
    private fun LogCard() {
        NeonCard(modifier = Modifier.clickable { if (!busy) log = "" }) {
            Text(
                (if (busy) "⏳ " else "") + log,
                color = Neon.Muted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    @Composable
    private fun GameTab(modifier: Modifier) {
        val shown = remember(apps, query, onlyGames) {
            apps.filter {
                (!onlyGames || it.isGame) &&
                    (query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true))
            }
        }
        val shape = RoundedCornerShape(14.dp)
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(color = Neon.Text, fontSize = 14.sp),
                cursorBrush = SolidColor(Neon.Cyan),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(Neon.Card)
                    .padding(14.dp),
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) Text("Tìm game hoặc app…", color = Neon.Muted, fontSize = 14.sp)
                        inner()
                    }
                }
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NeonChip("Chỉ game", onlyGames) { onlyGames = !onlyGames }
                Text("${shown.size} app", color = Neon.Muted, fontSize = 12.sp)
            }
            if (loading) {
                Text("Đang tải danh sách…", color = Neon.Muted, fontSize = 13.sp)
            } else if (shown.isEmpty()) {
                Text(
                    "Không có kết quả. Nếu game không hiện, tắt \"Chỉ game\" (một số game không tự khai báo là game).",
                    color = Neon.Muted,
                    fontSize = 13.sp
                )
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(84.dp),
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(shown, key = { it.pkg }) { app -> AppTile(app) }
            }
        }
    }

    @Composable
    private fun AppTile(app: AppItem) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .clickable { selected = app }
                .padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                bitmap = app.icon,
                contentDescription = null,
                modifier = Modifier.size(54.dp).clip(RoundedCornerShape(14.dp))
            )
            Spacer(Modifier.height(4.dp))
            Text(
                app.label,
                color = Neon.Text,
                fontSize = 11.sp,
                lineHeight = 13.sp,
                maxLines = 2,
                textAlign = TextAlign.Center,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    @Composable
    private fun ProfileEditor(p: Profile, onChange: (Profile) -> Unit) {
        SectionTitle("Chế độ")
        ChipRow(
            listOf("standard" to "Chuẩn", "performance" to "Hiệu năng", "battery" to "Tiết kiệm", "custom" to "Tuỳ chỉnh"),
            p.mode
        ) { onChange(p.copy(mode = it)) }
        Text(modeNote(p.mode), color = Neon.Muted, fontSize = 12.sp)
        if (p.mode == "custom") {
            val scales = ShizukuManager.SCALES
            val found = scales.indexOf(p.scale)
            val idx = if (found < 0) scales.lastIndex else found
            SectionTitle("Độ phân giải")
            Text(
                if (idx == scales.lastIndex) "Gốc (100%)" else "${(scales[idx].toFloat() * 100).roundToInt()}%",
                color = Neon.Text,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            NeonSlider(
                value = idx.toFloat(),
                onChange = { v -> onChange(p.copy(scale = scales[v.roundToInt().coerceIn(0, scales.lastIndex)])) },
                range = 0f..scales.lastIndex.toFloat(),
                steps = scales.size - 2
            )
            SectionTitle("Giới hạn FPS")
            ChipRow(
                ShizukuManager.FPS.map { it to (if (it == "disable") "Mặc định" else it) },
                p.fps
            ) { onChange(p.copy(fps = it)) }
            Text(
                "FPS chỉ giới hạn trần khung hình (nếu máy hỗ trợ), giúp mát và ổn định hơn — không làm game render nhanh hơn mức nó tự chạy.",
                color = Neon.Muted,
                fontSize = 11.sp
            )
        }
    }

    @Composable
    private fun GameDetail(app: AppItem, modifier: Modifier) {
        var profile by remember(app.pkg) { mutableStateOf(profileOf(app.pkg)) }
        Column(
            modifier = modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "← Quay lại",
                color = Neon.Cyan,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { selected = null }.padding(vertical = 4.dp)
            )
            NeonCard(glow = true) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Image(
                        bitmap = app.icon,
                        contentDescription = null,
                        modifier = Modifier.size(60.dp).clip(RoundedCornerShape(16.dp))
                    )
                    Column {
                        Text(app.label, color = Neon.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(app.pkg, color = Neon.Muted, fontSize = 11.sp)
                        Text(
                            if (app.isGame) "● GAME" else "● Chưa được đánh dấu là game",
                            color = if (app.isGame) Neon.Green else Neon.Amber,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            if (!app.isGame) {
                NeonCard {
                    Text(
                        "Android chỉ áp dụng Game Mode / độ phân giải / FPS cho app được đánh dấu là game. App này có thể bỏ qua lệnh. Các boost hệ thống vẫn hoạt động.",
                        color = Neon.Amber,
                        fontSize = 12.sp
                    )
                }
            }
            NeonCard {
                ProfileEditor(profile) { np ->
                    profile = np
                    prefs.edit().putString("p_${app.pkg}", np.encode()).apply()
                }
            }
            NeonButton(
                "⚡ BOOST & MỞ GAME",
                onClick = { boost(app, profile) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                filled = true
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NeonButton("Áp dụng", onClick = {
                    runShell("Áp dụng ${app.label}") {
                        ShizukuManager.applyGameProfile(app.pkg, profile.mode, profile.scale, profile.fps)
                    }
                }, modifier = Modifier.weight(1f), enabled = !busy)
                NeonButton("Khôi phục", onClick = {
                    runShell("Khôi phục ${app.label}") { ShizukuManager.resetGame(app.pkg) }
                }, modifier = Modifier.weight(1f), enabled = !busy)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NeonButton("Kiểm tra", onClick = {
                    runShell("Cấu hình ${app.label}") { ShizukuManager.gameInfo(app.pkg) }
                }, modifier = Modifier.weight(1f), enabled = !busy)
                NeonButton("Mở game", onClick = { launchApp(app.pkg) }, modifier = Modifier.weight(1f))
            }
            NeonCard {
                SectionTitle("Biên dịch (AOT)")
                Text(
                    "Chế độ biên dịch chọn ở tab Cài đặt (hiện tại: $compileMode). Có thể mất vài phút.",
                    color = Neon.Muted,
                    fontSize = 12.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NeonButton("Biên dịch", onClick = {
                        runShell("Biên dịch ${app.label}") { ShizukuManager.compile(app.pkg, compileMode) }
                    }, modifier = Modifier.weight(1f), enabled = !busy)
                    NeonButton("Hoàn tác", onClick = {
                        runShell("Hoàn tác biên dịch") { ShizukuManager.resetCompile(app.pkg) }
                    }, modifier = Modifier.weight(1f), enabled = !busy)
                }
            }
        }
    }

    @Composable
    private fun SettingsTab(modifier: Modifier) {
        Column(
            modifier = modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            NeonCard {
                SectionTitle("Boost hệ thống")
                SwitchRow("Dọn RAM nền", "am kill-all — tắt tiến trình nền an toàn", autoKill) {
                    autoKill = it; putBool("autoKill", it)
                }
                SwitchRow("Tắt animation", "Giảm độ trễ giao diện khi boost", autoAnim) {
                    autoAnim = it; putBool("autoAnim", it)
                }
                SwitchRow(
                    "Xung ổn định",
                    "Fixed performance (Android 11+, tuỳ máy). Không phải tối đa — giúp xung ổn định, hạn chế tụt FPS",
                    fixedPerf
                ) { fixedPerf = it; putBool("fixedPerf", it) }
                SwitchRow(
                    "Khởi động lại game khi boost",
                    "Cần để độ phân giải mới có hiệu lực. Game đang chạy sẽ bị tắt",
                    restartGame
                ) { restartGame = it; putBool("restartGame", it) }
            }
            NeonCard {
                SectionTitle("Tần số quét")
                ChipRow(
                    listOf("0" to "Không đổi", "60" to "60Hz", "90" to "90Hz", "120" to "120Hz"),
                    hz.toString()
                ) { hz = it.toInt(); prefs.edit().putInt("hz", hz).apply() }
                Text(
                    "Ghi min/peak_refresh_rate. Tuỳ máy, một số ROM từ chối. Bấm \"Khôi phục hệ thống\" để trả về giá trị cũ.",
                    color = Neon.Muted,
                    fontSize = 11.sp
                )
            }
            NeonCard {
                ProfileEditor(defProfile) { np ->
                    defProfile = np
                    prefs.edit().putString("d_profile", np.encode()).apply()
                }
                Text("↑ Cấu hình mặc định cho game chưa từng chỉnh riêng.", color = Neon.Muted, fontSize = 11.sp)
            }
            NeonCard {
                SectionTitle("Biên dịch")
                ChipRow(
                    listOf("speed-profile" to "speed-profile", "speed" to "speed (nặng hơn)"),
                    compileMode
                ) { compileMode = it; prefs.edit().putString("compileMode", it).apply() }
            }
            NeonCard {
                SectionTitle("Công cụ nhanh")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NeonButton("Tắt anim", onClick = {
                        runShell("Tắt animation") { ShizukuManager.setAnimations("0") }
                    }, modifier = Modifier.weight(1f), enabled = !busy)
                    NeonButton("Bật anim", onClick = {
                        runShell("Bật animation") { ShizukuManager.setAnimations("1.0") }
                    }, modifier = Modifier.weight(1f), enabled = !busy)
                    NeonButton("Dọn RAM", onClick = {
                        runShell("Dọn RAM nền") { ShizukuManager.killBackground() }
                    }, modifier = Modifier.weight(1f), enabled = !busy)
                }
                NeonButton(
                    "Khôi phục hệ thống",
                    onClick = { restoreSystem() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy
                )
            }
        }
    }
}
