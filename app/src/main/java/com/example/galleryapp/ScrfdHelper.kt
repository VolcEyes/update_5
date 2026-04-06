package com.example.galleryapp

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.scale
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

class ScrfdHelper(context: Context) {
    private var ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var ortSession: OrtSession? = null

    // Typically SCRFD runs well on 640x640 inputs
    private val inputSize = 640

    init {
        // Load the SCRFD model from assets
        val modelBytes = context.assets.open("scrfd_500m_bnkps.onnx").readBytes()
        ortSession = ortEnv.createSession(modelBytes)
    }

    data class FaceDetection(
        val boundingBox: FloatArray, // [xmin, ymin, xmax, ymax]
        val keypoints: FloatArray,   // [leftEyeX, leftEyeY, rightEyeX, rightEyeY, noseX, noseY, leftMouthX, leftMouthY, rightMouthX, rightMouthY]
        val score: Float
    )

    fun detectFaces(bitmap: Bitmap): List<FaceDetection> {
        // 1. Calculate ratios to scale bounding boxes/keypoints back to original size later
        val scaleX = bitmap.width.toFloat() / inputSize
        val scaleY = bitmap.height.toFloat() / inputSize

        // 2. Scale and convert image to NCHW Tensor (Similar to FaceNetHelper)
        val scaledBitmap = bitmap.scale(inputSize, inputSize, false)
        val floatBuffer = convertBitmapToNchwBuffer(scaledBitmap)

        val shape = longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
        val tensor = OnnxTensor.createTensor(ortEnv, floatBuffer, shape)

        // 3. Run Inference
        val result = ortSession?.run(mapOf("input.1" to tensor)) // Check actual input name for your SCRFD file!

        val detections = mutableListOf<FaceDetection>()

// 4. Parse Outputs Dynamically
        // Instead of guessing the index order, we map the tensors based on their actual dimensions.
        val scoresMap = mutableMapOf<Int, Array<FloatArray>>() // Map of num_anchors -> array
        val bboxesMap = mutableMapOf<Int, Array<FloatArray>>()
        val kpsMap = mutableMapOf<Int, Array<FloatArray>>()

        result?.forEach { entry ->
            val onnxValue = entry.value.value
            var data: Array<FloatArray>? = null

            // Check if the output shape is [1, N, C] (Standard for InsightFace)
            if (onnxValue is Array<*> && onnxValue.isNotEmpty() && onnxValue[0] is Array<*>) {
                try {
                    data = (onnxValue as Array<Array<FloatArray>>)[0]
                } catch (e: Exception) {
                    // Ignore cast exceptions and move on
                }
            }
            // Fallback: Check if the output shape is [N, C]
            else if (onnxValue is Array<*> && onnxValue.isNotEmpty() && onnxValue[0] is FloatArray) {
                try {
                    data = onnxValue as Array<FloatArray>
                } catch (e: Exception) {
                    // Ignore
                }
            }

            if (data != null && data.isNotEmpty()) {
                val numAnchors = data.size
                val channels = data[0].size

                // Map the tensor to the correct category based on the channel size!
                when (channels) {
                    1 -> scoresMap[numAnchors] = data
                    4 -> bboxesMap[numAnchors] = data
                    10 -> kpsMap[numAnchors] = data
                }
            }
        }

        // Now process the mapped tensors
        val strides = intArrayOf(8, 16, 32)
        for (stride in strides) {
            val featureWidth = inputSize / stride
            val featureHeight = inputSize / stride
            val expectedAnchors = featureWidth * featureHeight * 2

            // Grab the safely mapped arrays for this specific stride
            val scores = scoresMap[expectedAnchors]
            val bboxes = bboxesMap[expectedAnchors]
            val kps = kpsMap[expectedAnchors]

            // If the model didn't map them successfully, skip this stride
            if (scores == null || bboxes == null || kps == null) continue

            var anchorIndex = 0
            for (y in 0 until featureHeight) {
                for (x in 0 until featureWidth) {
                    for (anchor in 0 until 2) {
                        val score = scores[anchorIndex][0]

                        // Only process faces with high confidence
                        if (score > 0.5f) {

                            // 4a. Calculate the center of the current grid cell
                            val anchorCenterX = (x * stride).toFloat()
                            val anchorCenterY = (y * stride).toFloat()

                            // 4b. Decode Bounding Box
                            val bbox = bboxes[anchorIndex]
                            val xmin = anchorCenterX - bbox[0] * stride
                            val ymin = anchorCenterY - bbox[1] * stride
                            val xmax = anchorCenterX + bbox[2] * stride
                            val ymax = anchorCenterY + bbox[3] * stride

                            // 4c. Decode Keypoints
                            val kpDistances = kps[anchorIndex]
                            val realKeypoints = FloatArray(10)
                            for (i in 0 until 5) {
                                realKeypoints[i * 2] = anchorCenterX + kpDistances[i * 2] * stride
                                realKeypoints[(i * 2) + 1] = anchorCenterY + kpDistances[(i * 2) + 1] * stride
                            }

                            // 4d. Scale the coordinates back up to the original uncropped image size
                            val scaledBbox = floatArrayOf(
                                xmin * scaleX, ymin * scaleY,
                                xmax * scaleX, ymax * scaleY
                            )
                            val scaledKeypoints = FloatArray(10)
                            for (i in 0 until 10 step 2) {
                                scaledKeypoints[i] = realKeypoints[i] * scaleX
                                scaledKeypoints[i+1] = realKeypoints[i+1] * scaleY
                            }

                            detections.add(FaceDetection(scaledBbox, scaledKeypoints, score))
                        }
                        anchorIndex++
                    }
                }
            }
        }

        // 5. IMPORTANT: Apply Non-Maximum Suppression (NMS) here.
        // Because anchors overlap, SCRFD will detect the exact same face multiple times across different strides.
        // You must run a standard NMS function on the `detections` list using an IoU threshold of ~0.4f
        // to keep only the highest scoring box per face before returning the list.
        val finalDetections = applyNMS(detections, iouThreshold = 0.4f)

        tensor.close()
        result?.close()
        scaledBitmap.recycle()

        return detections
    }

