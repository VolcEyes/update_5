package com.example.galleryapp  // Το πακέτο της εφαρμογής, οργανώνει τον κώδικα

import android.Manifest  // Εισαγωγή για δικαιώματα πρόσβασης
import android.content.ContentUris  // Εισαγωγή για δημιουργία URI περιεχομένου
import android.content.pm.PackageManager  // Εισαγωγή για διαχείριση πακέτων και δικαιωμάτων
import android.media.MediaScannerConnection  // Εισαγωγή για σάρωση μέσων
import android.net.Uri  // Εισαγωγή για χειρισμό URI
import android.os.Build  // Εισαγωγή για πληροφορίες έκδοσης Android
import android.os.Bundle  // Εισαγωγή για δέσμες δεδομένων
import android.os.Environment  // Εισαγωγή για περιβάλλον αποθήκευσης
import android.provider.MediaStore  // Εισαγωγή για πρόσβαση σε MediaStore
import android.util.Log  // Εισαγωγή για καταγραφή μηνυμάτων
import android.view.View  // Εισαγωγή για προβολές UI
import android.widget.ProgressBar  // Εισαγωγή για μπάρα προόδου
import android.widget.Toast  // Εισαγωγή για εμφάνιση μηνυμάτων toast
import androidx.activity.enableEdgeToEdge  // Εισαγωγή για ενεργοποίηση edge-to-edge
import androidx.appcompat.app.AppCompatActivity  // Εισαγωγή για βασική δραστηριότητα
import androidx.core.app.ActivityCompat  // Εισαγωγή για αιτήματα δικαιωμάτων
import androidx.core.content.ContextCompat  // Εισαγωγή για συμβατότητα περιβάλλοντος
import androidx.core.view.ViewCompat  // Εισαγωγή για συμβατότητα προβολών
import androidx.core.view.WindowInsetsCompat  // Εισαγωγή για insets παραθύρου
import androidx.recyclerview.widget.GridLayoutManager  // Εισαγωγή για διαχείριση πλέγματος RecyclerView
import androidx.recyclerview.widget.RecyclerView  // Εισαγωγή για RecyclerView
import android.view.Menu
import androidx.appcompat.widget.SearchView
import android.content.Context
import android.view.MenuItem
import android.view.inputmethod.InputMethodManager
import android.view.ViewTreeObserver
import androidx.core.view.WindowInsetsControllerCompat
import android.widget.EditText
import androidx.lifecycle.lifecycleScope
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.mlkit.common.model.LocalModel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.custom.CustomObjectDetectorOptions
import android.database.Cursor
import androidx.cursoradapter.widget.SimpleCursorAdapter

import androidx.appcompat.widget.SearchView.OnSuggestionListener
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay


/*
Η κλάση MainActivity είναι η κύρια δραστηριότητα της εφαρμογής gallery.
Διαχειρίζεται την εμφάνιση εικόνων που είναι αποθηκευμένες στη συσκευή στο RecyclerView,
ζητά δικαιώματα πρόσβασης και φορτώνει τις εικόνες.
*/
class MainActivity : AppCompatActivity() {  // Κύρια κλάση δραστηριότητας που επεκτείνει AppCompatActivity

    private var imageRecycler: RecyclerView? = null  // Μεταβλητή για το RecyclerView που εμφανίζει τις εικόνες
    private var progressBar: ProgressBar? = null  // Μεταβλητή για την μπάρα προόδου
    private var allPictures: ArrayList<Image>? = null  // Λίστα με όλες τις εικόνες
    private var imageAdapter: ImageAdapter? = null   // NEW

    private var objectDetector: com.google.mlkit.vision.objects.ObjectDetector? = null

    private var suggestionJob: Job? = null

    private var suggestionsAdapter: androidx.cursoradapter.widget.SimpleCursorAdapter? = null


    // === SYNONYMS (plate ↔ dish etc.) ===
    private val synonymMap: Map<String, List<String>> = mapOf(
        "plate" to listOf("plate", "dish"),
        "dish" to listOf("dish", "plate"),
        // ← ADD MORE HERE (example):
        // "car" to listOf("car", "vehicle", "automobile"),
        // "person" to listOf("person", "human")
    )

