package com.google.ar.core.examples.kotlin.helloar.path

import com.google.gson.annotations.SerializedName

/**
 * Data classes for parsing the JSON response from the modern Google Routes API.
 */

data class RoutesApiResponse(
    @SerializedName("routes") val routes: List<Route>
)

data class Route(
    @SerializedName("legs") val legs: List<RouteLeg>,
    @SerializedName("polyline") val polyline: Polyline
)

data class RouteLeg(
    @SerializedName("steps") val steps: List<RouteLegStep>
)

data class RouteLegStep(
    @SerializedName("navigationInstruction") val instruction: Instruction,
    // You can add start/end location here if needed for your guidance logic
    @SerializedName("startLocation") val startLocation: StepLocation,
    @SerializedName("endLocation") val endLocation: StepLocation
)

data class Instruction(
    @SerializedName("instructions") val text: String
)

data class Polyline(
    @SerializedName("encodedPolyline") val encodedPolyline: String
)

data class StepLocation(
    @SerializedName("latLng") val latLng: LatLngPoint
)

data class LatLngPoint(
    @SerializedName("latitude") val latitude: Double,
    @SerializedName("longitude") val longitude: Double
)