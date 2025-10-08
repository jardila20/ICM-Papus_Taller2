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
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Locale

// Ruta (OSM Bonuspack / OSRM por JitPack)
import org.osmdroid.bonuspack.routing.OSRMRoadManager
import org.osmdroid.bonuspack.routing.Road
import org.osmdroid.bonuspack.routing.RoadManager

class MapaActivity : AppCompatActivity(), SensorEventListener {

    // --- UI ---
    private lateinit var map: MapView
    private lateinit var etBuscar: EditText

    // --- Ubicación ---
    private lateinit var locationManager: LocationManager
    private val locationListener = LocationListener { loc -> loc?.let { onLocation(it) } }
    private var currentLocation: Location? = null

    // Control de cámara (para que no “salte”)
    private var lastCameraLoc: Location? = null
    private var lastCameraTs: Long = 0L
    private val CAMERA_MIN_DIST_M = 25f
    private val CAMERA_MIN_INTERVAL_MS = 6_000L
    private val CAMERA_ANIM_DURATION_MS = 1_200L

    // --- Sensor de luz ---
    private lateinit var sensorManager: SensorManager
    private var lightSensor: Sensor? = null
    private val LUX_UMBRAL_OSCURO = 20f
    private var darkApplied = false

    // --- Marcadores ---
    private var currentMarker: Marker? = null
    private var searchMarker: Marker? = null
    private var lastLoggedLocation: Location? = null

