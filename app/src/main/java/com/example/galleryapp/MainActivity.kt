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
import android.util.Log
import android.view.View
import android.widget.ProgressBar
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.LinearLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

import androidx.recyclerview.widget.LinearLayoutManager
import kotlin.math.sqrt



class MainActivity : AppCompatActivity() {

    private lateinit var progressBar: ProgressBar

    // ML Helpers
    private var faceLandmarkerHelper: FaceLandmarkerHelper? = null
    //private var faceNetHelper: FaceNetHelper? = null
    private var onnxFaceHelper: OnnxFaceHelper? = null

    // Database Boxes
    private lateinit var imageBox: Box<ImageEntity>
    private lateinit var faceBox: Box<FaceEntity>

    // UI Elements
    private lateinit var recyclerView: RecyclerView
    private lateinit var imageAdapter: ImageAdapter

    private var scrfdHelper: ScrfdHelper? = null
    private var imageList = ArrayList<Image>()

    // This ensures only 3 images are processed by the ML models at the exact same time
    private val mlMutex = Mutex()

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

        imageBox = GalleryApp.boxStore.boxFor(ImageEntity::class.java)
        faceBox = GalleryApp.boxStore.boxFor(FaceEntity::class.java)

        recyclerView = findViewById(R.id.image_recycler)
        recyclerView.layoutManager = GridLayoutManager(this, 3)
        imageAdapter = ImageAdapter(this, imageList)
        recyclerView.adapter = imageAdapter

        // Initialize ML Helpers in the background to prevent UI freezing
// In MainActivity.kt -> onCreate()

        recyclerView = findViewById(R.id.image_recycler)
        progressBar = findViewById(R.id.recycler_progress) // Find the ProgressBar


        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Log.d("AppDebug", "Starting ML Models...")
                faceLandmarkerHelper = FaceLandmarkerHelper(this@MainActivity)
                //faceNetHelper = FaceNetHelper(this@MainActivity)
                onnxFaceHelper = OnnxFaceHelper(this@MainActivity)
                scrfdHelper= ScrfdHelper(this@MainActivity)
                Log.d("AppDebug", "ML Models Loaded Successfully!")

