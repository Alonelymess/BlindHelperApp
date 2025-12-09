package com.google.ar.core.examples.kotlin.helloar.path

import android.location.Location
import android.widget.TextView
import android.util.Log
import com.google.android.gms.maps.model.LatLng
import com.google.ar.core.examples.kotlin.helloar.HelloArActivity
import com.google.ar.core.examples.kotlin.helloar.VoiceAssistant
import com.google.maps.android.PolyUtil
import com.google.maps.android.SphericalUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale
//import kotlin.math.abs

class GuidanceManager(
    private val activity: HelloArActivity, 
    private val voiceAssistant: VoiceAssistant,
    private val guidanceState: GuidanceState,
    private val guidanceTextView: TextView 
) {
    companion object {
        private const val TAG = "GuidanceManager"
        private const val POLYLINE_TOLERANCE_METERS = 15.0
        private const val ARRIVAL_THRESHOLD_METERS = 5.0
    }

    private var currentDecodedPath: List<LatLng>? = null
    private var destinationName: String? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    private var lastReturnedGuidance: String? = null

    private fun bearingToClockfaceDirection(targetBearingDegrees: Double, phoneBearingDegrees: Float): String {
        // Calculate the difference between the target bearing and the phone's current bearing.
        val relativeBearing = targetBearingDegrees - phoneBearingDegrees

        // Normalize the relative bearing to be between 0 and 360 degrees.
        val normalizedRelativeBearing = (relativeBearing + 360) % 360

        // Convert the relative bearing to a clock hour.
        // A relative bearing of 0 (target is straight ahead) is 12 o'clock.
        val adjustedBearing = (normalizedRelativeBearing + 0.0) % 360.0
        var clockHourInt = (adjustedBearing / 30.0).toInt()
        Log.d(TAG, "Phone clock: ${((((phoneBearingDegrees+360)%360+15)%360)/30.0).toInt()}")
        Log.d(TAG, "Target clock: ${((((targetBearingDegrees+360)%360+15)%360)/30.0).toInt()}")
        Log.d(TAG, "Bearing: $adjustedBearing, clockHourInt: $clockHourInt")

        if (clockHourInt == 0) {
            clockHourInt = 12
        }
        else if (clockHourInt < 0){
            clockHourInt += 12
        }
        return "$clockHourInt o'clock"
    }

    fun setPolylinePath(path: List<LatLng>, destinationName: String) {
        this.currentDecodedPath = path
        this.destinationName = destinationName
        this.guidanceState.setStartGuiding(true)
        this.lastReturnedGuidance = null // Reset last returned guidance

        val initialMessage = "Navigation started to ${this.destinationName ?: "your destination"}."
        updateGuidanceDisplay(initialMessage)
        guidanceState.setInstruction(initialMessage) // Update state
        coroutineScope.launch {
            voiceAssistant.speakTextAndAWait(initialMessage)
        }
        Log.d(TAG, "Polyline path set. Points: ${path.size}. Destination: ${this.destinationName}")
    }

    fun updateGuidanceForLocation(currentUserLocation: Location, phoneBearing: Float): String? {
        Log.d(TAG, "Start guiding ${guidanceState.getStartGuiding()}, path set: ${currentDecodedPath != null}")
        if (!guidanceState.getStartGuiding() || currentDecodedPath == null) {
            Log.d(TAG, "Not guiding or path not set, skipping location update.")
            return null
        }

        val path = currentDecodedPath ?: return null
        if (path.isEmpty()) { // Explicitly handle empty path case early
            return "Continue straight"
        }

        val userLatLng = LatLng(currentUserLocation.latitude, currentUserLocation.longitude)
        Log.d(TAG, "Updating guidance for location: ${userLatLng.latitude},${userLatLng.longitude}")

        val isOnPath = PolyUtil.isLocationOnPath(userLatLng, path, true, POLYLINE_TOLERANCE_METERS)
        var guidanceText = ""

        if (isOnPath) {
            val endOfPath = path.last()
            val distanceToEnd = SphericalUtil.computeDistanceBetween(userLatLng, endOfPath)

            if (distanceToEnd < ARRIVAL_THRESHOLD_METERS) {
                guidanceText = "You are arriving at ${destinationName ?: "your destination"}. Navigation ending."
                updateGuidanceDisplay(guidanceText)
                guidanceState.setInstruction(guidanceText)
//                coroutineScope.launch {
//                     voiceAssistant.speakTextAndAWait(guidanceText)
//                     stopGuidance()
//                }
                lastReturnedGuidance = guidanceText
                return guidanceText 
            } else {
                // Find the index of the segment the user is on.
                val currentIndex = PolyUtil.locationIndexOnPath(userLatLng, path, true, POLYLINE_TOLERANCE_METERS)

                // The target for bearing calculation is the next vertex on the path.
                val targetVertex = if (currentIndex >= 0 && currentIndex < path.size - 1) {
                    path[currentIndex + 1]
                } else {
                    // Fallback to the end of the path if we're at the last segment or can't find the index.
                    endOfPath
                }

                val bearingToTarget = SphericalUtil.computeHeading(userLatLng, targetVertex)
                val clockfaceDirectionToTarget = bearingToClockfaceDirection(bearingToTarget, phoneBearing)
                
                guidanceText = if (clockfaceDirectionToTarget.contains("11") ||
                                   clockfaceDirectionToTarget.contains("12") ||
                                   clockfaceDirectionToTarget.contains("1")) {
                    // If heading is correct, provide distance to the final destination.
                    "Continue straight ${distanceToEnd.toInt()} meters"
                } else {
                    // If not, instruct to turn towards the correct direction for the current segment.
                    "Turn to $clockfaceDirectionToTarget"
                }
            }
        } else { // Off-path logic
            var closestVertexOnPath = path.first()
            var minDistance = SphericalUtil.computeDistanceBetween(userLatLng, closestVertexOnPath)

            for (i in 1 until path.size) {
                val pathVertex = path[i]
                val distanceToVertex = SphericalUtil.computeDistanceBetween(userLatLng, pathVertex)
                if (distanceToVertex < minDistance) {
                    minDistance = distanceToVertex
                    closestVertexOnPath = pathVertex
                }
            }

            val bearingToPath = SphericalUtil.computeHeading(userLatLng, closestVertexOnPath)
            val clockfaceDirectionToPath = bearingToClockfaceDirection(bearingToPath, phoneBearing)
            guidanceText = String.format(Locale.getDefault(),
                "Take %.0f meters to %s",
                minDistance, clockfaceDirectionToPath)
        }

        updateGuidanceDisplay(guidanceText)
        guidanceState.setInstruction(guidanceText) 
        
//        val coreMessageChanged = !guidanceText.startsWith(lastReturnedGuidance?.substringBefore(" Destination is about") ?: "") &&
//                                 !guidanceText.startsWith(lastReturnedGuidance?.substringBefore(" The path is approximately to your") ?: "")
//        val isOffPathWarning = guidanceText.contains("off the path")
//
//        if (lastReturnedGuidance != guidanceText && (coreMessageChanged || isOffPathWarning)) {
//            lastReturnedGuidance = guidanceText
//            return guidanceText
//        }
        return guidanceText
    }

    fun stopGuidance() {
        val wasGuiding = guidanceState.getStartGuiding()
        currentDecodedPath = null
        guidanceState.setStartGuiding(false)
        val stopMessage = "Navigation stopped."
        
        if (wasGuiding && !(guidanceState.getInstruction()?.contains("arriving") == true)) {
            guidanceState.setInstruction(stopMessage) 
            updateGuidanceDisplay(stopMessage)
            Log.d(TAG, "Navigation stopped and path cleared.")
            coroutineScope.launch {
                 voiceAssistant.speakTextAndAWait(stopMessage)
            }
        } else {
             Log.d(TAG, "Guidance already stopped or arrival announced. Last instruction: ${guidanceState.getInstruction()}")
        }
        destinationName = null 
        lastReturnedGuidance = null
    }

    private fun updateGuidanceDisplay(text: String) {
        activity.runOnUiThread {
            guidanceTextView.text = text
        }
    }
}

fun LatLngPoint.toLatLng() = com.google.android.gms.maps.model.LatLng(this.latitude, this.longitude)
fun Location.toLatLng() = LatLng(this.latitude, this.longitude)
