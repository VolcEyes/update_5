package com.example.galleryapp

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult

class FaceLandmarkerHelper(context: Context) {
    private var faceLandmarker: FaceLandmarker? = null

    init {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("face_landmarker.task") // We will add this asset in Step 3
            .build()

        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.IMAGE)
            .setNumFaces(5) // Max faces to detect per image
            .build()

        faceLandmarker = FaceLandmarker.createFromOptions(context, options)
    }

    fun detectFaces(bitmap: Bitmap): FaceLandmarkerResult? {
        val mpImage = BitmapImageBuilder(bitmap).build()
        return faceLandmarker?.detect(mpImage)
    }

    fun clear() {
        faceLandmarker?.close()
        faceLandmarker = null
    }
}