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

/*
Η κλάση MainActivity είναι η κύρια δραστηριότητα της εφαρμογής gallery.
Διαχειρίζεται την εμφάνιση εικόνων που είναι αποθηκευμένες στη συσκευή στο RecyclerView,
ζητά δικαιώματα πρόσβασης και φορτώνει τις εικόνες.
*/
class MainActivity : AppCompatActivity() {  // Κύρια κλάση δραστηριότητας που επεκτείνει AppCompatActivity

    private var imageRecycler: RecyclerView? = null  // Μεταβλητή για το RecyclerView που εμφανίζει τις εικόνες
    private var progressBar: ProgressBar? = null  // Μεταβλητή για την μπάρα προόδου
    private var allPictures: ArrayList<Image>? = null  // Λίστα με όλες τις εικόνες

    /*
    Η μέθοδος onCreate καλείται κατά τη δημιουργία της δραστηριότητας.
    Ρυθμίζει το UI, αρχικοποιεί τα στοιχεία και ελέγχει δικαιώματα.
    */
    override fun onCreate(savedInstanceState: Bundle?) {  // Υπερκάλυψη της onCreate
        super.onCreate(savedInstanceState)  // Κλήση του function της υπερκλάσης AppCompatActivity και προσθήκη δικών μου λειτουργιών
        enableEdgeToEdge()  // Εμφάνιση των περιεχομένω σε όλο το μήκος της οθόνης
        setContentView(R.layout.activity_main)  // Ορισμός του layout

        imageRecycler = findViewById(R.id.image_recycler)  // ID για εύρεση του RecyclerView
        progressBar = findViewById(R.id.recycler_progress)  // ID για εύρεση της μπάρας προόδου

        imageRecycler?.layoutManager = GridLayoutManager(this, 3)  // Ρύθμιση του recycler ώστε να έχει 3 στήλες
        imageRecycler?.setHasFixedSize(true)  // Ορισμός σταθερού μεγέθους για βελτίωση απόδοσης

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
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
                    imageRecycler?.adapter = ImageAdapter(this@MainActivity, allPictures!!)  // Ορισμός ImageAdapter
                } else {
                    Toast.makeText(this@MainActivity, "Δεν βρέθηκαν εικόνες στο MediaStore", Toast.LENGTH_LONG).show()  // Μήνυμα μη εύρεσης εικόνων
                }
                progressBar?.visibility = View.GONE  // Απόκρυψη μπάρας προόδου
            }
        }
    }

    /*
    Η μέθοδος getAllImages επιστρέφει λίστα με όλες τις εικόνες από MediaStore.
    Χρησιμοποιεί query για ανάκτηση δεδομένων εικόνων.
    */
    private fun getAllImages(): ArrayList<Image> {  // Ιδιωτική μέθοδος λήψης εικόνων
        val images = ArrayList<Image>()  // Δημιουργία λίστας εικόνων

        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI  // URI για εξωτερικές εικόνες
        val projection = arrayOf(
            MediaStore.Images.Media._ID,  // ID εικόνας
            MediaStore.Images.Media.DISPLAY_NAME  // Όνομα εμφάνισης
        )


        // Το ContentResolver χρησιμοποιείται για να εκτελεί ερωτήματα(queries/requests) σε ContentProviders του Android,
        // όπως το MediaStore, επιτρέποντας ασφαλή πρόσβαση σε δεδομένα συστήματος (π.χ. λίστα εικόνων) χωρίς άμεση χειρισμό αρχείων
        // ή βάσεων δεδομένων.
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)  // Λήψη δείκτη στήλης ID
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)  // Λήψη δείκτη στήλης ονόματος

            while (cursor.moveToNext()) {  // Βρόχος ανάγνωσης εγγραφών
                val id = cursor.getLong(idColumn)  // Λήψη ID
                val name = cursor.getString(nameColumn) ?: "Untitled"  // Λήψη ονόματος ή προεπιλεγμένο

                val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)  // Δημιουργία URI περιεχομένου

                val image = Image()  // Δημιουργία αντικειμένου Image
                image.imagePath = contentUri.toString()   // Ορισμός διαδρομής URI εικόνας
                image.imageTitle = name                   // Ορισμός τίτλου εικόνας
                images.add(image)  // Προσθήκη εικόνας στη λίστα
            }
        }
        return images  // Επιστροφή λίστας
    }
}