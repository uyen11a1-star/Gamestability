package com.you.gamespace

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {

    private data class AppItem(val label: String, val pkg: String)

    private var status by mutableStateOf("Chưa kết nối Shizuku")
    private var log by mutableStateOf("")
    private var busy by mutableStateOf(false)
    private var selected by mutableStateOf<AppItem?>(null)
    private var apps by mutableStateOf<List<AppItem>>(emptyList())
    private var crashError by mutableStateOf<String?>(null)

    private val permListener = Shizuku.OnRequestPermissionResultListener { _, grant ->
        runOnUiThread {
            status = if (grant == PackageManager.PERMISSION_GRANTED)
                "Shizuku sẵn sàng" else "Từ chối quyền"
        }
    }
    private val binderReceived = Shizuku.OnBinderReceivedListener { runOnUiThread { refreshStatus() } }
    private val binderDead = Shizuku.OnBinderDeadListener { runOnUiThread { refreshStatus() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            Shizuku.addRequestPermissionResultListener(permListener)
            Shizuku.addBinderReceivedListenerSticky(binderReceived)
            Shizuku.addBinderDeadListener(binderDead)
            loadApps()
        } catch (e: Throwable) {
            crashError = e.stackTraceToString()
        }
        setContent {
            val err = crashError
            if (err != null) ShowCrashScreen(err) else MainScreen()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        status = when {
            !ShizukuManager.isShizukuAvailable() -> "Shizuku chưa chạy"
            !ShizukuManager.hasPermission() -> "Shizuku đang chạy, chưa cấp quyền"
            else -> "Shizuku sẵn sàng"
        }
    }

    private fun loadApps() {
        Thread {
            val pm = packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val list = try {
                pm.queryIntentActivities(intent, 0)
                    .map { AppItem(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
                    .filter { it.pkg != packageName }
                    .distinctBy { it.pkg }
                    .sortedBy { it.label.lowercase() }
            } catch (e: Throwable) { emptyList() }
            runOnUiThread { apps = list }
        }.start()
    }

    // Chạy lệnh Shizuku ở thread nền, không chặn UI
    private fun runShell(title: String, after: (() -> Unit)? = null, block: () -> String) {
        if (busy) return
        if (!ShizukuManager.hasPermission()) { log = "Chưa có quyền Shizuku"; return }
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
                !ShizukuManager.isShizukuAvailable() ->
                    status = "Shizuku chưa chạy — mở app Shizuku và Start trước"
                ShizukuManager.hasPermission() -> {
                    refreshStatus()
                    runShell("wm size") { ShizukuManager.exec("wm size") }
                }
                else -> ShizukuManager.requestPermission()
            }
        } catch (e: Throwable) {
            status = "Lỗi kết nối: ${e.message}"
        }
    }

    private fun launchApp(pkg: String) {
        val i = packageManager.getLaunchIntentForPackage(pkg)
        if (i != null) startActivity(i) else log += "\nKhông mở được $pkg"
    }

    @Composable
    fun MainScreen() {
        MaterialTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("GameSpace", style = MaterialTheme.typography.headlineMedium)
                    Text(status)
                    Button(onClick = { connect() }) { Text("Kết nối / Cấp quyền") }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            enabled = !busy,
                            onClick = { runShell("Tắt animation") { ShizukuManager.setAnimations("0") } }
                        ) { Text("Tắt anim") }
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            enabled = !busy,
                            onClick = { runShell("Bật lại animation") { ShizukuManager.setAnimations("1.0") } }
                        ) { Text("Bật anim") }
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            enabled = !busy,
                            onClick = { runShell("Dọn RAM nền") { ShizukuManager.killBackground() } }
                        ) { Text("Dọn RAM") }
                    }

                    val sel = selected
                    if (sel != null) {
                        Text("Đã chọn: ${sel.label}", style = MaterialTheme.typography.titleSmall)
                        Button(
                            enabled = !busy,
                            onClick = {
                                runShell("Boost ${sel.label}", after = { launchApp(sel.pkg) }) {
                                    "Game mode: " + ShizukuManager.gameMode(sel.pkg, "performance") +
                                        "\nDọn RAM: " + ShizukuManager.killBackground()
                                }
                            }
                        ) { Text("Boost & mở game") }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                enabled = !busy,
                                onClick = { runShell("Downscale 0.8") { ShizukuManager.downscale(sel.pkg, "0.8") } }
                            ) { Text("Giảm độ phân giải") }
                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                enabled = !busy,
                                onClick = { runShell("Tắt downscale") { ShizukuManager.downscale(sel.pkg, "disable") } }
                            ) { Text("Tắt giảm") }
                        }
                        OutlinedButton(
                            enabled = !busy,
                            onClick = { runShell("Biên dịch speed-profile (lâu)") { ShizukuManager.compile(sel.pkg) } }
                        ) { Text("Biên dịch speed-profile (lâu)") }
                    }

                    if (log.isNotEmpty()) Text(log, fontSize = 12.sp, maxLines = 6)

                    Text("Chọn game:", style = MaterialTheme.typography.titleSmall)
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(apps, key = { it.pkg }) { app ->
                            val mark = if (app.pkg == selected?.pkg) "▶ " else ""
                            Text(
                                text = mark + app.label + "\n" + app.pkg,
                                fontSize = 13.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selected = app }
                                    .padding(vertical = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun ShowCrashScreen(error: String) {
        MaterialTheme {
            Surface(modifier = Modifier.fillMaxSize().background(Color(0xFF330000))) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("CÓ LỖI XẢY RA", color = Color.Red, fontSize = 20.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(error, color = Color.White, fontSize = 12.sp)
                }
            }
        }
    }

    override fun onDestroy() {
        try {
            Shizuku.removeRequestPermissionResultListener(permListener)
            Shizuku.removeBinderReceivedListener(binderReceived)
            Shizuku.removeBinderDeadListener(binderDead)
        } catch (e: Throwable) {}
        super.onDestroy()
    }
}
