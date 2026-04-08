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
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

import androidx.recyclerview.widget.LinearLayoutManager
import kotlin.math.sqrt

import android.content.Context
import android.graphics.ImageDecoder
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


class MainActivity : AppCompatActivity() {

    private lateinit var progressBar: ProgressBar

    // ML Helpers
    private var faceLandmarkerHelper: FaceLandmarkerHelper? = null
    //private var faceNetHelper: FaceNetHelper? = null
    private var faceNetHelper: OnnxFaceHelper? = null

    // Database Boxes
    private lateinit var imageBox: Box<ImageEntity>
    private lateinit var faceBox: Box<FaceEntity>

    private lateinit var personBox: Box<PersonEntity> // Add this line
    // UI Elements
    private lateinit var recyclerView: RecyclerView
    private lateinit var imageAdapter: ImageAdapter

    private var scrfdHelper: ScrfdHelper? = null
    private var imageList = ArrayList<Image>()

    // This ensures only 3 images are processed by the ML models at the exact same time
    private val mlMutex = Mutex()

    // Add these with your other UI Elements
    private lateinit var progressContainer: LinearLayout
    private lateinit var tvProgressText: TextView
    private lateinit var progressBarHorizontal: ProgressBar

    // Safety flag
    private var isProcessing = false

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

        progressContainer = findViewById(R.id.progress_container)
        tvProgressText = findViewById(R.id.tv_progress_text)
        progressBarHorizontal = findViewById(R.id.progress_bar_horizontal)

