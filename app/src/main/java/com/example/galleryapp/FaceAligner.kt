package com.example.galleryapp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint

object FaceAligner {
    // InsightFace's standard reference coordinates for a 112x112 image
    // These dictate EXACTLY where the eyes and nose must be placed
    private val REFERENCE_LEFT_EYE = floatArrayOf(38.2946f, 51.6963f)
    private val REFERENCE_RIGHT_EYE = floatArrayOf(73.5318f, 51.5014f)
    private val REFERENCE_NOSE = floatArrayOf(56.0252f, 71.7366f)

    /**
     * @param originalBitmap The large, uncropped image
     * @param srcPoints FloatArray of size 6: [leftEyeX, leftEyeY, rightEyeX, rightEyeY, noseX, noseY]
     * @return A perfectly aligned 112x112 bitmap ready for Buffalo_S
     */
    fun alignAndCrop(originalBitmap: Bitmap, srcPoints: FloatArray): Bitmap {
        val destPoints = floatArrayOf(
            REFERENCE_LEFT_EYE[0], REFERENCE_LEFT_EYE[1],
            REFERENCE_RIGHT_EYE[0], REFERENCE_RIGHT_EYE[1],
            REFERENCE_NOSE[0], REFERENCE_NOSE[1]
        )

        val matrix = Matrix()
        // setPolyToPoly calculates the affine transform needed to move srcPoints to destPoints
        matrix.setPolyToPoly(srcPoints, 0, destPoints, 0, 3)

        // Create the 112x112 target bitmap directly
        val alignedBitmap = Bitmap.createBitmap(112, 112, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(alignedBitmap)

        val paint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true // Smooths out the pixels during the warp
        }

        // Draw the original image onto the 112x112 canvas using the calculated warp matrix
        canvas.drawBitmap(originalBitmap, matrix, paint)

        return alignedBitmap
    }
}