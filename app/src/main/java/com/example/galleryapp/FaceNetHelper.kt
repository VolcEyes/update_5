package com.example.galleryapp

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.scale
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

class OnnxFaceHelper(context: Context) {
    private var ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var ortSession: OrtSession? = null

    // InsightFace Buffalo recognition models expect exactly 112x112
    private val imageSize = 112

    init {
        // Load the model from the assets folder
        val modelBytes = context.assets.open("w600k_mbf.onnx").readBytes()
        ortSession = ortEnv.createSession(modelBytes)
    }

    fun getFaceVector(alignedFace: Bitmap): FloatArray? {
        val scaledBitmap = alignedFace.scale(imageSize, imageSize, false)

        // 1. Convert to NCHW FloatBuffer
        val floatBuffer = convertBitmapToNchwBuffer(scaledBitmap)

        // 2. Create the tensor: Shape is [1, 3, 112, 112]
        val shape = longArrayOf(1, 3, imageSize.toLong(), imageSize.toLong())
        val tensor = OnnxTensor.createTensor(ortEnv, floatBuffer, shape)

        // 3. Run Inference
        val result = ortSession?.run(mapOf("input.1" to tensor))
        // 4. Extract the 512-dimensional output embedding
        val outputData = result?.get(0)?.value as? Array<FloatArray>
        val embedding = outputData?.get(0)

        // FREE MEMORY
        tensor.close()
        result?.close()
        scaledBitmap.recycle()

        return embedding
    }

    private fun convertBitmapToNchwBuffer(bitmap: Bitmap): FloatBuffer {
        val floatBuffer = FloatBuffer.allocate(3 * imageSize * imageSize)
        val pixels = IntArray(imageSize * imageSize)
        bitmap.getPixels(pixels, 0, imageSize, 0, 0, imageSize, imageSize)

        // NCHW separation: Calculate starting indices for Red, Green, and Blue channels
        val rOffset = 0
        val gOffset = imageSize * imageSize
        val bOffset = 2 * imageSize * imageSize

        for (i in pixels.indices) {
            val color = pixels[i]

            // Extract RGB and normalize
            val r = (((color shr 16) and 0xFF) - 127.5f) / 128.0f
            val g = (((color shr 8) and 0xFF) - 127.5f) / 128.0f
            val b = ((color and 0xFF) - 127.5f) / 128.0f

            // Place into the buffer at the correct NCHW offset
            floatBuffer.put(rOffset + i, r)
            floatBuffer.put(gOffset + i, g)
            floatBuffer.put(bOffset + i, b)
        }
        return floatBuffer
    }

    fun close() {
        ortSession?.close()
        ortEnv.close()
    }
}