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
import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.Paint.ANTI_ALIAS_FLAG
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.media.AudioManager
import android.media.ToneGenerator
import android.opengl.GLES30
import android.opengl.Matrix
import android.os.Environment
import android.util.Base64
import android.util.Log
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.Dimension
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.ar.core.Anchor
import com.google.ar.core.Frame
import com.google.ar.core.LightEstimate
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.Trackable
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.examples.java.common.helpers.DisplayRotationHelper
import com.google.ar.core.examples.java.common.helpers.TrackingStateHelper
import com.google.ar.core.examples.java.common.samplerender.Framebuffer
import com.google.ar.core.examples.java.common.samplerender.Mesh
import com.google.ar.core.examples.java.common.samplerender.SampleRender
import com.google.ar.core.examples.java.common.samplerender.Shader
import com.google.ar.core.examples.java.common.samplerender.Texture
import com.google.ar.core.examples.java.common.samplerender.VertexBuffer
import com.google.ar.core.examples.java.common.samplerender.arcore.BackgroundRenderer
import com.google.ar.core.examples.java.common.samplerender.arcore.SpecularCubemapFilter
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.NotYetAvailableException
import java.nio.ByteBuffer
import okhttp3.*
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
import com.google.ar.core.examples.kotlin.helloar.path.GuidanceState
import java.nio.ShortBuffer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

