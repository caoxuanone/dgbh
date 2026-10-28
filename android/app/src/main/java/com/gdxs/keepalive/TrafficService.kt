package com.gdxs.keepalive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import org.json.JSONArray
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 前台服务：真正的访问循环跑在这里，所以切到别的 App 也能继续。
 * 屏幕灭 → 暂停；屏幕亮 → 继续。
 */
class TrafficService : Service() {

    companion object {
        const val ACTION_START = "com.gdxs.keepalive.START"
        const val ACTION_STOP = "com.gdxs.keepalive.STOP"
        const val ACTION_APPLY = "com.gdxs.keepalive.APPLY"
        const val ACTION_HIT_ONCE = "com.gdxs.keepalive.HIT_ONCE"
        const val ACTION_PROBE = "com.gdxs.keepalive.PROBE"
        const val ACTION_PROBE_STOP = "com.gdxs.keepalive.PROBE_STOP"

        private const val CHANNEL_ID = "keepalive"
        private const val NOTIF_ID = 1001
        private const val PREFS = "gdxs"
        // 全程固定同一批测速文件，等于固定测速节点
        private const val FIXED_PIN = "__fixed__"
        private const val MONITOR_GAP = 5000L   // 每轮之间等 5 秒
        private const val MONITOR_CAP = 1800    // 单次监控最多 30 分钟
    }

