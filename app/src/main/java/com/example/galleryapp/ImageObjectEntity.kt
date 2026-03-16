package com.example.galleryapp

import androidx.room.Entity

@Entity(
    tableName = "ImagesObjects",
    primaryKeys = ["Image_id", "Obj_id", "instance_id"]
)
data class ImageObjectEntity(
    val Image_id: Int,
    val Obj_id: Int,
    val instance_id: Int,
    val pred_score: Double
)