                withContext(Dispatchers.Main) {
                    checkPermissionsAndLoadImages()
                }
            } catch (e: Exception) {
                // If there is an issue with the asset files or TFLite, it will print here!
                Log.e("AppDebug", "CRITICAL ERROR LOADING ML MODELS", e)
            }
        }
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
        lifecycleScope.launch(Dispatchers.IO) {
            val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_MODIFIED)
            val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"

            // 1. Create a temporary list to hold images
            val temporaryList = ArrayList<Image>()

            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection, null, null, sortOrder
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val dateModifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val dateModified = cursor.getLong(dateModifiedColumn)
                    val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

                    // 2. Add to the temporary list ONLY (no Main Thread calls here)
                    val imageItem = Image(contentUri.toString(), dateModified.toString())
                    temporaryList.add(imageItem)

                    // 3. Keep your ML background processing exactly as it is (with Mutex)
                    launch(Dispatchers.IO) {
                        mlMutex.withLock {
                            processImage(contentUri, dateModified)
                        }
                    }
                }
            }

            // 4. Update the UI exactly ONE time after the loop is completely finished
            withContext(Dispatchers.Main) {
                val startPosition = imageList.size
                imageList.addAll(temporaryList)
                imageAdapter.notifyItemRangeInserted(startPosition, temporaryList.size)
                progressBar.visibility = View.GONE
            }
        }
    }

    private suspend fun processImage(imageUri: Uri, dateModified: Long) {
        val uriString = imageUri.toString()

        // 1. Check if this image was already processed to avoid duplicate work
        val existingImage = imageBox.query()
            .equal(
                ImageEntity_.imageUri,
                uriString,
                io.objectbox.query.QueryBuilder.StringOrder.CASE_INSENSITIVE
            )
            .build()
            .findFirst()

        if (existingImage != null) return

        // 2. Save the base image to ObjectBox
        val imageEntity = ImageEntity(imageUri = uriString, dateModified = dateModified)
        imageBox.put(imageEntity)

        // 3. Load Bitmap safely
        val bitmap = loadBitmapFromUri(imageUri) ?: return

        val originalWidth = bitmap.width
        val originalHeight = bitmap.height

        // --- NEW STEP 4: Scale down for detection instead of tiling ---
        // InsightFace SCRFD works best on smaller images (around 640px).
        // This makes detection incredibly fast and uses less memory.
        val maxDetectSize = 640f
        val scaleFactor = if (originalWidth > originalHeight) {
            maxDetectSize / originalWidth
        } else {
            maxDetectSize / originalHeight
        }

        // Only scale if the original image is actually larger than 640px
        val detectBitmap = if (scaleFactor < 1.0f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (originalWidth * scaleFactor).toInt(),
                (originalHeight * scaleFactor).toInt(),
                true
            )
        } else {
            bitmap
        }

        // We need the inverse scale factor to map SCRFD's coordinates back to the original massive image
        val inverseScaleFactor = if (scaleFactor < 1.0f) 1.0f / scaleFactor else 1.0f

        // 5. Detect faces on the smaller image (No more tiling grid!)
        val detections = scrfdHelper?.detectFaces(detectBitmap) ?: emptyList()

        detections.forEach { detection ->
            // Only process confident faces
            if (detection.score > 0.6f) {
                val keypoints = detection.keypoints

                // 1. Extract all 5 critical keypoints (10 floats) and map them BACK to the original high-res image
                val srcPoints = floatArrayOf(
                    keypoints[0] * inverseScaleFactor, keypoints[1] * inverseScaleFactor, // Left Eye X, Y
                    keypoints[2] * inverseScaleFactor, keypoints[3] * inverseScaleFactor, // Right Eye X, Y
                    keypoints[4] * inverseScaleFactor, keypoints[5] * inverseScaleFactor, // Nose X, Y
                    keypoints[6] * inverseScaleFactor, keypoints[7] * inverseScaleFactor, // Left Mouth Corner X, Y
                    keypoints[8] * inverseScaleFactor, keypoints[9] * inverseScaleFactor  // Right Mouth Corner X, Y
                )

                // Calculate the actual bounding box in the original image for saving to the database
                val pxMinX = (detection.boundingBox[0] * inverseScaleFactor).toInt().coerceAtLeast(0)
                val pxMinY = (detection.boundingBox[1] * inverseScaleFactor).toInt().coerceAtLeast(0)
                val pxMaxX = (detection.boundingBox[2] * inverseScaleFactor).toInt().coerceAtMost(originalWidth)
                val pxMaxY = (detection.boundingBox[3] * inverseScaleFactor).toInt().coerceAtMost(originalHeight)

                // 2. Perform Similarity Transform & Crop directly from the base massive image
                // This uses the updated FaceAligner to output a perfect 112x112 face for Buffalo_S
                val alignedAndCroppedFace = FaceAligner.alignAndCrop(bitmap, srcPoints)

                // 3. Get the embedding vector using Buffalo_S
                val faceVector = onnxFaceHelper?.getFaceVector(alignedAndCroppedFace)

                // Save cropped face to internal storage
                val faceFileName =
                    "face_${System.currentTimeMillis()}_${java.util.UUID.randomUUID()}.jpg"
                val file = java.io.File(applicationContext.filesDir, faceFileName)
                java.io.FileOutputStream(file).use { out ->
                    alignedAndCroppedFace.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }

                // Save to database
                val faceEntity = FaceEntity(
                    faceVector = faceVector,
                    boundingBox = "$pxMinX,$pxMinY,$pxMaxX,$pxMaxY",
                    faceImagePath = file.absolutePath
                )
                faceEntity.image.target = imageEntity
                faceBox.put(faceEntity)

                // FREE MEMORY
                alignedAndCroppedFace.recycle()
            }
        }

        // FREE MEMORY
        // If we created a separate scaled bitmap, recycle it
        if (detectBitmap !== bitmap) {
            detectBitmap.recycle()
        }

        // Destroy the large base image at the very end
        bitmap.recycle()
    }

    private fun loadBitmapFromUri(uri: Uri): Bitmap? {
        return try {
            // First decode just the bounds to calculate the sample size
            val options = BitmapFactory.Options()
            options.inJustDecodeBounds = true
            contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, options)
            }

            // Target maximum dimensions (e.g., 1024x1024 is usually enough for face detection)
            val reqWidth = 3072
            val reqHeight = 3072
            var inSampleSize = 1

            if (options.outHeight > reqHeight || options.outWidth > reqWidth) {
                val halfHeight: Int = options.outHeight / 2
                val halfWidth: Int = options.outWidth / 2
                while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                    inSampleSize *= 2
                }
            }

            // Now decode the actual bitmap using the calculated sample size
            options.inJustDecodeBounds = false
            options.inSampleSize = inSampleSize
            options.inPreferredConfig = Bitmap.Config.ARGB_8888

            var bitmap = contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, options)
            }

            // 2. Android can sometimes ignore inPreferredConfig.
            // If the config is still wrong or null (like HARDWARE), we MUST convert it safely.
            if (bitmap != null && bitmap.config != Bitmap.Config.ARGB_8888) {
                val convertedBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                bitmap.recycle() // Recycle the old incorrect format to prevent memory leaks
                bitmap = convertedBitmap
            }

            return bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up ML resources to prevent memory leaks when the activity dies
        faceLandmarkerHelper?.clear()
        //faceNetHelper?.close()
        onnxFaceHelper?.close()
    }

    //SEARCH MENU

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    // 2. Listens for clicks on the Menu Item
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_search -> {
                showSearchBottomSheet() // Call our function when clicked
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // 3. Displays the 2/3 screen
    private fun showSearchBottomSheet() {
        val bottomSheetDialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_search, null)

        val facesRecyclerView = view.findViewById<RecyclerView>(R.id.recycler_faces)
        val btnApply = view.findViewById<Button>(R.id.btn_apply)

        // Set the layout to scroll horizontally
// The default LinearLayoutManager is vertical
// Scroll vertically in a grid with 4 columns
        facesRecyclerView.layoutManager = GridLayoutManager(this, 4)
        // 1. Get all faces from the database
        val allFaces = faceBox.all

        // 2. Filter for unique faces (Deduplication)
        val uniqueFaces = ArrayList<FaceEntity>()
        val similarityThreshold = 0.45f // Faces with similarity above this are considered the same person

        for (face in allFaces) {
            val currentVector = face.faceVector ?: continue
            var isUnique = true

            // Compare against already approved unique faces
            for (approvedFace in uniqueFaces) {
                val approvedVector = approvedFace.faceVector ?: continue
                if (calculateCosineSimilarity(currentVector, approvedVector) > similarityThreshold) {
                    isUnique = false // It's a duplicate of someone we already have
                    break
                }
            }
            if (isUnique) {
                uniqueFaces.add(face)
            }
        }

        // 3. Setup the Adapter
        var chosenFace: FaceEntity? = null
        val faceAdapter = FaceAdapter(this, uniqueFaces) { selectedFace ->
            chosenFace = selectedFace // Save the face the user clicked
        }
        facesRecyclerView.adapter = faceAdapter

        // 4. Apply button logic
// 4. Apply button logic
        // 4. Apply button logic (Centroid/Clustering Approach)
        btnApply.setOnClickListener {
            // Assume chosenPersonEntity is the Person the user tapped in the BottomSheet
            val selectedPerson = PersonEntity_
            val photosOfPerson = selectedPerson.faces.map { it.image.target }
            if (selectedPerson != null) {
                // 1. Fetch all photos assigned to this static Person ID directly
                // (No math, no vector comparisons, no nearest neighbors needed!)
                val photosOfPerson = selectedPerson.faces.mapNotNull { it.image.target }

                val filteredImages = photosOfPerson.map { entity ->
                    Image(entity.imageUri, entity.dateModified.toString())
                }

                // 2. Update UI
                imageList.clear()
                imageList.addAll(filteredImages)
                imageAdapter.notifyDataSetChanged()

                Toast.makeText(this@MainActivity, "Found ${filteredImages.size} photos of this person", Toast.LENGTH_SHORT).show()
                bottomSheetDialog.dismiss()
            } else {
                Toast.makeText(this@MainActivity, "Please select a person first.", Toast.LENGTH_SHORT).show()
            }

            imageList.clear()
            imageList.addAll(photosOfPerson)
            imageAdapter.notifyDataSetChanged()
        }

        // 5. Show the Dialog (2/3 height)
        val screenHeight = resources.displayMetrics.heightPixels
        val twoThirdsHeight = (screenHeight * 0.66).toInt()
        view.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, twoThirdsHeight)

        bottomSheetDialog.setContentView(view)
        val bottomSheetBehavior = BottomSheetBehavior.from(view.parent as View)
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
        bottomSheetBehavior.skipCollapsed = true

        bottomSheetDialog.show()
    }

    // Conceptual logic for your background job
    fun runFacialRecognitionBatchJob() {
        val allUnassignedFaces = faceBox.query().isNull(FaceEntity_.personId).build().find()

        // 1. Run DBSCAN (Graphing, Core Points, Chain Linking)
        val clusterer = FaceClusterer(eps = 0.45f, minPts = 2)
        val clusters = clusterer.cluster(allUnassignedFaces)

        // 2. Save Clusters to Database (Static Display Preparation)
        for (cluster in clusters) {
            // Create a new master "Person" for this chain of faces
            val person = PersonEntity(coverFaceImagePath = cluster.first().faceImagePath)
            val personId = personBox.put(person)

            // Assign every face in this DBSCAN chain to this exact Person ID
            for (face in cluster) {
                face.person.target = person
                faceBox.put(face)
            }
        }
    }

    // Helper math function to calculate if two faces belong to the same person
    private fun calculateCosineSimilarity(v1: FloatArray, v2: FloatArray): Float {
        var dotProduct = 0.0f
        var normA = 0.0f
        var normB = 0.0f
        for (i in v1.indices) {
            dotProduct += v1[i] * v2[i]
            normA += v1[i] * v1[i]
            normB += v2[i] * v2[i]
        }
        return if (normA == 0.0f || normB == 0.0f) 0.0f else (dotProduct / (sqrt(normA) * sqrt(normB)))
    }
}