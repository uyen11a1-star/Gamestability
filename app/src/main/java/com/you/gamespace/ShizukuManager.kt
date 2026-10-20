package com.you.gamespace

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

object ShizukuManager {

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

    // Thêm @SuppressLint để Kotlin không báo lỗi private API nữa
    @SuppressLint("PrivateApi")
    fun exec(cmd: String): String = try {
        val p = Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out.trim()
    } catch (e: Throwable) { "ERROR: ${e.message}" }

    fun execMulti(vararg cmds: String): String =
        cmds.joinToString("\n") { exec(it) }
}
