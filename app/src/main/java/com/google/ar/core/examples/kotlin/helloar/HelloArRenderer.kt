/*
 * Copyright 2021 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.ar.core.examples.kotlin.helloar

import android.annotation.SuppressLint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
//import androidx.compose.runtime.mutableStateOf
//import androidx.compose.runtime.getValue
//import androidx.compose.runtime.setValue
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.ImageFormat
import android.renderscript.*
import android.graphics.Paint
import android.graphics.Paint.ANTI_ALIAS_FLAG
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.opengl.GLES30
import android.opengl.Matrix
import android.os.Environment
import android.util.Base64
import android.util.Log
import androidx.annotation.ColorInt
import androidx.annotation.Dimension
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.ar.core.Anchor
import com.google.ar.core.Camera
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.InstantPlacementPoint
import com.google.ar.core.LightEstimate
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.Trackable
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.examples.java.common.helpers.DisplayRotationHelper
import com.google.ar.core.examples.java.common.helpers.TrackingStateHelper
import com.google.ar.core.examples.java.common.samplerender.Framebuffer
import com.google.ar.core.examples.java.common.samplerender.GLError
import com.google.ar.core.examples.java.common.samplerender.Mesh
import com.google.ar.core.examples.java.common.samplerender.SampleRender
import com.google.ar.core.examples.java.common.samplerender.Shader
import com.google.ar.core.examples.java.common.samplerender.Texture
import com.google.ar.core.examples.java.common.samplerender.VertexBuffer
import com.google.ar.core.examples.java.common.samplerender.arcore.BackgroundRenderer
import com.google.ar.core.examples.java.common.samplerender.arcore.PlaneRenderer
import com.google.ar.core.examples.java.common.samplerender.arcore.SpecularCubemapFilter
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.NotYetAvailableException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import okhttp3.*
//import com.google.ar.sceneform.ux.ArSceneView
import com.google.gson.Gson
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import android.widget.Toast
import com.google.android.gms.maps.GoogleMap
import com.google.ar.core.exceptions.SessionPausedException
import java.nio.ShortBuffer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay

@ColorInt
private var textColor = 255   // Obtained from style attributes.

@Dimension
private var textHeight = 12f  // Obtained from style attributes.

private val textPaint = Paint(ANTI_ALIAS_FLAG).apply {
  color = textColor
  if (textHeight == 0f) {
    textHeight = textSize
  } else {
    textSize = textHeight
  }
}

private val piePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
  style = Paint.Style.FILL
  textSize = textHeight
}

private val shadowPaint = Paint(0).apply {
  color = 0x101010
  maskFilter = BlurMaskFilter(8f, BlurMaskFilter.Blur.NORMAL)
}

/** Renders the HelloAR application using our example Renderer. */
class HelloArRenderer(
  val activity: HelloArActivity,
  val voiceAssistant: VoiceAssistant,
  val guidanceState: GuidanceState,
) :
  SampleRender.Renderer, DefaultLifecycleObserver {
  companion object {
    val TAG = "HelloArRenderer"

    // See the definition of updateSphericalHarmonicsCoefficients for an explanation of these
    // constants.
    private val sphericalHarmonicFactors =
      floatArrayOf(
        0.282095f,
        -0.325735f,
        0.325735f,
        -0.325735f,
        0.273137f,
        -0.273137f,
        0.078848f,
        -0.273137f,
        0.136569f
      )

    private val Z_NEAR = 0.1f
    private val Z_FAR = 100f

    // Assumed distance from the device camera to the surface on which user will try to place
    // objects.
    // This value affects the apparent scale of objects while the tracking method of the
    // Instant Placement point is SCREENSPACE_WITH_APPROXIMATE_DISTANCE.
    // Values in the [0.2, 2.0] meter range are a good choice for most AR experiences. Use lower
    // values for AR experiences where users are expected to place objects on surfaces close to the
    // camera. Use larger values for experiences where the user will likely be standing and trying
    // to
    // place an object on the ground or floor in front of them.
    val APPROXIMATE_DISTANCE_METERS = 2.0f

    val CUBEMAP_RESOLUTION = 16
    val CUBEMAP_NUMBER_OF_IMPORTANCE_SAMPLES = 32
  }


  lateinit var render: SampleRender
  lateinit var planeRenderer: PlaneRenderer
  lateinit var backgroundRenderer: BackgroundRenderer
  lateinit var virtualSceneFramebuffer: Framebuffer
  var hasSetTextureNames = false

  // Point Cloud
  lateinit var pointCloudVertexBuffer: VertexBuffer
  lateinit var pointCloudMesh: Mesh
  lateinit var pointCloudShader: Shader

  // Keep track of the last point cloud rendered to avoid updating the VBO if point cloud
  // was not changed.  Do this using the timestamp since we can't compare PointCloud objects.
  var lastPointCloudTimestamp: Long = 0

  // Virtual object (ARCore pawn)
  lateinit var virtualObjectMesh: Mesh
  lateinit var virtualObjectShader: Shader
  lateinit var virtualObjectAlbedoTexture: Texture
  lateinit var virtualObjectAlbedoInstantPlacementTexture: Texture

  private val wrappedAnchors = mutableListOf<WrappedAnchor>()

  // Environmental HDR
  lateinit var dfgTexture: Texture
  lateinit var cubemapFilter: SpecularCubemapFilter

  // Temporary matrix allocated here to reduce number of allocations for each frame.
  val modelMatrix = FloatArray(16)
  val viewMatrix = FloatArray(16)
  val projectionMatrix = FloatArray(16)
  val modelViewMatrix = FloatArray(16) // view x model

  val modelViewProjectionMatrix = FloatArray(16) // projection x view x model

  val sphericalHarmonicsCoefficients = FloatArray(9 * 3)
  val viewInverseMatrix = FloatArray(16)
  val worldLightDirection = floatArrayOf(0.0f, 0.0f, 0.0f, 0.0f)
  val viewLightDirection = FloatArray(4) // view x world light direction

  val session
    get() = activity.arCoreSessionHelper.session

  val displayRotationHelper = DisplayRotationHelper(activity)
  val trackingStateHelper = TrackingStateHelper(activity)

  //Drawing
  private lateinit var distanceTextView: DistanceTextView

  private var frameCounter = 0
  private val frameSkipInterval = 10 // Process every nth frame (adjust as needed)

  private var depthCount = 0
  private var imageCount = 0

  // Coroutine scope tied to the main thread
  private val scope = CoroutineScope(Dispatchers.Main)
  // An atomic flag to prevent multiple requests from being sent simultaneously
  private val isProcessing = AtomicBoolean(false)

  // Threshold depth
  private var DEPTH_THRESHOLD_MM = 2000

  // Data class to store detection result
  data class HitTestResultData(
    val distance: Float?,
    val className: String?,
    val confidence: Float?
  )


  // Connection
  // Create a TrustManager that trusts all certificates
  private val trustAllCerts = object : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {}
    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {}
    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
  }

  // Install the all-trusting trust manager
  private val sslContext = SSLContext.getInstance("TLS").apply {
    init(null, arrayOf(trustAllCerts), java.security.SecureRandom())
  }

  // Build the OkHttpClient
  private val client = OkHttpClient.Builder()
    .sslSocketFactory(sslContext.socketFactory, trustAllCerts)
    .hostnameVerifier { _, _ -> true } // Accept all hostnames
    .build()
  private val url = BuildConfig.MICRO_GUIDANCE_API
