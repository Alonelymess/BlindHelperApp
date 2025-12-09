package com.google.ar.core.examples.kotlin.helloar

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
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
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.examples.java.common.helpers.CameraPermissionHelper
import com.google.ar.core.examples.java.common.helpers.DepthSettings
import com.google.ar.core.examples.java.common.helpers.FullScreenHelper
import com.google.ar.core.examples.java.common.helpers.InstantPlacementSettings
import com.google.ar.core.examples.java.common.samplerender.SampleRender
import com.google.ar.core.examples.kotlin.common.helpers.ARCoreSessionLifecycleHelper
import com.google.ar.core.examples.kotlin.helloar.path.DirectionsResult
import com.google.ar.core.examples.kotlin.helloar.path.GuidanceManager
import com.google.ar.core.examples.kotlin.helloar.path.GuidanceState
import com.google.ar.core.examples.kotlin.helloar.path.PathFinder
import com.google.ar.core.examples.kotlin.helloar.path.PathFinderListener
import com.google.ar.core.examples.kotlin.helloar.utils.GeminiVlmService
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import com.google.firebase.FirebaseApp
import com.google.maps.android.PolyUtil
import kotlinx.coroutines.launch
import java.util.Locale
import com.google.android.gms.tasks.Task
import com.google.android.gms.tflite.java.TfLite

class HelloArActivity : AppCompatActivity(), OnMapReadyCallback, PathFinderListener, SensorEventListener {
    companion object {
        private const val TAG = "HelloArActivity"
    }

    // ARCore and Renderer
    lateinit var arCoreSessionHelper: ARCoreSessionLifecycleHelper
    lateinit var view: HelloArView
    lateinit var renderer: HelloArRenderer // Public for ARCoreSessionLifecycleHelper
    private lateinit var render: SampleRender

    // Location and Mapping
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private lateinit var placesClient: PlacesClient
    private var mMap: GoogleMap? = null

    // Navigation and Guidance
    private lateinit var voiceAssistant: VoiceAssistant
    private lateinit var pathFinder: PathFinder
    private lateinit var guidanceManager: GuidanceManager
    var currentPathGuidance: String? = null // Public for renderer access
        private set

    // UI and Views
    private lateinit var distanceTextView: TextView
    private lateinit var mapsTextView: TextView
    private lateinit var bevImageView: ImageView

    // Sensors
    private lateinit var sensorManager: SensorManager
    private var rotationVectorSensor: Sensor? = null
    var currentBearing: Float = 0f // Public for renderer access
        private set

    // State Management
    val depthSettings = DepthSettings()
    val instantPlacementSettings = InstantPlacementSettings()
    val guidanceState = GuidanceState()

