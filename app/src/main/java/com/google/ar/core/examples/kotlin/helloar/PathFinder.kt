package com.google.ar.core.examples.kotlin.helloar

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
import com.google.gson.Gson

//import com.google.android.libraries.navigation.Navigator
//import com.google.android.libraries.navigation.RoutingOptions
//import com.google.android.libraries.navigation.Waypoint
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException


// A data class to hold the complete result
data class DirectionsResult(
    val destination: Place,
    val steps: List<RouteLegStep>,
    val overviewPolyline: String
)

// The listener that will return the complete result
interface PathFinderListener {
    fun onPathFound(result: DirectionsResult)
    fun onPathError(message: String)
}

class PathFinder(
    private val context: Context,
//    private val mMap: GoogleMap,
    private val fusedLocationClient: FusedLocationProviderClient,
    private val placesClient: PlacesClient,
//    private val voiceAssistant: VoiceAssistant,
//    private val navigator: Navigator
) {
    companion object {
        private const val TAG = "PathFinder"
    }

    private val httpClient = OkHttpClient()
    private val gson = Gson()

    /**
     * The main public method. Searches for the nearest place matching the keyword
     * and then finds and draws a path to it.
     */
    fun findAndPreparePath(searchKeyword: String, listener: PathFinderListener) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(context, "Location permission is needed to find nearby places.", Toast.LENGTH_SHORT).show()
            return
        }

        fusedLocationClient.lastLocation.addOnSuccessListener { originLocation: Location? ->
            if (originLocation == null) {
                Toast.makeText(context, "Could not get current location for search.", Toast.LENGTH_SHORT).show()
                return@addOnSuccessListener
            }
//            val originLatLng = LatLng(originLocation.latitude, originLocation.longitude)

            // 1. Define the search area as a circle around the user's location.
//            val searchArea = CircularBounds.newInstance(originLatLng, 50000.0) // 50km radius

            // Define which fields you want the API to return for each place.
            val placeFields = listOf(Place.Field.ID, Place.Field.NAME, Place.Field.LAT_LNG, Place.Field.ADDRESS)

            // 3. Build the request with the search area, fields, and keyword.
            val request = SearchByTextRequest.builder(searchKeyword, placeFields)
//                .setLocationRestriction(searchArea)
                .setMaxResultCount(10) // Limit results to save data
                .build()

            // The user's location is passed into the main search function itself.
            placesClient.searchByText(request)
                .addOnSuccessListener { response ->
                    if (response.places.isEmpty()) {
                        Toast.makeText(context, "No '$searchKeyword' found nearby.", Toast.LENGTH_LONG).show()
                        return@addOnSuccessListener
                    }

                    Log.d(TAG, "Found ${response.places.size} places.")
                    // Log all places
                    response.places.forEach { place ->
                        Log.d(TAG, "Place: ${place.name}, ${place.address}, ${place.id}")
                    }

                    val sortedPlaces = response.places.sortedBy { place ->
                        // The 'it' here is a Place object from the list.
                        val placeLocation = Location("").apply {
                            latitude = place.latLng!!.latitude
                            longitude = place.latLng!!.longitude
                        }
                        // Use the 'distanceTo' method to get the distance in meters.
                        originLocation.distanceTo(placeLocation)
                    }

                    val nearestPlace = sortedPlaces.first()

                    if (nearestPlace.latLng != null) {
                        fetchDirections(originLocation, nearestPlace, listener)
                    }
                    else{
                        listener.onPathError("No '$searchKeyword' found nearby.")
                    }
                }
                .addOnFailureListener { exception ->
                    Log.e(TAG, "Places search failed", exception)
                    Toast.makeText(context, "Error finding places.", Toast.LENGTH_SHORT).show()
                    listener.onPathError("Error finding places.")
                }
        }
    }


    private fun fetchDirections(origin: Location, destinationPlace: Place, listener: PathFinderListener) {
        val apiKey = BuildConfig.MAPS_API_KEY
        val url = "https://routes.googleapis.com/directions/v2:computeRoutes"

        // 1. CREATE THE JSON REQUEST BODY
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
            // 3. ADD THE FIELD MASK - This tells the API exactly which fields to return
            .addHeader("X-Goog-FieldMask", "routes.legs.steps.navigationInstruction,routes.legs.steps.startLocation,routes.polyline.encodedPolyline")
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Directions API call failed", e)
                (context as HelloArActivity).runOnUiThread {
                    Toast.makeText(context, "Error fetching directions.", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()
                if (!response.isSuccessful || responseBody == null) {
                    (context as HelloArActivity).runOnUiThread {
                        Log.d(TAG, "Directions API call failed: $responseBody")
                        Toast.makeText(context, "Failed to get directions.", Toast.LENGTH_SHORT).show()
                    }
                    return
                }

                Log.d(TAG, "Directions API call successful: $responseBody")

                val directionsResponse = gson.fromJson(responseBody, RoutesApiResponse::class.java)
                Log.d(TAG, "Parsed response: $directionsResponse")

                val route = directionsResponse.routes.firstOrNull()
                val steps = route?.legs?.firstOrNull()?.steps
                val polyline = route?.polyline?.encodedPolyline

                if (route != null && !steps.isNullOrEmpty() && polyline != null) {
                    // Success! We have all the data. Return the complete result object.
                    val result = DirectionsResult(destinationPlace, steps, polyline)
                    listener.onPathFound(result)
                } else {
                    listener.onPathError("No route found.")
                }
            }
        })
    }

//    private fun drawPolylineOnMap(path: List<LatLng>, origin: LatLng, destination: LatLng) {
//        mMap.clear()
//        mMap.addPolyline(PolylineOptions().addAll(path).color(Color.BLUE).width(12f))
//        mMap.addMarker(MarkerOptions().position(origin).title("Your Location"))
//        mMap.addMarker(MarkerOptions().position(destination).title("Destination"))
//        val bounds = LatLngBounds.builder().include(origin).include(destination).build()
//        mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 150))
//    }
}