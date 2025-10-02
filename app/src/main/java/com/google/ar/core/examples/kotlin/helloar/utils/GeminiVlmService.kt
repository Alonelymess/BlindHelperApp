package com.google.ar.core.examples.kotlin.helloar

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.liveGenerationConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.nio.ShortBuffer
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set

/**
 * Service class for interacting with the Gemini VLM (Vision Language Model).
 * This class handles the communication with the Gemini API to generate guidance
 * based on camera input and textual instructions.
 */
class GeminiVlmService{

    companion object {
        private const val TAG = "GeminiVlmService"
    }

    @OptIn(PublicPreviewAPI::class)
    val config = liveGenerationConfig {
        maxOutputTokens = 200
        responseModality = ResponseModality.AUDIO
        temperature = 0.9f
        topK = 16
        topP = 0.1f
    }

    private val generativeModel = Firebase.ai(backend = GenerativeBackend.googleAI())
        .generativeModel("gemini-2.5-flash-lite") // Or your preferred model

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
            Log.w(TAG, "Depth buffer size (${depthBuffer.remaining()}) is less than required for ${width}x${height}. Returning null.")
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

    suspend fun generateGuidance(
        cameraImage: Bitmap,
        depthInfo: ShortBuffer?,
        depthWidth: Int,
        depthHeight: Int,
        currentInstruction: String,
        compassDirection: String
    ): String? {
        return try {
            val encodedDepthBitmap = depthBufferToEncodedRgbBitmap(depthInfo, depthWidth, depthHeight)
            Log.d(TAG, "Sending IMAGE request. Instruction: $currentInstruction, Compass: $compassDirection. Encoded depth: ${encodedDepthBitmap != null}")

            val prompt = content{
                text(getSystemPrompt(currentInstruction, compassDirection))
                image(cameraImage) 
                if (encodedDepthBitmap != null) {
                    image(encodedDepthBitmap) 
                } else {
                    Log.w(TAG, "IMAGE request: Encoded depth bitmap was null.")
                }
            }
            Log.d(TAG, "IMAGE Prompt parts count: ${prompt.parts.size}")

            val response = generativeModel.generateContent(prompt)
            Log.d(TAG, "IMAGE response: ${response.text}")
            response.text
        } catch (e: Exception) {
            Log.e(TAG, "Error in generateGuidance (IMAGE)", e)
            "Error: Could not get guidance from assistant (image)."
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
            val encodedDepthBitmap = depthBufferToEncodedRgbBitmap(depthInfo, depthWidth, depthHeight)
            Log.d(TAG, "Sending STREAM request. Encoded depth: ${encodedDepthBitmap != null}")

            val prompt = content{
                text(getSystemPrompt(currentInstruction, compassDirection))
                image(cameraImage)
                if (encodedDepthBitmap != null) {
                    image(encodedDepthBitmap)
                } else {
                    Log.w(TAG, "STREAM request: Encoded depth bitmap was null.")
                }
            }

            generativeModel.generateContentStream(prompt)
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

    fun getSystemPrompt(instruction: String, compassDirection: String): String {
        // Same as your existing single image prompt
        return """
        You are an expert navigation assistant for the blind and visually impaired. The user is taught to follow the left side of the pavement, specifically at the boundary between the pavement and the road.

I will provide you with 1 image:

1.  [**Camera Feed Image**]: A real-time,  first-person color image from the user's phone camera, showing their immediate surroundings. The red line in the middle is the border to split the camera in left and right. 

**Strict Rules for Your Response**:
-  **Indoor**: If the user's surroundings appear to be indoor, first guide them to the outside, including step by step and obstacle avoidance with distance estimations using the decoded depth from the Encoded Depth Map Image. Once outside, guide them to the left edge of the pavement.
-  **Safety and Pathing Preference**: Your absolute first priority is user safety. Guide the user to consistently follow the left edge of the pavement, maintaining their position at the boundary where the pavement meets the road. If the camera feed indicates the user is *not* on this specific path (e.g., too far onto the pavement, on the road, or in a bike lane), your first and only instruction must be to guide them safely to, or back to, this left edge.
-  **One Instruction at a Time**: Your entire response must be a single, direct command or alert. Focus only on the very next action the user needs to take. Keep sentences extremely short.
-  **Turning Strategy and Announcing Turns**: Use the map data (implicitly from the [Overall Direction]) and visual cues to know when a turn is approaching. Give a preparatory command. **Important**: Only instruct for a right turn (towards 3 o'clock) if it's essential for the [Overall Direction] or for immediate safety. Otherwise, prioritize maintaining the left-edge path. Left turns (towards 9 o'clock) are acceptable when the route requires. For example: "In about 15 meters, prepare to turn towards your 3 o'clock to follow the main route." or "Continue along the left edge. In about 10 meters, the pavement will curve to your 9 o'clock."
-  **Output Format**: ...(as obstacle) ... meters at ... o'clock. 




** Note **: For the clock face direction, the center of the clock is the bottom middle of the image

        """.trimIndent()
    }
}


// 2.  [**Encoded Depth Map Image**]: An RGB image where the 16-bit depth information (in millimeters) is encoded into the Red and Green channels. To get the actual depth in millimeters for any pixel in this Encoded Depth Map, YOU MUST USE THE FORMULA: `depth_mm = (Red_channel_value * 256) + Green_channel_value`. The Blue channel is not used. This depth map has the same field of view as the Camera Feed Image.

//**Example Scenario (incorporating left-edge preference)**:
//- [**Camera Feed Image**]: Shows a pavement with a road to its left. The user is slightly too far to the right on the pavement. A crack is visible 1 meter ahead, slightly to their left, near the road-pavement boundary.
//- [**Encoded Depth Map Image**]: Provides depth data confirming distances.
//- [**Overall Direction**]: "Head North to A street."
//- [**Current Compass Direction**]: North.
//
//**Your Expected Response**:
//"Obstacle 1 meter ahead. Take 1 step to the left. Continue walking"