    private fun convertBitmapToNchwBuffer(bitmap: Bitmap): FloatBuffer {
        // ... (Same implementation as OnnxFaceHelper convertBitmapToNchwBuffer) ...
        val floatBuffer = FloatBuffer.allocate(3 * inputSize * inputSize)
        val pixels = IntArray(inputSize * inputSize)
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        val rOffset = 0
        val gOffset = inputSize * inputSize
        val bOffset = 2 * inputSize * inputSize

        for (i in pixels.indices) {
            val color = pixels[i]
            val r = (((color shr 16) and 0xFF) - 127.5f) / 128.0f
            val g = (((color shr 8) and 0xFF) - 127.5f) / 128.0f
            val b = ((color and 0xFF) - 127.5f) / 128.0f
            floatBuffer.put(rOffset + i, r)
            floatBuffer.put(gOffset + i, g)
            floatBuffer.put(bOffset + i, b)
        }
        return floatBuffer
    }

    /**
     * Filters out overlapping bounding boxes, keeping only the ones with the highest confidence scores.
     */
    private fun applyNMS(detections: List<FaceDetection>, iouThreshold: Float = 0.4f): List<FaceDetection> {
        val keptDetections = mutableListOf<FaceDetection>()

        // 1. Sort all detections by confidence score in descending order
        val sortedDetections = detections.sortedByDescending { it.score }.toMutableList()

        while (sortedDetections.isNotEmpty()) {
            // 2. Take the box with the highest score and keep it
            val bestDetection = sortedDetections.removeAt(0)
            keptDetections.add(bestDetection)

            // 3. Compare this best box against all remaining boxes
            val iterator = sortedDetections.iterator()
            while (iterator.hasNext()) {
                val nextDetection = iterator.next()

                // If the overlap (IoU) is higher than the threshold, it's a duplicate. Remove it.
                val iou = calculateIoU(bestDetection.boundingBox, nextDetection.boundingBox)
                if (iou > iouThreshold) {
                    iterator.remove()
                }
            }
        }
        return keptDetections
    }

    /**
     * Calculates the Intersection over Union (IoU) between two bounding boxes.
     * Box format: [xmin, ymin, xmax, ymax]
     */
    private fun calculateIoU(box1: FloatArray, box2: FloatArray): Float {
        // Calculate the coordinates of the intersection rectangle
        val intersectXMin = maxOf(box1[0], box2[0])
        val intersectYMin = maxOf(box1[1], box2[1])
        val intersectXMax = minOf(box1[2], box2[2])
        val intersectYMax = minOf(box1[3], box2[3])

        // Calculate intersection area
        val intersectWidth = maxOf(0f, intersectXMax - intersectXMin)
        val intersectHeight = maxOf(0f, intersectYMax - intersectYMin)
        val intersectArea = intersectWidth * intersectHeight

        // Calculate the area of both bounding boxes
        val box1Area = (box1[2] - box1[0]) * (box1[3] - box1[1])
        val box2Area = (box2[2] - box2[0]) * (box2[3] - box2[1])

        // Calculate Union area
        val unionArea = box1Area + box2Area - intersectArea

        // Return IoU ratio (safeguard against division by zero)
        return if (unionArea > 0f) intersectArea / unionArea else 0f
    }


    fun close() {
        ortSession?.close()
        // Note: Do not close ortEnv here if OnnxFaceHelper is also using it
    }
}