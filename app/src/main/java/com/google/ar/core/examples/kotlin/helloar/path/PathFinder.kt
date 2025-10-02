package com.google.ar.core.examples.kotlin.helloar.path

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.net.PlacesClient
import com.google.android.libraries.places.api.net.SearchByTextRequest
import com.google.ar.core.examples.kotlin.helloar.BuildConfig
import com.google.ar.core.examples.kotlin.helloar.HelloArActivity
import com.google.gson.Gson

import com.google.android.gms.maps.model.LatLng // Added import
import com.google.maps.android.PolyUtil // Added import

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException


// A data class to hold the complete result
data class DirectionsResult(
    val destination: Place,
    val steps: List<RouteLegStep>, // Kept for potential high-level info
    val overviewPolyline: String,
    val decodedPath: List<LatLng> // Added decoded path
)

// The listener that will return the complete result
interface PathFinderListener {
    fun onPathFound(result: DirectionsResult)
    fun onPathError(message: String)
}

class PathFinder(
    private val context: Context,
    private val fusedLocationClient: FusedLocationProviderClient,
    private val placesClient: PlacesClient,
) {
    companion object {
        private const val TAG = "PathFinder"
    }

    private val httpClient = OkHttpClient()
    private val gson = Gson()

    /**
     * The main public method. Searches for the nearest place matching the keyword
     * and then finds a path to it.
     */
    fun findAndPreparePath(searchKeyword: String, listener: PathFinderListener) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(context, "Location permission is needed to find nearby places.", Toast.LENGTH_SHORT).show()
            listener.onPathError("Location permission not granted.")
            return
        }

        fusedLocationClient.lastLocation.addOnSuccessListener { originLocation: Location? ->
            if (originLocation == null) {
                Toast.makeText(context, "Could not get current location for search.", Toast.LENGTH_SHORT).show()
                listener.onPathError("Could not get current location.")
                return@addOnSuccessListener
            }

            val placeFields = listOf(Place.Field.ID, Place.Field.NAME, Place.Field.LAT_LNG, Place.Field.ADDRESS)
            val request = SearchByTextRequest.builder(searchKeyword, placeFields)
                .setMaxResultCount(10) 
                .build()

            placesClient.searchByText(request)
                .addOnSuccessListener { response ->
                    if (response.places.isEmpty()) {
                        Toast.makeText(context, "No '$searchKeyword' found nearby.", Toast.LENGTH_LONG).show()
                        listener.onPathError("No '$searchKeyword' found nearby.")
                        return@addOnSuccessListener
                    }

                    Log.d(TAG, "Found ${response.places.size} places.")
                    response.places.forEach { place ->
                        Log.d(TAG, "Place: ${place.name}, ${place.address}, ${place.id}")
                    }

                    val sortedPlaces = response.places.sortedBy { place ->
                        val placeLocation = Location("").apply {
                            latitude = place.latLng!!.latitude
                            longitude = place.latLng!!.longitude
                        }
                        originLocation.distanceTo(placeLocation)
                    }

                    val nearestPlace = sortedPlaces.first()

                    if (nearestPlace.latLng != null && nearestPlace.id != null) {
                        fetchDirections(originLocation, nearestPlace, listener)
                    } else {
                        val errorMsg = "Nearest place '$searchKeyword' found has no LatLng or ID."
                        Log.e(TAG, errorMsg)
                        Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
                        listener.onPathError(errorMsg)
                    }
                }
                .addOnFailureListener { exception ->
                    Log.e(TAG, "Places search failed", exception)
                    Toast.makeText(context, "Error finding places.", Toast.LENGTH_SHORT).show()
                    listener.onPathError("Error finding places: ${exception.message}")
                }
        }
    }


    private fun fetchDirections(origin: Location, destinationPlace: Place, listener: PathFinderListener) {
        val apiKey = BuildConfig.MAPS_API_KEY
        val url = "https://routes.googleapis.com/directions/v2:computeRoutes"

        val requestJson = """
        {
          "origin": {
            "location": {
              "latLng": {
                "latitude": ${origin.latitude},
                "longitude": ${origin.longitude}
              }
            }
          },
          "destination": {
            "placeId": "${destinationPlace.id}"
          },
          "travelMode": "WALK",
          "routingPreference": "ROUTING_PREFERENCE_UNSPECIFIED",
          "computeAlternativeRoutes": false,
          "languageCode": "en-US",
          "units": "METRIC"
        }
        """.trimIndent()

        val requestBody = requestJson.toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .addHeader("Content-Type", "application/json")
            .addHeader("X-Goog-Api-Key", apiKey)
            .addHeader("X-Goog-FieldMask", "routes.legs.steps.navigationInstruction,routes.legs.steps.startLocation,routes.legs.steps.endLocation,routes.polyline.encodedPolyline") // Added endLocation for steps
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Directions API call failed", e)
                (context as? HelloArActivity)?.runOnUiThread {
                    Toast.makeText(context, "Error fetching directions: ${e.message}", Toast.LENGTH_SHORT).show()
                }
                listener.onPathError("Error fetching directions: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()
                if (!response.isSuccessful || responseBody == null) {
                    Log.e(TAG, "Directions API call unsuccessful or empty response: $responseBody, Code: ${response.code}")
                     (context as? HelloArActivity)?.runOnUiThread {
                        Toast.makeText(context, "Failed to get directions. Code: ${response.code}", Toast.LENGTH_SHORT).show()
                    }
                    listener.onPathError("Failed to get directions. Code: ${response.code}")
                    return
                }

                Log.d(TAG, "Directions API call successful: $responseBody")

                try {
                    val directionsResponse = gson.fromJson(responseBody, RoutesApiResponse::class.java)
                    Log.d(TAG, "Parsed response: $directionsResponse")

                    val route = directionsResponse.routes.firstOrNull()
                    val steps = route?.legs?.firstOrNull()?.steps ?: emptyList()
                    val encodedPolylineString = route?.polyline?.encodedPolyline

                    if (route != null && !encodedPolylineString.isNullOrEmpty()) {
                        val decodedPath = PolyUtil.decode(encodedPolylineString)
                        if (decodedPath.isNotEmpty()){
                            Log.d(TAG, "Polyline decoded successfully. Points: ${decodedPath.size}")
                            val result = DirectionsResult(destinationPlace, steps, encodedPolylineString, decodedPath)
                            listener.onPathFound(result)
                        } else {
                            Log.e(TAG, "Decoded path is empty.")
                            listener.onPathError("No route path found after decoding.")
                        }
                    } else {
                        Log.e(TAG, "No route or polyline found in response.")
                        listener.onPathError("No route found in response.")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing directions response", e)
                    (context as? HelloArActivity)?.runOnUiThread {
                        Toast.makeText(context, "Error parsing directions: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                    listener.onPathError("Error parsing directions: ${e.message}")
                }
            }
        })
    }
}
