package com.google.ar.core.examples.kotlin.helloar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.Image
import android.os.Environment
import android.util.Log
import com.google.ar.core.CameraIntrinsics
import com.google.ar.core.examples.kotlin.helloar.HelloArRenderer.Companion.TAG
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ShortBuffer
import kotlin.math.sqrt

/**
 * A reusable module to read, process, and visualize raw depth images from ARCore.
 * Handles conversion to a 3D point cloud and generation of a Bird's-Eye View (BEV).
 *
 * @param intrinsics The camera intrinsic parameters from the ARCore session.
 */
class DepthProcessor(private val intrinsics: CameraIntrinsics){

    private var rawDepthData: ShortArray? = null
    private var depthWidth: Int = 0
    private var depthHeight: Int = 0

    private val fx: Float = intrinsics.focalLength[0]
    private val fy: Float = intrinsics.focalLength[1]
    private val cx: Float = intrinsics.principalPoint[0]
    private val cy: Float = intrinsics.principalPoint[1]

    private var pointCloud: List<Point3D>? = null
    private var bevBitmap: Bitmap? = null

    data class Point3D(val x: Float, val y: Float, val z: Float)
    data class DepthRangeAnalysis(
        val minMeters: Float,
        val maxMeters: Float,
        val meanMeters: Float,
        val medianMeters: Float
    )

    /**
     * Processes a raw depth image to generate a point cloud.
     * This method now handles the coordinate scaling internally.
     *
     * @param depthImage The low-resolution depth image from ARCore.
     * @param colorImageWidth The width of the high-resolution color image.
     * @param colorImageHeight The height of the high-resolution color image.
     */
    fun processImageAndCreatePointCloud(
        depthImage: Image,
        colorImageWidth: Int,
        colorImageHeight: Int
    ) {
        val depthWidth = depthImage.width
        val depthHeight = depthImage.height

        // Calculate the scaling ratio between the two images.
        val scaleX = colorImageWidth.toFloat() / depthWidth.toFloat()
        val scaleY = colorImageHeight.toFloat() / depthHeight.toFloat()

        val plane = depthImage.planes[0]
        val shortBuffer: ShortBuffer = plane.buffer.asShortBuffer()

        val points = mutableListOf<Point3D>()
        for (y in 0 until depthHeight) {
            for (x in 0 until depthWidth) {
                val index = y * depthWidth + x
                val depthMm = shortBuffer.get(index).toInt() and 0xFFFF
                if (depthMm == 0) continue

                val depthM = depthMm / 1000.0f

                // *** THIS IS THE FIX ***
                // Scale the depth pixel's (x, y) coordinate to match the color
                // image's coordinate system before applying the pinhole formula.
                val scaledX = x * scaleX
                val scaledY = y * scaleY

                // The pinhole camera formula now works because we are using coordinates
                // that are in the same scale as the intrinsics (cx, cy, fx, fy).
                val pointX = (scaledX - cx) * depthM / fx
                val pointY = (scaledY - cy) * depthM / fy
                val pointZ = depthM
                points.add(Point3D(pointX, pointY, pointZ))
            }
        }
        pointCloud = points
        Log.d("DepthProcessor", "Generated point cloud with ${pointCloud?.size ?: 0} points using coordinate scaling.")
    }

