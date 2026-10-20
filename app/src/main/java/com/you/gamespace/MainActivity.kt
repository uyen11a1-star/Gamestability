package com.you.gamespace

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {

    private val state = mutableStateOf("Chưa kết nối Shizuku")

    private val permListener = Shizuku.OnRequestPermissionResultListener { _, grant ->
        state.value = if (grant == PackageManager.PERMISSION_GRANTED)
            "Đã cấp quyền Shizuku" else "Từ chối quyền"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Bọc try-catch để nếu Shizuku lỗi thì app vẫn chạy, hiện lỗi lên màn hình
        try {
            Shizuku.addRequestPermissionResultListener(permListener)
        } catch (e: Throwable) {
            state.value = "Lỗi Shizuku: ${e.message}"
        }

        setContent {
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
    }

    override fun onDestroy() {
        try {
            Shizuku.removeRequestPermissionResultListener(permListener)
        } catch (e: Throwable) {}
        super.onDestroy()
    }
}
