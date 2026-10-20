package com.you.gamespace

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import kotlin.math.roundToInt

object ShizukuManager {

    // Giá trị hợp lệ lấy từ `cmd game help` (AOSP GameManagerShellCommand)
    val GAME_MODES = listOf("standard", "performance", "battery", "custom")
    val SCALES = listOf(
        "0.3", "0.35", "0.4", "0.45", "0.5", "0.55", "0.6",
        "0.65", "0.7", "0.75", "0.8", "0.85", "0.9", "disable"
    )
    val FPS = listOf("disable", "30", "45", "60", "90", "120")
    val COMPILE_MODES = listOf("speed-profile", "speed")

    private val PKG_REGEX = Regex("^[A-Za-z0-9._]+$")
    private var helpCache: String? = null

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
    // "2>&1" gộp stderr vào stdout để không bị treo khi stderr đầy.
    fun exec(cmd: String): String = try {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        m.isAccessible = true
        val p = m.invoke(null, arrayOf("sh", "-c", "($cmd) 2>&1"), null, null) as Process
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out.trim()
    } catch (e: Throwable) {
        "ERROR: ${e.cause?.message ?: e.message}"
    }

    private fun safe(pkg: String) = PKG_REGEX.matches(pkg)

    // ---- Hệ thống ----

    fun setAnimations(scale: String): String =
        listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")
            .joinToString("\n") { exec("settings put global $it $scale") }
            .ifBlank { "OK" }

    // am kill-all: kill các tiến trình nền an toàn (cached...)
    fun killBackground(): String = exec("am kill-all").ifBlank { "OK" }

    // cmd power set-fixed-performance-mode-enabled [true|false] (Android 11+, tuỳ máy)
    fun fixedPerformance(on: Boolean): String =
        exec("cmd power set-fixed-performance-mode-enabled $on").ifBlank { "OK" }

    // ---- Game Mode ----

    private fun gameHelp(): String {
        helpCache?.let { return it }
        val h = exec("cmd game help")
        if (h.isNotBlank() && !h.startsWith("ERROR")) helpCache = h
        return h
    }

    // Android mới: `cmd game set --downscale X --fps N <pkg>`.
    // Android cũ: chỉ có lệnh `downscale` (đã deprecated ở bản mới).
    private fun supportsGameSet() = gameHelp().contains("--downscale")

    fun applyGameProfile(pkg: String, mode: String, scale: String, fps: String): String {
        if (!safe(pkg)) return "Tên package không hợp lệ"
        if (mode !in GAME_MODES) return "Chế độ không hợp lệ"
        if (mode != "custom") return exec("cmd game mode $mode $pkg").ifBlank { "OK ($mode)" }
        if (scale !in SCALES || fps !in FPS) return "Giá trị không hợp lệ"
        return if (supportsGameSet()) {
            exec("cmd game set --downscale $scale --fps $fps $pkg")
                .ifBlank { "OK (độ phân giải $scale, FPS $fps)" }
        } else {
            val legacy = if (scale == "disable") "disable"
            else "0." + (scale.toFloat() * 10).roundToInt().coerceIn(5, 9)
            exec("cmd game downscale $legacy $pkg").ifBlank { "OK (độ phân giải $legacy)" } +
                "\n(Máy cũ: chỉ chỉnh được độ phân giải, không có FPS)"
        }
    }

    fun resetGame(pkg: String): String {
        if (!safe(pkg)) return "Tên package không hợp lệ"
        val a = if (supportsGameSet()) exec("cmd game set --downscale disable --fps disable $pkg")
        else exec("cmd game downscale disable $pkg")
        val b = exec("cmd game mode standard $pkg")
        return (a + "\n" + b).trim().ifBlank { "OK" }
    }

    fun gameInfo(pkg: String): String {
        if (!safe(pkg)) return "Tên package không hợp lệ"
        return exec("cmd game list-modes $pkg") + "\n" + exec("cmd game list-configs $pkg")
    }

    // ---- Biên dịch / process ----

    fun compile(pkg: String, mode: String): String {
        if (!safe(pkg) || mode !in COMPILE_MODES) return "Tham số không hợp lệ"
        return exec("cmd package compile -m $mode -f $pkg").ifBlank { "OK" }
    }

    fun resetCompile(pkg: String): String =
        if (safe(pkg)) exec("cmd package compile --reset $pkg").ifBlank { "OK" } else "Tên package không hợp lệ"

    fun forceStop(pkg: String): String =
        if (safe(pkg)) exec("am force-stop $pkg").ifBlank { "OK" } else "Tên package không hợp lệ"
}
