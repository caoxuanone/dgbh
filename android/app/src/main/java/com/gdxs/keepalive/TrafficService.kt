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

        private const val CHANNEL_ID = "keepalive"
        private const val NOTIF_ID = 1001
        private const val PREFS = "gdxs"
    }

    private lateinit var scheduler: ScheduledExecutorService
    private lateinit var workers: ExecutorService
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
        if (!Traffic.running || !Traffic.screenOn) return
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
        val text = when {
            !Traffic.running -> "已停止"
            !Traffic.screenOn -> "屏幕已锁 · 已暂停"
            else -> "已访问 ${Traffic.hits} 次 · ${fmtBytes(Traffic.bytes)}"
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("广电保活 · 后台运行中")
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