    // Activity Result Launchers
    private val locationPermissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                onLocationPermissionGranted()
            } else {
                Toast.makeText(this, "Location permission is required for navigation.", Toast.LENGTH_LONG).show()
            }
        }

    private val cameraPermissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                // Permission granted, now we can check for location permission and proceed.
                checkAndRequestLocationPermission()
            } else {
                // Permission denied. Explain to the user that the camera is necessary.
                Toast.makeText(this, "Camera permission is required to run this AR application", Toast.LENGTH_LONG).show()
                finish() // Close the app if camera permission is denied
            }
        }

    private val speechRecognitionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val rawSpokenText = voiceAssistant.parseVoiceInputResult(result.resultCode, result.data)
            val destination = extractDestinationFromCommand(rawSpokenText)

            if (destination != null) {
                Toast.makeText(this, "Searching for nearest: $destination", Toast.LENGTH_SHORT).show()
                pathFinder.findAndPreparePath(destination, this)
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

        // Initialize Views
        view = findViewById(R.id.hello_ar_view)
        distanceTextView = view.findViewById(R.id.distanceTextView)
        mapsTextView = view.findViewById(R.id.MapsDirection)
        bevImageView = view.findViewById(R.id.imageBEVView)
        val compassImageView = view.findViewById<ImageView>(R.id.compass_image_view)
        val listenButton = findViewById<Button>(R.id.listenVoiceButton)
        val showBEVButton = view.findViewById<Button>(R.id.showBEV)
        val mapFragment = supportFragmentManager.findFragmentById(R.id.map_fragment) as SupportMapFragment

        // Initialize Core Services
        initializeCoreServices()
        initializeArCore()

        // Initialize Renderer and link it to the view
        renderer = HelloArRenderer(this, GeminiVlmService(), voiceAssistant, guidanceState).also {
            it.setDistanceTextView(distanceTextView as DistanceTextView)
            it.setMapsTextView(mapsTextView)
            it.setCompassImageView(compassImageView)
            lifecycle.addObserver(it)
        }
        render = SampleRender(view.surfaceView, renderer, assets)

        // Initialize Sensors
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        // Set up button listeners
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

    private fun initializeCoreServices() {
        val initializeTask: Task<Void> by lazy { TfLite.initialize(this) }
        FirebaseApp.initializeApp(this)
        Places.initialize(applicationContext, BuildConfig.MAPS_API_KEY)

        lifecycle.addObserver(view)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        placesClient = Places.createClient(this)
        voiceAssistant = VoiceAssistant().apply { initializeTextToSpeech(this@HelloArActivity) }
        pathFinder = PathFinder(this, fusedLocationClient, placesClient)
        guidanceManager = GuidanceManager(this, voiceAssistant, guidanceState, mapsTextView)
    }

    private fun initializeArCore() {
        arCoreSessionHelper = ARCoreSessionLifecycleHelper(this).apply {
            exceptionCallback = { exception ->
                val message = when (exception) {
                    is UnavailableUserDeclinedInstallationException -> "Please install Google Play Services for AR"
                    is UnavailableApkTooOldException -> "Please update ARCore"
                    is UnavailableSdkTooOldException -> "Please update this app"
                    is UnavailableDeviceNotCompatibleException -> "This device does not support AR"
                    is CameraNotAvailableException -> "Camera not available. Try restarting the app."
                    else -> "Failed to create AR session: $exception"
                }
                Log.e(TAG, "ARCore threw an exception", exception)
                view.snackbarHelper.showError(this@HelloArActivity, message)
            }
            beforeSessionResume = ::configureSession
        }
        lifecycle.addObserver(arCoreSessionHelper)
        depthSettings.onCreate(this)
        instantPlacementSettings.onCreate(this)
    }

    override fun onResume() {
        super.onResume()
        // Start the permission check flow.
        checkAndRequestCameraPermission()

        rotationVectorSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        stopLocationUpdates()
        sensorManager.unregisterListener(this)
    }

    override fun onDestroy() {
        voiceAssistant.shutdownTextToSpeech() // Ensure TextToSpeech is released
        super.onDestroy()
    }

    private fun checkAndRequestCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> {
                // Camera permission is already granted, proceed to check for location.
                checkAndRequestLocationPermission()
            }
            else -> {
                // Camera permission has not been granted, so request it.
                cameraPermissionRequest.launch(Manifest.permission.CAMERA)
            }
        }
    }

    // Google Map setup
    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        Log.d(TAG, "GoogleMap is ready.")
        checkAndRequestLocationPermission()
    }

    private fun checkAndRequestLocationPermission() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED -> {
                onLocationPermissionGranted()
            }
            else -> {
                locationPermissionRequest.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun onLocationPermissionGranted() {
        if (mMap == null) {
            Log.d(TAG, "Location permission granted, but map is not ready yet. Awaiting onMapReady.")
            return // Exit if map isn't ready. onMapReady will handle it.
        }
        Log.d(TAG, "Location permission granted and map is ready. Enabling location feature")
        mMap?.isMyLocationEnabled = true
        mMap?.uiSettings?.isMyLocationButtonEnabled = true
        startLocationUpdates()
    }

    // Location Updates
    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (locationCallback != null) return // Already updating

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
            .setMinUpdateIntervalMillis(1000)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    currentPathGuidance = guidanceManager.updateGuidanceForLocation(location, currentBearing)
                }
            }
        }
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback!!, mainLooper)
    }

    private fun stopLocationUpdates() {
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
            locationCallback = null
        }
    }

    // PathFinderListener Implementation
    @SuppressLint("MissingPermission")
    override fun onPathFound(result: DirectionsResult) {
        // Add a null-check here as a safety measure.
        if (mMap == null) {
            Log.e(TAG, "onPathFound called but map is not ready.")
            return
        }
        guidanceManager.setPolylinePath(result.decodedPath, result.destination.name ?: "your destination")
        currentPathGuidance = null // Reset guidance on new path

        runOnUiThread {
            val map = mMap ?: run {
                Log.e(TAG, "Map not ready in onPathFound")
                return@runOnUiThread
            }
            map.clear()
            val decodedOverviewPath = PolyUtil.decode(result.overviewPolyline)
            map.addPolyline(PolylineOptions().addAll(decodedOverviewPath).color(Color.BLUE).width(12f))

            fusedLocationClient.lastLocation.addOnSuccessListener { myLocation: Location? ->
                val boundsBuilder = LatLngBounds.builder()
                val myLatLng = myLocation?.let { LatLng(it.latitude, it.longitude) }
                val destLatLng = result.destination.latLng

                myLatLng?.let { boundsBuilder.include(it) }
                destLatLng?.let { boundsBuilder.include(it) }

                try {
                    val bounds = boundsBuilder.build()
                    map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 100))
                } catch (e: IllegalStateException) {
                    // Fallback if bounds are empty or invalid
                    val fallbackLatLng = myLatLng ?: destLatLng
                    fallbackLatLng?.let {
                        map.moveCamera(CameraUpdateFactory.newLatLngZoom(it, 15f))
                    }
                    Log.e(TAG, "Error building LatLngBounds or moving camera: ${e.message}")
                }
            }
        }
    }

    override fun onPathError(message: String) {
        guidanceManager.stopGuidance()
        currentPathGuidance = null
        runOnUiThread {
            Toast.makeText(this, "Path Error: $message", Toast.LENGTH_LONG).show()
        }
    }

    // ARCore Session Configuration
    fun configureSession(session: Session) {
        session.configure(
            session.config.apply {
                lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                depthMode = if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                    Config.DepthMode.AUTOMATIC
                } else {
                    Config.DepthMode.DISABLED
                }
                instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP
                focusMode = Config.FocusMode.AUTO
            }
        )
    }

    // SensorEventListener Implementation
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No implementation needed
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
            val rotationMatrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            val orientationAngles = FloatArray(3)
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
            var azimuthDegrees = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
            azimuthDegrees = (azimuthDegrees + 360) % 360
            currentBearing = azimuthDegrees
            if (::renderer.isInitialized) {
                renderer.updateCompassBearing(currentBearing)
            }
        }
    }

    // --- Utility Function ---
    private fun extractDestinationFromCommand(command: String?): String? {
        if (command.isNullOrBlank()) return null

        val lowerCaseCommand = command.lowercase(Locale.getDefault())
        val patterns = listOf("take me to", "navigate to", "go to", "directions to", "find")

        for (pattern in patterns) {
            if (lowerCaseCommand.contains(pattern)) {
                val destination = lowerCaseCommand.substringAfter(pattern).trim()
                if (destination.isNotEmpty()) {
                    return destination
                }
            }
        }
        // If no pattern matches, assume the whole command is the destination
        return lowerCaseCommand.trim()
    }
}