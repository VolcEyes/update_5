package com.example.galleryapp

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private var imageRecycler: RecyclerView? = null
    private var progressBar: ProgressBar? = null
    private var allPictures: ArrayList<Image>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        imageRecycler = findViewById(R.id.image_recycler)
        progressBar = findViewById(R.id.recycler_progress)

        imageRecycler?.layoutManager = GridLayoutManager(this, 3)
        imageRecycler?.setHasFixedSize(true)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(permission), 101)
        } else {
            triggerScanAndLoad()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            triggerScanAndLoad()
        } else {
            Toast.makeText(this, "Permission Denied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun triggerScanAndLoad() {
        progressBar?.visibility = View.VISIBLE

        val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)

        Log.d("GalleryApp", "Scanning directory: ${picturesDir.absolutePath}")

        MediaScannerConnection.scanFile(this, arrayOf(picturesDir.absolutePath), null) { path, uri ->
            Log.d("GalleryApp", "Scan finished for: $path -> $uri")

            runOnUiThread {
                allPictures = getAllImages()
                Log.d("GalleryApp", "Images found after scan: ${allPictures?.size}")

                if (!allPictures.isNullOrEmpty()) {
                    imageRecycler?.adapter = ImageAdapter(this@MainActivity, allPictures!!)
                } else {
                    Toast.makeText(this@MainActivity, "No images found in MediaStore", Toast.LENGTH_LONG).show()
                }
                progressBar?.visibility = View.GONE
            }
        }
    }

    private fun getAllImages(): ArrayList<Image> {
        val images = ArrayList<Image>()

        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME
        )

        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn) ?: "Untitled"

                val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

                val image = Image()
                image.imagePath = contentUri.toString()   // content:// URI
                image.imageTitle = name                   // for title bar
                images.add(image)
            }
        }
        return images
    }
}