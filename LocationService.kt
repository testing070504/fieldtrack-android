package com.fieldtrack.app

import android.app.*
import android.content.*
import android.Manifest
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

fun prefs(c: Context): SharedPreferences = c.getSharedPreferences("ft", Context.MODE_PRIVATE)

class Db(c: Context) : SQLiteOpenHelper(c, "pts.db", null, 1) {
    override fun onCreate(d: SQLiteDatabase) { d.execSQL("create table pts(ts integer primary key,lat real,lng real,acc real)") }
    override fun onUpgrade(d: SQLiteDatabase, a: Int, b: Int) {}
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        try { ContextCompat.startForegroundService(c, Intent(c, LocationService::class.java)) } catch (e: Exception) {}
    }
}

class LocationService : Service(), LocationListener, SensorEventListener {
    private lateinit var lm: LocationManager
    private lateinit var db: Db
    private val h = Handler(Looper.getMainLooper())
    private var wl: PowerManager.WakeLock? = null
    private var on = false
    @Volatile private var busy = false
    private var netCb: ConnectivityManager.NetworkCallback? = null
    private var fused: FusedLocationProviderClient? = null
    private var cb: LocationCallback? = null
    private var hbTs = 0L
    private val tick = object : Runnable {
        override fun run() {
            upload()
            if (prefs(this@LocationService).getBoolean("active", false) && System.currentTimeMillis() - hbTs > 60000) { hbTs = System.currentTimeMillis(); heartbeat() }
            h.postDelayed(this, 20000)
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        db = Db(this)
        lm = getSystemService(LOCATION_SERVICE) as LocationManager
    }

    private fun pending() = DatabaseUtils.queryNumEntries(db.readableDatabase, "pts")

    private fun startFg() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("trk", "Tracking", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, "trk").setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Get Tracked").setContentText("Work day tracking is active").setContentIntent(pi).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) else startForeground(1, n)
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        startFg()
        val active = prefs(this).getBoolean("active", false)
        if (i?.action == "STOP") endTracking()
        if (!active && pending() == 0L) { stopForeground(true); stopSelf(); return START_NOT_STICKY }
        if (active) begin()
        h.removeCallbacks(tick); h.post(tick)
        return START_STICKY
    }

