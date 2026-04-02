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
    // Defines a 1-to-many relationship: One image can have multiple faces
    lateinit var faces: ToMany<FaceEntity>
}

@Entity
data class FaceEntity(
    @Id var id: Long = 0,
    var faceVector: FloatArray? = null, // Stores the embedding from MobileFaceNet
    var boundingBox: String = "" // Optional: to store where the face is in the image
) {
    // Links back to the original image
    lateinit var image: ToOne<ImageEntity>
}