import android.graphics.Canvas
import android.graphics.Color
import com.google.ar.core.examples.kotlin.helloar.utils.GeminiVlmService
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine


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
    private val geminiService: GeminiVlmService,
    val voiceAssistant: VoiceAssistant,
    val guidanceState: GuidanceState,
) :
    SampleRender.Renderer, DefaultLifecycleObserver {
    companion object {
        val TAG = "HelloArRenderer"
        private val Z_NEAR = 0.1f
        private val Z_FAR = 100f
        val APPROXIMATE_DISTANCE_METERS = 2.0f
        val CUBEMAP_RESOLUTION = 16
        val CUBEMAP_NUMBER_OF_IMPORTANCE_SAMPLES = 32
        private const val BEEP_VOLUME = 100 // Volume for ToneGenerator (0-100)
    }

    lateinit var render: SampleRender
    lateinit var backgroundRenderer: BackgroundRenderer
    lateinit var virtualSceneFramebuffer: Framebuffer
    var hasSetTextureNames = false

    lateinit var pointCloudVertexBuffer: VertexBuffer
    lateinit var pointCloudMesh: Mesh
    lateinit var pointCloudShader: Shader
    var lastPointCloudTimestamp: Long = 0

    lateinit var virtualObjectMesh: Mesh
    lateinit var virtualObjectShader: Shader
    lateinit var virtualObjectAlbedoTexture: Texture
    lateinit var virtualObjectAlbedoInstantPlacementTexture: Texture

    private val wrappedAnchors = mutableListOf<WrappedAnchor>()

    lateinit var dfgTexture: Texture
    lateinit var cubemapFilter: SpecularCubemapFilter

    val modelMatrix = FloatArray(16)
    val viewMatrix = FloatArray(16)
    val projectionMatrix = FloatArray(16)
    val modelViewMatrix = FloatArray(16)
    val modelViewProjectionMatrix = FloatArray(16)
    val sphericalHarmonicsCoefficients = FloatArray(9 * 3)
    val viewInverseMatrix = FloatArray(16)
    val worldLightDirection = floatArrayOf(0.0f, 0.0f, 0.0f, 0.0f)
    val viewLightDirection = FloatArray(4)

    val session
        get() = activity.arCoreSessionHelper.session

    val displayRotationHelper = DisplayRotationHelper(activity)
    val trackingStateHelper = TrackingStateHelper(activity)

    private lateinit var distanceTextView: DistanceTextView
    private lateinit var mapsTextView: TextView
    private lateinit var bevImageView: ImageView
    private lateinit var bevTextView: TextView
    private lateinit var compassImageView: ImageView

    private var currentBearing: Float = 0f
    private var frameCounter = 0
    private val frameSkipInterval = 10 // For danger detection
    private var depthCount = 0

    private val scope = CoroutineScope(Dispatchers.Main)
    private val isProcessing = AtomicBoolean(false)
    private val showBEV = AtomicBoolean(false)
    private var DEPTH_THRESHOLD_MM = 2000

    private var toneGenerator: ToneGenerator? = null

    private val trustAllCerts = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private val sslContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(trustAllCerts), java.security.SecureRandom())
    }

    private val client = OkHttpClient.Builder()
        .sslSocketFactory(sslContext.socketFactory, trustAllCerts)
        .hostnameVerifier { _, _ -> true }
        .build()
    private val url = BuildConfig.MICRO_GUIDANCE_API

    interface UploadCallback {
        fun onSuccess(response: String)
        fun onError(error: String)
    }

    fun setDistanceTextView(distanceTextView: DistanceTextView) {
        this.distanceTextView = distanceTextView
    }

    fun setMapsTextView(mapsTextView: TextView){
        this.mapsTextView = mapsTextView
    }

    fun setBEVImageView(bevImageView: ImageView) {
        this.bevImageView = bevImageView
    }

    fun setBEVTextView(bevTextView: TextView) {
        this.bevTextView = bevTextView
    }

    fun setCompassImageView(compassImageView: ImageView) {
        this.compassImageView = compassImageView
    }

    fun updateCompassBearing(bearing: Float) {
        currentBearing = bearing
        if (::compassImageView.isInitialized) {
            activity.runOnUiThread {
                compassImageView.rotation = -bearing
            }
        }
    }

    fun requestDrawBEV(){
        showBEV.set(true)
    }

    private fun startBeeping() {
        try {
            if (toneGenerator?.startTone(ToneGenerator.TONE_CDMA_NETWORK_CALLWAITING, 2000) == false) {
                Log.w(TAG, "ToneGenerator failed to start tone, already playing or error.")
            }
        } catch (e: RuntimeException) {
            Log.e(TAG, "RuntimeException while starting tone", e)
        }
    }

    private fun stopBeeping() {
        try {
            toneGenerator?.stopTone()
        } catch (e: RuntimeException) {
            Log.e(TAG, "RuntimeException while stopping tone", e)
        }
    }

    override fun onResume(owner: LifecycleOwner) {
        displayRotationHelper.onResume()
        hasSetTextureNames = false
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, BEEP_VOLUME)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Failed to initialize ToneGenerator", e)
            toneGenerator = null
        }
    }

    override fun onPause(owner: LifecycleOwner) {
        displayRotationHelper.onPause()
        stopBeeping()
        toneGenerator?.release()
        toneGenerator = null
    }

    override fun onSurfaceCreated(render: SampleRender) {
        this.render = render
        try {
            backgroundRenderer = BackgroundRenderer(render)
            virtualSceneFramebuffer = Framebuffer(render, /*width=*/ 1, /*height=*/ 1)
            cubemapFilter = SpecularCubemapFilter(render, CUBEMAP_RESOLUTION, CUBEMAP_NUMBER_OF_IMPORTANCE_SAMPLES)
            dfgTexture = Texture(render, Texture.Target.TEXTURE_2D, Texture.WrapMode.CLAMP_TO_EDGE, false)
            val dfgResolution = 64
            val dfgChannels = 2
            val halfFloatSize = 2
            val buffer: ByteBuffer = ByteBuffer.allocateDirect(dfgResolution * dfgResolution * dfgChannels * halfFloatSize)
            activity.assets.open("models/dfg.raw").use { it.read(buffer.array()) }
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, dfgTexture.textureId)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RG16F, dfgResolution, dfgResolution, 0, GLES30.GL_RG, GLES30.GL_HALF_FLOAT, buffer)

            pointCloudShader = Shader.createFromAssets(render, "shaders/point_cloud.vert", "shaders/point_cloud.frag", null)
                .setVec4("u_Color", floatArrayOf(31.0f / 255.0f, 188.0f / 255.0f, 210.0f / 255.0f, 1.0f))
                .setFloat("u_PointSize", 5.0f)
            pointCloudVertexBuffer = VertexBuffer(render, 4, null)
            pointCloudMesh = Mesh(render, Mesh.PrimitiveMode.POINTS, null, arrayOf(pointCloudVertexBuffer))

            virtualObjectAlbedoTexture = Texture.createFromAsset(render, "models/pawn_albedo.png", Texture.WrapMode.CLAMP_TO_EDGE, Texture.ColorFormat.SRGB)
            virtualObjectAlbedoInstantPlacementTexture = Texture.createFromAsset(render, "models/pawn_albedo_instant_placement.png", Texture.WrapMode.CLAMP_TO_EDGE, Texture.ColorFormat.SRGB)
            val virtualObjectPbrTexture = Texture.createFromAsset(render, "models/pawn_roughness_metallic_ao.png", Texture.WrapMode.CLAMP_TO_EDGE, Texture.ColorFormat.LINEAR)
            virtualObjectMesh = Mesh.createFromAsset(render, "models/pawn.obj")
            virtualObjectShader = Shader.createFromAssets(render, "shaders/environmental_hdr.vert", "shaders/environmental_hdr.frag", mapOf("NUMBER_OF_MIPMAP_LEVELS" to cubemapFilter.numberOfMipmapLevels.toString()))
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

    private fun yuv420888ToRgbBitmap(image: Image): Bitmap {
        val width = image.width
        val height = image.height
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer
        val yBytes = ByteArray(yBuffer.remaining())
        val uBytes = ByteArray(uBuffer.remaining())
        val vBytes = ByteArray(vBuffer.remaining())
        yBuffer.get(yBytes)
        uBuffer.get(uBytes)
        vBuffer.get(vBytes)

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
        val combinedBytesEfficient = concatenateByteArrays(yBytes, vBytes, uBytes) // NV21 format expects Y, V, U
        val yuvImage = YuvImage(combinedBytesEfficient, ImageFormat.NV21, image.width, image.height, null)
        val outputStream = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 100, outputStream)
        val rgbBytes = outputStream.toByteArray()
        return BitmapFactory.decodeByteArray(rgbBytes, 0, rgbBytes.size)
    }

    private fun drawGuidanceLine(bitmap: Bitmap): Bitmap {
        val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutableBitmap)
        val paint = Paint().apply {
            color = Color.RED
            strokeWidth = 5f
        }
        val centerX = mutableBitmap.width / 2f
        canvas.drawLine(centerX, 0f, centerX, mutableBitmap.height.toFloat(), paint)
        return mutableBitmap
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

    fun captureAndSaveMap(googleMap: GoogleMap?, filePath: String) {
        if (googleMap == null) {
            Log.e(TAG, "Cannot capture snapshot, GoogleMap is not ready.")
            return
        }
        googleMap.snapshot { bitmap ->
            if (bitmap == null) {
                Log.e(TAG, "Snapshot failed, bitmap is null.")
                activity.runOnUiThread{
                    Toast.makeText(activity, "Failed to capture map.", Toast.LENGTH_SHORT).show()
                }
                return@snapshot
            }
            saveImage(bitmap, filePath)
        }
    }

    private suspend fun takeMapSnapshot(googleMap: GoogleMap?): Bitmap? =
        suspendCoroutine { continuation ->
        if (googleMap == null) {
            Log.w(TAG, "Cannot capture map snapshot, GoogleMap is null.")
            continuation.resume(null)
            return@suspendCoroutine
        }
        googleMap.snapshot { bitmap ->
            continuation.resume(bitmap)
        }
    }

    private fun processAndSpeakGuidanceWithLocalServer(
        camera: Bitmap,
        depthData: ByteArray,
        instruction: String,
        bearing: Float) {

        val compassDirection = bearingToDirection(bearing)

        val filePath = Environment.getExternalStorageDirectory().path + "/DCIM/image.png"
        saveImage(camera, filePath)
        val base64Image = encodeImageToBase64(filePath)

        data class ImageRequest(
            val base64_depth: String,
            val overall_direction: String = instruction,
            val base64_image: String,
            val compass_direction: String = compassDirection,
            val frame: Int = depthCount
        )

        val base64Depth = Base64.encodeToString(depthData, Base64.NO_WRAP)
        val depthRequest = ImageRequest(
            base64_depth = base64Depth,
            base64_image = base64Image
        )
        val json = Gson().toJson(depthRequest)
        val requestBody = json.toRequestBody("application/json".toMediaTypeOrNull())

        val request = Request.Builder()
            .url(url + "infer")
            .addHeader("skip_zrok_interstitial", "true")
            .post(requestBody)
            .build()

        try {
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
            Log.d(TAG, "Response from api $responseBody")
            activity.runOnUiThread {
                distanceTextView.text = "Response from api $responseBody"
            }
            val results = responseBody?.let { Json.parseToJsonElement(it).jsonArray }

            for (element in results!!) {
                val guidance = element.jsonObject["response"]?.jsonPrimitive?.toString()
                val stringGuidance = guidance.toString().replace("\"", "").replace("\\", "")
                Log.d(TAG, stringGuidance)
                activity.runOnUiThread {
                    distanceTextView.text = stringGuidance
                }
                if (!stringGuidance.contains("No significant change")) {
                    voiceAssistant.vibrate()
                    voiceAssistant.speakTextAndWait(
                        stringGuidance,
                        object : VoiceAssistant.SpeechCompletionListener {
                            override fun onSpeechFinished() {
                                Log.d(TAG, "Speech finished. Starting 5-second wait phase.")
                                activity.runOnUiThread {
                                    distanceTextView.text = "Speech finished. Starting 5-second wait phase."
                                }
                                val startTime = System.currentTimeMillis()
                                while (System.currentTimeMillis() - startTime < 5000) { /* busy wait */ }
                                Log.d(TAG, "Wait phase finished. Ready for next guidance.")
                                activity.runOnUiThread {
                                    distanceTextView.text = "Speech finished. Ready for next guidance."
                                }
                            }
                        }
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending depth image", e)
            activity.runOnUiThread {
                distanceTextView.text = "Error sending depth image $e"
            }
        } finally {
            if (isProcessing.getAndSet(false)) { stopBeeping() }
        }
    }

    private fun processAndSpeakGuidanceWithGemini(
        camera: Bitmap, 
        depthInfo: ShortBuffer?,
        depthWidth: Int, 
        depthHeight: Int, 
        instruction: String, 
        bearing: Float
    ){
        scope.launch {
            var guidance: String? = null
//            val mapSnapshot = takeMapSnapshot(activity.mMap)
            val mapSnapshot = null
            try {
                Log.d(TAG, "Start VLM image request to Gemini")
                val direction = ""
//                if (activity.currentPathGuidance != null)
//                {
//                    guidance =  activity.currentPathGuidance + '.' + geminiService.generateGuidance(camera, depthInfo, depthWidth, depthHeight, instruction, direction)
//                }
//                else{
                guidance = geminiService.generateGuidance(camera, mapSnapshot, depthInfo, depthWidth, depthHeight, instruction, direction)
//                }

                guidance?.contains("Error")?.let {
                    if (!it){
                        activity.runOnUiThread {
                            distanceTextView.text = guidance
                        }
                        voiceAssistant.vibrate()
                        voiceAssistant.speakTextAndAWait(guidance)
                        Log.d(TAG, "Start wait after speech for Gemini image")
                        delay(2000L)
                        Log.d(TAG, "End wait after speech for Gemini image")
                    } else {
                        activity.runOnUiThread {
                            distanceTextView.text = guidance
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in VLM image request to Gemini", e)
                activity.runOnUiThread {
                    distanceTextView.text = "Error VLM image request $e"
                }
            } finally {
                if (isProcessing.getAndSet(false)) { stopBeeping() }
            }
        }
    }

    private fun processAndSpeakGuidanceWithGeminiCurl(
        camera: Bitmap, 
        depthInfo: ShortBuffer, 
        depthWidth: Int, 
        depthHeight: Int, 
        instruction: String, 
        bearing: Float
    ){
        scope.launch(Dispatchers.IO) { 
            try {
                Log.d(TAG, "--- STARTING NEW VLM CURL REQUEST ---")
//                val apiKey = BuildConfig.GEMINI_API_KEY
                val apiKey = ""
                val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"
                val filePath = Environment.getExternalStorageDirectory().path + "/DCIM/image.png"
                saveImage(camera, filePath)
                val cameraBase64 = encodeImageToBase64(filePath)

                val direction = bearingToDirection(bearing)
                val textPart = JSONObject().put("text", geminiService.getSystemPrompt(instruction, direction))
                val cameraPart = JSONObject().put("inline_data", JSONObject().put("mime_type", "image/png").put("data", cameraBase64))
                val partsArray = JSONArray().put(textPart).put(cameraPart)
                val contentsObject = JSONObject().put("parts", partsArray)
                val payload = JSONObject().put("contents", JSONArray().put(contentsObject))
                val requestBody = payload.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder().url(url).post(requestBody).header("Content-Type", "application/json").build()

                val response = client.newCall(request).execute()
                val responseBodyString = response.body?.string()

                if (responseBodyString == null) {
                    Log.e(TAG, "Response body was null for CURL request."); return@launch
                }
                Log.d(TAG, "Response from Gemini CURL: $responseBodyString")
                val jsonRootObject: JsonObject? = try { Json.parseToJsonElement(responseBodyString).jsonObject } catch (e: Exception) { 
                    Log.e(TAG, "Failed to parse JSON from CURL: ${e.message}"); null 
                }

                if (jsonRootObject == null) { Log.e(TAG, "Parsed JSON root object is null for CURL."); return@launch }
                val candidatesArray: JsonArray? = jsonRootObject["candidates"]?.jsonArray
                if (candidatesArray == null) { Log.w(TAG, "No 'candidates' array in CURL response."); return@launch }

                for (candidate in candidatesArray) {
                    val parts = candidate.jsonObject["content"]?.jsonObject?.get("parts")?.jsonArray
                    if (parts != null) {
                        for (part in parts) {
                            val newGuidance = part.jsonObject["text"]?.jsonPrimitive?.content
                            if (newGuidance != null) {
                                val sanitizedGuidance = newGuidance.trim()
                                Log.d(TAG, "Sanitized Guidance from CURL: $sanitizedGuidance")
                                activity.runOnUiThread {
                                    distanceTextView.text = "Response from Gemini CURL: $sanitizedGuidance"
                                }
                                if (sanitizedGuidance.isNotBlank() && !sanitizedGuidance.contains("No significant change")) {
                                    voiceAssistant.vibrate()
                                    voiceAssistant.speakTextAndWait(sanitizedGuidance, object : VoiceAssistant.SpeechCompletionListener {
                                        override fun onSpeechFinished() {
                                            Log.d(TAG, "CURL Speech finished. Starting 5-second wait phase.")
                                            activity.runOnUiThread {
                                                distanceTextView.text = "CURL Speech finished. Starting 5-second wait phase."
                                            }
                                            val startTime = System.currentTimeMillis()
                                            while (System.currentTimeMillis() - startTime < 5000) { /* busy wait */}
                                            Log.d(TAG, "CURL Wait phase finished. Ready for next guidance.")
                                            activity.runOnUiThread {
                                                distanceTextView.text = "CURL Speech finished. Ready for next guidance."
                                            }
                                        }
                                    })
                                } 
                                break 
                            }
                        }
                        break 
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in VLM CURL processing coroutine", e)
                activity.runOnUiThread {
                    distanceTextView.text = "Error in VLM CURL processing: $e"
                }
            } finally {
                if (isProcessing.getAndSet(false)) { stopBeeping() }
            }
        }
    }

    private fun processAndSpeakGuidanceWithGeminiStream(
        camera: Bitmap, 
        depthInfo: ShortBuffer, 
        depthWidth: Int, 
        depthHeight: Int, 
        instruction: String, 
        bearing: Float
    ){
        scope.launch {
            try {
                Log.d(TAG, "Start VLM stream request to Gemini")
                val direction = bearingToDirection(bearing)
                val guidanceFlow = geminiService.generateGuidanceStream(camera, depthInfo, depthWidth, depthHeight, instruction, direction)

                if (guidanceFlow != null) {
                    voiceAssistant.vibrate()
                    voiceAssistant.speakStreamAndAwaitAll(guidanceFlow, scope) 
                    Log.d(TAG, "Start wait after stream for Gemini")
                    delay(5000L) 
                    Log.d(TAG, "End wait after stream for Gemini")
                } else {
                     activity.runOnUiThread {
                        distanceTextView.text = "Error: Could not start guidance stream."
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in VLM stream request to Gemini", e)
                activity.runOnUiThread {
                    distanceTextView.text = "Error VLM stream request $e"
                }
            } finally {
                if (isProcessing.getAndSet(false)) { stopBeeping() }
            }
        }
    }

    private fun bearingToDirection(bearing: Float): String {
        val directions = arrayOf("North", "North-East", "East", "South-East", "South", "South-West", "West", "North-West", "North")
        return directions[Math.round(bearing / 45f) % 8] 
    }

    private fun haveARDepth(frame: Frame){
        try {
            frame.acquireDepthImage16Bits().use { depthImage ->
                if (showBEV.get()){
                    processAndVisualizeDepth(frame, activity)
                    showBEV.set(false)
                }

                if (frameCounter % frameSkipInterval == 0 && !isProcessing.get() && activity.guidanceState.getStartGuiding()) {
                    try {
                        depthCount += 1
                        val depthBuffer = depthImage.planes[0].buffer.asReadOnlyBuffer()
                        val depthShortBuffer: ShortBuffer = depthBuffer.asShortBuffer().asReadOnlyBuffer()
                        depthBuffer.rewind(); depthShortBuffer.rewind()

                        val tempDepthArray = ShortArray(depthShortBuffer.remaining())
                        depthShortBuffer.get(tempDepthArray); depthShortBuffer.rewind()

                        val depthAsByteArray = ByteArray(tempDepthArray.size * 2)
                        for (i in tempDepthArray.indices) {
                            val depthIntValue = tempDepthArray[i].toInt() and 0xFFFF
                            depthAsByteArray[i * 2] = (depthIntValue shr 8).toByte()
                            depthAsByteArray[i * 2 + 1] = (depthIntValue and 0xFF).toByte()
                        }

                        var danger = false; var maxDepth = 0
                        for (depthValueRaw in tempDepthArray) {
                            val depthValueMm = depthValueRaw.toInt() and 0xFFFF
                            if (depthValueMm > maxDepth) { maxDepth = depthValueMm }
                            if (depthValueMm < DEPTH_THRESHOLD_MM && depthValueMm > 0) {
                                danger = true; break
                            }
                        }
                        Log.d(TAG, "Max depth: $maxDepth mm. Danger: $danger.")
                        activity.runOnUiThread {
                            distanceTextView.text = "MaxD: $maxDepth, Dgr: $danger."
                        }

                        if (danger) {
                            if (isProcessing.compareAndSet(false, true)) {
                                Log.d(TAG, "Processing started for single image.")
                                startBeeping()
                                activity.runOnUiThread { distanceTextView.text = "Obstacle! Processing..." }

                                val overallDirection = guidanceState.getInstruction()
                                activity.runOnUiThread { mapsTextView.text = overallDirection }

                                frame.acquireCameraImage().use { image ->
                                    var currentTriggerFrameBitmap = yuv420888ToRgbBitmap(image)
//                                    currentTriggerFrameBitmap = drawGuidanceLine(currentTriggerFrameBitmap)
                                    depthShortBuffer.rewind()

                                    Log.d(TAG, "Dispatching IMAGE guidance request.")
                                    // PICK ONE of these to be your primary image processor
                                    processAndSpeakGuidanceWithGemini(
                                        // processAndSpeakGuidanceWithLocalServer(
                                        // processAndSpeakGuidanceWithGeminiCurl(
                                        // processAndSpeakGuidanceWithGeminiStream(
                                        currentTriggerFrameBitmap,
                                        null, depthImage.width, depthImage.height,
                                        overallDirection, currentBearing
                                    )
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error during depth processing or guidance call", e)
                        if(isProcessing.getAndSet(false)){ stopBeeping() }
                    }
                }
                backgroundRenderer.updateCameraDepthTexture(depthImage)
            }
        } catch (e: NotYetAvailableException) {
            // Normal
        } catch (e: Exception) {
            Log.e(TAG, "General error in depth handling or rendering loop", e)
            if(isProcessing.getAndSet(false)){ stopBeeping() }
        }
    }

    private fun noARDepth(frame:Frame) {
        if (frameCounter % frameSkipInterval == 0 && !isProcessing.get() && activity.guidanceState.getStartGuiding())
        {
            try {
                Log.d(TAG, "isProcessing: ${isProcessing.get()}")
                if (isProcessing.compareAndSet(false, true)){
                    Log.d(TAG, "Processing started for single image.")
                    startBeeping()
                    activity.runOnUiThread { distanceTextView.text = "Obstacle! Processing..." }

                    val overallDirection = guidanceState.getInstruction()
                    activity.runOnUiThread { mapsTextView.text = overallDirection }

                    frame.acquireCameraImage().use { image ->
                        var currentTriggerFrameBitmap = yuv420888ToRgbBitmap(image)
                        currentTriggerFrameBitmap = drawGuidanceLine(currentTriggerFrameBitmap)

                        Log.d(TAG, "Dispatching IMAGE guidance request.")
                        // PICK ONE of these to be your primary image processor
                        processAndSpeakGuidanceWithGemini(
                            // processAndSpeakGuidanceWithLocalServer(
                            // processAndSpeakGuidanceWithGeminiCurl(
                            // processAndSpeakGuidanceWithGeminiStream(
                            currentTriggerFrameBitmap,
                            null, 0, 0,
                            overallDirection, currentBearing
                        )
                    }
//                    Thread.sleep(2000)
                }
            }
            catch(e: Exception) {
                Log.e(TAG, "Error during depth processing or guidance call", e)
                if(isProcessing.getAndSet(false)){ stopBeeping() }
            }
        }
    }

    @SuppressLint("DefaultLocale")
    override fun onDrawFrame(render: SampleRender) {
        val session = session ?: return
        try {
            if (!hasSetTextureNames) {
                session.setCameraTextureNames(intArrayOf(backgroundRenderer.cameraColorTexture.textureId))
                hasSetTextureNames = true
            }

            displayRotationHelper.updateSessionIfNeeded(session)
            val frame = try { session.update() } catch (e: CameraNotAvailableException) {
                Log.e(TAG, "Camera not available during onDrawFrame", e); showError("Camera not available. Try restarting the app."); return
            }
            frameCounter++

            val camera = frame.camera
            try {
                backgroundRenderer.setUseDepthVisualization(render, activity.depthSettings.depthColorVisualizationEnabled())
                backgroundRenderer.setUseOcclusion(render, activity.depthSettings.useDepthForOcclusion())
            } catch (e: IOException) {
                Log.e(TAG, "Failed to read a required asset file", e); showError("Failed to read a required asset file: $e"); return
            }
            backgroundRenderer.updateDisplayGeometry(frame)

            val shouldGetDepthImage = activity.depthSettings.useDepthForOcclusion() || activity.depthSettings.depthColorVisualizationEnabled()
//            Log.d(TAG, "Should get depth image: $shouldGetDepthImage")

            if (camera.trackingState == TrackingState.TRACKING && shouldGetDepthImage) {
                haveARDepth(frame)
            }
            else{
                noARDepth(frame)
            }

            trackingStateHelper.updateKeepScreenOnFlag(camera.trackingState)
            val message: String? = when {
                camera.trackingState == TrackingState.PAUSED && camera.trackingFailureReason == TrackingFailureReason.NONE -> activity.getString(R.string.searching_planes)
                camera.trackingState == TrackingState.PAUSED -> TrackingStateHelper.getTrackingFailureReasonString(camera)
                session.hasTrackingPlane() && wrappedAnchors.isEmpty() -> activity.getString(R.string.waiting_taps)
                session.hasTrackingPlane() && wrappedAnchors.isNotEmpty() -> null
                else -> activity.getString(R.string.searching_planes)
            }
            if (message == null) activity.view.snackbarHelper.hide(activity) else activity.view.snackbarHelper.showMessage(activity, message)

            if (frame.timestamp != 0L) backgroundRenderer.drawBackground(render)
            if (camera.trackingState == TrackingState.PAUSED) return

            camera.getProjectionMatrix(projectionMatrix, 0, Z_NEAR, Z_FAR)
            camera.getViewMatrix(viewMatrix, 0) 
            updateLightEstimation(frame.lightEstimate, viewMatrix)

        } catch (t: Throwable) { 
            Log.e(TAG, "Exception onDrawFrame", t)
            if(isProcessing.getAndSet(false)){ stopBeeping() }
        }
    }

    fun processAndVisualizeDepth(frame: Frame, context: Context) {
        scope.launch {
            try {
                frame.acquireDepthImage16Bits().use { depthImage ->
                    frame.acquireCameraImage().use { image ->
                        val intrinsics = frame.camera.imageIntrinsics
                        val processor = DepthProcessor(intrinsics)
                        processor.processImageAndCreatePointCloud(depthImage, image.width, image.height)
                        processor.generateBev()
                        val bevBitmap = processor.getBevBitmap()
                        val analysis: DepthProcessor.DepthRangeAnalysis? = processor.getEffectiveRangeAnalysis()
                        activity.runOnUiThread {
                             bevImageView.setImageBitmap(bevBitmap)
                            analysis?.let { bevTextView.text = "Min: ${it.minMeters}m, Max: ${it.maxMeters}m, Mean: ${it.meanMeters}m" }
                        }
                        processor.saveBevToFile(context, "my_bev_image.png")?.let {
                            Log.i(TAG, "Successfully saved BEV to: $it")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not process depth frame for BEV: $e")
            }
        }
    }

    private fun Session.hasTrackingPlane() = getAllTrackables(Plane::class.java).any { it.trackingState == TrackingState.TRACKING }

    private fun updateLightEstimation(lightEstimate: LightEstimate, viewMatrix: FloatArray) {
        if (lightEstimate.state != LightEstimate.State.VALID) {
            virtualObjectShader.setBool("u_LightEstimateIsValid", false); return
        }
        virtualObjectShader.setBool("u_LightEstimateIsValid", true)
        Matrix.invertM(viewInverseMatrix, 0, viewMatrix, 0)
        virtualObjectShader.setMat4("u_ViewInverse", viewInverseMatrix)
        worldLightDirection[0] = lightEstimate.environmentalHdrMainLightDirection[0]
        worldLightDirection[1] = lightEstimate.environmentalHdrMainLightDirection[1]
        worldLightDirection[2] = lightEstimate.environmentalHdrMainLightDirection[2]
        Matrix.multiplyMV(viewLightDirection, 0, viewMatrix, 0, worldLightDirection, 0)
        virtualObjectShader.setVec4("u_ViewLightDirection", viewLightDirection)
        virtualObjectShader.setVec3("u_LightIntensity", lightEstimate.environmentalHdrMainLightIntensity)
        cubemapFilter.update(lightEstimate.acquireEnvironmentalHdrCubeMap())
    }

    private fun showError(errorMessage: String) = activity.view.snackbarHelper.showError(activity, errorMessage)
}

private data class WrappedAnchor(val anchor: Anchor, val trackable: Trackable)
