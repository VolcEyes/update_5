package com.example.galleryapp

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import androidx.core.graphics.scale

class FaceNetHelper(context: Context) {
    private var interpreter: Interpreter? = null
    private val imageSize = 160 // Standard input size for MobileFaceNet
    private val embeddingSize = 128 // Standard output size for MobileFaceNet

    init {
        interpreter = Interpreter(loadModelFile(context, "mobilefacenet.tflite")) // We will add this asset in Step 3
    }

    private fun loadModelFile(context: Context, modelName: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, fileDescriptor.startOffset, fileDescriptor.declaredLength)
    }

    fun getFaceVector(croppedFace: Bitmap): FloatArray {
        val scaledBitmap = croppedFace.scale(imageSize, imageSize, false)
        val byteBuffer = convertBitmapToByteBuffer(scaledBitmap)

        val output = Array(1) { FloatArray(embeddingSize) }
        interpreter?.run(byteBuffer, output)

        // FREE MEMORY 3: Destroy the FaceNet scaled bitmap
        scaledBitmap.recycle()

        return output[0]
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(4 * imageSize * imageSize * 3)
        byteBuffer.order(ByteOrder.nativeOrder())
        val intValues = IntArray(imageSize * imageSize)
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        var pixel = 0
        for (i in 0 until imageSize) {
            for (j in 0 until imageSize) {
                val `val` = intValues[pixel++]
                // Normalize to [-1, 1] as required by MobileFaceNet
                byteBuffer.putFloat((((`val` shr 16) and 0xFF) - 127.5f) / 128.0f)
                byteBuffer.putFloat((((`val` shr 8) and 0xFF) - 127.5f) / 128.0f)
                byteBuffer.putFloat(((`val` and 0xFF) - 127.5f) / 128.0f)
            }
        }
        return byteBuffer
    }

    fun close() {
        interpreter?.close()
    }
}