    private lateinit var scheduler: ScheduledExecutorService
    private lateinit var workers: ExecutorService
    private lateinit var probeWorker: ExecutorService
    private var pending: ScheduledFuture<*>? = null
    private val bag = ArrayList<String>()

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> setScreen(false)
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> setScreen(true)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        scheduler = Executors.newSingleThreadScheduledExecutor()
        workers = Executors.newFixedThreadPool(6)
        probeWorker = Executors.newSingleThreadExecutor()
        createChannel()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }

        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        Traffic.screenOn = pm?.isInteractive ?: true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 系统重启服务（START_STICKY）时 intent 为 null
        if (intent == null) {
            restore()
            if (Traffic.running) {
                startForegroundCompat()
                scheduleNext(1200)
            } else {
                stopSelf()
                return START_NOT_STICKY
            }
            return START_STICKY
        }

        when (intent.action) {
            ACTION_STOP -> {
                Traffic.running = false
                pending?.cancel(false)
                pending = null
                save()
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                val wasRunning = Traffic.running
                applyExtras(intent)
                if (!wasRunning) Traffic.resetStats()
                Traffic.running = true
                save()
                startForegroundCompat()
                scheduleNext(400)
            }

            ACTION_APPLY -> {
                applyExtras(intent)
                save()
                startForegroundCompat()
                if (Traffic.running) scheduleNext(800)
            }

            ACTION_HIT_ONCE -> {
                val list = Traffic.parseTargets(intent.getStringExtra("targets"))
                hitOnce(list)
            }

            ACTION_PROBE -> {
                Traffic.limitKBs = intent.getDoubleExtra("limitKBs", 650.0)
                Traffic.fastMBs = intent.getDoubleExtra("fastMBs", 2.0)
                Traffic.waitMin = intent.getIntExtra("waitMin", 10).coerceIn(1, 60)
                Traffic.speedSource = intent.getStringExtra("src") ?: "public"
                val list = Traffic.parseTargets(intent.getStringExtra("targets"))
                startForegroundCompat()
                pending?.cancel(false)
                pending = null
                startProbe(list)
            }

            ACTION_PROBE_STOP -> {
                Traffic.probing = false
                if (!Traffic.running) {
                    stopForeground(true)
                    stopSelf()
                }
            }

            else -> {
                restore()
                if (Traffic.running) {
                    startForegroundCompat()
                    scheduleNext(1200)
                } else {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        pending?.cancel(false)
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {
        }
        scheduler.shutdownNow()
        workers.shutdownNow()
        probeWorker.shutdownNow()
        super.onDestroy()
    }

    // ---------- 循环 ----------

    private fun applyExtras(intent: Intent) {
        Traffic.interval = intent.getIntExtra("interval", Traffic.interval).coerceIn(3, 3600)
        Traffic.jitter = intent.getIntExtra("jitter", Traffic.jitter).coerceIn(0, 600)
        Traffic.burst = intent.getIntExtra("burst", Traffic.burst).coerceIn(1, 6)
        Traffic.targets = Traffic.parseTargets(intent.getStringExtra("targets"))
    }

    private fun scheduleNext(delayMs: Long = -1L) {
        pending?.cancel(false)
        pending = null
        if (!Traffic.running || !Traffic.screenOn) return

        val wait = if (delayMs >= 0) {
            delayMs
        } else {
            val base = Traffic.interval * 1000L
            val jit = if (Traffic.jitter > 0) {
                ((Math.random() * 2 - 1) * Traffic.jitter * 1000L).toLong()
            } else 0L
            maxOf(1500L, base + jit)
        }
        pending = scheduler.schedule({ tick() }, wait, TimeUnit.MILLISECONDS)
    }

    private fun tick() {
        if (!Traffic.running || !Traffic.screenOn || Traffic.probing) return
        // 一次唤醒里连发 burst 个请求：唤醒次数不变、流量翻倍，比「多发几次唤醒」省电
        repeat(Traffic.burst.coerceIn(1, 6)) {
            val url = nextTarget()
            workers.execute {
                val r = Traffic.fetch(url)
                Traffic.record(url, r)
                updateNotification()
            }
        }
        scheduleNext()
    }

    private fun nextTarget(): String {
        val list = Traffic.targets.ifEmpty { Traffic.DEFAULT_TARGETS }
        if (bag.isEmpty()) {
            bag.addAll(list)
            bag.shuffle()
        }
        while (bag.isNotEmpty()) {
            val u = bag.removeAt(bag.size - 1)
            if (u in list) return u
        }
        return list.random()
    }

    // ---------- 域名对比测试 ----------

    /** 分段睡眠，期间可被「停止测试」打断 */
    private fun sleepWhileProbing(ms: Int) {
        var waited = 0
        while (waited < ms && Traffic.probing) {
            Thread.sleep(500)
            waited += 500
        }
    }

    /** 测一次，失败重试一次；单位 MB/s，全失败返回 -1 */
    private fun measureMBs(): Double {
        repeat(2) { attempt ->
            val s = Traffic.speedTest(Traffic.speedSource, 10, FIXED_PIN)
            if (s.bytes > 0) return s.mbps / 8.0
            if (attempt == 0) Thread.sleep(2000)
        }
        return -1.0
    }

    /**
     * 反复测速，直到 < limitMBs（回到被限速状态）。
     * 每轮「测 10 秒 + 等 5 秒」，最多 Traffic.waitMin 分钟。
     */
    private fun waitThrottled(limitMBs: Double, label: String): Double {
        val deadline = System.currentTimeMillis() + Traffic.waitMin * 60_000L
        var v = measureMBs()
        while (Traffic.probing && v >= limitMBs && System.currentTimeMillis() < deadline) {
            val left = ((deadline - System.currentTimeMillis()) / 1000).toInt()
            Traffic.probePhase =
                "$label · 等回落 ${fmtClock(left)} · 当前 ${fmtSpeed(v)} MB/s"
            updateNotification()
            Thread.sleep(MONITOR_GAP)
            if (!Traffic.probing) break
            v = measureMBs()
        }
        return v
    }

    private fun fmtClock(sec: Int): String =
        "${sec / 60}:${String.format(Locale.CHINA, "%02d", sec % 60)}"

    private fun fmtSpeed(v: Double): String = String.format(Locale.CHINA, "%.2f", v)

    private fun startProbe(list: List<String>) {
        if (Traffic.probing) return
        val targets = if (list.isEmpty()) Traffic.DEFAULT_TARGETS else list
        Traffic.probeReset(targets.size)
        updateNotification()

        probeWorker.execute {
            try {
                // 先把公共测速源体检一遍，免得第一次测速里偷偷多花 20 秒
                if (Traffic.speedSource == "public") {
                    Traffic.probePhase = "准备测速源…"
                    updateNotification()
                    try {
                        Traffic.publicSources()
                    } catch (_: Exception) {
                    }
                }

                val limitMBs = maxOf(0.01, Traffic.limitKBs / 1000.0)
                var abort = false

                // 1. 开测检查：确认现在是被限速状态
                Traffic.probePhase = "开测检查：测速 10 秒…"
                updateNotification()
                val v0 = waitThrottled(limitMBs, "开测检查")
                if (!Traffic.probing) {
                    abort = true
                } else if (v0 < 0) {
                    Traffic.probePhase = "测速一直失败，先检查网络或换个测速源"
                    updateNotification()
                    Thread.sleep(3000)
                    abort = true
                } else {
                    Traffic.baseMBs = v0
                    if (v0 >= limitMBs) {
                        Traffic.notThrottled = true
                        abort = true
                    }
                }

                var last = if (abort) 0.0 else v0

                if (!abort) {
                    for (i in targets.indices) {
                        if (!Traffic.probing) break
                        val url = targets[i]
                        Traffic.probeIndex = i + 1
                        Traffic.probeCurrent = url

                        // 上一条结束时还在灰区，先等回落
                        if (last >= limitMBs) {
                            val v = waitThrottled(limitMBs, "第 ${i + 1} 个链接前等待")
                            if (!Traffic.probing) break
                            if (v >= limitMBs) {
                                Traffic.notThrottled = true
                                break
                            }
                            last = v
                        }

                        // 2. 访问目标
                        Traffic.probePhase = "访问中…"
                        updateNotification()
                        val r = Traffic.fetch(url)
                        Traffic.record(url, r)

                        if (r.status !in 200..399) {
                            Traffic.probeAdd(url, r.status, 0.0, emptyList(), 0, "fail")
                            updateNotification()
                            continue
                        }

                        // 3. 等 3 秒再测（加速起点从这里算）
                        val hitStart = System.currentTimeMillis()
                        Traffic.probePhase = "等 3 秒…"
                        updateNotification()
                        sleepWhileProbing(3000)
                        if (!Traffic.probing) break

                        // 连测 3 次，取最大值判定（提速是慢慢爬上去的，单次容易漏）
                        val runs = ArrayList<Double>()
                        for (k in 0 until 3) {
                            if (!Traffic.probing) break
                            Traffic.probePhase = "第 ${k + 1}/3 次测速…"
                            updateNotification()
                            runs.add(measureMBs())
                        }
                        if (!Traffic.probing) break

                        val valid = runs.filter { it >= 0 }
                        val v1 = if (valid.isEmpty()) -1.0 else valid.max()

                        if (v1 < 0) {
                            Traffic.probeAdd(url, r.status, 0.0, runs, 0, "fail")
                            last = limitMBs
                            continue
                        }

                        // 4. 3 次都没超过加速线 → 无效
                        if (v1 <= Traffic.fastMBs) {
                            Traffic.probeAdd(url, r.status, v1, runs, 0, "miss")
                            last = v1
                            continue
                        }

                        // 5. 解除了：一直监控到掉回限速线以下
                        var cur = v1
                        while (Traffic.probing) {
                            val alive = ((System.currentTimeMillis() - hitStart) / 1000).toInt()
                            Traffic.probePhase =
                                "已解除限速 · 监控 ${fmtClock(alive)} · 当前 ${fmtSpeed(cur)} MB/s"
                            updateNotification()
                            if (alive >= MONITOR_CAP) break
                            Thread.sleep(MONITOR_GAP)
                            if (!Traffic.probing) break
                            val v = measureMBs()
                            if (v < 0) continue
                            cur = v
                            if (v < limitMBs) break
                        }
                        if (!Traffic.probing) break

                        val alive = ((System.currentTimeMillis() - hitStart) / 1000).toInt()
                        Traffic.probeAdd(url, r.status, v1, runs, alive, "hit")
                        updateNotification()
                        last = cur
                    }
                }
            } catch (_: InterruptedException) {
            } finally {
                Traffic.probePhase = ""
                Traffic.probing = false
                Traffic.probeCurrent = ""
                Traffic.probeDoneAt = System.currentTimeMillis()
                save()

                if (Traffic.running) {
                    updateNotification()
                    scheduleNext(1500)
                } else {
                    stopForeground(true)
                    stopSelf()
                }
            }
        }
    }

    private fun hitOnce(list: List<String>) {
        val url = (if (list.isEmpty()) Traffic.DEFAULT_TARGETS else list).random()
        workers.execute {
            val r = Traffic.fetch(url)
            Traffic.record(url, r)
            if (Traffic.running) {
                updateNotification()
            } else {
                stopSelf()
            }
        }
    }

    private fun setScreen(on: Boolean) {
        Traffic.screenOn = on
        if (!Traffic.running) return
        if (!on) {
            pending?.cancel(false)
            pending = null
        } else {
            scheduleNext(1000)
        }
        updateNotification()
    }

    // ---------- 通知 ----------

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(CHANNEL_ID, "后台访问", NotificationManager.IMPORTANCE_LOW)
            ch.description = "显示访问次数与流量，可随时停止"
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
    }

    private fun startForegroundCompat() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, TrafficService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val title = if (Traffic.probing) "广电保活 · 域名测试中" else "广电保活 · 后台运行中"
        val text = when {
            Traffic.probing ->
                "${Traffic.probeIndex}/${Traffic.probeTotal} · " +
                    Traffic.probePhase.ifEmpty { Traffic.probeCurrent.removePrefix("https://") }.take(36)
            !Traffic.running -> "已停止"
            !Traffic.screenOn -> "屏幕已锁 · 已暂停"
            else -> "已访问 ${Traffic.hits} 次 · ${fmtBytes(Traffic.bytes)}"
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_keepalive)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stat_keepalive),
                    "停止",
                    stop
                ).build()
            )
            .build()
    }

    private fun updateNotification() {
        try {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            nm.notify(NOTIF_ID, buildNotification())
        } catch (_: Exception) {
        }
    }

    private fun fmtBytes(n: Long): String = when {
        n <= 0 -> "0 B"
        n < 1024 -> "$n B"
        n < 1024 * 1024 -> String.format("%.1f KB", n / 1024.0)
        else -> String.format("%.2f MB", n / 1024.0 / 1024.0)
    }

    // ---------- 配置持久化 ----------

    private fun save() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putInt("interval", Traffic.interval)
            .putInt("jitter", Traffic.jitter)
            .putInt("burst", Traffic.burst)
            .putString("targets", JSONArray(Traffic.targets).toString())
            .putBoolean("running", Traffic.running)
            .apply()
    }

    private fun restore() {
        val sp = getSharedPreferences(PREFS, MODE_PRIVATE)
        Traffic.interval = sp.getInt("interval", 60).coerceIn(3, 3600)
        Traffic.jitter = sp.getInt("jitter", 0).coerceIn(0, 600)
        Traffic.burst = sp.getInt("burst", 1).coerceIn(1, 6)
        Traffic.targets = Traffic.parseTargets(sp.getString("targets", null))
        Traffic.running = sp.getBoolean("running", false)
        if (Traffic.running && Traffic.startedAt == 0L) Traffic.resetStats()
    }
}
