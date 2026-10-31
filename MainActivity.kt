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
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.os.UserManager
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

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
        lockDown()
        val pend = DatabaseUtils.queryNumEntries(Db(this).readableDatabase, "pts")
        if (prefs(this).getBoolean("active", false) || pend > 0) ContextCompat.startForegroundService(this, Intent(this, LocationService::class.java))
    }

    private fun dpm() = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private fun admin() = ComponentName(this, AdminReceiver::class.java)
    private val locks = listOf(UserManager.DISALLOW_FACTORY_RESET, UserManager.DISALLOW_SAFE_BOOT, UserManager.DISALLOW_APPS_CONTROL, UserManager.DISALLOW_CONFIG_LOCATION)

    // Runs only when this phone was set up as a company-managed device (Device Owner)
    private fun lockDown() {
        val d = dpm()
        if (!d.isDeviceOwnerApp(packageName)) return
        val a = admin()
        try { d.setUninstallBlocked(a, packageName, true) } catch (e: Exception) {}
        for (r in locks) try { d.addUserRestriction(a, r) } catch (e: Exception) {}
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 29) perms.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        for (p in perms) try { d.setPermissionGrantState(a, packageName, p, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED) } catch (e: Exception) {}
    }

    private fun unlock() {
        val d = dpm(); val a = admin()
        try { d.setUninstallBlocked(a, packageName, false) } catch (e: Exception) {}
        for (r in locks) try { d.clearUserRestriction(a, r) } catch (e: Exception) {}
        try { d.clearDeviceOwnerApp(packageName) } catch (e: Exception) {}
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
        @JavascriptInterface fun managed(): Boolean = dpm().isDeviceOwnerApp(packageName)
        @JavascriptInterface fun release(id: String, pw: String) {
            Thread {
                var ok = false
                try {
                    val base = prefs(this@MainActivity).getString("base", "") ?: ""
                    val u = URL("$base/api/verify-admin").openConnection() as HttpURLConnection
                    u.requestMethod = "POST"; u.doOutput = true; u.connectTimeout = 15000; u.readTimeout = 15000
                    u.setRequestProperty("Content-Type", "application/json")
                    u.outputStream.use { it.write(JSONObject().put("phone", id).put("password", pw).toString().toByteArray()) }
                    ok = u.responseCode == 200
                } catch (e: Exception) {}
                runOnUiThread {
                    if (ok) { unlock(); Toast.makeText(this@MainActivity, "Phone released. The app can now be uninstalled.", Toast.LENGTH_LONG).show() }
                    else Toast.makeText(this@MainActivity, "Wrong admin login, or no internet", Toast.LENGTH_LONG).show()
                }
            }.start()
        }
        @JavascriptInterface fun server() { runOnUiThread { ask() } }
    }
}
