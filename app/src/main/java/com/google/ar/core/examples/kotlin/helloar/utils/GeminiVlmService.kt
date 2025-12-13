package com.google.ar.core.examples.kotlin.helloar.utils

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.Chat
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.google.firebase.ai.type.liveGenerationConfig
import com.google.firebase.ai.type.thinkingConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.ShortBuffer
import kotlin.math.abs

@Serializable
data class ObstacleResponse(
    val obstacle: List<Obstacle> = emptyList(),
    val instruction: String = ""
)

@Serializable
data class Obstacle(
    val name: String,
    val distance: Int
)

/**
 * Service class for interacting with the Gemini VLM (Vision Language Model).
 * This class handles the communication with the Gemini API to generate guidance
 * based on camera input and textual instructions.
 */
class GeminiVlmService {

    companion object {
        private const val TAG = "GeminiVlmService"
        private const val MAX_HISTORY_TURNS = 1 // Each turn has a user and a model response
    }

    // This will hold the chat session and its history.
    private var chat: Chat? = null

    @OptIn(PublicPreviewAPI::class)
    val config = liveGenerationConfig {
        maxOutputTokens = 200
        responseModality = ResponseModality.AUDIO
        temperature = 0.9f
        topK = 16
        topP = 0.1f
    }

    val jsonSchema = Schema.obj(
        mapOf(
//            "obstacle" to Schema.array(
//                Schema.obj(
//                    mapOf(
//                        "name" to Schema.string(),
//                        "distance" to Schema.integer(),
//                    )
//                )
//            ),
            "instruction" to Schema.string()
        )
    )


    // Set the thinking configuration
    val generationConfig = generationConfig {
        thinkingConfig = thinkingConfig {
            thinkingBudget = 200
        }
        responseMimeType = "application/json"
        responseSchema = jsonSchema
    }

    private val generativeModel = Firebase.ai(backend = GenerativeBackend.googleAI())
        .generativeModel(
            "gemini-flash-latest",
            generationConfig,
        ) // Or your preferred model

