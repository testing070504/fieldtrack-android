package com.fieldtrack.app

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
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

class LocationService : Service(), LocationListener {
    private lateinit var lm: LocationManager
    private lateinit var db: Db
    private val h = Handler(Looper.getMainLooper())
    private var wl: PowerManager.WakeLock? = null
    private var on = false
    @Volatile private var busy = false
    private var netCb: ConnectivityManager.NetworkCallback? = null
    private var fused: FusedLocationProviderClient? = null
    private var cb: LocationCallback? = null
    private val tick = object : Runnable { override fun run() { upload(); h.postDelayed(this, 20000) } }

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
            .setContentTitle("FieldTrack").setContentText("Work day tracking is active").setContentIntent(pi).setOngoing(true).build()
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
        on = true; kVar = -1f; sTs = 0L
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
        try { lm.removeUpdates(this) } catch (e: Exception) {}
        try { cb?.let { fused?.removeLocationUpdates(it) } } catch (e: Exception) {}
        wl?.let { if (it.isHeld) it.release() }
    }

    private var kLat = 0.0; private var kLng = 0.0; private var kVar = -1f; private var kTs = 0L
    private var sLat = 0.0; private var sLng = 0.0; private var sTs = 0L

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

    @Suppress("DEPRECATION")
    override fun onLocationChanged(l: Location) {
        if (!prefs(this).getBoolean("active", false)) return
        val fake = if (Build.VERSION.SDK_INT >= 31) l.isMock else l.isFromMockProvider
        if (fake) { prefs(this).edit().putBoolean("mock", true).apply(); return }   // fake-GPS apps
        if (l.accuracy > 30f) return   // only good fixes
        val now = System.currentTimeMillis()
        val d = FloatArray(1)
        if (sTs > 0) {   // reject impossible jumps (> 40 m/s)
            Location.distanceBetween(sLat, sLng, l.latitude, l.longitude, d)
            val dt = (now - sTs) / 1000f
            if (dt > 0 && dt < 120 && d[0] / dt > 40f) return
        }
        if (kVar < 0f) { kLat = l.latitude; kLng = l.longitude; kVar = l.accuracy * l.accuracy }
        else {           // Kalman smoothing: trust accurate fixes more
            kVar += ((now - kTs) / 1000f) * 9f
            val g = kVar / (kVar + l.accuracy * l.accuracy)
            kLat += g * (l.latitude - kLat); kLng += g * (l.longitude - kLng); kVar *= (1 - g)
        }
        kTs = now
        prefs(this).edit().putString("last", "{\"latitude\":$kLat,\"longitude\":$kLng,\"accuracy\":${l.accuracy},\"ts\":$now}").apply()
        if (sTs > 0) {   // standing still: store at most one point a minute
            Location.distanceBetween(sLat, sLng, kLat, kLng, d)
            if (d[0] < Math.max(12f, l.accuracy * 0.6f) && now - sTs < 60000) return
        }
        sLat = kLat; sLng = kLng; sTs = now
        val v = ContentValues().apply { put("ts", now); put("lat", kLat); put("lng", kLng); put("acc", l.accuracy.toDouble()) }
        db.writableDatabase.insertWithOnConflict("pts", null, v, SQLiteDatabase.CONFLICT_IGNORE)
    }
    override fun onProviderEnabled(p: String) {}
    override fun onProviderDisabled(p: String) {}
    @Deprecated("old API") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}

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
