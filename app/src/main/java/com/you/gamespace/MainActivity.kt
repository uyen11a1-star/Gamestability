package com.you.gamespace

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
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
    val systemGame: Boolean,
    val icon: ImageBitmap,
    val launchers: List<String>
)

private fun modeNote(mode: String) = when (mode) {
    "standard" -> "Chế độ mặc định của hệ thống."
    "performance" -> "Android 12+ và game phải hỗ trợ. Cấu hình do hãng máy quyết định."
    "battery" -> "Giảm tải để mát hơn, tiết kiệm pin (nếu game hỗ trợ)."
    else -> "Tự đặt độ phân giải và giới hạn FPS (Android 13+). Độ phân giải chỉ có hiệu lực sau khi game khởi động lại."
}

class MainActivity : ComponentActivity() {

    private var shizukuState by mutableStateOf(0) // 0 tắt, 1 chưa cấp quyền, 2 sẵn sàng
    private var tab by mutableStateOf(0)
    private var query by mutableStateOf("")
    private var appFilter by mutableStateOf(0) // 0 = game nhận diện, 1 = tất cả
    private var loading by mutableStateOf(true)
    private var apps by mutableStateOf<List<AppItem>>(emptyList())
    private var selected by mutableStateOf<AppItem?>(null)
    private var log by mutableStateOf("")
    private var busy by mutableStateOf(false)
    private var permTick by mutableStateOf(0)

    private var manual by mutableStateOf<Set<String>>(emptySet())
    private var vault by mutableStateOf<Set<String>>(emptySet())

    private var autoAnim by mutableStateOf(true)
    private var autoKill by mutableStateOf(true)
    private var fixedPerf by mutableStateOf(false)
    private var restartGame by mutableStateOf(true)
    private var autoOverlay by mutableStateOf(true)
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
        Store.init(this)
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
        permTick++
        sweepVault()
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

    private fun reloadSets() {
        manual = Store.getSet("manual_games")
        vault = Store.getSet("vault")
    }

    private fun loadSettings() {
        val sp = Store.prefs
        autoAnim = sp.getBoolean("autoAnim", true)
        autoKill = sp.getBoolean("autoKill", true)
        fixedPerf = sp.getBoolean("fixedPerf", false)
        restartGame = sp.getBoolean("restartGame", true)
        autoOverlay = sp.getBoolean("autoOverlay", true)
        hz = sp.getInt("hz", 0)
        val cm = sp.getString("compileMode", "speed-profile") ?: "speed-profile"
        compileMode = if (cm in ShizukuManager.COMPILE_MODES) cm else "speed-profile"
        defProfile = Store.defaultProfile()
        reloadSets()
    }

    private fun putBool(key: String, v: Boolean) { Store.prefs.edit().putBoolean(key, v).apply() }

    private fun isGame(a: AppItem) = a.systemGame || a.pkg in manual

    private fun matchesQuery(a: AppItem) =
        query.isBlank() || a.label.contains(query, true) || a.pkg.contains(query, true)

    private fun loadApps() {
        Thread {
            val list = try {
                val pm = packageManager
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                // MATCH_DISABLED_COMPONENTS: vẫn thấy game đã bị ẩn icon trong không gian
                pm.queryIntentActivities(intent, PackageManager.MATCH_DISABLED_COMPONENTS)
                    .filter { it.activityInfo.packageName != packageName }
                    .groupBy { it.activityInfo.packageName }
                    .map { (pkg, rs) ->
                        val first = rs.first()
                        AppItem(
                            label = first.loadLabel(pm).toString(),
                            pkg = pkg,
                            systemGame = first.activityInfo.applicationInfo.category == ApplicationInfo.CATEGORY_GAME,
                            icon = first.loadIcon(pm).toBitmap(96, 96).asImageBitmap(),
                            launchers = rs.map { "$pkg/${it.activityInfo.name}" }
                        )
                    }
                    .sortedBy { it.label.lowercase() }
                    .let { base -> base + missingVaultApps(base) }
                    .sortedBy { it.label.lowercase() }
            } catch (e: Throwable) { emptyList() }
            runOnUiThread {
                apps = list
                loading = false
            }
        }.start()
    }

