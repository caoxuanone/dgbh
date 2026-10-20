package com.gdxs.keepalive

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

/**
 * 全局运行状态 + HTTP 请求实现。
 * 前台服务负责循环，页面只读这里的快照。
 */
object Traffic {

    const val UA =
        "Mozilla/5.0 (Linux; Android 14; PGT-AN10) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Mobile Safari/537.36"

    val DEFAULT_TARGETS = listOf(
        "https://www.10099.com.cn/",
        "https://www.10099.com.cn/index.html",
        "https://www.10099.com.cn/login.html",
        "https://www.10099.com.cn/help-center.html",
        "https://www.10099.com.cn/browser-version.html",
        "https://www.10099.com.cn/transaction-manage-package-selection.html",
        "https://www.10099.com.cn/transaction-manage-number-selection.html",
        "https://www.10099.com.cn/personal-center-deposit-order.html",
        "https://www.10099.com.cn/help-center-video-ringtone.html",
        "https://cblink.10099.com.cn/",
        "https://partner.10099.com.cn/",
        "https://www.10099.com.cn/js/common-85ed7.js",
        "https://www.10099.com.cn/js/vendor-21074.js",
        "https://www.10099.com.cn/js/polyfill-45864.js",
        "https://www.10099.com.cn/js/sdc_10.12.7.1min.js",
        "https://www.10099.com.cn/css/common-b2baa.css",
        "https://www.10099.com.cn/css/vendor-e996f.css"
    )

    @Volatile var running = false
    @Volatile var screenOn = true
    @Volatile var hits = 0
    @Volatile var ok = 0
    @Volatile var fail = 0
    @Volatile var bytes = 0L
    @Volatile var startedAt = 0L
    @Volatile var interval = 60
    @Volatile var jitter = 0
    @Volatile var burst = 1
    @Volatile var targets: List<String> = DEFAULT_TARGETS

    // ---- 域名对比测试 ----
    @Volatile var probing = false
    @Volatile var probeIndex = 0
    @Volatile var probeTotal = 0
    @Volatile var probeCurrent = ""
    @Volatile var probePhase = ""
    @Volatile var probeDoneAt = 0L
    @Volatile var upRatio = 1.5
    @Volatile var downRatio = 0.67
    @Volatile var probeGap = 90
    @Volatile var speedSource = "public"
    @Volatile var targetsVersion = 0

    private val probeResults = ArrayDeque<String>()
    private val probeLock = Any()

    /** 测速源。public = speedtest.im 的公开下载源清单（国内 CDN 大文件）。 */
    val SPEED_SOURCES = mapOf(
        "gdxs" to "https://www.10099.com.cn/js/common-85ed7.js",
        "tencent" to "https://mirrors.cloud.tencent.com/ubuntu/ls-lR.gz",
        "huawei" to "https://mirrors.huaweicloud.com/ubuntu/ls-lR.gz"
    )

    private const val PUBLIC_CATALOG = "https://speedtest.im/api/download_sources.php"
    // 只有 1、2 这两个池是下载源；U1 是 Ookla 的上传端点
    private val PUBLIC_POOLS = listOf("1", "2")
    private const val MIN_FILE_BYTES = 20L * 1024 * 1024

    @Volatile private var healthyUrls: List<String> = emptyList()
    @Volatile private var healthyAt = 0L
    private val fileQueue = ArrayDeque<String>()
    private val pinned = HashMap<String, List<String>>()
    private val badFiles = HashMap<String, Long>()
    private val publicLock = Any()

    data class SpeedSample(val mbps: Double, val seg: List<Double>, val bytes: Long)