    private fun expandWithSynonyms(baseTerms: List<String>): List<String> {
        val expanded = mutableSetOf<String>()
        for (term in baseTerms) {
            expanded.add(term)
            synonymMap[term]?.let { expanded.addAll(it) }
        }
        return expanded.toList()
    }

    private lateinit var galleryDao: GalleryDao

    /*
    Η μέθοδος onCreate καλείται κατά τη δημιουργία της δραστηριότητας.
    Ρυθμίζει το UI, αρχικοποιεί τα στοιχεία και ελέγχει δικαιώματα.
    */
    override fun onCreate(savedInstanceState: Bundle?) {  // Υπερκάλυψη της onCreate
        super.onCreate(savedInstanceState)  // Κλήση του function της υπερκλάσης AppCompatActivity και προσθήκη δικών μου λειτουργιών
        enableEdgeToEdge()  // Εμφάνιση των περιεχομένω σε όλο το μήκος της οθόνης
        setContentView(R.layout.activity_main)  // Ορισμός του layout

        val db = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java,
            "gallery.db"
        )
            .fallbackToDestructiveMigration()   // ← THIS IS THE FIX
            .build()
        galleryDao = db.galleryDao()


        initializeObjectDetector()

        imageRecycler = findViewById(R.id.image_recycler)  // ID για εύρεση του RecyclerView
        progressBar = findViewById(R.id.recycler_progress)  // ID για εύρεση της μπάρας προόδου

