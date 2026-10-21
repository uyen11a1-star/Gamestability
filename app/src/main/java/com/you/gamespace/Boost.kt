package com.you.gamespace

import android.content.Context
import android.content.pm.PackageManager
import android.util.Base64
import kotlin.math.roundToInt

// Logic chạy lệnh Shizuku mức cao: hồ sơ game, không gian (ẩn icon), cấp quyền.
// Mọi hàm ở đây BLOCKING -> gọi từ thread nền.
object Boost {

    private val PKG_REGEX = Regex("^[A-Za-z0-9._]+$")
    private val DIGITS = Regex("^[0-9]+$")

    // ---------- Hồ sơ game ----------

    fun applyProfile(pkg: String, p: Profile, systemGame: Boolean): String {
        if (systemGame) return ShizukuManager.applyGameProfile(pkg, p.mode, p.scale, p.fps)
        // App chưa được Android nhận là game: lệnh `cmd game` bị bỏ qua -> dùng compat framework
        if (p.mode != "custom") {
            return "App chưa được Android nhận là game nên Chuẩn/Hiệu năng/Tiết kiệm không có tác dụng. Dùng chế độ Tuỳ chỉnh để giảm độ phân giải."
        }
        return compatScale(pkg, p.scale) + "\n(FPS không chỉnh được với app chưa được nhận là game)"
    }

    fun resetProfile(pkg: String, systemGame: Boolean): String {
        val a = if (systemGame) ShizukuManager.resetGame(pkg) else ""
        return (a + "\n" + compatScale(pkg, "disable")).trim()
    }

    // DOWNSCALED + DOWNSCALE_xx (compat framework). App bị ép về mức bật cao nhất,
    // nên chỉ bật đúng 1 mức: reset mức cũ trước.
    private fun compatScale(pkg: String, scale: String): String {
        if (!PKG_REGEX.matches(pkg) || scale !in ShizukuManager.SCALES) return "Tham số không hợp lệ"
        val key = "compat_$pkg"
        val prev = Store.prefs.getString(key, null)?.takeIf { DIGITS.matches(it) }
        if (scale == "disable") {
            if (prev != null) ShizukuManager.exec("am compat reset DOWNSCALE_$prev $pkg")
            val r = ShizukuManager.exec("am compat reset DOWNSCALED $pkg")
            Store.prefs.edit().remove(key).apply()
            return "Độ phân giải về mặc định. " + r.take(120)
        }
        val level = (scale.toFloat() * 100).roundToInt()
        if (prev != null && prev != level.toString()) ShizukuManager.exec("am compat reset DOWNSCALE_$prev $pkg")
        val a = ShizukuManager.exec("am compat enable DOWNSCALED $pkg")
        val b = ShizukuManager.exec("am compat enable DOWNSCALE_$level $pkg")
        Store.prefs.edit().putString(key, level.toString()).apply()
        return "Độ phân giải $level% (compat): ${a.take(80)} | ${b.take(80)}"
    }

    // ---------- Không gian (ẩn app khỏi launcher) ----------
    // Shell/Shizuku KHÔNG đổi được trạng thái từng activity của app thường (AOSP chặn),
    // chỉ đổi được cả package giữa ENABLED <-> DISABLED_USER => dùng `pm disable-user`.

    fun isRunning(pkg: String): Boolean {
        if (!PKG_REGEX.matches(pkg)) return true
        val r = ShizukuManager.exec("pidof $pkg")
        if (r.isBlank()) return false
        return !Regex("^[0-9 ]+$").matches(r) // lỗi lạ -> coi như đang chạy cho an toàn
    }

    private fun isHidden(pm: PackageManager, pkg: String): Boolean = try {
        val st = pm.getApplicationEnabledSetting(pkg)
        st == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER ||
            st == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    } catch (e: Throwable) { false }

    private fun setHidden(pkg: String, hidden: Boolean): String {
        if (!PKG_REGEX.matches(pkg)) return "Tên package không hợp lệ"
        val verb = if (hidden) "disable-user" else "enable"
        return ShizukuManager.exec("pm $verb --user 0 $pkg")
    }

    // Chuyển game vào không gian: vô hiệu hoá app => biến mất khỏi launcher
    fun moveIn(ctx: Context, pkg: String): String {
        if (!PKG_REGEX.matches(pkg)) return "Tên package không hợp lệ"
        if (isRunning(pkg)) return "$pkg đang chạy — thoát game rồi chuyển vào không gian"
        val out = setHidden(pkg, true)
        if (!isHidden(ctx.packageManager, pkg)) {
            return "Máy từ chối ẩn $pkg: ${out.take(160)}"
        }
        Store.putSet("vault", Store.getSet("vault") + pkg)
        writeRestoreScript()
        return "Đã chuyển $pkg vào không gian"
    }

    fun moveOut(ctx: Context, pkg: String): String {
        val out = setHidden(pkg, false)
        Store.putSet("vault", Store.getSet("vault") - pkg)
        writeRestoreScript()
        return if (isHidden(ctx.packageManager, pkg)) "Chưa hiện lại $pkg: ${out.take(160)}" else "Đã đưa $pkg ra ngoài"
    }

    // Bật lại app trước khi mở game từ không gian
    fun ensureLaunchable(ctx: Context, pkg: String): String {
        if (pkg !in Store.getSet("vault")) return ""
        val out = setHidden(pkg, false)
        for (i in 0 until 15) {
            if (!isHidden(ctx.packageManager, pkg)) return ""
            Thread.sleep(200)
        }
        return "Chưa bật được $pkg: ${out.take(160)}"
    }

    // Ẩn lại các game trong không gian nếu đang hiện và game không chạy
    // (disable-user sẽ tắt app nên tuyệt đối không làm khi game đang chạy)
    fun sweep(ctx: Context): String {
        val sb = StringBuilder()
        for (pkg in Store.getSet("vault")) {
            if (isHidden(ctx.packageManager, pkg)) continue
            if (isRunning(pkg)) continue
            setHidden(pkg, true)
            sb.append("Ẩn lại $pkg\n")
        }
        return sb.toString().trim()
    }

    // File khôi phục: chạy được bằng Shizuku/rish nếu lỡ gỡ GameSpace khi game còn bị ẩn
    fun writeRestoreScript() {
        val sb = StringBuilder("#!/system/bin/sh\n# GameSpace: hiện lại các game đang ẩn\n")
        for (pkg in Store.getSet("vault")) {
            if (PKG_REGEX.matches(pkg)) sb.append("pm enable --user 0 ").append(pkg).append('\n')
        }
        val b64 = Base64.encodeToString(sb.toString().toByteArray(), Base64.NO_WRAP)
        ShizukuManager.exec("mkdir -p /sdcard/GameSpace && echo '$b64' | base64 -d > /sdcard/GameSpace/restore.sh")
    }

    // ---------- Quyền cho thanh bên ----------

    fun grantOverlayPerms(self: String): String {
        if (!PKG_REGEX.matches(self)) return "Tên package không hợp lệ"
        val a = ShizukuManager.exec("cmd appops set $self SYSTEM_ALERT_WINDOW allow").ifBlank { "OK" }
        val b = ShizukuManager.exec("cmd appops set $self GET_USAGE_STATS allow").ifBlank { "OK" }
        val c = ShizukuManager.exec("pm grant $self android.permission.POST_NOTIFICATIONS").ifBlank { "OK" }
        return "Hiển thị trên app khác: $a\nTheo dõi app đang mở: $b\nThông báo: ${c.take(100)}"
    }
}