    // Game đang bị ẩn (disable-user) có thể không có trong kết quả launcher -> lấy trực tiếp theo package
    private fun missingVaultApps(base: List<AppItem>): List<AppItem> {
        val have = base.map { it.pkg }.toSet()
        val pm = packageManager
        val out = ArrayList<AppItem>()
        for (pkg in Store.getSet("vault")) {
            if (pkg in have) continue
            try {
                val ai = pm.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS)
                out.add(
                    AppItem(
                        label = pm.getApplicationLabel(ai).toString(),
                        pkg = pkg,
                        systemGame = ai.category == ApplicationInfo.CATEGORY_GAME,
                        icon = pm.getApplicationIcon(ai).toBitmap(96, 96).asImageBitmap(),
                        launchers = emptyList()
                    )
                )
            } catch (e: Throwable) {}
        }
        return out
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

    private fun sweepVault() {
        if (Store.getSet("vault").isEmpty() || !ShizukuManager.hasPermission()) return
        Thread {
            val r = try { Boost.sweep(applicationContext) } catch (e: Throwable) { "" }
            if (r.isNotEmpty()) runOnUiThread { log = r }
        }.start()
    }

    private fun launchApp(app: AppItem) {
        val comp = app.launchers.firstOrNull()?.let { ComponentName.unflattenFromString(it) }
        val i = if (comp != null) {
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(comp)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        } else packageManager.getLaunchIntentForPackage(app.pkg)
        if (i == null) { log += "\nKhông mở được ${app.label}"; return }
        try { startActivity(i) } catch (e: Throwable) { log += "\nKhông mở được ${app.label}: ${e.message}" }
    }

    // Sau khi boost / mở game: bật thanh bên rồi mở game
    private fun afterLaunchPrep(app: AppItem) {
        reloadSets()
        if (autoOverlay && isGame(app)) {
            if (Settings.canDrawOverlays(this)) {
                try { OverlayService.start(this, app.pkg, app.label, app.systemGame) }
                catch (e: Throwable) { log += "\nThanh bên lỗi: ${e.message}" }
            } else {
                log += "\nThanh bên: chưa cấp quyền hiển thị trên app khác (tab Cài đặt → Thanh bên)"
            }
        }
        launchApp(app)
    }

    private fun openGame(app: AppItem) {
        if (app.pkg in vault) {
            runShell("Mở ${app.label}", after = { afterLaunchPrep(app) }) {
                Boost.ensureLaunchable(applicationContext, app.pkg).ifBlank { "OK" }
            }
        } else afterLaunchPrep(app)
    }

    private fun applyRefresh(rate: Int): String {
        val sb = StringBuilder()
        for (key in listOf("min_refresh_rate", "peak_refresh_rate")) {
            if (!Store.prefs.contains("prev_$key")) {
                val prev = ShizukuManager.exec("settings get system $key")
                if (Regex("^(null|[0-9.]+)$").matches(prev)) {
                    Store.prefs.edit().putString("prev_$key", prev).apply()
                }
            }
            sb.append(ShizukuManager.exec("settings put system $key $rate.0").ifBlank { "OK" }).append(' ')
        }
        return sb.toString().trim()
    }

    private fun restoreRefresh(): String {
        val sb = StringBuilder()
        for (key in listOf("min_refresh_rate", "peak_refresh_rate")) {
            val prev = Store.prefs.getString("prev_$key", null) ?: continue
            val r = if (prev == "null") ShizukuManager.exec("settings delete system $key")
            else ShizukuManager.exec("settings put system $key $prev")
            sb.append(r.ifBlank { "OK" }).append(' ')
            Store.prefs.edit().remove("prev_$key").apply()
        }
        return if (sb.isEmpty()) "không có gì để khôi phục" else sb.toString().trim()
    }