        imageBox = GalleryApp.boxStore.boxFor(ImageEntity::class.java)
        faceBox = GalleryApp.boxStore.boxFor(FaceEntity::class.java)
        personBox = GalleryApp.boxStore.boxFor(PersonEntity::class.java) // Add this line

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
                faceNetHelper = OnnxFaceHelper(this@MainActivity)
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
        val maxDetectSize = 1024f
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
            if (detection.score > 0.53f) {
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
                val faceVector = faceNetHelper?.getFaceVector(alignedAndCroppedFace)

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
        faceNetHelper?.close()
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
                showSearchBottomSheet()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // 3. Displays the 2/3 screen
    private fun showSearchBottomSheet() {
        val bottomSheetDialog = BottomSheetDialog(this)
        // Inflate the bottom sheet layout
        val view = layoutInflater.inflate(R.layout.bottom_sheet_search, null)
        bottomSheetDialog.setContentView(view)
        val btnScanNewImages = view.findViewById<Button>(R.id.btnScanNewImagesBottomSheet)

        val facesRecyclerView = view.findViewById<RecyclerView>(R.id.recycler_faces)
        val btnApply = view.findViewById<Button>(R.id.btn_apply)

        // Add this right after you initialize facesRecyclerView and btnApply
        val btnProcessImages = view.findViewById<Button>(R.id.btn_process_images)
        val btnViewTotalFaces = view.findViewById<Button>(R.id.btn_view_total_faces) // NEW BUTTON

        btnProcessImages.setOnClickListener {
            startImageProcessing()
            bottomSheetDialog.dismiss() // Close the sheet so they can see the progress bar!
        }

        facesRecyclerView.layoutManager = GridLayoutManager(this, 4)

        // --- NEW SIMPLIFIED LOGIC ---

        // 1. Grab all successfully clustered people from the database
        val allPersons = personBox.all
        val uniqueFacesToDisplay = ArrayList<FaceEntity>()

        // 2. Extract exactly ONE cover face for each unique person to show in the grid
        for (person in allPersons) {
            val uniqueImageCount = person.faces.map { it.image.targetId }.distinct().size

            if (uniqueImageCount >= 3) {
                var coverFace = person.faces.find { it.faceImagePath == person.coverFaceImagePath }
                if (coverFace != null) {
                    uniqueFacesToDisplay.add(coverFace)
                }
            }
        }

        // 4. Setup the Adapter
        var chosenFace: FaceEntity? = null
        val faceAdapter = FaceAdapter(this, uniqueFacesToDisplay) { selectedFace ->
            chosenFace = selectedFace // Save the face the user clicked
        }
        facesRecyclerView.adapter = faceAdapter

        btnViewTotalFaces.setOnClickListener {
            val personId = chosenFace?.person?.targetId
            if (personId != null) {
                bottomSheetDialog.dismiss()
                showPersonFacesBottomSheet(personId)
            }
        }

        // ... Keep your btnApply.setOnClickListener code exactly as it is right here ...
        // 4. Apply button logic
        btnApply.setOnClickListener {
            // 1. Did the user actually tap a face? If not, stop here.
            if (chosenFace == null) {
                Toast.makeText(this@MainActivity, "Please tap a face first.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 2. Check if this face was successfully grouped into a "Person" by DBSCAN
            val cachedPerson = chosenFace?.person?.target

            if (cachedPerson != null) {
                // SCENARIO A: The face belongs to a clustered Person

                // --- FIX: RE-FETCH FROM DB TO BYPASS CACHE ---
                val freshPerson = personBox.get(cachedPerson.id)

                if (freshPerson != null) {
                    // Use freshPerson instead of the cached selectedPerson
                    // ADDED .distinctBy { it.imageUri } HERE to remove duplicates
                    val photosOfPerson = freshPerson.faces
                        .mapNotNull { it.image.target }
                        .distinctBy { it.imageUri }

                    val filteredImages = photosOfPerson.map { entity ->
                        Image(entity.imageUri, entity.dateModified.toString())
                    }

                    imageList.clear()
                    imageList.addAll(filteredImages)
                    imageAdapter.notifyDataSetChanged()

                    Toast.makeText(this@MainActivity, "Found ${filteredImages.size} photos of this person", Toast.LENGTH_SHORT).show()
                    bottomSheetDialog.dismiss()
                } else {
                    Toast.makeText(this@MainActivity, "Error: Could not load person details.", Toast.LENGTH_SHORT).show()
                }

            } else {
                // SCENARIO B: The face is unassigned (Clustering skipped it)
                // Fallback: Just display the single original image that this face belongs to!
                val singleImageEntity = chosenFace?.image?.target

                if (singleImageEntity != null) {
                    val singleImage = Image(singleImageEntity.imageUri, singleImageEntity.dateModified.toString())

                    imageList.clear()
                    imageList.add(singleImage)
                    imageAdapter.notifyDataSetChanged()

                    Toast.makeText(this@MainActivity, "Showing 1 unclustered photo", Toast.LENGTH_SHORT).show()
                    bottomSheetDialog.dismiss()
                } else {
                    Toast.makeText(this@MainActivity, "Error: Could not load the image.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // 5. Show the Dialog (2/3 height)
        val screenHeight = resources.displayMetrics.heightPixels
        val twoThirdsHeight = (screenHeight * 0.66).toInt()
        view.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, twoThirdsHeight)

        bottomSheetDialog.setContentView(view)
        val bottomSheetBehavior = BottomSheetBehavior.from(view.parent as View)
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
        bottomSheetBehavior.skipCollapsed = true

        btnScanNewImages?.setOnClickListener {
            // Ensure you have requested READ_MEDIA_IMAGES (Android 13+)
            // or READ_EXTERNAL_STORAGE (Android 12-) permissions before calling this!
            Toast.makeText(this, "Scanning for new images in background...", Toast.LENGTH_SHORT).show()

            scanNewlyAddedImages(this)

            // Optional: Close the bottom sheet automatically after clicking
            // bottomSheetDialog.dismiss()
        }

        bottomSheetDialog.show()
    }

    private fun startImageProcessing() {
        if (isProcessing) {
            Toast.makeText(this, "Already processing...", Toast.LENGTH_SHORT).show()
            return
        }

        isProcessing = true
        progressContainer.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            val totalImages = imageList.size
            var processedCount = 0

            // 1. Setup the initial progress bar state
            withContext(Dispatchers.Main) {
                progressBarHorizontal.max = totalImages
                progressBarHorizontal.progress = 0
                tvProgressText.text = "Processed 0 / $totalImages images"
            }

            // 2. Process every image one by one
            for (image in imageList) {
                // Note: Ensure '.imageUri' and '.dateModified' match the property names in your Image.kt data class
                val uri = Uri.parse(image.imagePath)
                val dateMod = image.imageTitle.toLongOrNull() ?: 0L

                mlMutex.withLock {
                    processImage(uri, dateMod)
                }

                processedCount++

                // 3. Update the UI as it progresses
                withContext(Dispatchers.Main) {
                    progressBarHorizontal.progress = processedCount
                    tvProgressText.text = "Processed $processedCount / $totalImages images"
                }
            }

            // 4. Once all images are scanned, run the clustering algorithm automatically!
            withContext(Dispatchers.Main) {
                tvProgressText.text = "Clustering faces together..."
            }

            runFacialRecognitionBatchJob()

            // 5. Processing is completely finished
            withContext(Dispatchers.Main) {
                isProcessing = false
                Toast.makeText(this@MainActivity, "Processing is Complete!", Toast.LENGTH_LONG).show()

                // Hide the progress bar after 3 seconds
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    progressContainer.visibility = View.GONE
                }, 3000)
            }
        }
    }

    private fun showPersonFacesBottomSheet(personId: Long) {
        val bottomSheetDialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_person_faces, null)
        bottomSheetDialog.setContentView(view)

        val recyclerView = view.findViewById<RecyclerView>(R.id.recycler_all_faces)
        val btnSetPreview = view.findViewById<Button>(R.id.btn_set_preview)

        recyclerView.layoutManager = GridLayoutManager(this, 4)

        // 1. Fetch the person and all their grouped faces
// 1. Fetch the person and all their grouped faces
        val person = personBox.get(personId) ?: return

        // FIX: Filter out the duplicate bounding boxes.
        // This ensures only ONE face crop is shown per unique photograph.
        val allFacesOfPerson = person.faces.distinctBy { it.image.targetId }

        // 2. Setup the adapter (reusing your existing FaceAdapter)
        var selectedPreviewFace: FaceEntity? = null
        val faceAdapter = FaceAdapter(this, allFacesOfPerson) { selectedFace ->
            selectedPreviewFace = selectedFace
            btnSetPreview.isEnabled = true // <-- Change .enabled to .isEnabled
        }
        recyclerView.adapter = faceAdapter

        // 3. Handle saving the new cover photo
        btnSetPreview.setOnClickListener {
            if (selectedPreviewFace != null) {
                // 1. Save to database
                setCustomCoverFaceForPerson(personId, selectedPreviewFace!!.faceImagePath)

                // 2. Close the second sheet
                //bottomSheetDialog.dismiss()///

                // 3. Re-open the first sheet to refresh the UI with the new preview
                showSearchBottomSheet()
            }
        }
// ... [Adapter setup code above] ...

        // 1. Set content view FIRST
        bottomSheetDialog.setContentView(view)

        // 2. Safely find the internal BottomSheet FrameLayout
        val bottomSheet = bottomSheetDialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)

        if (bottomSheet != null) {
            val behavior = BottomSheetBehavior.from(bottomSheet)
            val screenHeight = resources.displayMetrics.heightPixels
            val twoThirdsHeight = (screenHeight * 0.66).toInt()

            // 3. Apply the height to the FrameLayout correctly
            val layoutParams = bottomSheet.layoutParams
            layoutParams.height = twoThirdsHeight
            bottomSheet.layoutParams = layoutParams

            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
        }

        bottomSheetDialog.show()

    }

    fun setCustomCoverFaceForPerson(personId: Long, chosenFacePath: String) {
        // Fetch the fresh Person entity from ObjectBox
        val person = personBox.get(personId)

        if (person != null) {
            // Update the string path to point to the user's favorite face crop
            person.coverFaceImagePath = chosenFacePath

            // Save it back to the database
            personBox.put(person)

            Toast.makeText(this, "Preview photo updated successfully!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun scanNewlyAddedImages(context: Context) {
        lifecycleScope.launch(Dispatchers.IO) {
            val prefs = context.getSharedPreferences("GalleryPrefs", Context.MODE_PRIVATE)
            // Retrieve the timestamp of the last scan (0L if it's the first time)
            val lastScanTime = prefs.getLong("last_scan_time", 0L)
            var maxScannedTime = lastScanTime

            val newFacesToCluster = mutableListOf<FaceEntity>()

            // 1. Query MediaStore for images added strictly AFTER lastScanTime
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_ADDED
            )
            val selection = "${MediaStore.Images.Media.DATE_ADDED} > ?"
            val selectionArgs = arrayOf((lastScanTime / 1000).toString()) // MediaStore date is in seconds
            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} ASC"

            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val dateAdded = cursor.getLong(dateAddedColumn) * 1000 // Convert back to milliseconds

                    val contentUri = Uri.withAppendedPath(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id.toString()
                    )

                    if (dateAdded > maxScannedTime) {
                        maxScannedTime = dateAdded
                    }

                    // 2. Load the image bitmap
                    val bitmap = loadBitmapFromUri(context, contentUri) ?: continue

                    // 3. Save the base ImageEntity to ObjectBox
                    val imageEntity = ImageEntity(imageUri = contentUri.toString(), dateModified = dateAdded)
                    imageBox.put(imageEntity)

                    // 4. Run Detection (SCRFD)
                    val detections = scrfdHelper?.detectFaces(bitmap)

                    if (detections != null) {
                        for (detection in detections) {
                            // 5. Align & Crop
                            val alignedFace = FaceAligner.alignAndCrop(bitmap, detection.keypoints)

                            // 6. Extract Vector (FaceNet)
                            val faceVector = faceNetHelper?.getFaceVector(alignedFace) ?: continue

                            // 7. Create and save FaceEntity to DB
                            val faceEntity = FaceEntity(
                                faceVector = faceVector,
                                boundingBox = detection.boundingBox.joinToString(",")
                            )
                            faceEntity.image.target = imageEntity

                            faceBox.put(faceEntity) // Save so it gets a real ID
                            newFacesToCluster.add(faceEntity)
                        }
                    }
                }
            }

            // 8. Trigger Incremental DBSCAN if we extracted new faces
            if (newFacesToCluster.isNotEmpty()) {
                // Initialize the clusterer locally on the background thread
                val clusterer = FaceClusterer(faceBox, personBox, eps = 0.62f, minPts = 2)

                clusterer.enqueueFacesForClustering(newFacesToCluster)
                clusterer.processClusteringQueue()
            }

            // 9. Save the newest timestamp so we skip these images next time
            prefs.edit().putLong("last_scan_time", maxScannedTime).apply()

            // 10. Notify UI that the background job is done
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Scan Complete! Found and clustered ${newFacesToCluster.size} faces.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Helper to safely load Bitmaps across different Android versions
    private fun loadBitmapFromUri(context: Context, uri: Uri): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // Prevents hardware bitmap crashes with ONNX
                }
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // Conceptual logic for your background job
    fun runFacialRecognitionBatchJob() {
        // FIX: Look for 0 (unassigned), not null!
        val allUnassignedFaces = faceBox.query().equal(FaceEntity_.personId, 0L).build().find()

        Log.d("ClusterDebug", "Started Job: Found ${allUnassignedFaces.size} faces to cluster")

        if (allUnassignedFaces.isEmpty()) {
            Log.d("ClusterDebug", "No new faces to process.")
            return
        }

        // Using 0.60f to allow a slightly wider margin for error in matching
// 1. Initialize with your ObjectBox instances
        val clusterer = FaceClusterer(
            faceBox = faceBox,
            personBox = personBox,
            eps = 0.62f,
            minPts = 2
        )

// 2. Add your extracted faces to the queue
        clusterer.enqueueFacesForClustering(allUnassignedFaces)

// 3. Run the incremental DBSCAN
// This handles EVERYTHING: finding neighbors, making new PersonEntities,
// setting thumbnails, and saving to the database.
        clusterer.processClusteringQueue()

        Log.d("ClusterDebug", "Incremental DBSCAN finished processing queue.")
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