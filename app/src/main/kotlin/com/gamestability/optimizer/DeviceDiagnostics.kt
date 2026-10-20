package com.gamestability.optimizer

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.app.ActivityManager
import android.view.Display
import android.view.WindowManager
import kotlin.math.roundToInt

/** Reads only public Android APIs. Missing values remain null rather than being fabricated. */
class DeviceDiagnostics(private val context: Context) {
    data class DeviceInfo(
        val model: String,
        val manufacturer: String,
        val androidVersion: String,
        val apiLevel: Int,
        val screenSize: String,
        val refreshRate: Float?,
        val supportedRefreshRates: List<Float>,
        val totalRamMb: Long?,
        val availableRamMb: Long?,
        val availableStorageMb: Long?,
        val batteryLevel: Int?
    )

    fun getDeviceInfo(): DeviceInfo {
        val batteryIntent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val level = batteryIntent?.getIntExtra("level", -1) ?: -1
        val scale = batteryIntent?.getIntExtra("scale", -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) ((level * 100f) / scale).roundToInt() else null

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        val ramAvailable = try {
            activityManager?.getMemoryInfo(memoryInfo)
            if (activityManager != null) memoryInfo.availMem / (1024 * 1024) else null
        } catch (_: Exception) { null }
        val ramTotal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            try { activityManager?.let { memoryInfo.totalMem / (1024 * 1024) } } catch (_: Exception) { null }
        } else null

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display
        } else {
            @Suppress("DEPRECATION")
            windowManager?.defaultDisplay
        }
        val refresh = try { display?.refreshRate?.takeIf { it > 0f } } catch (_: Exception) { null }
        val rates = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                display?.supportedModes?.map { it.refreshRate }
                    ?.filter { it > 0f }?.distinct()?.sorted() ?: emptyList()
            } else emptyList()
        } catch (_: Exception) { emptyList() }

        val availableStorage = try {
            val stat = StatFs(Environment.getDataDirectory().path)
            (stat.availableBytes / (1024 * 1024))
        } catch (_: Exception) { null }

        val metrics = context.resources.displayMetrics
        val screen = "${(metrics.widthPixels / metrics.density).roundToInt()} × ${(metrics.heightPixels / metrics.density).roundToInt()} dp"

        return DeviceInfo(
            model = Build.MODEL ?: "Không rõ",
            manufacturer = Build.MANUFACTURER ?: "Không rõ",
            androidVersion = Build.VERSION.RELEASE ?: "Không rõ",
            apiLevel = Build.VERSION.SDK_INT,
            screenSize = screen,
            refreshRate = refresh,
            supportedRefreshRates = rates,
            totalRamMb = ramTotal,
            availableRamMb = ramAvailable,
            availableStorageMb = availableStorage,
            batteryLevel = batteryPct
        )
    }
}
