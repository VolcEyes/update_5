package com.example.galleryapp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint

object FaceAligner {
    // InsightFace's standard reference coordinates for a 112x112 image
    // Includes all 5 points: Left Eye, Right Eye, Nose, Left Mouth, Right Mouth
    private val REFERENCE_POINTS = floatArrayOf(
        38.2946f, 51.6963f,
        73.5318f, 51.5014f,
        56.0252f, 71.7366f,
        41.5493f, 92.3655f,
        70.7299f, 92.2041f
    )

    /**
     * @param originalBitmap The large, uncropped image
     * @param srcPoints FloatArray of size 10: [leftEyeX, leftEyeY, rightEyeX, rightEyeY, noseX, noseY, leftMouthX, leftMouthY, rightMouthX, rightMouthY]
     * @return A perfectly aligned 112x112 bitmap ready for Buffalo_S
     */
    fun alignAndCrop(originalBitmap: Bitmap, srcPoints: FloatArray): Bitmap {
        // Use Umeyama algorithm to get a strict Similarity Transform matrix
        val matrix = calculateSimilarityTransform(srcPoints, REFERENCE_POINTS)

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

    /**
     * Computes a 2D Similarity Transform (scale, rotation, translation)
     * without shearing, using the Umeyama least-squares algorithm.
     */
    private fun calculateSimilarityTransform(src: FloatArray, dst: FloatArray): Matrix {
        val numPoints = src.size / 2

        var srcMeanX = 0f
        var srcMeanY = 0f
        var dstMeanX = 0f
        var dstMeanY = 0f

        // 1. Find the centroids of both point sets
        for (i in 0 until numPoints) {
            srcMeanX += src[i * 2]
            srcMeanY += src[i * 2 + 1]
            dstMeanX += dst[i * 2]
            dstMeanY += dst[i * 2 + 1]
        }
        srcMeanX /= numPoints
        srcMeanY /= numPoints
        dstMeanX /= numPoints
        dstMeanY /= numPoints

        // 2. Subtract centroids and calculate variance/covariance
        var sigmaX = 0f
        var sigmaY = 0f
        var variance = 0f

        for (i in 0 until numPoints) {
            val srcX = src[i * 2] - srcMeanX
            val srcY = src[i * 2 + 1] - srcMeanY
            val dstX = dst[i * 2] - dstMeanX
            val dstY = dst[i * 2 + 1] - dstMeanY

            sigmaX += (srcX * dstX + srcY * dstY)
            sigmaY += (srcX * dstY - srcY * dstX)
            variance += (srcX * srcX + srcY * srcY)
        }

        // Safety check to avoid division by zero
        if (variance < 1e-6f) {
            return Matrix().apply {
                setTranslate(dstMeanX - srcMeanX, dstMeanY - srcMeanY)
            }
        }

        // 3. Calculate scale and rotation (a and b)
        val a = sigmaX / variance
        val b = sigmaY / variance

        // 4. Calculate translation
        val tx = dstMeanX - (a * srcMeanX - b * srcMeanY)
        val ty = dstMeanY - (b * srcMeanX + a * srcMeanY)

        // 5. Construct Android Matrix
        // Android Matrix values are stored as:
        // [ MSCALE_X, MSKEW_X,  MTRANS_X ]
        // [ MSKEW_Y,  MSCALE_Y, MTRANS_Y ]
        // [ MPERSP_0, MPERSP_1, MPERSP_2 ]
        val matrixValues = floatArrayOf(
            a, -b, tx,
            b,  a, ty,
            0f, 0f, 1f
        )

        return Matrix().apply { setValues(matrixValues) }
    }
}