    // --- JSON interno ---
    private val jsonFileName = "ubicaciones.json"
    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    // --- Permisos ---
    private val requestLocation =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            val granted = (res[Manifest.permission.ACCESS_FINE_LOCATION] == true) ||
                    (res[Manifest.permission.ACCESS_COARSE_LOCATION] == true)
            if (granted) startLocationUpdates()
            else Toast.makeText(this, "Permiso de ubicación denegado", Toast.LENGTH_LONG).show()
        }

    // --- Ruta ---
    private lateinit var roadManager: RoadManager
    private var routeOverlay: Polyline? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // osmdroid necesita un userAgent
        Configuration.getInstance().userAgentValue = packageName

        setContentView(R.layout.activity_mapa)
        map = findViewById(R.id.map)
        etBuscar = findViewById(R.id.etBuscar)

        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)

        // Long click en mapa (crear pin con dirección + ruta + distancia)
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

        // Bonuspack/OSRM
        roadManager = OSRMRoadManager(this, packageName).apply {
            (this as OSRMRoadManager).setMean(OSRMRoadManager.MEAN_BY_CAR)
        }

        checkPermissionAndStart()

        // Buscar por texto (IME action Done)
        etBuscar.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                val query = v.text.toString().trim()
                if (query.isNotEmpty()) geocodificarYMarcar(query)
                true
            } else false
        }
    }

    // ===== Permisos / Ubicación =====
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
            // Menos ruido: mínimo 5 m entre updates
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3000L, 5f, locationListener)
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 5f, locationListener)
        } catch (_: SecurityException) {}

        // Cámara inicial con lastKnown
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

    // ===== Ubicación + JSON + cámara suave =====
    private fun onLocation(loc: Location) {
        currentLocation = loc

        // Actualiza SIEMPRE el pin de mi ubicación
        if (currentMarker == null) {
            currentMarker = Marker(map).apply {
                position = GeoPoint(loc.latitude, loc.longitude)
                title = "Posición actual"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            map.overlays.add(currentMarker)
        } else {
            currentMarker?.position = GeoPoint(loc.latitude, loc.longitude)
        }
        map.invalidate()

        // Solo recentrar si pasó tiempo y distancia mínimos
        val now = System.currentTimeMillis()
        val movedEnough = lastCameraLoc?.distanceTo(loc)?.let { it > CAMERA_MIN_DIST_M } ?: true
        val timeEnough = now - lastCameraTs > CAMERA_MIN_INTERVAL_MS
        if (movedEnough && timeEnough) {
            moveMarkerAndCamera(loc, animate = true)
            lastCameraLoc = Location(loc)
            lastCameraTs = now
        }

        // Registrar JSON si >30 m
        val last = lastLoggedLocation
        val moved30 = last == null || last.distanceTo(loc) > 30f
        if (moved30) {
            appendLocationToJson(loc)
            lastLoggedLocation = Location(loc)
        }
    }

    private fun moveMarkerAndCamera(loc: Location, animate: Boolean) {
        val p = GeoPoint(loc.latitude, loc.longitude)
        val ctrl = map.controller
        if (animate) ctrl.animateTo(p, 16.0, CAMERA_ANIM_DURATION_MS) else {
            ctrl.setZoom(16.0); ctrl.setCenter(p)
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

    // ===== Geocoder: texto → punto (actualiza siempre el marcador) =====
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
                val p = GeoPoint(addr.latitude, addr.longitude)
                val titulo = addr.getAddressLine(0) ?: query
                withContext(Dispatchers.Main) {
                    colocarSearchMarker(p.latitude, p.longitude, titulo) // ✅ actualiza título/snippet
                    showDistanceToastTo(p)
                    drawRouteFromCurrentTo(p)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MapaActivity, "Error al geocodificar: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ===== Marcador de destino (siempre se refresca) =====
    private fun colocarSearchMarker(lat: Double, lng: Double, titulo: String) {
        val p = GeoPoint(lat, lng)
        if (searchMarker == null) {
            searchMarker = Marker(map).apply {
                position = p
                title = titulo
                snippet = "Destino"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            map.overlays.add(searchMarker)
        } else {
            searchMarker?.apply {
                position = p
                title = titulo        // ✅ actualiza el título
                snippet = "Destino"   // ✅ y el snippet
            }
        }
        map.controller.animateTo(p, 17.0, 800L)
        map.invalidate() // ✅ fuerza redibujado para que no quede el título anterior
    }

    // ===== Long press: punto → dirección (y ruta) =====
    private fun onLongPressAt(p: GeoPoint) {
        lifecycleScope.launch(Dispatchers.IO) {
            var titulo = "Marcador"
            try {
                val geocoder = Geocoder(this@MapaActivity, Locale.getDefault())
                val res = geocoder.getFromLocation(p.latitude, p.longitude, 1)
                if (!res.isNullOrEmpty()) titulo = res[0].getAddressLine(0) ?: titulo
            } catch (_: Exception) {}
            withContext(Dispatchers.Main) {
                colocarSearchMarker(p.latitude, p.longitude, titulo) // ✅ actualiza siempre
                showDistanceToastTo(p)
                drawRouteFromCurrentTo(p)
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

    // ===== Sensor de luz: modo oscuro / claro =====
    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_LIGHT) return
        val lux = event.values[0]
        if (lux < LUX_UMBRAL_OSCURO && !darkApplied) aplicarEstiloOscuro(true)
        else if (lux >= LUX_UMBRAL_OSCURO && darkApplied) aplicarEstiloOscuro(false)
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun aplicarEstiloOscuro(oscuro: Boolean) {
        val tilesOverlay: TilesOverlay = map.overlayManager.tilesOverlay
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

    // ===== Ruta con OSRM (Bonuspack) =====
    private fun drawRouteFromCurrentTo(dest: GeoPoint) {
        val here = currentLocation
        if (here == null) {
            Toast.makeText(this, "Tu ubicación aún no está lista", Toast.LENGTH_SHORT).show()
            return
        }
        val start = GeoPoint(here.latitude, here.longitude)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val waypoints = arrayListOf(start, dest)
                val road: Road = roadManager.getRoad(waypoints)

                withContext(Dispatchers.Main) {
                    // Limpia ruta previa
                    routeOverlay?.let { map.overlays.remove(it) }

                    val poly = RoadManager.buildRoadOverlay(road)
                    poly.outlinePaint.strokeWidth = 8f
                    poly.outlinePaint.color = 0xFFE53935.toInt() // rojo

                    routeOverlay = poly
                    map.overlays.add(poly)

                    val bbox: BoundingBox = road.mBoundingBox ?: BoundingBox.fromGeoPoints(waypoints)
                    map.zoomToBoundingBox(bbox, true, 80)
                    map.invalidate()

                    if (road.mStatus != Road.STATUS_OK) {
                        Toast.makeText(this@MapaActivity, "Ruta no óptima o servicio limitado", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MapaActivity, "Error al calcular ruta: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