//  private val latestHitTestResults = mutableStateOf<List<HitTestResultData>>(emptyList())

  interface UploadCallback {
    fun onSuccess(response: String)
    fun onError(error: String)
  }

  fun setDistanceTextView(distanceTextView: DistanceTextView) {
    this.distanceTextView = distanceTextView
  }

  override fun onResume(owner: LifecycleOwner) {
    displayRotationHelper.onResume()
    hasSetTextureNames = false
  }

  override fun onPause(owner: LifecycleOwner) {
    displayRotationHelper.onPause()
  }

  override fun onSurfaceCreated(render: SampleRender) {
    // Prepare the rendering objects.
    // This involves reading shaders and 3D model files, so may throw an IOException.
    try {
      planeRenderer = PlaneRenderer(render)
      backgroundRenderer = BackgroundRenderer(render)
      virtualSceneFramebuffer = Framebuffer(render, /*width=*/ 1, /*height=*/ 1)

      cubemapFilter =
        SpecularCubemapFilter(render, CUBEMAP_RESOLUTION, CUBEMAP_NUMBER_OF_IMPORTANCE_SAMPLES)
      // Load environmental lighting values lookup table
      dfgTexture =
        Texture(
          render,
          Texture.Target.TEXTURE_2D,
          Texture.WrapMode.CLAMP_TO_EDGE,
          /*useMipmaps=*/ false
        )
      // The dfg.raw file is a raw half-float texture with two channels.
      val dfgResolution = 64
      val dfgChannels = 2
      val halfFloatSize = 2

      val buffer: ByteBuffer =
        ByteBuffer.allocateDirect(dfgResolution * dfgResolution * dfgChannels * halfFloatSize)
      activity.assets.open("models/dfg.raw").use { it.read(buffer.array()) }

      // SampleRender abstraction leaks here.
      GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, dfgTexture.textureId)
      GLError.maybeThrowGLException("Failed to bind DFG texture", "glBindTexture")
      GLES30.glTexImage2D(
        GLES30.GL_TEXTURE_2D,
        /*level=*/ 0,
        GLES30.GL_RG16F,
        /*width=*/ dfgResolution,
        /*height=*/ dfgResolution,
        /*border=*/ 0,
        GLES30.GL_RG,
        GLES30.GL_HALF_FLOAT,
        buffer
      )
      GLError.maybeThrowGLException("Failed to populate DFG texture", "glTexImage2D")

      // Point cloud
      pointCloudShader =
        Shader.createFromAssets(
            render,
            "shaders/point_cloud.vert",
            "shaders/point_cloud.frag",
            /*defines=*/ null
          )
          .setVec4("u_Color", floatArrayOf(31.0f / 255.0f, 188.0f / 255.0f, 210.0f / 255.0f, 1.0f))
          .setFloat("u_PointSize", 5.0f)

      // four entries per vertex: X, Y, Z, confidence
      pointCloudVertexBuffer =
        VertexBuffer(render, /*numberOfEntriesPerVertex=*/ 4, /*entries=*/ null)
      val pointCloudVertexBuffers = arrayOf(pointCloudVertexBuffer)
      pointCloudMesh =
        Mesh(render, Mesh.PrimitiveMode.POINTS, /*indexBuffer=*/ null, pointCloudVertexBuffers)

      // Virtual object to render (ARCore pawn)
      virtualObjectAlbedoTexture =
        Texture.createFromAsset(
          render,
          "models/pawn_albedo.png",
          Texture.WrapMode.CLAMP_TO_EDGE,
          Texture.ColorFormat.SRGB
        )

      virtualObjectAlbedoInstantPlacementTexture =
        Texture.createFromAsset(
          render,
          "models/pawn_albedo_instant_placement.png",
          Texture.WrapMode.CLAMP_TO_EDGE,
          Texture.ColorFormat.SRGB
        )

      val virtualObjectPbrTexture =
        Texture.createFromAsset(
          render,
          "models/pawn_roughness_metallic_ao.png",
          Texture.WrapMode.CLAMP_TO_EDGE,
          Texture.ColorFormat.LINEAR
        )
      virtualObjectMesh = Mesh.createFromAsset(render, "models/pawn.obj")
      virtualObjectShader =
        Shader.createFromAssets(
            render,
            "shaders/environmental_hdr.vert",
            "shaders/environmental_hdr.frag",
            mapOf("NUMBER_OF_MIPMAP_LEVELS" to cubemapFilter.numberOfMipmapLevels.toString())
          )
          .setTexture("u_AlbedoTexture", virtualObjectAlbedoTexture)
          .setTexture("u_RoughnessMetallicAmbientOcclusionTexture", virtualObjectPbrTexture)
          .setTexture("u_Cubemap", cubemapFilter.filteredCubemapTexture)
          .setTexture("u_DfgTexture", dfgTexture)

    } catch (e: IOException) {
      Log.e(TAG, "Failed to read a required asset file", e)
      showError("Failed to read a required asset file: $e")
    }
  }

  override fun onSurfaceChanged(render: SampleRender, width: Int, height: Int) {
    displayRotationHelper.onSurfaceChanged(width, height)
    virtualSceneFramebuffer.resize(width, height)
  }

  fun getDepthValueAt(depthImage: Image, x: Int, y: Int): UInt {
    // Ensure the coordinates are within bounds
    if (x < 0 || x >= depthImage.width || y < 0 || y >= depthImage.height) {
      throw IllegalArgumentException("Coordinates are out of bounds")
    }

    // The depth image has a single plane, which stores depth for each
    // pixel as 16-bit unsigned integers.
    val plane = depthImage.planes[0]
    val byteIndex = x * plane.pixelStride + y * plane.rowStride
    val buffer = plane.buffer.order(ByteOrder.nativeOrder())
    val depthSample = buffer.getShort(byteIndex)
    return depthSample.toUInt()
  }

  private fun createHitResultFromPose(frame: Frame, camera: Camera, targetPose: Pose): HitResult? {
    try {
      // 1. Get the camera's pose and projection matrix
      val cameraPose = camera.pose
      val projectionMatrix = FloatArray(16)
      camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f)

      // 2. Project the targetPose to screen coordinates
      val targetTranslation = targetPose.translation
      val targetPoint = floatArrayOf(targetTranslation[0], targetTranslation[1], targetTranslation[2], 1f)

      // Transform the target point to camera space
      val viewMatrix = FloatArray(16)
      cameraPose.inverse().toMatrix(viewMatrix, 0)
      val cameraSpacePoint = FloatArray(4)
      Matrix.multiplyMV(cameraSpacePoint, 0, viewMatrix, 0, targetPoint, 0)

      // Project the camera-space point to screen space
      val screenSpacePoint = FloatArray(4)
      Matrix.multiplyMV(screenSpacePoint, 0, projectionMatrix, 0, cameraSpacePoint, 0)

      // Normalize the screen-space point to NDC
      val ndcX = screenSpacePoint[0] / screenSpacePoint[3]
      val ndcY = screenSpacePoint[1] / screenSpacePoint[3]

      // Convert NDC to screen coordinates
      val screenX = (ndcX * 0.5f + 0.5f) * virtualSceneFramebuffer.width
      val screenY = (ndcY * -0.5f + 0.5f) * virtualSceneFramebuffer.height

      // 3. Perform a hit test at the calculated screen coordinates
      val hitResultList = frame.hitTest(screenX, screenY)

      // 4. Return the first hit result (if any)
      return if (hitResultList.isNotEmpty()) {
        hitResultList[0]
      } else {
        null
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error in createHitResultFromPose", e)
      return null
    }
  }

  private fun drawVirtualObject(hitPose: Pose, cameraPose: Pose, viewMatrix: FloatArray, projectionMatrix: FloatArray) {
    // Get the translation from the hit pose
    val translation = hitPose.translation
    // Create a model matrix for the virtual object
    Matrix.setIdentityM(modelMatrix, 0)
    Matrix.translateM(modelMatrix, 0, translation[0], translation[1], translation[2])

    // Calculate the model-view-projection matrix
    Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0)
    Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0)

    // Draw the virtual object
    virtualObjectShader.setMat4("u_ModelViewProjection", modelViewProjectionMatrix)
    virtualObjectShader.setVec3("u_LightDirection", floatArrayOf(viewLightDirection[0], viewLightDirection[1], viewLightDirection[2]))
    virtualObjectShader.setVec3("u_ViewPosition", floatArrayOf(cameraPose.tx(), cameraPose.ty(), cameraPose.tz()))
    render.draw(virtualObjectMesh, virtualObjectShader)
  }

  private fun yuv420888ToRgbBitmap(image: Image): Bitmap? {
    val width = image.width
    val height = image.height

    // Get YUV planes
    val yBuffer = image.planes[0].buffer
    val uBuffer = image.planes[1].buffer
    val vBuffer = image.planes[2].buffer

    // YUV buffers to byte arrays
    val yBytes = ByteArray(yBuffer.remaining())
    val uBytes = ByteArray(uBuffer.remaining())
    val vBytes = ByteArray(vBuffer.remaining())

    yBuffer.get(yBytes)
    uBuffer.get(uBytes)
    vBuffer.get(vBytes)

    // Concatenate Y, U, and V byte arrays
    val combinedBytes = yBytes + uBytes + vBytes

    // Alternatively, using System.arraycopy for more efficiency
    fun concatenateByteArrays(vararg arrays: ByteArray): ByteArray {
      val totalLength = arrays.sumOf { it.size }
      val result = ByteArray(totalLength)
      var currentIndex = 0

      for (array in arrays) {
        System.arraycopy(array, 0, result, currentIndex, array.size)
        currentIndex += array.size
      }

      return result
    }

    val combinedBytesEfficient = concatenateByteArrays(yBytes, vBytes, uBytes)

    // Convert YUV to RGB
    val yuvImage = YuvImage(combinedBytesEfficient, ImageFormat.NV21, image.width, image.height, null)
    val outputStream = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 100, outputStream)
    val rgbBytes = outputStream.toByteArray()

