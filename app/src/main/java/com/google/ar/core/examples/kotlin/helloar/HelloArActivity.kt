package com.google.ar.core.examples.kotlin.helloar

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
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
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import com.google.maps.android.PolyUtil
import android.graphics.Color
import android.location.Location
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.ActivityCompat
import com.google.ar.core.examples.kotlin.helloar.path.DirectionsResult
import com.google.ar.core.examples.kotlin.helloar.path.GuidanceManager
import com.google.ar.core.examples.kotlin.helloar.path.GuidanceState
import com.google.ar.core.examples.kotlin.helloar.path.PathFinder
import com.google.ar.core.examples.kotlin.helloar.path.PathFinderListener
import com.google.ar.core.examples.kotlin.helloar.path.toLatLng
import com.google.firebase.FirebaseApp

class HelloArActivity : AppCompatActivity(), OnMapReadyCallback, PathFinderListener {

  companion object {
    private const val TAG = "HelloArActivity"
  }

  // ARCore and Renderer Components
  lateinit var arCoreSessionHelper: ARCoreSessionLifecycleHelper
  lateinit var view: HelloArView
  lateinit var renderer: HelloArRenderer
  val instantPlacementSettings = InstantPlacementSettings()
  val depthSettings = DepthSettings()

  // UI Components
  private lateinit var distanceTextView: DistanceTextView
  private lateinit var mapsTextView: TextView
  private lateinit var mapFragment: SupportMapFragment
  private lateinit var bevImageView: ImageView
  private lateinit var bevTextView: TextView
  private var mMap: GoogleMap? = null

  // Google Services and Custom Navigation Engine
  private lateinit var fusedLocationClient: FusedLocationProviderClient
  private lateinit var placesClient: PlacesClient
  private lateinit var pathFinder: PathFinder
  private lateinit var guidanceManager: GuidanceManager
  private lateinit var voiceAssistant: VoiceAssistant
  private lateinit var locationCallback: LocationCallback

  // Shared State for communication between components
  val guidanceState = GuidanceState()
  var showBEV = false

  // --- ActivityResultLaunchers for Permissions and Voice Input ---

