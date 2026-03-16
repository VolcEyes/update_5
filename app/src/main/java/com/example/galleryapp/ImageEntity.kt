package com.example.galleryapp

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "ImagesTable",
    indices = [Index(value = ["contentUri"], unique = true)]
)
data class ImageEntity(
    @PrimaryKey(autoGenerate = true) val Image_id: Int = 0,
    val name: String,
    val contentUri: String
)