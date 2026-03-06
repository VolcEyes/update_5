package com.example.galleryapp

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

// Η κλάση ImageFullActivity είναι μια δραστηριότητα που εμφανίζει μια εικόνα σε πλήρη οθόνη, λαμβάνοντας δεδομένα από intent.
class ImageFullActivity : AppCompatActivity() {

    // Η μέθοδος onCreate καλείται κατά τη δημιουργία της δραστηριότητας και ρυθμίζει την προβολή της εικόνας.
    override fun onCreate(savedInstanceState: Bundle?) {
        // Κλήση της γονικής μεθόδου onCreate.
        super.onCreate(savedInstanceState)
        // Ορισμός του layout για την εμφάνιση της πλήρους εικόνας.
        setContentView(R.layout.activity_image_full)

        // Αναζήτηση του ImageView με ID iv_full_image.
        val fullImageView = findViewById<ImageView>(R.id.iv_full_image)

        // Λήψη του μονοπατιού της εικόνας από το intent, προεπιλεγμένη τιμή κενή συμβολοσειρά.
        val imagePath = intent.getStringExtra("image_path") ?: ""
        // Λήψη του τίτλου της εικόνας από το intent, προεπιλεγμένη τιμή "Image".
        val imageTitle = intent.getStringExtra("image_title") ?: "Image"

        // Εμφάνιση του τίτλου εικόνας
        supportActionBar?.title = imageTitle

        // Έλεγχος αν το ImageView βρέθηκε.
        if (fullImageView == null) {
            // Καταγραφή σφάλματος αν το ImageView δεν βρέθηκε.
            Log.e("ImageFullActivity", "ImageView with ID 'iv_full_image' NOT FOUND in activity_image_full.xml!")
            // Εμφάνιση μηνύματος σφάλματος layout.
            Toast.makeText(this, "Layout error: ImageView ID mismatch", Toast.LENGTH_LONG).show()
            // Τερματισμός της δραστηριότητας.
            finish()
            // Επιστροφή από τη μέθοδο.
            return
        }

        // Έλεγχος αν το μονοπάτι της εικόνας δεν είναι κενό.
        if (imagePath.isNotEmpty()) {
            // Δοκιμή φόρτωσης της εικόνας.
            try {
                // Φόρτωση της εικόνας από URI στο ImageView.
                fullImageView.setImageURI(Uri.parse(imagePath))
                // Καταγραφή επιτυχούς φόρτωσης εικόνας.
                Log.d("ImageFullActivity", "✅ Loading image URI: $imagePath")
                // Πιάσιμο εξαίρεσης σε περίπτωση αποτυχίας.
            } catch (e: Exception) {
                // Καταγραφή σφάλματος φόρτωσης εικόνας.
                Log.e("ImageFullActivity", "Failed to load image", e)
                // Εμφάνιση μηνύματος αποτυχίας εμφάνισης εικόνας.
                Toast.makeText(this, "Cannot display image", Toast.LENGTH_SHORT).show()
            }
            // Εκτέλεση αν το μονοπάτι είναι κενό.
        } else {
            // Εμφάνιση μηνύματος ότι δεν λήφθηκε μονοπάτι εικόνας.
            Toast.makeText(this, "No image path received", Toast.LENGTH_SHORT).show()
        }
    }
}