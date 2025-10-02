package com.google.ar.core.examples.kotlin.helloar

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.os.Bundle
import android.speech.RecognizerIntent
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.net.PlacesClient
import com.google.ar.core.*
import com.google.ar.core.examples.java.common.helpers.CameraPermissionHelper
import com.google.ar.core.examples.java.common.helpers.DepthSettings
import com.google.ar.core.examples.java.common.helpers.FullScreenHelper
import com.google.ar.core.examples.java.common.helpers.InstantPlacementSettings
import com.google.ar.core.examples.java.common.samplerender.SampleRender
import com.google.ar.core.examples.kotlin.common.helpers.ARCoreSessionLifecycleHelper
import com.google.ar.core.examples.kotlin.helloar.path.*
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import com.google.firebase.FirebaseApp
import com.google.maps.android.PolyUtil // Keep this for map drawing
import kotlinx.coroutines.launch
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

class HelloArActivity : AppCompatActivity(), OnMapReadyCallback, PathFinderListener, SensorEventListener {
    companion object {
        private const val TAG = "HelloArActivity"
        private const val SPEECH_REQUEST_CODE = 0
    }

    lateinit var arCoreSessionHelper: ARCoreSessionLifecycleHelper
    lateinit var view: HelloArView
    lateinit var renderer: HelloArRenderer // Made lateinit, will be initialized with activity context

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var placesClient: PlacesClient
    lateinit var voiceAssistant: VoiceAssistant // Made lateinit for Renderer access
    private lateinit var pathFinder: PathFinder
    private lateinit var guidanceManager: GuidanceManager

    private var mMap: GoogleMap? = null
    private lateinit var mapFragment: SupportMapFragment

    private lateinit var distanceTextView: TextView 
    private lateinit var mapsTextView: TextView 
    private lateinit var bevImageView: ImageView
    private lateinit var bevTextView: TextView

    private lateinit var sensorManager: SensorManager
    private var rotationVectorSensor: Sensor? = null
    var currentBearing: Float = 0f // Make accessible to Renderer

    var currentPathGuidance: String? = null // To store guidance from GuidanceManager

    val depthSettings = DepthSettings()
    val instantPlacementSettings = InstantPlacementSettings()
    val guidanceState = GuidanceState() 
    var showBEV = false

