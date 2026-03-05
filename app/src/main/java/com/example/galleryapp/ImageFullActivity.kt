package com.example.galleryapp

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class ImageFullActivity : AppCompatActivity() {   // ← changed to match your actual class/file name

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_full)

        // Safe findViewById (prevents the #1 crash cause)
        val fullImageView = findViewById<ImageView>(R.id.iv_full_image)

        val imagePath = intent.getStringExtra("image_path") ?: ""
        val imageTitle = intent.getStringExtra("image_title") ?: "Image"

        // This is why you see the title
        supportActionBar?.title = imageTitle

        if (fullImageView == null) {
            Log.e("ImageFullActivity", "ImageView with ID 'iv_full_image' NOT FOUND in activity_image_full.xml!")
            Toast.makeText(this, "Layout error: ImageView ID mismatch", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        if (imagePath.isNotEmpty()) {
            try {
                fullImageView.setImageURI(Uri.parse(imagePath))
                Log.d("ImageFullActivity", "✅ Loading image URI: $imagePath")
            } catch (e: Exception) {
                Log.e("ImageFullActivity", "Failed to load image", e)
                Toast.makeText(this, "Cannot display image", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "No image path received", Toast.LENGTH_SHORT).show()
        }
    }
}