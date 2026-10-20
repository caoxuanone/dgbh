package com.gdxs.keepalive

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        return o.toString()
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
