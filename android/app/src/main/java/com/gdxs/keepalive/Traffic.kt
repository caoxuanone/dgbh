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
import java.util.concurrent.atomic.AtomicLong

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

    // ---- 逐域名测试 ----
    @Volatile var probing = false
    @Volatile var probeIndex = 0
    @Volatile var probeTotal = 0
    @Volatile var probeCurrent = ""
    @Volatile var probeDoneAt = 0L
    @Volatile var keepMbps = 5.0
    @Volatile var dropMbps = 0.5
    @Volatile var probeGap = 90
    @Volatile var speedSource = "gdxs"
    @Volatile var targetsVersion = 0

    private val probeResults = ArrayDeque<String>()
    private val probeLock = Any()

    /** 测速源。公共镜像大多对单 IP 限速，只作对照。 */
    val SPEED_SOURCES = mapOf(
        "gdxs" to "https://www.10099.com.cn/js/common-85ed7.js",
        "tencent" to "https://mirrors.cloud.tencent.com/ubuntu/ls-lR.gz",
        "huawei" to "https://mirrors.huaweicloud.com/ubuntu/ls-lR.gz"
    )

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

    // ---------- 逐域名测试 ----------

    fun probeReset(total: Int) {
        synchronized(probeLock) { probeResults.clear() }
        probing = true
        probeIndex = 0
        probeTotal = total
        probeCurrent = ""
        probeDoneAt = 0L
    }

    fun probeAdd(url: String, status: Int, mbps: Double, verdict: String) {
        val o = JSONObject()
            .put("url", url)
            .put("status", status)
            .put("mbps", Math.round(mbps * 10.0) / 10.0)
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
            .put("current", probeCurrent)
            .put("doneAt", probeDoneAt)
            .put("keep", keepMbps)
            .put("drop", dropMbps)
        val arr = JSONArray()
        synchronized(probeLock) { for (line in probeResults) arr.put(JSONObject(line)) }
        p.put("results", arr)
        return p
    }

    /**
     * 测下行速率，单位 Mbps；失败返回 0。
     * 4 个并发连接拉同一个源，最多 4MB 或 6 秒——单连接受 TCP 慢启动影响，波动很大。
     */
    fun speedTest(src: String = speedSource): Double {
        val url = SPEED_SOURCES[src] ?: SPEED_SOURCES.getValue("gdxs")
        val started = System.currentTimeMillis()
        val limitMs = 6000L
        val cap = 4_000_000L
        val total = AtomicLong(0)
        val pool = Executors.newFixedThreadPool(4)

        try {
            val futures = (0 until 4).map { idx ->
                pool.submit {
                    var pos = idx * 1_048_576L
                    while (total.get() < cap && System.currentTimeMillis() - started < limitMs) {
                        var conn: HttpURLConnection? = null
                        try {
                            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                                connectTimeout = 6000
                                readTimeout = 6000
                                useCaches = false
                                setRequestProperty("Cache-Control", "no-cache")
                                setRequestProperty("User-Agent", UA)
                                setRequestProperty("Range", "bytes=$pos-${pos + 1_048_575L}")
                            }
                            val code = conn.responseCode
                            // 文件比 Range 短（比如 565KB 的 js）：从头重下
                            if (code == 416) {
                                pos = 0L
                                continue
                            }
                            if (code < 200 || code >= 300) break

                            var read = 0L
                            val buf = ByteArray(32 * 1024)
                            conn.inputStream.use { stream ->
                                while (true) {
                                    val n = stream.read(buf)
                                    if (n <= 0) break
                                    read += n
                                    total.addAndGet(n.toLong())
                                    if (total.get() >= cap) break
                                    if (System.currentTimeMillis() - started >= limitMs) break
                                }
                            }
                            // 206 支持分段，往后接着拉；200 是整文件，下一轮从头
                            pos = if (code == 206) pos + read else 0L
                            if (read == 0L) break
                        } catch (_: Exception) {
                            break
                        } finally {
                            conn?.disconnect()
                        }
                    }
                }
            }
            for (f in futures) {
                try {
                    f.get(limitMs + 3000, TimeUnit.MILLISECONDS)
                } catch (_: Exception) {
                }
            }
        } finally {
            pool.shutdownNow()
        }

        val sec = (System.currentTimeMillis() - started) / 1000.0
        val bytes = total.get()
        if (sec < 0.2 || bytes < 100_000L) return 0.0
        return bytes * 8.0 / sec / 1_000_000.0
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
