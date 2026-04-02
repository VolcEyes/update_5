package com.example.galleryapp

import android.app.Application
import io.objectbox.BoxStore

class GalleryApp : Application() {
    companion object {
        lateinit var boxStore: BoxStore
            private set
    }

    override fun onCreate() {
        super.onCreate()
        // Initialize ObjectBox
        boxStore = MyObjectBox.builder()
            .androidContext(this)
            .build()
    }
}