    private val log = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.CHINA)

    data class Result(
        val status: Int,
        val size: Int,
        val ms: Long,
        val waf: Boolean,
        val error: String?
    )

    fun resetStats() {
        hits = 0
        ok = 0
        fail = 0
        bytes = 0L
        startedAt = System.currentTimeMillis()
        synchronized(log) { log.clear() }
    }

    fun record(url: String, r: Result) {
        hits++
        bytes += r.size
        if (r.status in 200..399) ok++ else fail++
        val o = JSONObject()
            .put("t", fmt.format(Date()))
            .put("url", url)
            .put("status", r.status)
            .put("bytes", r.size)
            .put("ms", r.ms)
            .put("waf", r.waf)
        if (r.error != null) o.put("error", r.error)
        synchronized(log) {
            log.addFirst(o.toString())
            while (log.size > 60) log.removeLast()
        }
    }

    fun snapshotJson(): String {
        val o = JSONObject()
        o.put("running", running)
        o.put("screenOn", screenOn)
        o.put("hits", hits)
        o.put("ok", ok)
        o.put("fail", fail)
        o.put("bytes", bytes)
        o.put("interval", interval)
        o.put("jitter", jitter)
        o.put("burst", burst)
        o.put(
            "uptimeMs",
            if (running && startedAt > 0) System.currentTimeMillis() - startedAt else 0L
        )
        val arr = JSONArray()
        synchronized(log) { for (line in log) arr.put(JSONObject(line)) }
        o.put("log", arr)
        o.put("targets", JSONArray(targets))
        o.put("targetsVersion", targetsVersion)
        o.put("probe", probeJson())
        return o.toString()
    }

    // ---------- 域名对比测试 ----------

    fun probeReset(total: Int) {
        synchronized(probeLock) { probeResults.clear() }
        probing = true
        probeIndex = 0
        probeTotal = total
        probeCurrent = ""
        probePhase = ""
        probeDoneAt = 0L
    }

    fun probeAdd(
        url: String,
        status: Int,
        base: Double,
        after: Double,
        ratio: Double,
        seg: List<Double>,
        verdict: String
    ) {
        val o = JSONObject()
            .put("url", url)
            .put("status", status)
            .put("base", Math.round(base * 10.0) / 10.0)
            .put("after", Math.round(after * 10.0) / 10.0)
            .put("ratio", Math.round(ratio * 100.0) / 100.0)
            .put("seg", JSONArray(seg.map { Math.round(it * 10.0) / 10.0 }))
            .put("verdict", verdict)
        synchronized(probeLock) { probeResults.addLast(o.toString()) }
    }

    fun probeVerdicts(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        synchronized(probeLock) {
            for (line in probeResults) {
                val o = JSONObject(line)
                out.add(o.optString("url") to o.optString("verdict"))
            }
        }
        return out
    }

    fun probeJson(): JSONObject {
        val p = JSONObject()
            .put("running", probing)
            .put("index", probeIndex)
            .put("total", probeTotal)
            .put("current", probePhase.ifEmpty { probeCurrent })
            .put("doneAt", probeDoneAt)
            .put("up", upRatio)
            .put("down", downRatio)
        val arr = JSONArray()
        synchronized(probeLock) { for (line in probeResults) arr.put(JSONObject(line)) }
        p.put("results", arr)
        return p
    }

    // ---------- 公共测速源 ----------

    private fun isBad(u: String): Boolean {
        val t = synchronized(publicLock) { badFiles[u] } ?: return false
        if (System.currentTimeMillis() - t > 30 * 60 * 1000) {
            synchronized(publicLock) { badFiles.remove(u) }
            return false
        }
        return true
    }

    private fun markBad(u: String) {
        synchronized(publicLock) { badFiles[u] = System.currentTimeMillis() }
    }

    /** 拉清单 + 逐个体检（Range 取 2 字节，确认真的能下），缓存 10 分钟 */
    fun publicSources(): List<String> {
        val now = System.currentTimeMillis()
        val cached = healthyUrls
        if (cached.isNotEmpty() && now - healthyAt < 10 * 60 * 1000) return cached

        val raw = ArrayList<String>()
        var conn: HttpURLConnection? = null
        try {
            val c = (URL(PUBLIC_CATALOG).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 12000
                useCaches = false
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Referer", "https://speedtest.im/")
            }
            conn = c
            val txt = c.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
            val j = JSONObject(txt)
            val pools = j.optJSONObject("healthTokens") ?: j.optJSONObject("downloadSourcePools")
            if (pools != null) {
                for (k in PUBLIC_POOLS) {
                    val v = pools.opt(k) ?: continue
                    if (v is JSONObject) {
                        val keys = v.keys()
                        while (keys.hasNext()) {
                            val u = keys.next()
                            if (u.startsWith("http")) raw.add(u)
                        }
                    } else if (v is JSONArray) {
                        for (i in 0 until v.length()) {
                            val u = v.optString(i)
                            if (u.startsWith("http")) raw.add(u)
                        }
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            conn?.disconnect()
        }
        if (raw.isEmpty()) return cached

        // 体检：并发 12，整体最多 15 秒
        val found = java.util.Collections.synchronizedList(ArrayList<Pair<String, Long>>())
        val cursor = AtomicLong(0)
        val checkBy = System.currentTimeMillis() + 15000
        val pool = Executors.newFixedThreadPool(12)
        try {
            val futures = (0 until 12).map {
                pool.submit(Runnable {
                    while (true) {
                        if (System.currentTimeMillis() > checkBy) return@Runnable
                        val idx = cursor.getAndIncrement()
                        if (idx >= raw.size) return@Runnable
                        val u = raw[idx.toInt()]
                        val size = probeFile(u)
                        if (size > 0) found.add(u to size)
                    }
                })
            }
            for (f in futures) {
                try {
                    f.get(18000, TimeUnit.MILLISECONDS)
                } catch (_: Exception) {
                }
            }
        } finally {
            pool.shutdownNow()
        }

        var list = found.filter { it.second >= MIN_FILE_BYTES }.sortedByDescending { it.second }
        if (list.size < 4) list = found.sortedByDescending { it.second }.take(8)
        if (list.isEmpty()) return cached

        val urls = list.map { it.first }
        healthyUrls = urls
        healthyAt = System.currentTimeMillis()
        synchronized(publicLock) {
            fileQueue.clear()
            val head = urls.take(24).shuffled()
            fileQueue.addAll(head)
        }
        return urls
    }

    /** Range 取 2 字节，返回文件总大小；失败返回 0 */
    private fun probeFile(u: String): Long {
        var conn: HttpURLConnection? = null
        return try {
            val c = (URL(u).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                useCaches = false
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Range", "bytes=0-1")
            }
            conn = c
            val code = c.responseCode
            if (code !in 200..299) return 0L
            val cr = c.getHeaderField("Content-Range") ?: ""
            val total = Regex("/(\\d+)\\s*\$").find(cr)?.groupValues?.get(1)?.toLongOrNull()
            total ?: (c.getHeaderField("Content-Length")?.toLongOrNull() ?: 0L)
        } catch (_: Exception) {
            0L
        } finally {
            conn?.disconnect()
        }
    }

    private fun takeFromQueue(n: Int): List<String> {
        val out = ArrayList<String>()
        synchronized(publicLock) {
            var guard = 0
            while (out.size < n && guard++ < 400) {
                if (fileQueue.isEmpty()) {
                    val all = healthyUrls
                    if (all.isEmpty()) break
                    fileQueue.addAll(all.take(24).shuffled())
                }
                val u = fileQueue.removeFirst()
                if (isBad(u) || out.contains(u)) continue
                out.add(u)
            }
        }
        return out
    }

    /** 同一个 pin（域名）前后两次测速用同一批文件 */
    private fun pickFiles(pin: String, n: Int): List<String> {
        if (pin.isNotEmpty()) {
            val cur = synchronized(publicLock) { pinned[pin] }
            if (cur != null) {
                val good = cur.filter { !isBad(it) }
                if (good.size == cur.size) return cur
                val filled = good + takeFromQueue(n - good.size)
                synchronized(publicLock) { pinned[pin] = filled }
                return filled
            }
        }
        val out = takeFromQueue(n)
        if (pin.isNotEmpty()) {
            synchronized(publicLock) {
                if (pinned.size > 32) pinned.clear()
                pinned[pin] = out
            }
        }
        return out
    }

    /**
     * 按时间测下行速率（默认 30 秒），单位 Mbps。
     * 4 并发，丢掉前 1 秒热身，返回总速度 + 三段速度。
     */
    fun speedTest(src: String = speedSource, seconds: Int = 30, pin: String = ""): SpeedSample {
        val totalMs = seconds * 1000L
        val list: List<String> = if (src == "public") {
            val all = try { publicSources() } catch (_: Exception) { emptyList() }
            val picked = if (all.isNotEmpty()) pickFiles(pin, 4) else emptyList()
            when {
                picked.isNotEmpty() -> picked
                all.isNotEmpty() -> all.take(4)
                else -> listOf(SPEED_SOURCES.getValue("tencent"))
            }
        } else {
            listOf(SPEED_SOURCES[src] ?: SPEED_SOURCES.getValue("gdxs"))
        }

        val t0 = System.currentTimeMillis()
        val deadline = t0 + totalMs
        val segMs = totalMs / 3
        val buckets = AtomicLongArray(3)
        val total = AtomicLong(0)
        val stop = AtomicBoolean(false)
        val pool = Executors.newFixedThreadPool(4)

        try {
            val futures = (0 until 4).map { idx ->
                pool.submit(Runnable {
                    var i = idx
                    while (!stop.get() && System.currentTimeMillis() < deadline) {
                        val u = list[i % list.size]
                        i += 1
                        if (deadline - System.currentTimeMillis() < 800) break

                        var got = 0L
                        var conn: HttpURLConnection? = null
                        try {
                            val c = (URL(u).openConnection() as HttpURLConnection).apply {
                                connectTimeout = 5000
                                readTimeout = 4000 // 停摆 4 秒就换文件
                                useCaches = false
                                setRequestProperty("User-Agent", UA)
                                setRequestProperty("Cache-Control", "no-cache")
                            }
                            conn = c
                            val code = c.responseCode
                            if (code !in 200..299) {
                                markBad(u)
                                continue
                            }
                            val buf = ByteArray(32 * 1024)
                            val stream = c.inputStream
                            try {
                                while (true) {
                                    val n = stream.read(buf)
                                    if (n <= 0) break
                                    got += n.toLong()
                                    val t = System.currentTimeMillis() - t0
                                    if (t < 1000) continue // 热身
                                    if (t >= totalMs) {
                                        stop.set(true)
                                        break
                                    }
                                    val b = minOf(2, (t / segMs).toInt())
                                    buckets.addAndGet(b, n.toLong())
                                    total.addAndGet(n.toLong())
                                }
                            } finally {
                                try {
                                    stream.close()
                                } catch (_: Exception) {
                                }
                            }
                            if (got == 0L) markBad(u)
                        } catch (_: Exception) {
                            if (got == 0L) markBad(u)
                        } finally {
                            conn?.disconnect()
                        }
                    }
                })
            }
            for (f in futures) {
                try {
                    f.get(totalMs + 9000, TimeUnit.MILLISECONDS)
                } catch (_: Exception) {
                }
            }
        } finally {
            pool.shutdownNow()
        }

        val bytes = total.get()
        val span = doubleArrayOf(
            maxOf(1000L, segMs - 1000).toDouble(),
            segMs.toDouble(),
            segMs.toDouble()
        )
        val seg = (0..2).map { i -> Math.round(buckets.get(i) * 8.0 / span[i] / 1000.0 * 10.0) / 10.0 }
        val mbps = if (bytes > 0) bytes * 8.0 / ((totalMs - 1000) / 1000.0) / 1_000_000.0 else 0.0
        return SpeedSample(Math.round(mbps * 10.0) / 10.0, seg, bytes)
    }

    fun fetch(url: String): Result {
        val started = System.currentTimeMillis()
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 20000
                instanceFollowRedirects = true
                requestMethod = "GET"
                useCaches = false
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("User-Agent", UA)
                setRequestProperty(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
                )
                setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val buf = stream?.use { it.readBytes() } ?: ByteArray(0)
            val head = String(buf, 0, minOf(buf.size, 4096), Charsets.UTF_8)
            Result(
                status = code,
                size = buf.size,
                ms = System.currentTimeMillis() - started,
                waf = head.contains("aliyun_waf_aa") || head.contains("id=\"renderData\""),
                error = null
            )
        } catch (e: Exception) {
            Result(0, 0, System.currentTimeMillis() - started, false, e.message ?: "error")
        } finally {
            conn?.disconnect()
        }
    }

    fun parseTargets(json: String?): List<String> {
        if (json.isNullOrBlank()) return DEFAULT_TARGETS
        return try {
            val arr = JSONArray(json)
            val out = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val s = arr.optString(i, "").trim()
                if (s.startsWith("http://") || s.startsWith("https://")) out.add(s)
            }
            if (out.isEmpty()) DEFAULT_TARGETS else out
        } catch (_: Exception) {
            DEFAULT_TARGETS
        }
    }
}
