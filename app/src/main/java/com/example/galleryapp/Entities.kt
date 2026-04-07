package com.example.galleryapp

import io.objectbox.annotation.Backlink
import io.objectbox.annotation.Entity
import io.objectbox.annotation.HnswIndex
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
data class PersonEntity(
    @Id var id: Long = 0,
    var name: String = "Unknown Person",
    var coverFaceImagePath: String = "" // The thumbnail to display in your BottomSheet
) {
    @Backlink(to = "person")
    lateinit var faces: ToMany<FaceEntity>
}

@Entity
data class FaceEntity(
    @Id var id: Long = 0,
    @HnswIndex(dimensions = 512) var faceVector: FloatArray? = null,
    var boundingBox: String = "",
    var faceImagePath: String = ""
) {
    lateinit var image: ToOne<ImageEntity>
    lateinit var person: ToOne<PersonEntity> // NEW: Links this specific face to a master Person
}