    /**
     * Generates a Bird's-Eye View image from the 3D point cloud.
     *
     * @param gridSizeM The width and depth of the BEV grid in meters.
     * @param resolutionPxPerM The number of pixels per meter in the output image.
     */
    fun generateBev(gridSizeM: Int = 10, resolutionPxPerM: Int = 50) {
        if (pointCloud == null) {
            Log.e("DepthProcessor", "Point cloud not generated. Cannot create BEV.")
            return
        }

        val gridDimPx = gridSizeM * resolutionPxPerM
        val bitmap = Bitmap.createBitmap(gridDimPx, gridDimPx, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLACK) // Start with a black background

        pointCloud!!.forEach { point ->
            // Convert 3D world coordinates (X, Z) to 2D BEV grid coordinates.
            // Assumes camera is at the bottom-center of the map.
            val gridX = ((point.x * resolutionPxPerM) + (gridDimPx / 2)).toInt()
            val gridZ = (gridDimPx - 1) - (point.z * resolutionPxPerM).toInt() // Invert Z

            if (gridX in 0 until gridDimPx && gridZ in 0 until gridDimPx) {
                // Mark the pixel white. A more advanced version could color by height (Y).
                bitmap.setPixel(gridX, gridZ, Color.WHITE)
            }
        }
        bevBitmap = bitmap
        Log.d("DepthProcessor", "Bird's-Eye View generated.")
    }

    /**
     * Creates a colorized visualization of the raw depth map.
     *
     * @return A Bitmap ready to be displayed, or null if no data is loaded.
     */
    fun getDepthVisualizationBitmap(): Bitmap? {
        if (rawDepthData == null) return null

        val bitmap = Bitmap.createBitmap(depthWidth, depthHeight, Bitmap.Config.ARGB_8888)
        val maxDepth = rawDepthData!!.maxOfOrNull { it }?.toInt()?.and(0xFFFF) ?: 1

        for (i in rawDepthData!!.indices) {
            val depth = rawDepthData!![i].toInt() and 0xFFFF
            val intensity = (depth.toFloat() / maxDepth * 255).toInt().coerceIn(0, 255)
            val color = Color.rgb(intensity, intensity, intensity) // Grayscale
            val x = i % depthWidth
            val y = i / depthWidth
            bitmap.setPixel(x, y, color)
        }
        return bitmap
    }

    /**
     * Returns the generated Bird's-Eye View bitmap.
     */
    fun getBevBitmap(): Bitmap? = bevBitmap

    /**
     * Calculates and returns statistics about the sensor's effective range.
     *
     * @return A DepthRangeAnalysis data class, or null if no data is loaded.
     */
    fun getEffectiveRangeAnalysis(): DepthRangeAnalysis? {
        if (rawDepthData == null) return null

        val validDepths = rawDepthData!!.map { it.toInt() and 0xFFFF }.filter { it > 0 }
        if (validDepths.isEmpty()) return null

        val minMeters = validDepths.minOrNull()!! / 1000f
        val maxMeters = validDepths.maxOrNull()!! / 1000f
        val meanMeters = validDepths.average().toFloat() / 1000f

        val sortedDepths = validDepths.sorted()
        val medianMeters = if (sortedDepths.size % 2 == 0) {
            (sortedDepths[sortedDepths.size / 2 - 1] + sortedDepths[sortedDepths.size / 2]) / 2.0f / 1000f
        } else {
            sortedDepths[sortedDepths.size / 2] / 1000f
        }

        return DepthRangeAnalysis(minMeters, maxMeters, meanMeters, medianMeters)
    }

    /**
     * Saves the generated Bird's-Eye View to a file in the app's external files directory.
     *
     * @param context The application context.
     * @param filename The name of the file to save (e.g., "bev_output.png").
     * @return The absolute path to the saved file, or null on failure.
     */
    fun saveBevToFile(context: Context, filename: String): String? {
        if (bevBitmap == null) {
            Log.e("DepthProcessor", "No BEV to save. Generate it first.")
            return null
        }

        val bevPath = Environment.getExternalStorageDirectory().path + "/DCIM/bev.png"
        val file = File(bevPath)
        try {
            val outputStream = FileOutputStream(file)
            bevBitmap!!.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            outputStream.flush()
            outputStream.close()
            Log.d(TAG, "Image saved successfully: $bevPath")
            return file.absolutePath
        } catch (e: IOException) {
            e.printStackTrace()
            Log.e(TAG, "Error saving image: ${e.message}")
            return null
        }
    }
}