package com.example.icm_papus_taller2

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Locale

class MapaActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private lateinit var locationManager: LocationManager
    private var marker: Marker? = null
    private var lastLoggedLocation: Location? = null

    private val jsonFileName = "ubicaciones.json"
    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    private val requestLocation =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            val granted = (res[Manifest.permission.ACCESS_FINE_LOCATION] == true) ||
                    (res[Manifest.permission.ACCESS_COARSE_LOCATION] == true)
            if (granted) startLocationUpdates()
            else Toast.makeText(this, "Permiso de ubicación denegado", Toast.LENGTH_LONG).show()
        }

    private val listener = LocationListener { loc ->
        if (loc != null) handleLocationUpdate(loc)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Configuración de osmdroid (obligatorio poner un userAgent)
        Configuration.getInstance().userAgentValue = packageName

        setContentView(R.layout.activity_mapa)
        map = findViewById(R.id.map)
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)

        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        checkPermissionAndStart()
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        if (hasLocationPermission()) startLocationUpdates()
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
        stopLocationUpdates()
    }

    private fun checkPermissionAndStart() {
        if (hasLocationPermission()) {
            startLocationUpdates()
        } else {
            val perms = arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
            requestLocation.launch(perms)
        }
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun startLocationUpdates() {
        if (!hasLocationPermission()) return
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 3000L, 0f, listener
            )
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER, 3000L, 0f, listener
            )
        } catch (_: SecurityException) { }

        if (hasLocationPermission()) {
            try {
                val last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                last?.let { moveMarkerAndCamera(it, animate = false) }
            } catch (se: SecurityException) {
                se.printStackTrace()
            }
        }

    }

    private fun stopLocationUpdates() {
        locationManager.removeUpdates(listener)
    }

    private fun handleLocationUpdate(loc: Location) {
        moveMarkerAndCamera(loc, animate = true)

        val last = lastLoggedLocation
        val movedEnough = if (last == null) true else last.distanceTo(loc) > 30f
        if (movedEnough) {
            appendLocationToJson(loc)
            lastLoggedLocation = Location(loc)
        }
    }

    private fun moveMarkerAndCamera(loc: Location, animate: Boolean) {
        val p = GeoPoint(loc.latitude, loc.longitude)
        if (marker == null) {
            marker = Marker(map).apply {
                position = p
                title = "Posición actual"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            map.overlays.add(marker)
        } else {
            marker?.position = p
        }
        val controller = map.controller
        if (animate) {
            controller.animateTo(p, 16.0, 1000L)
        } else {
            controller.setZoom(16.0)
            controller.setCenter(p)
        }
        map.invalidate()
    }

    private fun appendLocationToJson(loc: Location) {
        val file = File(filesDir, jsonFileName)
        val arr = readJsonArray(file)
        val obj = JSONObject().apply {
            put("lat", loc.latitude)
            put("lng", loc.longitude)
            put("accuracy_m", loc.accuracy.toDouble())
            put("datetime", sdf.format(System.currentTimeMillis()))
        }
        arr.put(obj)
        file.writeText(arr.toString(2))
    }

    private fun readJsonArray(file: File): JSONArray {
        return try {
            if (!file.exists()) JSONArray()
            else {
                val br = BufferedReader(InputStreamReader(file.inputStream()))
                val text = br.readText().also { br.close() }
                if (text.isBlank()) JSONArray() else JSONArray(text)
            }
        } catch (_: Exception) {
            JSONArray()
        }
    }
}
