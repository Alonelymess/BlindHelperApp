package com.google.ar.core.examples.kotlin.helloar.path

import java.util.concurrent.atomic.AtomicReference

/**
 * A simple, thread-safe class to hold the current state of navigation guidance.
 * This allows different components (like GuidanceManager and HelloArRenderer)
 * to share the current instruction without needing direct references to each other.
 */
class GuidanceState {
    // An AtomicReference is used to ensure that reads and writes of the
    // instruction string are safe to call from any thread.
    private val currentInstruction = AtomicReference<String>("")
    private var startGuiding = false

    /**
     * Updates the current guidance instruction.
     * Called by GuidanceManager.
     */
    fun setInstruction(instruction: String) {
        currentInstruction.set(instruction)
    }

    /**
     * Retrieves the latest guidance instruction.
     * Called by HelloArRenderer.
     */
    fun getInstruction(): String {
        return currentInstruction.get()
    }

    /**
     * Clears the current instruction.
     */
    fun clear() {
        currentInstruction.set("")
    }

    fun getStartGuiding(): Boolean {
        return startGuiding
    }

    fun setStartGuiding(state: Boolean) {
        startGuiding = state
    }

    fun doneGuiding() {
        startGuiding = false

    }
}