package com.fieldtrack.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.database.DatabaseUtils
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import androidx.core.content.ContextCompat

class MainActivity : Activity() {
    private lateinit var w: WebView

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        w = WebView(this)
        setContentView(w)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.mediaPlaybackRequiresUserGesture = false
        w.webViewClient = WebViewClient()
        w.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(r: PermissionRequest) { runOnUiThread { r.grant(r.resources) } }
        }
        w.addJavascriptInterface(Bridge(), "FT")
        val l = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(l.toTypedArray(), 1)
        val u = prefs(this).getString("base", null)
        if (u == null) ask() else w.loadUrl(u)
    }

    override fun onStart() {
        super.onStart()
        val pend = DatabaseUtils.queryNumEntries(Db(this).readableDatabase, "pts")
        if (prefs(this).getBoolean("active", false) || pend > 0) ContextCompat.startForegroundService(this, Intent(this, LocationService::class.java))
    }

    private fun ask() {
        val e = EditText(this); e.hint = "https://track.yourcompany.com"
        AlertDialog.Builder(this).setTitle("Server address").setView(e).setCancelable(false)
            .setPositiveButton("Connect") { _, _ ->
                val v = e.text.toString().trim().trimEnd('/')
                if (v.startsWith("http")) { prefs(this).edit().putString("base", v).apply(); w.loadUrl(v) } else ask()
            }.show()
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, g: IntArray) {
        if (c == 1) { if (Build.VERSION.SDK_INT >= 29) requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 2) else battery() }
        else if (c == 2) battery()
    }

    private fun battery() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName))
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {}
    }

    @Deprecated("back")
    override fun onBackPressed() { if (w.canGoBack()) w.goBack() else super.onBackPressed() }

    inner class Bridge {
        @JavascriptInterface fun start(token: String) {
            prefs(this@MainActivity).edit().putString("token", token).putBoolean("active", true).apply()
            ContextCompat.startForegroundService(this@MainActivity, Intent(this@MainActivity, LocationService::class.java))
        }
        @JavascriptInterface fun stop() {
            prefs(this@MainActivity).edit().putBoolean("active", false).apply()
            ContextCompat.startForegroundService(this@MainActivity, Intent(this@MainActivity, LocationService::class.java).setAction("STOP"))
        }
        @JavascriptInterface fun lastPos(): String = prefs(this@MainActivity).getString("last", "") ?: ""
        @JavascriptInterface fun status(): String {
            val p = prefs(this@MainActivity)
            val n = DatabaseUtils.queryNumEntries(Db(this@MainActivity).readableDatabase, "pts")
            return "{\"pending\":$n,\"sent\":${p.getLong("sent", 0)},\"active\":${p.getBoolean("active", false)}}"
        }
        @JavascriptInterface fun bg(): Boolean = Build.VERSION.SDK_INT < 29 ||
            checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        @JavascriptInterface fun server() { runOnUiThread { ask() } }
    }
}
