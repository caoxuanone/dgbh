package com.gdxs.keepalive

import java.util.concurrent.TimeUnit

/**
 * 网络重置。
 *
 * 普通 App 改不了飞行模式（需要 WRITE_SECURE_SETTINGS，属系统权限），
 * 所以这里只能走 root：有 su 就自动开关一次，没有就由调用方退回「手动提示」。
 */
object NetReset {

    @Volatile private var rootChecked: Boolean? = null
    @Volatile var lastMethod = ""

    fun hasRoot(): Boolean {
        rootChecked?.let { return it }
        val ok = exec("id")
        rootChecked = ok
        return ok
    }

    /** 关 10 秒再开，返回是否成功 */
    fun cycle(): Boolean {
        if (!hasRoot()) return false

        // Android 11+ 的官方开关
        if (exec("cmd connectivity airplane-mode enable")) {
            lastMethod = "airplane"
            Thread.sleep(10000)
            exec("cmd connectivity airplane-mode disable")
            return true
        }

        // 老系统：直接关数据，走一次 PDP 重连
        if (exec("svc data disable")) {
            lastMethod = "data"
            Thread.sleep(10000)
            exec("svc data enable")
            return true
        }

        // 再退一步：改设置 + 广播（部分 ROM 有效）
        if (exec("settings put global airplane_mode_on 1")) {
            exec("am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true")
            lastMethod = "settings"
            Thread.sleep(10000)
            exec("settings put global airplane_mode_on 0")
            exec("am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false")
            return true
        }
        return false
    }

    private fun exec(cmd: String): Boolean = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        val done = p.waitFor(8, TimeUnit.SECONDS)
        if (!done) p.destroy()
        done && p.exitValue() == 0
    } catch (_: Exception) {
        false
    }
}
