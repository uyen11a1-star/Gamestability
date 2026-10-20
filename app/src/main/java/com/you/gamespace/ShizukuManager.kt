package com.you.gamespace

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

object ShizukuManager {

    private val PKG_REGEX = Regex("^[A-Za-z0-9._]+$")

    fun isShizukuAvailable(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Throwable) { false }

    fun hasPermission(): Boolean {
        if (!isShizukuAvailable() || Shizuku.isPreV11()) return false
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) { false }
    }

    fun requestPermission(code: Int = 1001) {
        if (isShizukuAvailable() && !hasPermission()) {
            Shizuku.requestPermission(code)
        }
    }

    // newProcess là private ở Shizuku API mới (sẽ bị gỡ ở API 14) -> gọi bằng reflection
    // để build được với cả bản 13.0.0 lẫn bản mới. BLOCKING: chỉ gọi từ thread nền.
    fun exec(cmd: String): String = try {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        m.isAccessible = true
        val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        p.waitFor()
        (out + err).trim()
    } catch (e: Throwable) {
        "ERROR: ${e.cause?.message ?: e.message}"
    }

    private fun safe(pkg: String) = PKG_REGEX.matches(pkg)

    fun setAnimations(scale: String): String =
        listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")
            .joinToString("\n") { exec("settings put global $it $scale") }
            .ifBlank { "OK" }

    fun killBackground(): String = exec("am kill-all").ifBlank { "OK" }

    // cmd game mode [standard|performance|battery] <pkg>  (Android 12/13+, tuỳ game hỗ trợ)
    fun gameMode(pkg: String, mode: String): String =
        if (safe(pkg)) exec("cmd game mode $mode $pkg").ifBlank { "OK" } else "Tên package không hợp lệ"

    // cmd game downscale [0.5|0.6|0.7|0.8|0.9|disable] <pkg>
    fun downscale(pkg: String, factor: String): String =
        if (safe(pkg)) exec("cmd game downscale $factor $pkg").ifBlank { "OK" } else "Tên package không hợp lệ"

    // cmd package compile -m speed-profile -f <pkg>  (có thể chạy khá lâu)
    fun compile(pkg: String): String =
        if (safe(pkg)) exec("cmd package compile -m speed-profile -f $pkg").ifBlank { "OK" } else "Tên package không hợp lệ"

    fun forceStop(pkg: String): String =
        if (safe(pkg)) exec("am force-stop $pkg").ifBlank { "OK" } else "Tên package không hợp lệ"
}