        imageRecycler?.layoutManager = GridLayoutManager(this, 3)  // Ρύθμιση του recycler ώστε να έχει 3 στήλες
        imageRecycler?.setHasFixedSize(true)  // Ορισμός σταθερού μεγέθους για βελτίωση απόδοσης

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val appliedInsets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            v.setPadding(
                appliedInsets.left,
                appliedInsets.top,
                appliedInsets.right,
                appliedInsets.bottom
            )
            insets
        }

        checkAndRequestPermissions()  // Κλήση ελέγχου δικαιωμάτων
    }

    /*
    Η μέθοδος checkAndRequestPermissions ελέγχει και ζητά δικαιώματα πρόσβασης σε εικόνες.
    Ανάλογα με την έκδοση Android, χρησιμοποιεί διαφορετικά δικαιώματα.
    */
    private fun checkAndRequestPermissions() {  // Ιδιωτική μέθοδος ελέγχου δικαιωμάτων
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {  // Έλεγχος έκδοσης Android
            Manifest.permission.READ_MEDIA_IMAGES  // Δικαίωμα για Android 13+
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE  // Δικαίωμα για παλαιότερες εκδόσεις
        }

        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {  // Έλεγχος αν δίνεται δικαίωμα
            ActivityCompat.requestPermissions(this, arrayOf(permission), 101)  // Αίτημα δικαιώματος
        } else {
            triggerScanAndLoad()  // Κλήση σάρωσης και φόρτωσης
        }
    }

    /*
    Η μέθοδος onRequestPermissionsResult χειρίζεται το αποτέλεσμα αιτήματος δικαιωμάτων.
    Αν δοθεί δικαίωμα, προχωρά στη σάρωση, αλλιώς εμφανίζει μήνυμα.
    */
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {  // Υπερκάλυψη χειρισμού αποτελεσμάτων δικαιωμάτων
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)  // Κλήση του function της υπερκλάσης AppCompatActivity και προσθήκη δικών μου λειτουργιών
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {  // Έλεγχος αν δόθηκε δικαίωμα
            triggerScanAndLoad()  // Κλήση σάρωσης και φόρτωσης
        } else {
            Toast.makeText(this, "Permission Denied", Toast.LENGTH_SHORT).show()  // Εμφάνιση μηνύματος άρνησης δικαιωμάτων
        }
    }

    /*
    Η μέθοδος triggerScanAndLoad ενεργοποιεί σάρωση μέσων και φόρτωση εικόνων.
    Εμφανίζει πρόοδο σάρωσης και ενημερώνει το UI μετά τη σάρωση.
    */
    private fun triggerScanAndLoad() {  // Ιδιωτική μέθοδος σάρωσης και φόρτωσης
        progressBar?.visibility = View.VISIBLE  // Εμφάνιση μπάρας προόδου

        val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)  // Λήψη καταλόγου εικόνων

        Log.d("GalleryApp", "Scanning directory: ${picturesDir.absolutePath}")  // Καταγραφή καταλόγου

        MediaScannerConnection.scanFile(this, arrayOf(picturesDir.absolutePath), null) { path, uri ->  // Σάρωση αρχείων
            Log.d("GalleryApp", "Scan finished for: $path -> $uri")  // Μήνυμα που εμφανίζεται στο LogCat για έλεγχο ροής κώδικα/σφαλμάτων κλπ.

            runOnUiThread {  // Εκτέλεση στο UI Thread (H εφαρμογή κάνει απλές και απαραίτητες λειτουργίες σε αυτό το Thread, που χρησιμοποιείτε μόνο για UI)
                allPictures = getAllImages()  // Λήψη όλων των εικόνων
                Log.d("GalleryApp", "Εικόνες που βρέθηκαν μετά το σκανάρισμα: ${allPictures?.size}")  // Καταγραφή αριθμού εικόνων

                if (!allPictures.isNullOrEmpty()) {  // Έλεγχος αν υπάρχουν εικόνες
                    imageAdapter = ImageAdapter(this@MainActivity, allPictures!!)
                    imageRecycler?.adapter = imageAdapter  // Updated
                } else {
                    Toast.makeText(this@MainActivity, "Δεν βρέθηκαν εικόνες στο MediaStore", Toast.LENGTH_LONG).show()  // Μήνυμα μη εύρεσης εικόνων
                }
                progressBar?.visibility = View.GONE  // Απόκρυψη μπάρας προόδου
                startObjectIndexing()
            }
        }
    }

    /*
    Η μέθοδος getAllImages επιστρέφει λίστα με όλες τις εικόνες από MediaStore.
    Χρησιμοποιεί query για ανάκτηση δεδομένων εικόνων.
    */
    private fun getAllImages(): ArrayList<Image> {
        val images = ArrayList<Image>()

        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED
        )
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        contentResolver.query(uri, projection, null, null, sortOrder)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn) ?: "Untitled"
                val contentUri = ContentUris.withAppendedId(uri, id).toString()

                val image = Image().apply {
                    imagePath = contentUri
                    imageTitle = name
                }
                images.add(image)
            }
        }

        Log.d("GalleryApp", "✅ MediaStore returned TOTAL ${images.size} images")

        // Insert ALL images into database (unique by contentUri)
        lifecycleScope.launch(Dispatchers.IO) {
            images.forEach { img ->
                galleryDao.insertImage(ImageEntity(name = img.imageTitle, contentUri = img.imagePath))
            }
            Log.d("GalleryApp", "✅ Inserted ${images.size} images into ImagesTable")
        }

        return images
    }

    /*
    Φιλτράρει τις εικόνες βάσει του imageTitle (DISPLAY_NAME).
    */
    /*
     * Live search with your exact rules:
     * - 1 word  → most instances → highest confidence
     * - 2-3 words → most matched classes → total objects → highest confidence
     */
    /*
  * Live search that treats "plastic bag", "coffee cup", etc. as ONE object
  */
    private fun filterImages(query: String) {
        val trimmed = query.trim().lowercase()
        if (trimmed.isEmpty()) {
            imageAdapter?.updateList(allPictures ?: ArrayList())
            return
        }

        val words = trimmed.split("\\s+".toRegex())
            .filter { it.length >= 2 }
            .take(5)   // safety limit

        val terms = mutableSetOf<String>()
        terms.addAll(words)                    // individual words

        // ← THIS BLOCK FIXES "park bench" + "jean"
        // Add every possible multi-word combination that the user might have typed
        for (i in words.indices) {
            for (len in 2..3) {                // support up to 3-word objects
                if (i + len <= words.size) {
                    val phrase = words.subList(i, i + len).joinToString(" ")
                    terms.add(phrase)
                }
            }
        }

        val expandedTerms = expandWithSynonyms(terms.toList())

        lifecycleScope.launch(Dispatchers.IO) {
            val dbResults = galleryDao.getSortedImages(expandedTerms)

            val uriMap = allPictures?.associateBy { it.imagePath } ?: emptyMap()
            val sortedImages = ArrayList(dbResults.mapNotNull { uriMap[it.contentUri] })

            withContext(Dispatchers.Main) {
                imageAdapter?.updateList(sortedImages)
            }
        }
    }
    /*
    Δημιουργεί το SearchView στο action bar (σύγχρονο στυλ 2025-2026).
    */
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)

        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem.actionView as SearchView
        // === AUTOCOMPLETE SUGGESTIONS (fixed type + cast) ===
        // === SAFE AUTOCOMPLETE WITH DEBOUNCE (prevents cursor crash on fast typing/deleting) ===
        suggestionsAdapter = SimpleCursorAdapter(
            this,
            android.R.layout.simple_list_item_1,
            null,
            arrayOf("suggestion"),
            intArrayOf(android.R.id.text1),
            0
        )
        searchView.suggestionsAdapter = suggestionsAdapter

        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false

            override fun onQueryTextChange(newText: String?): Boolean {
                val text = newText ?: ""
                val lastWord = text.trim().split("\\s+".toRegex()).lastOrNull() ?: ""

                suggestionJob?.cancel()

                if (lastWord.length >= 2) {
                    suggestionJob = lifecycleScope.launch(Dispatchers.IO) {
                        delay(180) // ← debounce prevents race condition

                        try {
                            val cursor = galleryDao.getSuggestions(lastWord)
                            withContext(Dispatchers.Main) {
                                val oldCursor = suggestionsAdapter?.cursor
                                suggestionsAdapter?.changeCursor(cursor)
                                oldCursor?.close() // safe close AFTER swap
                            }
                        } catch (e: Exception) {
                            Log.e("GalleryApp", "Suggestion query failed", e)
                        }
                    }
                } else {
                    suggestionsAdapter?.changeCursor(null)
                }

                filterImages(text) // your smart sorting + synonyms still work
                return true
            }
        })