    /**
     * Converts a ShortBuffer of depth data into an RGB Bitmap, encoding the 16-bit depth
     * value into the R and G channels.
     */
    private fun depthBufferToEncodedRgbBitmap(
        depthBuffer: ShortBuffer?,
        width: Int,
        height: Int
    ): Bitmap? {
        if (depthBuffer == null || width <= 0 || height <= 0) {
            Log.w(TAG, "Invalid input to depthBufferToEncodedRgbBitmap")
            return null
        }

        val bitmap = createBitmap(width, height)
        val originalPosition = depthBuffer.position()

        if (depthBuffer.remaining() < width * height) {
            Log.w(
                TAG,
                "Depth buffer size (${depthBuffer.remaining()}) is less than required for ${width}x${height}. Returning null."
            )
            depthBuffer.position(originalPosition)
            return null
        }

        val depthArray = ShortArray(width * height)
        depthBuffer.get(depthArray, 0, width * height)
        depthBuffer.position(originalPosition)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val depthIndex = y * width + x
                val depthValue = depthArray[depthIndex].toInt() and 0xFFFF
                val red = (depthValue shr 8) and 0xFF
                val green = depthValue and 0xFF
                val blue = 0
                bitmap[x, y] = Color.argb(255, red, green, blue)
            }
        }
        return bitmap
    }

    private fun mapClockFace(avoidInstruction:String, currentInstruction: String): String {
        /**
         * Map the clock face of the avoid instruction to the current instruction.
         */
        try {
            // Extract the clockface from both string
            val avoidClockFace = avoidInstruction.substringAfter("to ").substringBefore(" o'clock").toInt()
            val currentClockFace = currentInstruction.substringAfter("to ").substringBefore(" o'clock").toInt()
            Log.d(TAG, "Avoid clock face: $avoidClockFace, Current clock face: $currentClockFace")
            if (abs(avoidClockFace - currentClockFace) >= 6){
                return currentClockFace.toString()
            }
            else{
                return avoidClockFace.toString()
            }
        }
        catch (e: Exception){
            return avoidInstruction
        }
    }

    suspend fun generateGuidance(
        cameraImage: Bitmap,
//        mapImage: Bitmap?,
//        depthInfo: ShortBuffer?,
//        depthWidth: Int?,
//        depthHeight: Int?,
        currentInstruction: String,
        compassDirection: String,
        obstacle: String,
        depth: Int
    ): String? {
        try {
            // Start a new chat session if it's null or if the history is too long.
            if (chat == null || (chat?.history?.size ?: 0) >= MAX_HISTORY_TURNS * 2) {
                chat = generativeModel.startChat()
                Log.d(TAG, "Starting new chat session. History size was: ${chat?.history?.size}")
            }

//            val encodedDepthBitmap = depthBufferToEncodedRgbBitmap(depthInfo, depthWidth, depthHeight)
            Log.d(
                TAG,
                "Sending IMAGE request. Instruction: $currentInstruction, Compass: $compassDirection, Obstacle: $obstacle, Depth: $depth"
            )

            val prompt = content {
                text(getSystemPrompt(currentInstruction, compassDirection, obstacle, depth))

                image(cameraImage)
//                if (mapImage != null){
//                    image(mapImage)
//                }
//                else
//                {
//                    Log.w(TAG, "IMAGE Prompt: Map image was null.")
//                }
//                if (encodedDepthBitmap != null) {
//                    image(encodedDepthBitmap)
//                } else {
//                    Log.w(TAG, "IMAGE request: Encoded depth bitmap was null.")
//                }
            }
            Log.d(TAG, "IMAGE Prompt parts count: ${prompt.parts.size}")

            val response = chat!!.sendMessage(prompt) // Use sendMessage on the chat object
            Log.d(TAG, "IMAGE response: ${response.text}")
            val jsonResponseString = response.text

            if (jsonResponseString != null) {
                try {
                    val obstacleResponse = Json.decodeFromString<ObstacleResponse>(jsonResponseString)

//                    if (obstacleResponse.obstacle.isEmpty()) {
//                        return obstacleResponse.instruction.ifBlank {
//                            // Provide a default safe message if there's no instruction
//                            "Path is clear. Go straight."
//                        }
//                    }

//                    // Combine instructions from all found obstacles into one clear message.
//                    val obstacleText = obstacleResponse.obstacle.joinToString(separator = ", ") { obs ->
//                        "${obs.name} at ${obs.distance} meters"
//                    }
//                    val clockFace = mapClockFace(obstacleResponse.instruction, currentInstruction)
//                    val avoidText =
//                        try {
//                            val clockFaceInt = clockFace.toInt()
//                            "Go to $clockFaceInt o'clock to avoid"
//                        }
//                        catch (e: Exception) {
//                            clockFace
//                        }
//                    val guidanceText = "$obstacleText. $avoidText"
                    val guidanceText = obstacleResponse.instruction.ifBlank {
                        // Provide a default safe message if there's no instruction
                        "Path is clear. Go straight."
                    }
                    Log.d(TAG, "Guidance text: $guidanceText")
                    return guidanceText

                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse JSON response: $jsonResponseString", e)
                    // If parsing fails, return the raw text. It might be a valid (non-JSON) response.
                    return currentInstruction
                }
            } else {
                Log.w(TAG, "Gemini response text was null.")
                return currentInstruction
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in generateGuidance (IMAGE)", e)
            return "Error: Could not get guidance from assistant (image)."
        }
    }

    fun generateGuidanceStream(
        cameraImage: Bitmap,
        depthInfo: ShortBuffer,
        depthWidth: Int,
        depthHeight: Int,
        currentInstruction: String,
        compassDirection: String
    ): Flow<String>? {
        return try {
            // Start a new chat session if it's null or if the history is too long.
            if (chat == null || (chat?.history?.size ?: 0) >= MAX_HISTORY_TURNS * 2) {
                chat = generativeModel.startChat()
                Log.d(
                    TAG,
                    "Starting new chat session for stream. History size was: ${chat?.history?.size}"
                )
            }

            val encodedDepthBitmap = depthBufferToEncodedRgbBitmap(depthInfo, depthWidth, depthHeight)
            Log.d(TAG, "Sending STREAM request. Encoded depth: ${encodedDepthBitmap != null}")

            val prompt = content {
                text(getSystemPrompt(currentInstruction, compassDirection))
                image(cameraImage)
                if (encodedDepthBitmap != null) {
                    image(encodedDepthBitmap)
                } else {
                    Log.w(TAG, "STREAM request: Encoded depth bitmap was null.")
                }
            }

            chat!!.sendMessageStream(prompt) // Use sendMessageStream on the chat object
                .map { generateContentResponse ->
                    val textChunk = generateContentResponse.text
                    Log.d("GeminiServiceStream", "Received chunk: $textChunk")
                    textChunk ?: ""
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error generating guidance stream", e)
            null
        }
    }

    fun getSystemPrompt(instruction: String, compassDirection: String, obstacle: String = "", depth: Int = 0): String {
        var curr_instruction = instruction
        if (instruction.contains("Navigation")){
            curr_instruction = ""
        }
        return """
        You are a navigation assistant for an user. The user ideally follow the left side of the pavement, at the boundary between the pavement and the road.

I will provide you with the following information:
1.  [**Camera Feed Image**]: A real-time, first-person color image from the user's camera with $obstacle detected at ${depth/1000} meters.
2.  [**Map Instruction**]: A high-level instruction from the navigation system. Current instruction: "$curr_instruction".

**Strict Rules for Your Response**:
-  **Identify pedestrian lane**: Identify the lane and keep the user inside that lane, preferably on the left side of the lane.
-  **Obstacle avoidance**: Avoid obstacles on the lane, remember, ON THE LANE.
-  **Map Understanding**: Use the map instruction as the guideline to construct you navigation.
-  **Clock-Face Directions**: Use clock-face directions for all movements (e.g., "move to 11 o'clock"). The range for immediate avoidance maneuvers is from 9 o'clock to 3 o'clock.
-  **Intuitive navigation**: Incorporate map instruction and avoidance instruction to make an intuitive instruction.
-  **Output format**: Only return the instruction with clock-face directions, don't need to mention about the obstacle.
 """.trimIndent()
    }
}

//2.  [**Map Image**]: A real-time, current map with blue line as the line to follow and the blue dot is the user's location.
// **Example 1**:
//Map instruction: Take 12 meters to your 9 o'clock
//Image: A pole at 12 o'clock 2 meters
//Your Answer:
//"Pole at 12 o'clock, 2 meters. Turn to 9 o'clock to follow the map"
//
//**Example 2**:
//Map instruction: Take 12 meters to your 11 o'clock
//Image: Stairs up at 12 o'clock, 1 meter
//Your Answer:
//"Stairs up at 12 o'clock, 1 meter. Prepare to ascend and head to 11 o'clock to follow the map"
//
//
//**Example 3**:
//Map instruction: 343 meters at 12 o'clock to your next turn
//Image: A pole at 12 o'clock 2 meter
//Your Answer:
//"Pole at 12 o'clock, 2 meters. Move 1 step to 9 o'clock and head to 12 o'clock to follow the map"

//-  **Output Format**: Your response must be in the format: "[type] at [clock-face] o'clock, [distance] meters. Move [num of step] to [clock-face] o'clock to avoid and move [map-instruction]]". If the path is clear for the next 2 meters, respond with "Go straight."