    private fun boost(app: AppItem, p: Profile) {
        runShell("Boost ${app.label}", after = { afterLaunchPrep(app) }) {
            val sb = StringBuilder()
            val pre = Boost.ensureLaunchable(applicationContext, app.pkg)
            if (pre.isNotEmpty()) sb.append(pre).append('\n')
            if (autoKill) sb.append("Dọn RAM: ").append(ShizukuManager.killBackground()).append('\n')
            if (autoAnim) sb.append("Tắt animation: ").append(ShizukuManager.setAnimations("0")).append('\n')
            if (fixedPerf) sb.append("Xung ổn định: ").append(ShizukuManager.fixedPerformance(true)).append('\n')
            if (hz > 0) sb.append("Tần số quét ").append(hz).append("Hz: ").append(applyRefresh(hz)).append('\n')
            if (isGame(app)) {
                sb.append("Game mode: ").append(Boost.applyProfile(app.pkg, p, app.systemGame)).append('\n')
            }
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

    private fun moveIn(app: AppItem) {
        runShell("Chuyển ${app.label} vào không gian", after = { reloadSets() }) {
            Boost.moveIn(applicationContext, app.pkg)
        }
    }

    private fun moveOut(app: AppItem) {
        runShell("Đưa ${app.label} ra ngoài", after = { reloadSets() }) {
            Boost.moveOut(applicationContext, app.pkg)
        }
    }

    private fun moveAll() {
        val list = apps.filter { isGame(it) && it.pkg !in vault }
        if (list.isEmpty()) { log = "Không có game nào để chuyển"; return }
        runShell("Chuyển ${list.size} game vào không gian", after = { reloadSets() }) {
            list.joinToString("\n") { Boost.moveIn(applicationContext, it.pkg) }
        }
    }

    private fun moveAllOut() {
        val pkgs = vault.toList()
        if (pkgs.isEmpty()) { log = "Không gian đang trống"; return }
        runShell("Đưa tất cả game ra ngoài", after = { reloadSets() }) {
            pkgs.joinToString("\n") { Boost.moveOut(applicationContext, it) }
        }
    }

    private fun toggleMark(app: AppItem) {
        val m = Store.getSet("manual_games")
        if (app.pkg in m) {
            Store.putSet("manual_games", m - app.pkg)
            if (app.pkg in vault) {
                moveOut(app)
            } else {
                reloadSets()
                log = "Đã bỏ đánh dấu ${app.label}"
            }
        } else {
            Store.putSet("manual_games", m + app.pkg)
            reloadSets()
            log = "Đã đánh dấu ${app.label} là game"
        }
    }

    private fun usageOk(): Boolean = try {
        val ao = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        ao.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName) ==
            AppOpsManager.MODE_ALLOWED
    } catch (e: Throwable) { false }

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
                    NeonTab("KHÔNG GIAN", tab == 0) { tab = 0 }
                    NeonTab("ỨNG DỤNG", tab == 1) { tab = 1 }
                    NeonTab("CÀI ĐẶT", tab == 2) { tab = 2 }
                }
                when (tab) {
                    0 -> SpaceTab(Modifier.weight(1f))
                    1 -> AppsTab(Modifier.weight(1f))
                    else -> SettingsTab(Modifier.weight(1f))
                }
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
                Text("boost · stable · galaxy", color = Neon.Muted, fontSize = 11.sp)
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
    private fun SearchBox() {
        BasicTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            textStyle = TextStyle(color = Neon.Text, fontSize = 14.sp),
            cursorBrush = SolidColor(Neon.Cyan),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Neon.Card)
                .padding(14.dp),
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty()) Text("Tìm game hoặc app…", color = Neon.Muted, fontSize = 14.sp)
                    inner()
                }
            }
        )
    }

    @Composable
    private fun SpaceTab(modifier: Modifier) {
        val shown = remember(apps, vault, query) { apps.filter { it.pkg in vault && matchesQuery(it) } }
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SearchBox()
            if (loading) {
                Text("Đang tải danh sách…", color = Neon.Muted, fontSize = 13.sp)
            } else if (shown.isEmpty()) {
                Text(
                    if (vault.isEmpty()) "Chưa có game nào trong không gian. Vào tab ỨNG DỤNG để chuyển game vào — game sẽ biến mất khỏi màn hình chính và chỉ hiện ở đây."
                    else "Không có kết quả.",
                    color = Neon.Muted,
                    fontSize = 13.sp
                )
            }
            AppGrid(shown, Modifier.weight(1f))
        }
    }

    @Composable
    private fun AppsTab(modifier: Modifier) {
        val gamesOutside = remember(apps, vault, manual) { apps.count { isGame(it) && it.pkg !in vault } }
        val shown = remember(apps, vault, manual, query, appFilter) {
            apps.filter { it.pkg !in vault && (appFilter == 1 || isGame(it)) && matchesQuery(it) }
        }
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SearchBox()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NeonChip("Game ($gamesOutside)", appFilter == 0) { appFilter = 0 }
                NeonChip("Tất cả", appFilter == 1) { appFilter = 1 }
            }
            if (gamesOutside > 0) {
                NeonButton(
                    "Chuyển $gamesOutside game vào không gian",
                    onClick = { moveAll() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    filled = true
                )
            }
            if (loading) {
                Text("Đang tải danh sách…", color = Neon.Muted, fontSize = 13.sp)
            } else if (shown.isEmpty()) {
                Text(
                    "Không có kết quả. App nào Android chưa nhận là game thì chọn \"Tất cả\", mở app đó rồi bấm Đánh dấu là game.",
                    color = Neon.Muted,
                    fontSize = 13.sp
                )
            }
            AppGrid(shown, Modifier.weight(1f))
        }
    }

    @Composable
    private fun AppGrid(list: List<AppItem>, modifier: Modifier) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(84.dp),
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(list, key = { it.pkg }) { app -> AppTile(app) }
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
                color = if (isGame(app)) Neon.Cyan else Neon.Text,
                fontSize = 11.sp,
                lineHeight = 13.sp,
                maxLines = 2,
                textAlign = TextAlign.Center,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    @Composable
    private fun ProfileEditor(p: Profile, note: String?, onChange: (Profile) -> Unit) {
        SectionTitle("Chế độ")
        ChipRow(
            listOf("standard" to "Chuẩn", "performance" to "Hiệu năng", "battery" to "Tiết kiệm", "custom" to "Tuỳ chỉnh"),
            p.mode
        ) { onChange(p.copy(mode = it)) }
        Text(modeNote(p.mode), color = Neon.Muted, fontSize = 12.sp)
        if (note != null) Text(note, color = Neon.Amber, fontSize = 12.sp)
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
        var profile by remember(app.pkg) { mutableStateOf(Store.profileOf(app.pkg)) }
        val game = isGame(app)
        val vaulted = app.pkg in vault
        val marked = app.pkg in manual
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
                            when {
                                app.systemGame -> "● GAME (Android nhận diện)"
                                marked -> "● GAME (đánh dấu thủ công)"
                                else -> "● Chưa là game"
                            },
                            color = if (game) Neon.Green else Neon.Amber,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            NeonCard {
                SectionTitle("Không gian")
                if (!app.systemGame) {
                    Text(
                        if (marked) "Bạn đã đánh dấu app này là game. GameSpace coi nó như game: dùng được không gian, thanh bên và boost."
                        else "Android chưa nhận app này là game. Đánh dấu để GameSpace coi nó là game (không gian, thanh bên, boost).",
                        color = Neon.Muted,
                        fontSize = 12.sp
                    )
                    NeonButton(
                        if (marked) "Bỏ đánh dấu game" else "Đánh dấu là game",
                        onClick = { toggleMark(app) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy
                    )
                }
                if (game) {
                    Text(
                        if (vaulted) "Game đang ở trong không gian: không hiện ngoài màn hình chính, chỉ mở từ GameSpace."
                        else "Chuyển vào không gian để ẩn game khỏi màn hình chính (game được vô hiệu hoá tạm thời, dữ liệu vẫn giữ).",
                        color = Neon.Muted,
                        fontSize = 12.sp
                    )
                    NeonButton(
                        if (vaulted) "Đưa ra ngoài" else "Chuyển vào không gian",
                        onClick = { if (vaulted) moveOut(app) else moveIn(app) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy
                    )
                }
            }

            NeonCard {
                ProfileEditor(
                    profile,
                    if (game && !app.systemGame) "Android chưa nhận app này là game nên chỉ \"Tuỳ chỉnh → độ phân giải\" hoạt động (qua compat). Chuẩn/Hiệu năng/Tiết kiệm và FPS sẽ bị bỏ qua."
                    else null
                ) { np ->
                    profile = np
                    Store.saveProfile(app.pkg, np)
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
                    runShell("Áp dụng ${app.label}") { Boost.applyProfile(app.pkg, profile, app.systemGame) }
                }, modifier = Modifier.weight(1f), enabled = !busy && game)
                NeonButton("Khôi phục", onClick = {
                    runShell("Khôi phục ${app.label}") { Boost.resetProfile(app.pkg, app.systemGame) }
                }, modifier = Modifier.weight(1f), enabled = !busy && game)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NeonButton("Kiểm tra", onClick = {
                    runShell("Cấu hình ${app.label}") { ShizukuManager.gameInfo(app.pkg) }
                }, modifier = Modifier.weight(1f), enabled = !busy)
                NeonButton("Mở game", onClick = { openGame(app) }, modifier = Modifier.weight(1f), enabled = !busy)
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
        val tick = permTick
        val canDraw = remember(tick) { Settings.canDrawOverlays(this@MainActivity) }
        val usage = remember(tick) { usageOk() }
        Column(
            modifier = modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            NeonCard {
                SectionTitle("Thanh bên trong game")
                SwitchRow(
                    "Hiện thanh bên khi mở game",
                    "FPS, nhiệt độ, CPU, RAM và chỉnh hiệu năng, độ phân giải ngay trên game",
                    autoOverlay
                ) { autoOverlay = it; putBool("autoOverlay", it) }
                Text(
                    (if (canDraw) "✔" else "✘") + " Hiển thị trên ứng dụng khác\n" +
                        (if (usage) "✔" else "✘") + " Theo dõi app đang mở (để chỉ hiện thanh bên khi game ở màn hình)",
                    color = if (canDraw && usage) Neon.Green else Neon.Amber,
                    fontSize = 12.sp
                )
                NeonButton(
                    "Cấp quyền qua Shizuku",
                    onClick = {
                        runShell("Cấp quyền thanh bên", after = {
                            permTick++
                            if (!Settings.canDrawOverlays(this)) {
                                try {
                                    startActivity(
                                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                                    )
                                } catch (e: Throwable) {}
                            }
                        }) { Boost.grantOverlayPerms(packageName) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy
                )
                Text(
                    "Một số ROM (MIUI, ColorOS…) còn cần bật thêm \"Hiển thị cửa sổ pop-up khi chạy nền\" trong cài đặt app.",
                    color = Neon.Muted,
                    fontSize = 11.sp
                )
            }
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
                ) { hz = it.toInt(); Store.prefs.edit().putInt("hz", hz).apply() }
                Text(
                    "Ghi min/peak_refresh_rate. Tuỳ máy, một số ROM từ chối. Bấm \"Khôi phục hệ thống\" để trả về giá trị cũ.",
                    color = Neon.Muted,
                    fontSize = 11.sp
                )
            }
            NeonCard {
                ProfileEditor(defProfile, null) { np ->
                    defProfile = np
                    Store.prefs.edit().putString("d_profile", np.encode()).apply()
                }
                Text("↑ Cấu hình mặc định cho game chưa từng chỉnh riêng.", color = Neon.Muted, fontSize = 11.sp)
            }
            NeonCard {
                SectionTitle("Biên dịch")
                ChipRow(
                    listOf("speed-profile" to "speed-profile", "speed" to "speed (nặng hơn)"),
                    compileMode
                ) { compileMode = it; Store.prefs.edit().putString("compileMode", it).apply() }
            }
            NeonCard {
                SectionTitle("Không gian")
                Text(
                    "Game trong không gian bị vô hiệu hoá tạm thời (pm disable-user) nên biến mất khỏi màn hình chính; dữ liệu game vẫn giữ nguyên. Khi mở từ GameSpace, game được bật lại và sẽ ẩn lại sau khi thoát. Lúc đang ẩn, game không nhận được thông báo.\n\n" +
                        "Trước khi gỡ GameSpace hãy bấm \"Đưa tất cả game ra ngoài\". Nếu lỡ gỡ rồi, chạy /sdcard/GameSpace/restore.sh bằng Shizuku để bật lại game.",
                    color = Neon.Muted,
                    fontSize = 12.sp
                )
                NeonButton(
                    "Đưa tất cả game ra ngoài",
                    onClick = { moveAllOut() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy && vault.isNotEmpty()
                )
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
