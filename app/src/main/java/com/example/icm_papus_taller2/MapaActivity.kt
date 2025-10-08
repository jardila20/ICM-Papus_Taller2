package com.example.icm_papus_taller2

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Locale

class MapaActivity : AppCompatActivity(), SensorEventListener {

    // UI
    private lateinit var map: MapView
    private lateinit var etBuscar: EditText

    // Ubicación
    private lateinit var locationManager: LocationManager
    private val locationListener = LocationListener { loc -> loc?.let { onLocation(it) } }
    private var currentLocation: Location? = null

    // Sensor luz
    private lateinit var sensorManager: SensorManager
    private var lightSensor: Sensor? = null
    private val LUX_UMBRAL_OSCURO = 20f
    private var darkApplied = false

    // Marcadores
    private var currentMarker: Marker? = null
    private var searchMarker: Marker? = null
    private var lastLoggedLocation: Location? = null

    // JSON interno (como en taller)
    private val jsonFileName = "ubicaciones.json"
    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    // Permisos
    private val requestLocation =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            val granted = (res[Manifest.permission.ACCESS_FINE_LOCATION] == true) ||
                    (res[Manifest.permission.ACCESS_COARSE_LOCATION] == true)
            if (granted) startLocationUpdates()
            else Toast.makeText(this, "Permiso de ubicación denegado", Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // osmdroid necesita un UserAgent (equivalente a setup de clase)
        Configuration.getInstance().userAgentValue = packageName

        setContentView(R.layout.activity_mapa)
        map = findViewById(R.id.map)
        etBuscar = findViewById(R.id.etBuscar)

        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)

        val eventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean = false
            override fun longPressHelper(p: GeoPoint?): Boolean {
                p ?: return false
                onLongPressAt(p)
                return true
            }
        })
        map.overlays.add(eventsOverlay)

        // Servicios
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)

        // Permisos + ubicación
        checkPermissionAndStart()

        // Buscar por texto al presionar Done
        etBuscar.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                val query = v.text.toString().trim()
                if (query.isNotEmpty()) geocodificarYMarcar(query)
                true
            } else false
        }
    }

    // -------- Permisos / ubicación ----------
    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun checkPermissionAndStart() {
        if (hasLocationPermission()) startLocationUpdates()
        else requestLocation.launch(arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ))
    }

    private fun startLocationUpdates() {
        if (!hasLocationPermission()) return
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3000L, 0f, locationListener)
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 0f, locationListener)
        } catch (_: SecurityException) {}


        if (hasLocationPermission()) {
            try {
                val last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                last?.let { moveMarkerAndCamera(it, animate = false) }
            } catch (_: SecurityException) {}
        }
    }

    private fun stopLocationUpdates() {
        locationManager.removeUpdates(locationListener)
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        lightSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        if (hasLocationPermission()) startLocationUpdates()
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
        sensorManager.unregisterListener(this)
        stopLocationUpdates()
    }

    // -------- Ubicación + JSON ----------
    private fun onLocation(loc: Location) {
        currentLocation = loc
        moveMarkerAndCamera(loc, animate = true)

        val last = lastLoggedLocation
        val movedEnough = last == null || last.distanceTo(loc) > 30f
        if (movedEnough) {
            appendLocationToJson(loc)
            lastLoggedLocation = Location(loc)
        }
    }

    private fun moveMarkerAndCamera(loc: Location, animate: Boolean) {
        val p = GeoPoint(loc.latitude, loc.longitude)
        if (currentMarker == null) {
            currentMarker = Marker(map).apply {
                position = p
                title = "Posición actual"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            map.overlays.add(currentMarker)
        } else {
            currentMarker?.position = p
        }
        val ctrl = map.controller
        if (animate) ctrl.animateTo(p, 16.0, 800L) else { ctrl.setZoom(16.0); ctrl.setCenter(p) }
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

    // -------- Geocoder (texto -> posición) ----------
    private fun geocodificarYMarcar(query: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val geocoder = Geocoder(this@MapaActivity, Locale.getDefault())
                val results = geocoder.getFromLocationName(query, 1)
                if (results.isNullOrEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MapaActivity, "Dirección no encontrada", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                val addr = results.first()
                val lat = addr.latitude
                val lng = addr.longitude
                val titulo = addr.getAddressLine(0) ?: query
                withContext(Dispatchers.Main) {
                    colocarSearchMarker(lat, lng, titulo)
                    showDistanceToastTo(GeoPoint(lat, lng))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MapaActivity, "Error al geocodificar: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun colocarSearchMarker(lat: Double, lng: Double, titulo: String) {
        val p = GeoPoint(lat, lng)
        if (searchMarker == null) {
            searchMarker = Marker(map).apply {
                position = p
                title = titulo
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            map.overlays.add(searchMarker)
        } else {
            searchMarker?.position = p
            searchMarker?.title = titulo
        }
        map.controller.animateTo(p, 17.0, 800L)
        map.invalidate()
    }

    // -------- LongClick (posición -> texto) + distancia ----------
    private fun onLongPressAt(p: GeoPoint) {
        lifecycleScope.launch(Dispatchers.IO) {
            var titulo = "Marcador"
            try {
                val geocoder = Geocoder(this@MapaActivity, Locale.getDefault())
                val res = geocoder.getFromLocation(p.latitude, p.longitude, 1)
                if (!res.isNullOrEmpty()) titulo = res[0].getAddressLine(0) ?: titulo
            } catch (_: Exception) {}
            withContext(Dispatchers.Main) {
                colocarSearchMarker(p.latitude, p.longitude, titulo)
                showDistanceToastTo(p)
            }
        }
    }

    private fun showDistanceToastTo(p: GeoPoint) {
        val here = currentLocation ?: run {
            Toast.makeText(this, "Ubicación actual no disponible aún", Toast.LENGTH_SHORT).show()
            return
        }
        val target = Location(here).apply { latitude = p.latitude; longitude = p.longitude }
        val distM = here.distanceTo(target)
        val msg = if (distM < 1000)
            String.format(Locale.getDefault(), "Distancia: %.0f m", distM)
        else
            String.format(Locale.getDefault(), "Distancia: %.2f km", distM / 1000f)
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    // -------- Sensor de luminosidad (modo oscuro/claro) ----------
    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_LIGHT) return
        val lux = event.values[0]
        if (lux < LUX_UMBRAL_OSCURO && !darkApplied) aplicarEstiloOscuro(true)
        else if (lux >= LUX_UMBRAL_OSCURO && darkApplied) aplicarEstiloOscuro(false)
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun aplicarEstiloOscuro(oscuro: Boolean) {
        val tilesOverlay = map.overlayManager.tilesOverlay
        if (oscuro) {
            val matrix = ColorMatrix().apply { setSaturation(0f) }
            val darken = ColorMatrix(
                floatArrayOf(
                    0.7f, 0f, 0f, 0f, 0f,
                    0f, 0.7f, 0f, 0f, 0f,
                    0f, 0f, 0.7f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            matrix.postConcat(darken)
            tilesOverlay.setColorFilter(ColorMatrixColorFilter(matrix))
            darkApplied = true
        } else {
            tilesOverlay.setColorFilter(null)
            darkApplied = false
        }
        map.invalidate()
    }
}