//    // Create RGB array
//    val rgbBytes = ByteArray(width * height * 3)
//
//    // YUV → RGB conversion
//    for (y in 0 until height) {
//      for (x in 0 until width) {
//        val yValue = yBytes[y * width + x].toInt() and 0xFF
//        val uValue = uBytes[(y / 2) * (width / 2) + (x / 2)].toInt() and 0xFF
//        val vValue = vBytes[(y / 2) * (width / 2) + (x / 2)].toInt() and 0xFF
//
//        // YUV to RGB conversion formula
//        val r = (yValue + 1.402 * (vValue - 128)).coerceIn(0.0, 255.0).toInt()
//        val g = (yValue - 0.344136 * (uValue - 128) - 0.714136 * (vValue - 128)).coerceIn(0.0, 255.0).toInt()
//        val b = (yValue + 1.772 * (uValue - 128)).coerceIn(0.0, 255.0).toInt()
//
//        // Store RGB values in the byte array
//        val index = (y * width + x) * 3
//        rgbBytes[index] = r.toByte()
//        rgbBytes[index + 1] = g.toByte()
//        rgbBytes[index + 2] = b.toByte()
//      }
//    }

//     Encode the RGB byte array to Base64
//    return Base64.encodeToString(rgbBytes, Base64.NO_WRAP)

    return BitmapFactory.decodeByteArray(rgbBytes, 0, rgbBytes.size)
  }

  fun saveImage(bitmap: Bitmap?, filePath: String) {
    val file = File(filePath)
    try {
      val outputStream = FileOutputStream(file)
      bitmap?.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
      outputStream.flush()
      outputStream.close()
      Log.d(TAG, "Image saved successfully: $filePath")
    } catch (e: IOException) {
      e.printStackTrace()
      Log.e(TAG, "Error saving image: ${e.message}")
    }
  }

  fun encodeImageToBase64(filePath: String): String {
    val file = File(filePath)
    val bytes = file.readBytes()
    return Base64.encodeToString(bytes, Base64.NO_WRAP)
  }

  /**
   * Captures a snapshot of the current map, converts it to a PNG,
   * and uploads it to a specified server URL.
   *
   * @param googleMap The GoogleMap instance to capture.
   * @param serverUrl The URL of the server endpoint that will receive the image.
   */
  fun captureAndSaveMap(googleMap: GoogleMap?, filePath: String) {
    if (googleMap == null) {
      Log.e(TAG, "Cannot capture snapshot, GoogleMap is not ready.")
      return
    }

    // 1. CAPTURE THE MAP SNAPSHOT
    // This is an asynchronous operation. The result is returned in a callback.
    googleMap.snapshot { bitmap ->
      if (bitmap == null) {
        Log.e(TAG, "Snapshot failed, bitmap is null.")
        activity.runOnUiThread{
          Toast.makeText(activity, "Failed to capture map.", Toast.LENGTH_SHORT).show()
        }
        return@snapshot
      }

      // 2. CONVERT THE BITMAP TO A BYTE ARRAY (PNG FORMAT)
      val stream = ByteArrayOutputStream()
      bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream)

      saveImage(bitmap, filePath)
    }
  }


  @SuppressLint("DefaultLocale")
  override fun onDrawFrame(render: SampleRender) {
    val session = session ?: return

    try {
      // Texture names should only be set once on a GL thread unless they change. This is done during
      // onDrawFrame rather than onSurfaceCreated since the session is not guaranteed to have been
      // initialized during the execution of onSurfaceCreated.
      if (!hasSetTextureNames) {
        session.setCameraTextureNames(intArrayOf(backgroundRenderer.cameraColorTexture.textureId))
        hasSetTextureNames = true
      }

      // -- Update per-frame state

      // Notify ARCore session that the view size changed so that the perspective matrix and
      // the video background can be properly adjusted.
      displayRotationHelper.updateSessionIfNeeded(session)

      // Obtain the current frame from ARSession. When the configuration is set to
      // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
      // camera framerate.
      val frame =
        try {
          session.update()
        } catch (e: CameraNotAvailableException) {
          Log.e(TAG, "Camera not available during onDrawFrame", e)
          showError("Camera not available. Try restarting the app.")
          return
        }

      // Increment the frame counter
      frameCounter++

      // Variables to hold the result
      var results: JsonArray? = null

//    // Check if it's time to process a frame
//    if (frameCounter % frameSkipInterval == 0) {
////      processFrame(frame)
//      imageCount +=1
//      try {
//        val image: Image? = try {
//          frame.acquireCameraImage()
//
//        } catch (e: NotYetAvailableException) {
//          null
//        }

//        image?.use { // Use the image within this block, it will be closed automatically
//          // Distance to center of the phone
//          val centerX = image.width / 2
//          val centerY = image.height / 2
////          Log.d(TAG, "Center of the image: ($centerX, $centerY)")
//          val hitResultList = frame.hitTest(centerX.toFloat(), centerY.toFloat())
//          val hitResult: HitResult? = hitResultList.firstOrNull()
//
//          Log.d(
//            TAG,
//            "Distance to phone: ${hitResult?.distance} meters"
//          )
//
//          if (hitResult != null) {
//            // Update the distance text
//            val distanceText = String.format(
//              "Distance to phone is ${System.lineSeparator()} %.2f meters",
//              hitResult.distance
//            )
//            activity.runOnUiThread {
//              distanceTextView.distanceText = distanceText
//            }
//          } else {
//            Log.e(TAG, "hitResult is null")
//          }
//
//          // Detect object and show distance
//          val width = it.width
//
//          uploadImage(it, imageCount, object : UploadCallback {
//            @SuppressLint("DefaultLocale")
//            override fun onSuccess(response: String) {
//              // Send the image to the predict api
//              Log.d(TAG, "Result: $response")
//
//              results = Json.parseToJsonElement(response).jsonArray
//
//              // Speak the text from the API response
//              for (element in results!!) {
//
////                // Using detection
////                val className = element.jsonObject["class"].toString()
////                if (className.trim() == "\"pedestrian lane\""){
//////                  val obstacleX = element.jsonObject["x"]?.jsonPrimitive?.floatOrNull
//////                  val obstacleY = element.jsonObject["y"]?.jsonPrimitive?.floatOrNull
//////                  val obstaclehitResultList = obstacleX?.let { it1 -> obstacleY?.let { it2 -> frame.hitTest(it1.toFloat(), it2.toFloat()) } }
//////                  val obstaclehitResult: HitResult? = obstaclehitResultList?.firstOrNull()
////                  val direction = element.jsonObject["direction"]?.jsonPrimitive?.toString()
////                  if (direction != null) {
////                    speakText(direction)
////                  }
////                }
//
//                  // Using VLM
//                  val guidance = element.jsonObject["response"]?.jsonPrimitive?.toString()
//                  Log.d(TAG, guidance.toString().replace("\"", "").replace("\\", ""))
//                  speakText(guidance.toString().replace("\"", "").replace("\\", ""))
////                else
////                  speakText(laneDirection)
//              }
//
////              val hitTestResults = mutableListOf<HitTestResultData>()
//
//              // Draw back on phone
////              for (element in results!!) {
////                val x = element.jsonObject["x"]?.jsonPrimitive?.floatOrNull
////                val y = element.jsonObject["y"]?.jsonPrimitive?.floatOrNull
////                val w = element.jsonObject["w"]?.jsonPrimitive?.floatOrNull
////                val h = element.jsonObject["h"]?.jsonPrimitive?.floatOrNull
////                val confidence = element.jsonObject["confidence"]?.jsonPrimitive?.floatOrNull
////                val className = element.jsonObject["class"].toString()
////
////                if (x != null && y != null && w != null && h != null) {
////                  Log.d(TAG, "x: $x, y: $y, w: $w, h: $h")
//////                  // Store null distance for now; we'll calculate it in onDrawFrame
//////                  hitTestResults.add(HitTestResultData(null, className, confidence))
////                }
////
////                Log.d(TAG, "Parse result: $results")
////              }
//            }
//
//            override fun onError(error: String) {
//              Log.e(TAG, "Error response: $error")
//            }
//          }) ////////////////////////////////////////////
//        }
//      } catch (e: Exception) {
//        Log.e(TAG, "Something wrong", e)
//      }
//    }



      val camera = frame.camera

      // Update BackgroundRenderer state to match the depth settings.
      try {
        backgroundRenderer.setUseDepthVisualization(
          render,
          activity.depthSettings.depthColorVisualizationEnabled()
        )
        backgroundRenderer.setUseOcclusion(render, activity.depthSettings.useDepthForOcclusion())
      } catch (e: IOException) {
        Log.e(TAG, "Failed to read a required asset file", e)
        showError("Failed to read a required asset file: $e")
        return
      }

      // BackgroundRenderer.updateDisplayGeometry must be called every frame to update the coordinates
      // used to draw the background camera image.
      backgroundRenderer.updateDisplayGeometry(frame)
      val shouldGetDepthImage =
        activity.depthSettings.useDepthForOcclusion() ||
                activity.depthSettings.depthColorVisualizationEnabled()

      if (camera.trackingState == TrackingState.TRACKING && shouldGetDepthImage) {
        try {
          val depthImage = frame.acquireDepthImage16Bits()
          if (frameCounter % frameSkipInterval == 0
              && !isProcessing.get()
              && activity.guidanceState.getStartGuiding()
          ){
            try {
              depthCount += 1
//            val depthImage = frame.acquireDepthImage16Bits()
              val depthBuffer = depthImage.planes[0].buffer
              val depthData = ByteArray(depthBuffer.remaining())
              depthBuffer.get(depthData)
              depthBuffer.rewind()
              val depthShortBuffer: ShortBuffer = depthBuffer.asShortBuffer()

              var danger = false
              // Check if any depth data bigger than 2 meter
              var maxDepth = 0
              for (i in 0 until depthShortBuffer.remaining())
              {
                // 4. Read Depth Value (in millimeters)
                val depthValueMm = depthBuffer.get(i).toInt() and 0xFFFF // Ensure it's treated as unsigned short
                if (depthValueMm > maxDepth){ maxDepth = depthValueMm}
                // 5. Compare with Threshold
                if (depthValueMm < DEPTH_THRESHOLD_MM) {
                  println("Depth value $depthValueMm mm found below threshold ($DEPTH_THRESHOLD_MM mm)")
                  danger = true // Found a value below the threshold
                  break
                }
              }
              Log.d(TAG, "Max depth: $maxDepth")
              activity.runOnUiThread {
                distanceTextView.text = "Max depth: $maxDepth"
              }
              Log.d(TAG, "Danger: $danger")
              activity.runOnUiThread {
                distanceTextView.text = "Danger: $danger"
              }
              if (danger
                and !isProcessing.get()
              ) {
                isProcessing.set(true)
                Log.d(TAG, "Processing started, flag set to true.")
                activity.runOnUiThread {
                  distanceTextView.text = "Processing started, flag set to true."
                }

                // Get map and encode to base64
//                val mapPath = Environment.getExternalStorageDirectory().path + "/DCIM/map_snapshot.png"
//                val naviPath = Environment.getExternalStorageDirectory().path + "/DCIM/navi_snapshot.png"
//                val naviBitmap = activity.navFragment.view?.drawToBitmap(Bitmap.Config.ARGB_8888)
//                captureAndSaveMap(activity.map, mapPath)
//                saveImage(naviBitmap, naviPath)

//                val base64Navi = encodeImageToBase64(naviPath)
//                val base64Map = encodeImageToBase64(mapPath)

                // Get overall direction
                val overallDirection = guidanceState.getInstruction()

                val base64Depth = Base64.encodeToString(depthData, Base64.NO_WRAP)
                // Now send the image when there is an obstacle within 2 meters
                val image = frame.acquireCameraImage()
                val rgbBitmap = yuv420888ToRgbBitmap(image)
                val filePath =
                  Environment.getExternalStorageDirectory().path + "/DCIM/image.png"
                saveImage(rgbBitmap, filePath)
                val base64Image = encodeImageToBase64(filePath)

                data class ImageRequest(
                  val base64_depth: String,
                  val overall_direction: String = overallDirection,
//                  val base64_map: String,
//                  val base64_navi: String,
                  val base64_image: String,
                  val frame: Int = depthCount
                )

                val depthRequest = ImageRequest(
                  base64_depth = base64Depth,
//                  base64_map = base64Map,
//                  base64_navi = base64Navi,
                  base64_image = base64Image
                )
                val json = Gson().toJson(depthRequest)
                val requestBody = json.toRequestBody("application/json".toMediaTypeOrNull())

                val request = Request.Builder()
                  .url(url + "infer")
                  .addHeader("skip_zrok_interstitial", "true") // Set the header
                  .post(requestBody)
                  .build()

                try {
                  val response = client.newCall(request).execute()
                  val responseBody = response.body?.string()
                  Log.d(TAG, "Response from api $responseBody")
                  activity.runOnUiThread {
                    distanceTextView.text = "Response from api $responseBody"
                  }
                  results = responseBody?.let { Json.parseToJsonElement(it).jsonArray }

                  // Speak the text from the API response
                  for (element in results!!) {

//                // Using detection
//                val className = element.jsonObject["class"].toString()
//                if (className.trim() == "\"pedestrian lane\""){
////                  val obstacleX = element.jsonObject["x"]?.jsonPrimitive?.floatOrNull
////                  val obstacleY = element.jsonObject["y"]?.jsonPrimitive?.floatOrNull
////                  val obstaclehitResultList = obstacleX?.let { it1 -> obstacleY?.let { it2 -> frame.hitTest(it1.toFloat(), it2.toFloat()) } }
////                  val obstaclehitResult: HitResult? = obstaclehitResultList?.firstOrNull()
//                  val direction = element.jsonObject["direction"]?.jsonPrimitive?.toString()
//                  if (direction != null) {
//                    speakText(direction)
//                  }
//                }

                    // Using VLM
                    val guidance = element.jsonObject["response"]?.jsonPrimitive?.toString()
                    val stringGuidance = guidance.toString().replace("\"", "").replace("\\", "")
                    Log.d(TAG, stringGuidance)
                    activity.runOnUiThread {
                      distanceTextView.text = stringGuidance
                    }
                    if (!stringGuidance.contains("No significant change")) {
                      voiceAssistant.vibrate()
                      voiceAssistant.speakTextAndWait(
                        guidance.toString().replace("\"", "").replace("\\", ""),
                        object : VoiceAssistant.SpeechCompletionListener {
                          override fun onSpeechFinished() {
                            // *** THIS CODE RUNS ONLY AFTER SPEECH IS DONE ***
                            Log.d(TAG, "Speech finished. Starting 5-second wait phase.")
                            activity.runOnUiThread {
                              distanceTextView.text = "Speech finished. Starting 5-second wait phase."
                            }
                            // *** SIMULATED 5-SECOND PROCESSING LOOP ***
                            Log.d(TAG, "Starting simulated 5-second CPU-intensive task...")
                            activity.runOnUiThread {
                              distanceTextView.text = "Starting simulated 5-second CPU-intensive task..."
                            }
                            val startTime = System.currentTimeMillis()
                            var count = 0L
                            // This is a "busy-wait" loop. It will peg a CPU core at 100% for 5 seconds.
                            while (System.currentTimeMillis() - startTime < 5000) {
                              // Perform some meaningless work to keep the CPU busy.
                              count++
                            }
                            val endTime = System.currentTimeMillis()
                            Log.d(TAG, "Finished simulated task. Duration: ${endTime - startTime}ms. Count: $count")
                            Log.d(TAG, "Wait phase finished.")
                            Log.d(TAG, "Speech finished. Ready for next guidance.")
                            activity.runOnUiThread {
                              distanceTextView.text = "Speech finished. Ready for next guidance."
                            }
                            isProcessing.set(false)
                          }
                        }
                      )
                    }
                    else{
                      isProcessing.set(false)
                    }
                  }
                } catch (e: Exception) {
                  isProcessing.set(false)
                  Log.e(TAG, "Error sending depth image", e)
                  activity.runOnUiThread {
                    distanceTextView.text = "Error sending depth image $e"
                  }
                } finally {
                  // Free image
                  image.close()
                }
              }

            } catch (e: Exception) {
              Log.e(TAG, "Error getting depth image", e)
            }

          }

          backgroundRenderer.updateCameraDepthTexture(depthImage)
          depthImage.close()
        } catch (e: NotYetAvailableException) {
          // This normally means that depth data is not available yet. This is normal so we will not
          // spam the logcat with this.
        }
      }

      // Handle one tap per frame.
      handleTap(frame, camera)

      // Keep the screen unlocked while tracking, but allow it to lock when tracking stops.
      trackingStateHelper.updateKeepScreenOnFlag(camera.trackingState)

      // Show a message based on whether tracking has failed, if planes are detected, and if the user
      // has placed any objects.
      val message: String? =
        when {
          camera.trackingState == TrackingState.PAUSED &&
                  camera.trackingFailureReason == TrackingFailureReason.NONE ->
            activity.getString(R.string.searching_planes)
          camera.trackingState == TrackingState.PAUSED ->
            TrackingStateHelper.getTrackingFailureReasonString(camera)
          session.hasTrackingPlane() && wrappedAnchors.isEmpty() ->
            activity.getString(R.string.waiting_taps)
          session.hasTrackingPlane() && wrappedAnchors.isNotEmpty() -> null
          else -> activity.getString(R.string.searching_planes)
        }
      if (message == null) {
        activity.view.snackbarHelper.hide(activity)
      } else {
        activity.view.snackbarHelper.showMessage(activity, message)
      }

      // -- Draw background
      if (frame.timestamp != 0L) {
        // Suppress rendering if the camera did not produce the first frame yet. This is to avoid
        // drawing possible leftover data from previous sessions if the texture is reused.
        backgroundRenderer.drawBackground(render)
      }

      // If not tracking, don't draw 3D objects.
      if (camera.trackingState == TrackingState.PAUSED) {
        return
      }

      // -- Draw non-occluded virtual objects (planes, point cloud)

      // Get projection matrix.
      camera.getProjectionMatrix(projectionMatrix, 0, Z_NEAR, Z_FAR)

      // Get camera matrix and draw.
      camera.getViewMatrix(viewMatrix, 0)
      frame.acquirePointCloud().use { pointCloud ->
        if (pointCloud.timestamp > lastPointCloudTimestamp) {
          pointCloudVertexBuffer.set(pointCloud.points)
          lastPointCloudTimestamp = pointCloud.timestamp
        }
        Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
        pointCloudShader.setMat4("u_ModelViewProjection", modelViewProjectionMatrix)
        render.draw(pointCloudMesh, pointCloudShader)
      }

      // Visualize planes.
      planeRenderer.drawPlanes(
        render,
        session.getAllTrackables<Plane>(Plane::class.java),
        camera.displayOrientedPose,
        projectionMatrix
      )

      // -- Draw occluded virtual objects

      // Update lighting parameters in the shader
      updateLightEstimation(frame.lightEstimate, viewMatrix)

      // Visualize anchors created by touch.
      render.clear(virtualSceneFramebuffer, 0f, 0f, 0f, 0f)
      for ((anchor, trackable) in
      wrappedAnchors.filter { it.anchor.trackingState == TrackingState.TRACKING }) {
        // Get the current pose of an Anchor in world space. The Anchor pose is updated
        // during calls to session.update() as ARCore refines its estimate of the world.
        anchor.pose.toMatrix(modelMatrix, 0)

        // Calculate model/view/projection matrices
        Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0)

        // Update shader properties and draw
        virtualObjectShader.setMat4("u_ModelView", modelViewMatrix)
        virtualObjectShader.setMat4("u_ModelViewProjection", modelViewProjectionMatrix)
        val texture =
          if ((trackable as? InstantPlacementPoint)?.trackingMethod ==
            InstantPlacementPoint.TrackingMethod.SCREENSPACE_WITH_APPROXIMATE_DISTANCE
          ) {
            virtualObjectAlbedoInstantPlacementTexture
          } else {
            virtualObjectAlbedoTexture
          }
        virtualObjectShader.setTexture("u_AlbedoTexture", texture)
        render.draw(virtualObjectMesh, virtualObjectShader, virtualSceneFramebuffer)
      }

      // Compose the virtual scene with the background.
      backgroundRenderer.drawVirtualScene(render, virtualSceneFramebuffer, Z_NEAR, Z_FAR)
    }
    catch (e: SessionPausedException){
      Log.w(TAG, "session.update() was called on a paused session. This is expected during the app lifecycle and is handled safely.")
    }
  }

  /** Checks if we detected at least one plane. */
  private fun Session.hasTrackingPlane() =
    getAllTrackables(Plane::class.java).any { it.trackingState == TrackingState.TRACKING }

  /** Update state based on the current frame's light estimation. */
  private fun updateLightEstimation(lightEstimate: LightEstimate, viewMatrix: FloatArray) {
    if (lightEstimate.state != LightEstimate.State.VALID) {
      virtualObjectShader.setBool("u_LightEstimateIsValid", false)
      return
    }
    virtualObjectShader.setBool("u_LightEstimateIsValid", true)
    Matrix.invertM(viewInverseMatrix, 0, viewMatrix, 0)
    virtualObjectShader.setMat4("u_ViewInverse", viewInverseMatrix)
    updateMainLight(
      lightEstimate.environmentalHdrMainLightDirection,
      lightEstimate.environmentalHdrMainLightIntensity,
      viewMatrix
    )
    updateSphericalHarmonicsCoefficients(lightEstimate.environmentalHdrAmbientSphericalHarmonics)
    cubemapFilter.update(lightEstimate.acquireEnvironmentalHdrCubeMap())
  }

  private fun updateMainLight(
    direction: FloatArray,
    intensity: FloatArray,
    viewMatrix: FloatArray
  ) {
    // We need the direction in a vec4 with 0.0 as the final component to transform it to view space
    worldLightDirection[0] = direction[0]
    worldLightDirection[1] = direction[1]
    worldLightDirection[2] = direction[2]
    Matrix.multiplyMV(viewLightDirection, 0, viewMatrix, 0, worldLightDirection, 0)
    virtualObjectShader.setVec4("u_ViewLightDirection", viewLightDirection)
    virtualObjectShader.setVec3("u_LightIntensity", intensity)
  }

  private fun updateSphericalHarmonicsCoefficients(coefficients: FloatArray) {
    // Pre-multiply the spherical harmonics coefficients before passing them to the shader. The
    // constants in sphericalHarmonicFactors were derived from three terms:
    //
    // 1. The normalized spherical harmonics basis functions (y_lm)
    //
    // 2. The lambertian diffuse BRDF factor (1/pi)
    //
    // 3. A <cos> convolution. This is done to so that the resulting function outputs the irradiance
    // of all incoming light over a hemisphere for a given surface normal, which is what the shader
    // (environmental_hdr.frag) expects.
    //
    // You can read more details about the math here:
    // https://google.github.io/filament/Filament.html#annex/sphericalharmonics
    require(coefficients.size == 9 * 3) {
      "The given coefficients array must be of length 27 (3 components per 9 coefficients"
    }

    // Apply each factor to every component of each coefficient
    for (i in 0 until 9 * 3) {
      sphericalHarmonicsCoefficients[i] = coefficients[i] * sphericalHarmonicFactors[i / 3]
    }
    virtualObjectShader.setVec3Array(
      "u_SphericalHarmonicsCoefficients",
      sphericalHarmonicsCoefficients
    )
  }

  // Handle only one tap per frame, as taps are usually low frequency compared to frame rate.
  private fun handleTap(frame: Frame, camera: Camera) {
    if (camera.trackingState != TrackingState.TRACKING) return
    val tap = activity.view.tapHelper.poll() ?: return

    val hitResultList =
      if (activity.instantPlacementSettings.isInstantPlacementEnabled) {
        frame.hitTestInstantPlacement(tap.x, tap.y, APPROXIMATE_DISTANCE_METERS)
      } else {
        frame.hitTest(tap)
      }

    // Hits are sorted by depth. Consider only closest hit on a plane, Oriented Point, Depth Point,
    // or Instant Placement Point.
    val firstHitResult =
      hitResultList.firstOrNull { hit ->
        when (val trackable = hit.trackable!!) {
          is Plane ->
            trackable.isPoseInPolygon(hit.hitPose) &&
              PlaneRenderer.calculateDistanceToPlane(hit.hitPose, camera.pose) > 0
          is Point -> trackable.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
          is InstantPlacementPoint -> true
          // DepthPoints are only returned if Config.DepthMode is set to AUTOMATIC.
          is DepthPoint -> true
          else -> false
        }
      }

//    Log.d(TAG, "Distance to phone is: ${firstHitResult?.distance}")

    if (firstHitResult != null) {
      // Cap the number of objects created. This avoids overloading both the
      // rendering system and ARCore.
      if (wrappedAnchors.size >= 20) {
        wrappedAnchors[0].anchor.detach()
        wrappedAnchors.removeAt(0)
      }

      // Adding an Anchor tells ARCore that it should track this position in
      // space. This anchor is created on the Plane to place the 3D model
      // in the correct position relative both to the world and to the plane.
      wrappedAnchors.add(WrappedAnchor(firstHitResult.createAnchor(), firstHitResult.trackable))

      // For devices that support the Depth API, shows a dialog to suggest enabling
      // depth-based occlusion. This dialog needs to be spawned on the UI thread.
      activity.runOnUiThread { activity.view.showOcclusionDialogIfNeeded() }
    }
  }

  private fun showError(errorMessage: String) =
    activity.view.snackbarHelper.showError(activity, errorMessage)
}

/**
 * Associates an Anchor with the trackable it was attached to. This is used to be able to check
 * whether or not an Anchor originally was attached to an {@link InstantPlacementPoint}.
 */
private data class WrappedAnchor(
  val anchor: Anchor,
  val trackable: Trackable,
)