// Replace your entire setOnSuggestionListener block with this one
// (and DELETE the duplicate block below it — you had the same code twice)

        searchView.setOnSuggestionListener(object : SearchView.OnSuggestionListener {
            override fun onSuggestionSelect(position: Int): Boolean = false

            override fun onSuggestionClick(position: Int): Boolean {
                val cursor = searchView.suggestionsAdapter?.cursor ?: return false
                if (cursor.isClosed) return false

                cursor.moveToPosition(position)
                val selected = cursor.getString(cursor.getColumnIndexOrThrow("suggestion"))

                val currentQuery = searchView.query.toString().trim()

                // Replace only the last (partial) word → fixes "ri rifle" bug
                val words = currentQuery.split("\\s+".toRegex()).toMutableList()
                if (words.isNotEmpty()) {
                    words[words.lastIndex] = selected
                } else {
                    words.add(selected)
                }

                val newQuery = words.joinToString(" ") + " "

                searchView.setQuery(newQuery, false)

                // === FIXED: Move cursor to end (this was red before) ===
                searchView.post {
                    val editText = searchView.findViewById<EditText>(androidx.appcompat.R.id.search_src_text)
                    editText?.setSelection(newQuery.length)
                }

                return true
            }
        })


        // Focus change listener to show/hide keyboard reliably
        searchView.setOnQueryTextFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                view.showKeyboard()
            } else {
                view.hideKeyboard()
            }
        }

        // Auto-show keyboard on expand with layout timing fix
        searchItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                searchView.setIconifiedByDefault(false)  // Prevent iconified state
                searchView.isIconified = false  // Force full expansion
                searchView.isFocusable = true
                searchView.isFocusableInTouchMode = true
                searchView.requestFocus()  // Request focus immediately

                // ← NEW: Wait for layout to complete before showing keyboard
                val layoutListener = object : ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        searchView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                        searchView.showKeyboard()  // Now show keyboard when view is laid out
                    }
                }
                searchView.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)

                return true
            }

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                searchView.setQuery("", false)  // Clear query on collapse
                filterImages("")  // Clear filter
                searchView.hideKeyboard()  // Hide keyboard
                return true
            }
        })

        return true
    }

    fun View.showKeyboard() {
        requestFocus()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {  // API 30+
            val controller = ViewCompat.getWindowInsetsController(this) ?: return
            controller.show(WindowInsetsCompat.Type.ime())
        } else {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun View.hideKeyboard() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {  // API 30+
            val controller = ViewCompat.getWindowInsetsController(this) ?: return
            controller.hide(WindowInsetsCompat.Type.ime())
        } else {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(windowToken, 0)
        }
    }

    private fun initializeObjectDetector() {
        try {
            val localModel = LocalModel.Builder()
                .setAssetFilePath("best_float16.tflite")
                .build()

            val options = CustomObjectDetectorOptions.Builder(localModel)
                .setDetectorMode(CustomObjectDetectorOptions.SINGLE_IMAGE_MODE)
                .enableMultipleObjects()
                .enableClassification()
                .setClassificationConfidenceThreshold(0.35f)
                .setMaxPerObjectLabelCount(1)
                .build()

            objectDetector = ObjectDetection.getClient(options)
            Log.d("GalleryApp", "✅ ML Kit detector ready")
        } catch (e: Exception) {
            Log.e("GalleryApp", "Failed to load model", e)
        }
    }

    private fun startObjectIndexing() {
        if (objectDetector == null || allPictures.isNullOrEmpty()) return

        lifecycleScope.launch(Dispatchers.IO) {
            val imagesToProcess = allPictures!!   // first 20 images (safe)

            Log.d("GalleryApp", "🔄 Starting smart indexing on ${imagesToProcess.size} images...")

            for (img in imagesToProcess) {
                val contentUri = img.imagePath

                // Skip if already in DB
                val existingId = galleryDao.getImageIdByUri(contentUri)
                if (existingId != null && galleryDao.hasDetections(existingId)) {
                    Log.d("GalleryApp", "⏭️ Skipping already indexed: ${img.imageTitle}")
                    continue
                }

                val uri = Uri.parse(contentUri)
                try {
                    val inputImage = InputImage.fromFilePath(this@MainActivity, uri)

                    // Insert (or reuse existing)
                    // Get existing image ID (preferred) or insert only if missing
                    val imageId = galleryDao.getImageIdByUri(contentUri)
                        ?: run {
                            val insertResult = galleryDao.insertImage(
                                ImageEntity(name = img.imageTitle, contentUri = contentUri)
                            )
                            if (insertResult > 0) insertResult.toInt() else galleryDao.getImageIdByUri(contentUri)
                        } ?: continue   // skip this image if something went wrong

                    if (imageId <= 0) {
                        Log.e("GalleryApp", "Failed to get imageId for ${img.imageTitle}")
                        continue
                    }
                    objectDetector!!.process(inputImage)
                        .addOnSuccessListener { detectedObjects ->

                            if (detectedObjects.isEmpty()) return@addOnSuccessListener

                            lifecycleScope.launch(Dispatchers.IO) {
                                for (obj in detectedObjects) {
                                    for (label in obj.labels) {
                                        val title = label.text.lowercase().trim()
                                        val score = label.confidence.toDouble()

                                        val objId = galleryDao.getOrInsertObject(title)
                                        if (objId <= 0) {
                                            Log.w("GalleryApp", "Skipped detection for '$title' (no valid Obj_id)")
                                            continue
                                        }
                                        galleryDao.insertWithIncrement(imageId, objId, score)
                                        galleryDao.insertWithIncrement(imageId, objId, score)
                                    }
                                }
                            }
                        }
                } catch (e: Exception) {
                    Log.e("GalleryApp", "Failed to process ${img.imageTitle}", e)
                }
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "✅ Indexing finished! Search now works without duplicates", Toast.LENGTH_LONG).show()
            }
        }
    }
}