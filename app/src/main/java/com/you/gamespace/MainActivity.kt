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

class MainActivity : ComponentActivity() {

    private val state = mutableStateOf("Chưa kết nối Shizuku")
    private var crashError by mutableStateOf<String?>(null)

    private val permListener = Shizuku.OnRequestPermissionResultListener { _, grant ->
        state.value = if (grant == PackageManager.PERMISSION_GRANTED)
            "Đã cấp quyền Shizuku" else "Từ chối quyền"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            // Thử khởi tạo Shizuku, nếu lỗi sẽ nhảy xuống catch
            Shizuku.addRequestPermissionResultListener(permListener)
            
            setContent { 
                if (crashError != null) ShowCrashScreen(crashError!!) else MainScreen()
            }
        } catch (e: Throwable) {
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
                        try {
                            if (!ShizukuManager.isShizukuAvailable()) {
                                state.value = "Shizuku chưa chạy"
                            } else if (ShizukuManager.hasPermission()) {
                                state.value = "OK. wm size = " + ShizukuManager.exec("wm size")
                            } else {
                                ShizukuManager.requestPermission()
                            }
                        } catch (e: Throwable) {
                            state.value = "Lỗi nút bấm: ${e.message}"
                        }
                    }) { Text("Kết nối / Test") }

                    Button(onClick = {
                        try {
                            if (ShizukuManager.hasPermission()) {
                                ShizukuManager.execMulti(
                                    "settings put global window_animation_scale 0",
                                    "settings put global transition_animation_scale 0",
                                    "settings put global animator_duration_scale 0"
                                )
                                state.value = "Đã tắt animation"
                            } else state.value = "Chưa có quyền Shizuku"
                        } catch (e: Throwable) {
                            state.value = "Lỗi Boost: ${e.message}"
                        }
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
                    Text("CÓ LỖI XẢY RA", color = Color.Red, fontSize = 20.sp)
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
