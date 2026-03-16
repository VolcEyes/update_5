package com.example.galleryapp

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [ImageEntity::class, ObjectEntity::class, ImageObjectEntity::class],
    version = 2,                    // ← CHANGED FROM 1 TO 2
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun galleryDao(): GalleryDao
}