    private fun begin() {
        if (on) return
        on = true; kVar = -1f; sTs = 0L; hasAnchor = false; rejN = 0; spd = 0f; still = false
        try { sm = getSystemService(SENSOR_SERVICE) as SensorManager; sm?.registerListener(this, sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_NORMAL) } catch (e: Exception) {}
        wl = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ft:trk").also { it.acquire() }
        startLocation()
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            netCb = object : ConnectivityManager.NetworkCallback() { override fun onAvailable(n: Network) { upload() } }
            cm.registerDefaultNetworkCallback(netCb!!)
        } catch (e: Exception) {}
    }

    private fun endTracking() {
        on = false
        try { sm?.unregisterListener(this) } catch (e: Exception) {}
        try { lm.removeUpdates(this) } catch (e: Exception) {}
        try { cb?.let { fused?.removeLocationUpdates(it) } } catch (e: Exception) {}
        wl?.let { if (it.isHeld) it.release() }
    }

    private var kLat = 0.0; private var kLng = 0.0; private var kVar = -1f; private var kTs = 0L
    private var sLat = 0.0; private var sLng = 0.0; private var sTs = 0L
    private var sm: SensorManager? = null
    private var aMean = 9.8f; private var aVar = 1f; private var still = false; private var stillSince = 0L
    private var anchorLat = 0.0; private var anchorLng = 0.0; private var hasAnchor = false
    private var rejN = 0; private var rejLat = 0.0; private var rejLng = 0.0; private var spd = 0f

    private fun startLocation() {
        val ok = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this) == ConnectionResult.SUCCESS
        if (ok) {
            try {
                val f = LocationServices.getFusedLocationProviderClient(this)
                val c = object : LocationCallback() {
                    override fun onLocationResult(r: LocationResult) { for (x in r.locations) onLocationChanged(x) }
                }
                val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L).setMinUpdateIntervalMillis(2000L).build()
                f.requestLocationUpdates(req, c, Looper.getMainLooper())
                fused = f; cb = c
                return
            } catch (e: Exception) {}
        }
        try { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000, 0f, this, Looper.getMainLooper()) } catch (e: Exception) {}
    }

    // Motion sensor: is the phone lying still? (tiny, steady acceleration for a while)
    override fun onSensorChanged(e: SensorEvent) {
        val m = Math.sqrt((e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]).toDouble()).toFloat()
        val dv = m - aMean
        aMean += 0.05f * dv
        aVar = aVar * 0.95f + 0.05f * dv * dv
        val s = aVar < 0.04f
        if (s && !still) stillSince = System.currentTimeMillis()
        still = s
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    private fun publish(lat: Double, lng: Double, acc: Float, ts: Long) {
        prefs(this).edit().putString("last", "{\"latitude\":$lat,\"longitude\":$lng,\"accuracy\":$acc,\"ts\":$ts}").apply()
    }

    @Suppress("DEPRECATION")
    override fun onLocationChanged(l: Location) {
        if (!prefs(this).getBoolean("active", false)) return
        val fake = if (Build.VERSION.SDK_INT >= 31) l.isMock else l.isFromMockProvider
        if (fake) { prefs(this).edit().putBoolean("mock", true).apply(); return }   // fake-GPS apps
        if (l.accuracy > 25f) return   // only good fixes
        val now = System.currentTimeMillis()
        val d = FloatArray(1)
        val moving = l.hasSpeed() && l.speed > 1.2f
        // 1) Phone is lying still (motion sensor quiet for 15 s, GPS shows no speed): hold the position, ignore GPS wobble
        if (still && hasAnchor && !moving && now - stillSince > 15000) { publish(anchorLat, anchorLng, l.accuracy, now); return }
        // 2) Outlier gate: a jump faster than the phone itself reports is rejected, unless 3 readings in a row agree on the new place
        if (sTs > 0) {
            Location.distanceBetween(sLat, sLng, l.latitude, l.longitude, d)
            val dt = (now - sTs) / 1000f
            val limit = Math.max(8f, 2.5f * Math.max(spd, if (l.hasSpeed()) l.speed else 0f) + 6f)
            if (dt > 0 && dt < 120 && d[0] > 40f && d[0] / dt > limit) {
                val dd = FloatArray(1)
                Location.distanceBetween(rejLat, rejLng, l.latitude, l.longitude, dd)
                rejN = if (rejN > 0 && dd[0] < 30f) rejN + 1 else 1
                rejLat = l.latitude; rejLng = l.longitude
                if (rejN < 3) return
            } else if (dt > 0) spd = 0.6f * spd + 0.4f * (d[0] / dt)
        }
        rejN = 0
        if (kVar < 0f) { kLat = l.latitude; kLng = l.longitude; kVar = l.accuracy * l.accuracy }
        else {           // Kalman smoothing: trust accurate fixes more
            kVar += ((now - kTs) / 1000f) * 9f
            val g = kVar / (kVar + l.accuracy * l.accuracy)
            kLat += g * (l.latitude - kLat); kLng += g * (l.longitude - kLng); kVar *= (1 - g)
        }
        kTs = now
        publish(kLat, kLng, l.accuracy, now)
        if (sTs > 0) {   // standing still: store at most one point a minute
            Location.distanceBetween(sLat, sLng, kLat, kLng, d)
            if (d[0] < Math.max(12f, l.accuracy * 0.6f) && now - sTs < 60000) return
        }
        sLat = kLat; sLng = kLng; sTs = now
        anchorLat = kLat; anchorLng = kLng; hasAnchor = true
        val v = ContentValues().apply { put("ts", now); put("lat", kLat); put("lng", kLng); put("acc", l.accuracy.toDouble()) }
        db.writableDatabase.insertWithOnConflict("pts", null, v, SQLiteDatabase.CONFLICT_IGNORE)
    }
    override fun onProviderEnabled(p: String) {}
    override fun onProviderDisabled(p: String) {}
    @Deprecated("old API") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}

    private fun postJson(url: String, tok: String, body: String): Int {
        val u = URL(url).openConnection() as HttpURLConnection
        u.requestMethod = "POST"; u.connectTimeout = 15000; u.readTimeout = 20000; u.doOutput = true
        u.setRequestProperty("Content-Type", "application/json"); u.setRequestProperty("Authorization", "Bearer $tok")
        u.outputStream.use { it.write(body.toByteArray()) }
        val c = u.responseCode; u.disconnect(); return c
    }

    // Why did the app stop last time? (Android 11+). Reported once, so the admin can see force-stops.
    private fun exitEvents(): JSONArray? {
        if (Build.VERSION.SDK_INT < 30) return null
        val last = prefs(this).getLong("exitChk", 0L)
        val out = JSONArray()
        try {
            val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            for (i in am.getHistoricalProcessExitReasons(packageName, 0, 8)) {
                if (i.timestamp <= last) continue
                val t = when (i.reason) {
                    ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_USER_STOPPED -> "force_stop"
                    ApplicationExitInfo.REASON_LOW_MEMORY -> "killed_memory"
                    ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash"
                    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE, ApplicationExitInfo.REASON_SIGNALED, ApplicationExitInfo.REASON_OTHER -> "killed"
                    else -> null
                } ?: continue
                out.put(JSONObject().put("ts", i.timestamp).put("type", t))
            }
        } catch (e: Exception) {}
        return if (out.length() > 0) out else null
    }

    // Sent every minute while on duty: battery, GPS switch, internet, background-location permission, fake-GPS flag
    private fun heartbeat() {
        Thread {
            try {
                val p = prefs(this)
                val base = p.getString("base", "") ?: ""
                val tok = p.getString("token", "") ?: ""
                val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val lvl = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val sc = b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
                val st = b?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                val pct = if (lvl >= 0 && sc > 0) lvl * 100 / sc else -1
                val chg = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
                val gps = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                val net = cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                val bg = Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
                val j = JSONObject().put("battery", pct).put("charging", if (chg) 1 else 0).put("gps", if (gps) 1 else 0)
                    .put("net", if (net) 1 else 0).put("bg", if (bg) 1 else 0).put("mock", if (p.getBoolean("mock", false)) 1 else 0)
                val ev = exitEvents()
                if (ev != null) j.put("events", ev)
                if (postJson("$base/api/health", tok, j.toString()) == 200) p.edit().putLong("exitChk", System.currentTimeMillis()).apply()
            } catch (e: Exception) {}
        }.start()
    }

    private fun upload() {
        if (busy) return
        busy = true
        Thread {
            try {
                val p = prefs(this)
                val tok = p.getString("token", "") ?: ""
                val base = p.getString("base", "") ?: ""
                while (true) {
                    val c = db.readableDatabase.rawQuery("select ts,lat,lng,acc from pts order by ts limit 200", null)
                    val arr = JSONArray(); var last = 0L
                    while (c.moveToNext()) {
                        arr.put(JSONObject().put("ts", c.getLong(0)).put("lat", c.getDouble(1)).put("lng", c.getDouble(2)).put("acc", c.getDouble(3)))
                        last = c.getLong(0)
                    }
                    c.close()
                    if (arr.length() == 0) break
                    val u = URL("$base/api/points").openConnection() as HttpURLConnection
                    u.requestMethod = "POST"; u.connectTimeout = 15000; u.readTimeout = 20000; u.doOutput = true
                    u.setRequestProperty("Content-Type", "application/json"); u.setRequestProperty("Authorization", "Bearer $tok")
                    u.outputStream.use { it.write(JSONObject().put("points", arr).toString().toByteArray()) }
                    val code = u.responseCode; u.disconnect()
                    if (code != 200) break
                    db.writableDatabase.delete("pts", "ts<=?", arrayOf(last.toString()))
                    p.edit().putLong("sent", p.getLong("sent", 0) + arr.length()).apply()
                }
            } catch (e: Exception) {
            } finally {
                busy = false
                if (!prefs(this).getBoolean("active", false) && pending() == 0L) h.post { stopForeground(true); stopSelf() }
            }
        }.start()
    }

    override fun onDestroy() {
        endTracking()
        h.removeCallbacks(tick)
        try { netCb?.let { (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it) } } catch (e: Exception) {}
        super.onDestroy()
    }
}