  @SuppressLint("MissingPermission")
  private val locationPermissionRequest =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
      if (isGranted) {
        // Permission granted, now we can start location updates
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
        // The activity itself is the listener
        pathFinder.findAndPreparePath(keyword, this)
      } else {
        Toast.makeText(this, "Could not understand destination.", Toast.LENGTH_SHORT).show()
        voiceAssistant.speakTextAndWait("Could not find the destination, please try again", object : VoiceAssistant.SpeechCompletionListener {
          override fun onSpeechFinished() {
            Toast.makeText(this@HelloArActivity, "Speech finished", Toast.LENGTH_SHORT).show()
          }
        })
      }
    }

  // --- Activity Lifecycle Methods ---

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)

    // Find UI components
    mapFragment = supportFragmentManager.findFragmentById(R.id.map_fragment) as SupportMapFragment
    view = findViewById(R.id.hello_ar_view)
    distanceTextView = view.findViewById(R.id.distanceTextView)
    mapsTextView = view.findViewById<TextView>(R.id.MapsDirection)
    bevImageView = view.findViewById<ImageView>(R.id.imageBEVView)
    bevTextView = view.findViewById<TextView>(R.id.textBEVView)
    val listenButton = findViewById<Button>(R.id.listenVoiceButton)
    val showBEVButton = view.findViewById<Button>(R.id.showBEV)

    // Initialize all helper classes and services
    initializeCoreServices()

    // Setup ARCore
    initializeArCore()

    // Set up Gemini
    FirebaseApp.initializeApp(this)
    val geminiVlmService = GeminiVlmService()
    // Setup Renderer
    renderer = HelloArRenderer(this, geminiVlmService, voiceAssistant, guidanceState)
    lifecycle.addObserver(renderer)
    SampleRender(view.surfaceView, renderer, assets)
    renderer.setDistanceTextView(distanceTextView)
    renderer.setMapsTextView(mapsTextView)
    renderer.setBEVImageView(bevImageView)
    renderer.setBEVTextView(bevTextView)



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

    // Start the map initialization process
    mapFragment.getMapAsync(this)
  }

  override fun onMapReady(googleMap: GoogleMap) {
    mMap = googleMap
    Log.d(TAG, "GoogleMap is ready.")
    val distanceText = "GoogleMap is ready."
    this.runOnUiThread {
      distanceTextView.text = distanceText
    }
    // Now that the map is ready, check for location permissions to enable user location
    checkLocationPermission()
  }

  override fun onResume() {
    super.onResume()
    // It's good practice to restart location updates when the app becomes active
    if (::locationCallback.isInitialized) {
      checkLocationPermission()
    }
  }

  override fun onPause() {
    super.onPause()
    // Stop location updates when the app is not in the foreground to save battery
    fusedLocationClient.removeLocationUpdates(locationCallback)
  }

  override fun onDestroy() {
    if (::voiceAssistant.isInitialized) {
      voiceAssistant.shutdownTextToSpeech()
    }
    super.onDestroy()
  }

  // --- Initialization and Permissions ---

  private fun initializeCoreServices() {
    lifecycle.addObserver(view)
    fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
    Places.initialize(applicationContext, BuildConfig.MAPS_API_KEY)
    placesClient = Places.createClient(this)
    voiceAssistant = VoiceAssistant()
    voiceAssistant.initializeTextToSpeech(this)
    pathFinder = PathFinder(this, fusedLocationClient, placesClient)
    guidanceManager = GuidanceManager(guidanceState)
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
      this.runOnUiThread {
        distanceTextView.text = String.format("ARCore threw an exception", exception)
      }
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

  private fun startLocationUpdates() {
    val locationRequest = LocationRequest.create().apply {
      interval = 5000 // 5 seconds
      fastestInterval = 2000 // 2 seconds
      priority = LocationRequest.PRIORITY_HIGH_ACCURACY
    }

    locationCallback = object : LocationCallback() {
      override fun onLocationResult(locationResult: LocationResult) {
        locationResult.lastLocation?.let { location ->
          guidanceManager.updateUserLocation(location)
        }
      }
    }

    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
      fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, mainLooper)
    }
  }

  // --- PathFinderListener Implementation ---

  @SuppressLint("MissingPermission")
  override fun onPathFound(result: DirectionsResult) {
    runOnUiThread {
      if (mMap == null) return@runOnUiThread
      mMap!!.clear()

      // Draw the route on the map
      val decodedPath = PolyUtil.decode(result.overviewPolyline)
      mMap!!.addPolyline(PolylineOptions().addAll(decodedPath).color(Color.BLUE).width(12f))

      // Set the route in our guidance engine
      voiceAssistant.speakTextAndWait(
        "Found a path to ${result.destination.name} with ${result.steps.size} turns.",
        object : VoiceAssistant.SpeechCompletionListener {
          override fun onSpeechFinished() {
            Toast.makeText(this@HelloArActivity, "Speech finished", Toast.LENGTH_SHORT).show()
          }
        }
      )

      Log.d(TAG, "onPathFound: ${result.steps}")
      this.runOnUiThread {
        distanceTextView.text = String.format("onPathFound: ${result.steps}")
      }
      guidanceManager.setRoute(result.steps)

      // Move camera to show the full route
      val destination = result.destination
      fusedLocationClient.lastLocation.addOnSuccessListener{myLocation: Location? ->
        val bounds = destination.latLng?.let {
          myLocation?.let { it1 ->
            LatLngBounds.builder()
              .include(it1.toLatLng())
              .include(it)
              .build()
          }
        }
        bounds?.let { CameraUpdateFactory.newLatLngBounds(it, 150) }?.let { mMap!!.animateCamera(it) }
      }

    }
  }

  override fun onPathError(message: String) {
    runOnUiThread {
      Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
  }

  // --- Helper and ARCore Methods ---

  private fun extractDestinationFromCommand(rawText: String?): String? {
    if (rawText.isNullOrBlank()) return null
    val triggerPhrases = listOf("i want to go to", "take me to", "navigate to", "find a path to", "directions to", "go to", "find")
    val lowercasedText = rawText.lowercase().trim()
    for (phrase in triggerPhrases) {
      if (lowercasedText.startsWith("$phrase ")) {
        return rawText.substring(phrase.length).trim()
      }
    }
    return rawText.trim()
  }


  // Configure the session, using Lighting Estimation, and Depth mode.
  fun configureSession(session: Session) {
    session.configure(
      session.config.apply {
        lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR

        // Depth API is used if it is configured in Hello AR's settings.
        depthMode =
          if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
            Config.DepthMode.AUTOMATIC
          } else {
            Config.DepthMode.DISABLED
          }

        // Instant Placement is used if it is configured in Hello AR's settings.
        instantPlacementMode =
          if (instantPlacementSettings.isInstantPlacementEnabled) {
            Config.InstantPlacementMode.LOCAL_Y_UP
          } else {
            Config.InstantPlacementMode.DISABLED
          }
      }
    )
  }

  override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<String>,
    results: IntArray
  ) {
    super.onRequestPermissionsResult(requestCode, permissions, results)
    if (!CameraPermissionHelper.hasCameraPermission(this)) {
      // Use toast instead of snackbar here since the activity will exit.
      Toast.makeText(this, "Camera permission is needed to run this application", Toast.LENGTH_LONG)
        .show()
      if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
        // Permission denied with checking "Do not ask again".
        CameraPermissionHelper.launchPermissionSettings(this)
      }
      finish()
    }
  }

  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus)
  }

}
