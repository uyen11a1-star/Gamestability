package com.gamestability.optimizer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { GameStabilityApp() }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @androidx.compose.runtime.Composable
    private fun GameStabilityApp() {
        val diagnostics = remember { DeviceDiagnostics(this) }
        val thermalMonitor = remember { ThermalMonitor(this) }
        var deviceInfo by remember { mutableStateOf(diagnostics.getDeviceInfo()) }
        var thermalState by remember { mutableStateOf(thermalMonitor.getCurrentThermalState()) }
        var showAdvice by remember { mutableStateOf(false) }
        var selectedProfile by remember { mutableStateOf(GameProfile.STABLE) }

        DisposableEffect(thermalMonitor) {
            thermalMonitor.startMonitoring { thermalState = it }
            onDispose { thermalMonitor.stopMonitoring() }
        }

        val background = Color(0xFF0B1018)
        val panel = Color(0xFF151E2B)
        val accent = Color(0xFF62E6B2)
        val muted = Color(0xFFA8B5C6)
        MaterialTheme(colorScheme = darkColorScheme(
            primary = accent,
            onPrimary = Color(0xFF07130F),
            background = background,
            surface = panel,
            onBackground = Color(0xFFF3F7FB),
            onSurface = Color(0xFFF3F7FB)
        )) {
            Surface(modifier = Modifier.fillMaxSize(), color = background) {
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("GAME STABILITY", color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                            Spacer(Modifier.height(5.dp))
                            Text("Chơi mượt hơn.\nGiữ máy mát hơn.", fontSize = 27.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold)
                        }
                        Box(
                            modifier = Modifier.width(62.dp).height(62.dp).background(Color(0xFF17352E), RoundedCornerShape(18.dp)),
                            contentAlignment = Alignment.Center
                        ) { Text("FPS", color = accent, fontSize = 18.sp, fontWeight = FontWeight.Black) }
                    }
                    Text("Trợ lý kiểm tra và tối ưu an toàn • Không root • Không ADB", color = muted, fontSize = 13.sp)

                    Card(colors = CardDefaults.cardColors(containerColor = panel), shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("TRẠNG THÁI NHIỆT", color = muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(thermalState.statusName, modifier = Modifier.weight(1f), fontSize = 24.sp, fontWeight = FontWeight.Bold,
                                    color = if (thermalState.status >= android.os.PowerManager.THERMAL_STATUS_SEVERE && thermalState.isAvailable) Color(0xFFFF8D80) else accent)
                                Text(if (thermalState.isAvailable) "● ĐANG THEO DÕI" else "○ CHƯA HỖ TRỢ", color = muted, fontSize = 10.sp)
                            }
                            Text(thermalMonitor.advice(thermalState), color = Color(0xFFD5DFEA), fontSize = 14.sp, lineHeight = 20.sp)
                            if (thermalState.headroom != null) {
                                Text("Chỉ số headroom: ${"%.2f".format(thermalState.headroom)} (ước tính hệ thống, không phải °C)", color = muted, fontSize = 11.sp)
                            }
                        }
                    }

                    Text("CẤU HÌNH CHƠI GAME", color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
                    Card(colors = CardDefaults.cardColors(containerColor = panel), shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Chọn mục tiêu để nhận gợi ý phù hợp", color = Color(0xFFD5DFEA), fontSize = 13.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = selectedProfile == GameProfile.COOL,
                                    onClick = { selectedProfile = GameProfile.COOL },
                                    label = { Text("Mát máy") }
                                )
                                FilterChip(
                                    selected = selectedProfile == GameProfile.STABLE,
                                    onClick = { selectedProfile = GameProfile.STABLE },
                                    label = { Text("Ổn định FPS") }
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = selectedProfile == GameProfile.LOW_GRAPHICS,
                                    onClick = { selectedProfile = GameProfile.LOW_GRAPHICS },
                                    label = { Text("Đồ họa nhẹ") }
                                )
                            }
                            Text(selectedProfile.title, color = accent, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text(selectedProfile.instructions, color = Color(0xFFD5DFEA), fontSize = 13.sp, lineHeight = 20.sp)
                            Text("Lưu ý: đây là cấu hình hướng dẫn. Android không cho app này tự đổi chất lượng đồ họa của game khác.", color = muted, fontSize = 11.sp, lineHeight = 16.sp)
                        }
                    }

                    Text("THÔNG TIN THIẾT BỊ", color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
                    Card(colors = CardDefaults.cardColors(containerColor = panel), shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            InfoRow("Thiết bị", "${deviceInfo.manufacturer} ${deviceInfo.model}")
                            InfoRow("Android", "${deviceInfo.androidVersion} (API ${deviceInfo.apiLevel})")
                            InfoRow("Màn hình", deviceInfo.screenSize)
                            InfoRow("Tần số quét hiện tại", deviceInfo.refreshRate?.let { "${"%.0f".format(it)} Hz" } ?: "Không có dữ liệu")
                            InfoRow("Chế độ quét được báo cáo", if (deviceInfo.supportedRefreshRates.isEmpty()) "Không có dữ liệu" else deviceInfo.supportedRefreshRates.joinToString { "${it.toInt()} Hz" })
                            InfoRow("RAM tổng", deviceInfo.totalRamMb?.let { "${it / 1024} GB" } ?: "Không có dữ liệu")
                            InfoRow("RAM khả dụng", deviceInfo.availableRamMb?.let { "${it} MB" } ?: "Không có dữ liệu")
                            InfoRow("Bộ nhớ trong còn trống", deviceInfo.availableStorageMb?.let { "${it / 1024} GB" } ?: "Không có dữ liệu")
                            InfoRow("Pin", deviceInfo.batteryLevel?.let { "$it%" } ?: "Không có dữ liệu")
                        }
                    }

                    Button(
                        onClick = { deviceInfo = diagnostics.getDeviceInfo(); thermalState = thermalMonitor.getCurrentThermalState(); showAdvice = true },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(15.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = accent)
                    ) { Text("PHÂN TÍCH & TỐI ƯU AN TOÀN", fontWeight = FontWeight.Bold) }

                    if (showAdvice) {
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF17352E)), shape = RoundedCornerShape(18.dp)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("GỢI Ý CHO PHIÊN CHƠI", color = accent, fontWeight = FontWeight.Bold)
                                Text("• Hạ đồ họa hoặc hiệu ứng trong chính game nếu FPS tụt.\n• Nếu máy nóng, giảm độ sáng và nghỉ khi cần.\n• Đóng thủ công ứng dụng nặng mà bạn không dùng.\n• Bật chế độ game/hiệu năng của OPPO nếu máy có cung cấp.", color = Color(0xFFE2EEE9), fontSize = 13.sp, lineHeight = 20.sp)
                                Text("Ứng dụng không tự ép xung, đóng cưỡng chế app khác hoặc thay đổi đồ họa game.", color = muted, fontSize = 11.sp)
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = { try { startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS)) } catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) } },
                        modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(15.dp)
                    ) { Text("MỞ CÀI ĐẶT MÀN HÌNH") }
                    OutlinedButton(
                        onClick = { openSettingsSafely(Settings.ACTION_BATTERY_SAVER_SETTINGS) },
                        modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(15.dp)
                    ) { Text("MỞ CÀI ĐẶT TIẾT KIỆM PIN") }
                    OutlinedButton(
                        onClick = { openSettingsSafely(Settings.ACTION_DISPLAY_SETTINGS) },
                        modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(15.dp)
                    ) { Text("MỞ CÀI ĐẶT ĐỘ SÁNG / MÀN HÌNH") }
                    OutlinedButton(
                        onClick = { try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) } },
                        modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(15.dp)
                    ) { Text("CÀI ĐẶT ỨNG DỤNG") }

                    Text("Lưu ý: Android không cho app thông thường ép xung CPU/GPU hoặc đo FPS của mọi game bên ngoài. Màn hình 90 Hz không có nghĩa game luôn chạy 90 FPS. Các số liệu chỉ hiển thị khi hệ thống cung cấp.", color = muted, fontSize = 11.sp, lineHeight = 16.sp)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }

    private fun openSettingsSafely(action: String) {
        try {
            startActivity(Intent(action))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private enum class GameProfile(val title: String, val instructions: String) {
        COOL(
            "Ưu tiên mát máy",
            "• Hạ FPS trong game xuống mức ổn định vừa đủ.\n• Giảm độ sáng màn hình ở thanh cài đặt nhanh.\n• Tránh chơi khi đang sạc nếu máy nóng.\n• Tháo ốp dày nếu máy nóng rõ rệt và đặt máy ở nơi thoáng."
        ),
        STABLE(
            "Ưu tiên FPS ổn định",
            "• Chọn FPS cố định mà game giữ được lâu thay vì mức cao nhất.\n• Giảm bóng đổ, phản chiếu, hiệu ứng và khử răng cưa trước.\n• Đóng thủ công ứng dụng nặng không dùng.\n• Kiểm tra lại sau 10–15 phút chơi để phát hiện tụt hiệu năng do nhiệt."
        ),
        LOW_GRAPHICS(
            "Đồ họa nhẹ / ít hiệu ứng",
            "• Trong cài đặt game, đặt Graphics/Quality ở Low hoặc Smooth.\n• Tắt Shadow, Motion Blur, Bloom và hiệu ứng hậu kỳ nếu game có.\n• Giảm Render Scale/Resolution nếu game cung cấp.\n• Chỉ tăng FPS sau khi game ổn định; nếu máy nóng hoặc khựng, thử hạ một mức."
        )
    }

    @androidx.compose.runtime.Composable
    private fun InfoRow(label: String, value: String) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), color = Color(0xFFA8B5C6), fontSize = 13.sp)
            Spacer(Modifier.width(12.dp))
            Text(value, color = Color(0xFFF3F7FB), fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}
