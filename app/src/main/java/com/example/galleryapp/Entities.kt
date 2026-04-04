package com.example.galleryapp

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.relation.ToMany
import io.objectbox.relation.ToOne

@Entity
data class ImageEntity(
    @Id var id: Long = 0,
    var imageUri: String = "",
    var dateModified: Long = 0
) {
    lateinit var faces: ToMany<FaceEntity>
}

@Entity
data class FaceEntity(
    @Id var id: Long = 0,
    var faceVector: FloatArray? = null,
    var boundingBox: String = "",
    var faceImagePath: String = "" // NEW: Stores the path to the cropped preview
) {
    lateinit var image: ToOne<ImageEntity>
}