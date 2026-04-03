package com.example.galleryapp

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.objectbox.Box
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    // ML Helpers
    private lateinit var faceLandmarkerHelper: FaceLandmarkerHelper
    private lateinit var faceNetHelper: FaceNetHelper

    // Database Boxes
    private lateinit var imageBox: Box<ImageEntity>
    private lateinit var faceBox: Box<FaceEntity>

    // UI Elements
    private lateinit var recyclerView: RecyclerView
    private lateinit var imageAdapter: ImageAdapter
    private var imageList = ArrayList<Image>()

    // Permission Launcher for Gallery Access
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            loadGalleryImages()
        } else {
            Toast.makeText(this, "Permission denied to read images", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. Initialize DB boxes
        imageBox = GalleryApp.boxStore.boxFor(ImageEntity::class.java)
        faceBox = GalleryApp.boxStore.boxFor(FaceEntity::class.java)

        // 2. Initialize ML Helpers
        faceLandmarkerHelper = FaceLandmarkerHelper(this)
        faceNetHelper = FaceNetHelper(this)

        // 3. Setup UI (Ensure these match your actual layout IDs and Adapter parameters)
        recyclerView = findViewById(R.id.image_recycler)
        recyclerView.layoutManager = GridLayoutManager(this, 3)
        imageAdapter = ImageAdapter(this, imageList)
        recyclerView.adapter = imageAdapter

        // 4. Check Permissions & Load Images
        checkPermissionsAndLoadImages()
    }

    private fun checkPermissionsAndLoadImages() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            loadGalleryImages()
        } else {
            requestPermissionLauncher.launch(permission)
        }
    }

    private fun loadGalleryImages() {
        // Launch a coroutine to fetch and process images off the main thread
        lifecycleScope.launch(Dispatchers.IO) {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_MODIFIED
            )
            val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"

            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val dateModifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val dateModified = cursor.getLong(dateModifiedColumn)
                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id
                    )

                    // Adjust the Image object instantiation based on your exact Image.kt class
                    val imageItem = Image(contentUri.toString(), dateModified.toString())

                    // Update UI on the main thread
                    withContext(Dispatchers.Main) {
                        imageList.add(imageItem)
                        imageAdapter.notifyItemInserted(imageList.size - 1)
                    }

                    // Process image for faces in the background
                    processImage(contentUri, dateModified)
                }
            }
        }
    }

    private suspend fun processImage(imageUri: Uri, dateModified: Long) {
        val uriString = imageUri.toString()

        // 1. Check if this image was already processed to avoid duplicate work
        // (ImageEntity_ is auto-generated by ObjectBox after you build the project)
        val existingImage = imageBox.query()
            .equal(ImageEntity_.imageUri, uriString, io.objectbox.query.QueryBuilder.StringOrder.CASE_INSENSITIVE)
            .build()
            .findFirst()

        if (existingImage != null) return

        // 2. Save the base image to ObjectBox
        val imageEntity = ImageEntity(imageUri = uriString, dateModified = dateModified)
        imageBox.put(imageEntity)

        // 3. Load Bitmap safely
        val bitmap = loadBitmapFromUri(imageUri) ?: return

        // 4. Detect faces using MediaPipe
        val result = faceLandmarkerHelper.detectFaces(bitmap)

        // 5. Crop each detected face and vectorize it
        result?.faceLandmarks()?.forEach { landmarks ->
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxX = Float.MIN_VALUE
            var maxY = Float.MIN_VALUE

            // Extract the tightest bounding box from the facial landmarks
            landmarks.forEach { landmark ->
                if (landmark.x() < minX) minX = landmark.x()
                if (landmark.y() < minY) minY = landmark.y()
                if (landmark.x() > maxX) maxX = landmark.x()
                if (landmark.y() > maxY) maxY = landmark.y()
            }

            val width = bitmap.width
            val height = bitmap.height

            // Convert normalized coordinates [0.0, 1.0] to pixel coordinates
            val pxMinX = (minX * width).toInt().coerceAtLeast(0)
            val pxMinY = (minY * height).toInt().coerceAtLeast(0)
            val pxMaxX = (maxX * width).toInt().coerceAtMost(width)
            val pxMaxY = (maxY * height).toInt().coerceAtMost(height)

            val boxWidth = pxMaxX - pxMinX
            val boxHeight = pxMaxY - pxMinY

            if (boxWidth > 0 && boxHeight > 0) {
                // Crop out the face
                val croppedFace = Bitmap.createBitmap(bitmap, pxMinX, pxMinY, boxWidth, boxHeight)

                // Extract 192-dimensional vector using MobileFaceNet
                val faceVector = faceNetHelper.getFaceVector(croppedFace)

                // Create FaceEntity and establish the ToOne relationship
                val faceEntity = FaceEntity(
                    faceVector = faceVector,
                    boundingBox = "$pxMinX,$pxMinY,$pxMaxX,$pxMaxY"
                )
                faceEntity.image.target = imageEntity

                // Save relation to ObjectBox database
                faceBox.put(faceEntity)
            }
        }
    }

    private fun loadBitmapFromUri(uri: Uri): Bitmap? {
        return try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up ML resources to prevent memory leaks when the activity dies
        faceLandmarkerHelper.clear()
        faceNetHelper.close()
    }
}