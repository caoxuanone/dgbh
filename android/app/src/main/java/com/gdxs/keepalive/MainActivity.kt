package com.gdxs.keepalive

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private var askedBattery = false

    private val assetLoader by lazy {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        webView.setBackgroundColor(Color.parseColor("#0b0e14"))
        setContentView(webView)

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        webView.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)
        }

        webView.addJavascriptInterface(Bridge(), "AndroidBridge")
        webView.loadUrl("https://appassets.androidplatform.net/assets/www/index.html")

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onStart() {
        super.onStart()
        webView.evaluateJavascript("window.__dashVisible && window.__dashVisible(true)", null)
    }

    override fun onStop() {
        // 页面切后台就停掉每秒轮询，省电；访问循环在服务里不受影响
        webView.evaluateJavascript("window.__dashVisible && window.__dashVisible(false)", null)
        super.onStop()
    }

    override fun onDestroy() {
        try {
            webView.removeJavascriptInterface("AndroidBridge")
        } catch (_: Exception) {
        }
        webView.destroy()
        super.onDestroy()
    }

    /** 页面调用的原生接口 */
    inner class Bridge {

        @JavascriptInterface
        fun snapshot(): String = Traffic.snapshotJson()

        @JavascriptInterface
        fun start(interval: Int, jitter: Int, burst: Int, targetsJson: String) {
            val i = Intent(this@MainActivity, TrafficService::class.java).apply {
                action = TrafficService.ACTION_START
                putExtra("interval", interval)
                putExtra("jitter", jitter)
                putExtra("burst", burst)
                putExtra("targets", targetsJson)
            }
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            runOnUiThread { askBatteryOnce() }
        }

        @JavascriptInterface
        fun stop() {
            startService(
                Intent(this@MainActivity, TrafficService::class.java)
                    .setAction(TrafficService.ACTION_STOP)
            )
        }

        @JavascriptInterface
        fun applyConfig(interval: Int, jitter: Int, burst: Int, targetsJson: String) {
            if (!Traffic.running) return
            startService(
                Intent(this@MainActivity, TrafficService::class.java).apply {
                    action = TrafficService.ACTION_APPLY
                    putExtra("interval", interval)
                    putExtra("jitter", jitter)
                    putExtra("burst", burst)
                    putExtra("targets", targetsJson)
                }
            )
        }

        @JavascriptInterface
        fun hitOnce(targetsJson: String) {
            startService(
                Intent(this@MainActivity, TrafficService::class.java).apply {
                    action = TrafficService.ACTION_HIT_ONCE
                    putExtra("targets", targetsJson)
                }
            )
        }

        @JavascriptInterface
        fun startProbe(targetsJson: String, keep: Double, drop: Double, gap: Int, src: String) {
            val i = Intent(this@MainActivity, TrafficService::class.java).apply {
                action = TrafficService.ACTION_PROBE
                putExtra("targets", targetsJson)
                putExtra("keep", keep)
                putExtra("drop", drop)
                putExtra("gap", gap)
                putExtra("src", src)
            }
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        }

        @JavascriptInterface
        fun stopProbe() {
            startService(
                Intent(this@MainActivity, TrafficService::class.java)
                    .setAction(TrafficService.ACTION_PROBE_STOP)
            )
        }

        @JavascriptInterface
        fun openBatterySettings() {
            runOnUiThread { showBatterySettings() }
        }
    }

    private fun askBatteryOnce() {
        if (askedBattery) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        askedBattery = true
        AlertDialog.Builder(this)
            .setTitle("锁定后台")
            .setMessage(
                "允许忽略电池优化后，切到别的 App 或长时间不动时，系统更不容易杀掉后台访问服务。\n\n" +
                    "部分机型还需要在「最近任务」里下拉本应用卡片点锁头，或把它加进电池/后台白名单。"
            )
            .setPositiveButton("去设置") { _, _ -> showBatterySettings() }
            .setNegativeButton("稍后", null)
            .show()
    }

    private fun showBatterySettings() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (_: Exception) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (_: Exception) {
            }
        }
    }
}
