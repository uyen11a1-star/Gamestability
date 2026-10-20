package com.you.gamespace

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import rikka.shizuku.Shizuku
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class MainActivity : ComponentActivity() {

    private val state = mutableStateOf("Chưa kết nối Shizuku")
    private var crashError by mutableStateOf<String?>(null)

    private val permListener = Shizuku.OnRequestPermissionResultListener { _, grant ->
        state.value = if (grant == PackageManager.PERMISSION_GRANTED)
            "Đã cấp quyền Shizuku" else "Từ chối quyền"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Bắt lỗi toàn cục, ghi ra file
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            try {
                val file = File(getExternalFilesDir(null), "GameSpaceCrash.txt")
                file.writeText(sw.toString())
            } catch (e: Exception) {}
            android.os.Process.killProcess(android.os.Process.myPid())
        }

        try {
            // Thử khởi tạo Shizuku
            Shizuku.addRequestPermissionResultListener(permListener)
            
            // Nếu có lỗi trong lúc vẽ giao diện, nó sẽ nhảy vào catch
            setContent { 
                if (crashError != null) {
                    ShowCrashScreen(crashError!!)
                } else {
                    MainScreen()
                }
            }
        } catch (e: Throwable) {
            // Nếu lỗi ngay từ đầu, hiển thị màn hình đỏ
            crashError = e.stackTraceToString()
            setContent { ShowCrashScreen(crashError!!) }
        }
    }

    @Composable
    fun MainScreen() {
        MaterialTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("GameSpace", style = MaterialTheme.typography.headlineMedium)
                    Text(state.value)

                    Button(onClick = {
                        if (!ShizukuManager.isShizukuAvailable()) {
                            state.value = "Shizuku chưa chạy"
                        } else if (ShizukuManager.hasPermission()) {
                            state.value = "OK. wm size = " + ShizukuManager.exec("wm size")
                        } else {
                            ShizukuManager.requestPermission()
                        }
                    }) { Text("Kết nối / Test") }

                    Button(onClick = {
                        if (ShizukuManager.hasPermission()) {
                            ShizukuManager.execMulti(
                                "settings put global window_animation_scale 0",
                                "settings put global transition_animation_scale 0",
                                "settings put global animator_duration_scale 0"
                            )
                            state.value = "Đã tắt animation"
                        } else state.value = "Chưa có quyền Shizuku"
                    }) { Text("Boost: tắt animation") }
                }
            }
        }
    }

    @Composable
    fun ShowCrashScreen(error: String) {
        MaterialTheme {
            Surface(modifier = Modifier.fillMaxSize().background(Color(0xFF330000))) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("APP BỊ LỖI", color = Color.Red, fontSize = 20.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(error, color = Color.White, fontSize = 12.sp)
                }
            }
        }
    }

    override fun onDestroy() {
        try { Shizuku.removeRequestPermissionResultListener(permListener) } catch (e: Throwable) {}
        super.onDestroy()
    }
}
