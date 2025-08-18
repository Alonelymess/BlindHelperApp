package com.google.ar.core.examples.kotlin.helloar

import android.location.Location
import android.text.Html
import android.util.Log
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.SphericalUtil

class GuidanceManager(private val guidanceState: GuidanceState) {
    private var steps: List<RouteLegStep> = emptyList()
    private var currentStepIndex = 0

    fun setRoute(newSteps: List<RouteLegStep>) {
        this.steps = newSteps
        this.currentStepIndex = 0
        guidanceState.setStartGuiding()
        provideGuidanceForCurrentStep() // Give the first instruction immediately
    }

    fun updateUserLocation(userLocation: Location) {
        if (steps.isEmpty() || currentStepIndex >= steps.size) return

        val nextStep = steps[currentStepIndex]
        val nextStepStartLatLng = nextStep.startLocation.latLng.toLatLng()

        val distanceToNextStep = SphericalUtil.computeDistanceBetween(
            userLocation.toLatLng(),
            nextStepStartLatLng
        )

        // If user is within 5 meters of the start of the next step, advance guidance.
        if (distanceToNextStep < 5) {
            currentStepIndex++
            if (currentStepIndex < steps.size) {
                provideGuidanceForCurrentStep()
            } else {
                guidanceState.setInstruction("You have arrived.")
                guidanceState.doneGuiding()
            }
        }
    }

    private fun provideGuidanceForCurrentStep() {
        if (currentStepIndex < steps.size) {
            val instruction = steps[currentStepIndex].instruction.text
            guidanceState.setInstruction(instruction)
        }
    }

    fun clear() {
        steps = emptyList()
        currentStepIndex = 0
    }
}

// Helper extension functions
fun LatLngPoint.toLatLng() = com.google.android.gms.maps.model.LatLng(this.latitude, this.longitude)
fun Location.toLatLng() = LatLng(this.latitude, this.longitude)