    @SuppressLint("MissingPermission")
    private val locationPermissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                startLocationUpdates()
                mMap?.isMyLocationEnabled = true
            } else {
                Toast.makeText(this, "Location permission is required for navigation.", Toast.LENGTH_LONG).show()
            }
        }

    private val speechRecognitionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val rawSpokenText = voiceAssistant.parseVoiceInputResult(result.resultCode, result.data)
            val keyword = extractDestinationFromCommand(rawSpokenText)

            if (keyword != null) {
                Toast.makeText(this, "Searching for nearest: $keyword", Toast.LENGTH_SHORT).show()
                pathFinder.findAndPreparePath(keyword, this)
            } else {
                Toast.makeText(this, "Could not understand destination.", Toast.LENGTH_SHORT).show()
                lifecycleScope.launch {
                    voiceAssistant.speakTextAndAWait("Could not find the destination, please try again")
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mapFragment = supportFragmentManager.findFragmentById(R.id.map_fragment) as SupportMapFragment
        view = findViewById(R.id.hello_ar_view)
        distanceTextView = view.findViewById(R.id.distanceTextView) 
        mapsTextView = view.findViewById<TextView>(R.id.MapsDirection) 
        bevImageView = view.findViewById<ImageView>(R.id.imageBEVView)
        bevTextView = view.findViewById<TextView>(R.id.textBEVView)

        val compassImageView = view.findViewById<ImageView>(R.id.compass_image_view)
        val listenButton = findViewById<Button>(R.id.listenVoiceButton)
        val showBEVButton = view.findViewById<Button>(R.id.showBEV)

        initializeCoreServices()
        initializeArCore()

        FirebaseApp.initializeApp(this)
        val geminiVlmService = GeminiVlmService()
        // Initialize renderer here as it depends on initialized voiceAssistant and guidanceState from initializeCoreServices()
        renderer = HelloArRenderer(this, geminiVlmService, voiceAssistant, guidanceState)
        lifecycle.addObserver(renderer)
        SampleRender(view.surfaceView, renderer, assets)
        renderer.setDistanceTextView(distanceTextView as DistanceTextView)
        renderer.setMapsTextView(mapsTextView) 
        renderer.setBEVImageView(bevImageView)
        renderer.setBEVTextView(bevTextView)
        renderer.setCompassImageView(compassImageView)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        listenButton.setOnClickListener {
            try {
                val voiceIntent = voiceAssistant.createVoiceInputIntent()
                speechRecognitionLauncher.launch(voiceIntent)
            } catch (e: Exception) {
                Toast.makeText(this, "Speech recognition not available: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        showBEVButton.setOnClickListener {
            renderer.requestDrawBEV()
            bevImageView.visibility = View.VISIBLE
        }

        bevImageView.setOnClickListener {
            it.visibility = View.GONE
        }

        mapFragment.getMapAsync(this)
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        Log.d(TAG, "GoogleMap is ready.")
        checkLocationPermission()
    }

    override fun onResume() {
        super.onResume()
        if (::locationCallback.isInitialized) {
            checkLocationPermission() 
        }
        rotationVectorSensor?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        if (::fusedLocationClient.isInitialized && ::locationCallback.isInitialized) {
             fusedLocationClient.removeLocationUpdates(locationCallback)
        }
        sensorManager.unregisterListener(this)
    }

    override fun onDestroy() {
        if (::voiceAssistant.isInitialized) {
            voiceAssistant.shutdownTextToSpeech()
        }
        super.onDestroy()
    }

    private fun initializeCoreServices() {
        lifecycle.addObserver(view)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        Places.initialize(applicationContext, BuildConfig.MAPS_API_KEY)
        placesClient = Places.createClient(this)
        voiceAssistant = VoiceAssistant()
        voiceAssistant.initializeTextToSpeech(this)
        pathFinder = PathFinder(this, fusedLocationClient, placesClient)
        guidanceManager = GuidanceManager(this, voiceAssistant, guidanceState, mapsTextView)
    }

    private fun initializeArCore() {
        arCoreSessionHelper = ARCoreSessionLifecycleHelper(this)
        arCoreSessionHelper.exceptionCallback = { exception ->
            val message = when (exception) {
                is UnavailableUserDeclinedInstallationException -> "Please install Google Play Services for AR"
                is UnavailableApkTooOldException -> "Please update ARCore"
                is UnavailableSdkTooOldException -> "Please update this app"
                is UnavailableDeviceNotCompatibleException -> "This device does not support AR"
                is CameraNotAvailableException -> "Camera not available. Try restarting the app."
                else -> "Failed to create AR session: $exception"
            }
            Log.e(TAG, "ARCore threw an exception", exception)
            view.snackbarHelper.showError(this, message)
        }
        arCoreSessionHelper.beforeSessionResume = ::configureSession
        lifecycle.addObserver(arCoreSessionHelper)
        depthSettings.onCreate(this)
        instantPlacementSettings.onCreate(this)
    }

    private fun checkLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates()
            mMap?.isMyLocationEnabled = true
        } else {
            locationPermissionRequest.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000).apply {
            setMinUpdateIntervalMillis(2000) 
        }.build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    val pathUpdate = guidanceManager.updateGuidanceForLocation(location)
                    if (pathUpdate != null) {
                        currentPathGuidance = pathUpdate
                        Log.d(TAG, "Updated currentPathGuidance: $currentPathGuidance")
                    }
                }
            }
        }
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, mainLooper)
    }

    // --- PathFinderListener Implementation ---
    @SuppressLint("MissingPermission")
    override fun onPathFound(result: DirectionsResult) {
        Log.d(TAG, "onPathFound: Decoded polyline has ${result.decodedPath.size} points.")
        guidanceManager.setPolylinePath(result.decodedPath, result.destination.name ?: "your destination")
        currentPathGuidance = null // Reset path guidance on new path

        runOnUiThread {
            if (mMap == null) {
                Log.e(TAG, "Map not ready in onPathFound")
                return@runOnUiThread
            }
            mMap!!.clear()
            val decodedOverviewPath = PolyUtil.decode(result.overviewPolyline)
            mMap!!.addPolyline(PolylineOptions().addAll(decodedOverviewPath).color(Color.BLUE).width(12f))

            val destinationLatLng = result.destination.latLng
            fusedLocationClient.lastLocation.addOnSuccessListener { myLocation: Location? ->
                val boundsBuilder = LatLngBounds.builder()
                if (myLocation != null) {
                    boundsBuilder.include(LatLng(myLocation.latitude, myLocation.longitude))
                }
                if (destinationLatLng != null) {
                    boundsBuilder.include(LatLng(destinationLatLng.latitude, destinationLatLng.longitude))
                } else {
                    if (myLocation != null) mMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(myLocation.latitude, myLocation.longitude), 15f))
                    return@addOnSuccessListener
                }
                try {
                    val bounds = boundsBuilder.build()
                    mMap!!.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 100))
                } catch (e: IllegalStateException) {
                    Log.e(TAG, "Error building LatLngBounds or moving camera: ${e.message}")
                    if (myLocation != null) mMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(myLocation.latitude, myLocation.longitude), 15f))
                }
            }
        }
    }

    override fun onPathError(message: String) {
        guidanceManager.stopGuidance() 
        currentPathGuidance = null // Clear path guidance on error
        runOnUiThread {
            Toast.makeText(this, "Path Error: $message", Toast.LENGTH_LONG).show()
        }
    }
    
    fun configureSession(session: Session) {
        session.configure(
            session.config.apply {
                lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                depthMode = if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
                instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP
            }
        )
    }

    private fun extractDestinationFromCommand(command: String?): String? {
        command?.toLowerCase()?.let {
            val patterns = listOf(
                "take me to", "navigate to", "go to", "directions to", "find"
            )
            for (pattern in patterns) {
                if (it.contains(pattern)) {
                    val destination = it.substringAfter(pattern).trim()
                    if (destination.isNotEmpty()) {
                        return destination
                    }
                }
            }
            if (it.isNotBlank()) return it.trim() 
        }
        return null
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
            val rotationMatrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            val orientationAngles = FloatArray(3)
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
            var azimuthDegrees = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
            azimuthDegrees = (azimuthDegrees + 360) % 360 
            currentBearing = azimuthDegrees
            if(::renderer.isInitialized) { // Ensure renderer is initialized
              renderer.updateCompassBearing(currentBearing) 
            }
        }
    }
}
