package com.gamestability.optimizer

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log

/** Uses the public thermal status API (Android 10+). It does not read sensor files. */
class ThermalMonitor(context: Context) {
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private var listener: PowerManager.OnThermalStatusChangedListener? = null

    data class ThermalState(
        val status: Int,
        val statusName: String,
        val isAvailable: Boolean,
        val headroom: Float? = null
    )

    fun getCurrentThermalState(): ThermalState {
        val pm = powerManager ?: return unavailable()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return unavailable()
        val status = try { pm.currentThermalStatus } catch (e: Exception) {
            Log.w(TAG, "Could not read thermal status", e)
            THERMAL_STATUS_UNKNOWN
        }
        val headroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                pm.getThermalHeadroom(10).takeIf { !it.isNaN() && !it.isInfinite() && it >= 0f }
            } catch (e: Exception) {
                Log.w(TAG, "Could not read thermal headroom", e)
                null
            }
        } else null
        return ThermalState(status, statusToName(status), status != THERMAL_STATUS_UNKNOWN, headroom)
    }

    fun startMonitoring(onUpdate: (ThermalState) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            onUpdate(unavailable())
            return
        }
        val pm = powerManager ?: run { onUpdate(unavailable()); return }
        stopMonitoring()
        val newListener = PowerManager.OnThermalStatusChangedListener { status ->
            val current = getCurrentThermalState()
            onUpdate(current.copy(status = status, statusName = statusToName(status), isAvailable = true))
        }
        listener = newListener
        try {
            pm.addThermalStatusListener(newListener)
            onUpdate(getCurrentThermalState())
        } catch (e: Exception) {
            listener = null
            Log.w(TAG, "Could not register thermal listener", e)
            onUpdate(unavailable())
        }
    }

    fun stopMonitoring() {
        val old = listener ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try { powerManager?.removeThermalStatusListener(old) }
            catch (e: Exception) { Log.w(TAG, "Could not remove thermal listener", e) }
        }
        listener = null
    }

    fun advice(state: ThermalState): String = when {
        !state.isAvailable -> "Thiết bị chưa cung cấp trạng thái nhiệt qua API Android này. Hãy theo dõi cảm giác nóng và cảnh báo của hệ thống."
        state.status >= PowerManager.THERMAL_STATUS_CRITICAL -> "Máy đang ở trạng thái nhiệt rất cao. Hãy dừng chơi và để máy nguội tự nhiên."
        state.status >= PowerManager.THERMAL_STATUS_SEVERE -> "Máy khá nóng. Hạ đồ họa/FPS trong game và nghỉ một lúc nếu hiện tượng kéo dài."
        state.status >= PowerManager.THERMAL_STATUS_MODERATE -> "Nhiệt độ đang tăng. Có thể giảm độ sáng và đóng các tác vụ nặng mà bạn không dùng."
        state.status >= PowerManager.THERMAL_STATUS_LIGHT -> "Trạng thái nhiệt nhẹ. Tiếp tục theo dõi khi chơi lâu."
        else -> "Chưa có dấu hiệu nhiệt cao theo trạng thái hệ thống hiện tại."
    }

    private fun unavailable() = ThermalState(
        THERMAL_STATUS_UNKNOWN, "Không có dữ liệu", false
    )

    private fun statusToName(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "Bình thường"
        PowerManager.THERMAL_STATUS_LIGHT -> "Nhẹ"
        PowerManager.THERMAL_STATUS_MODERATE -> "Trung bình"
        PowerManager.THERMAL_STATUS_SEVERE -> "Cao"
        PowerManager.THERMAL_STATUS_CRITICAL -> "Rất cao"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "Khẩn cấp"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "Sắp tắt do nhiệt"
        else -> "Không có dữ liệu"
    }

    companion object {
        private const val TAG = "ThermalMonitor"
        private const val THERMAL_STATUS_UNKNOWN = -1
    }
}
