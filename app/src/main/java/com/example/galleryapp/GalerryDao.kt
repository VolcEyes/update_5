package com.example.galleryapp

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

data class ImageSearchResult(
    val Image_id: Int,
    val name: String,
    val contentUri: String,
    val matched_classes: Int,
    val total_objects: Int,
    val top_confidence: Double
)

@Dao
interface GalleryDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertImage(image: ImageEntity): Long

    @Query("SELECT Obj_id FROM Objects WHERE Obj_Title = :title LIMIT 1")
    suspend fun getObjIdByTitle(title: String): Int?


    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertObject(obj: ObjectEntity): Long

    @Transaction
    suspend fun getOrInsertObject(title: String): Int {
        val existing = getObjIdByTitle(title)
        if (existing != null) return existing

        val newId = insertObject(ObjectEntity(Obj_Title = title))
        return if (newId > 0) newId.toInt() else getObjIdByTitle(title)!!
    }

    @Query("SELECT MAX(instance_id) FROM ImagesObjects WHERE Image_id = :imageId AND Obj_id = :objId")
    suspend fun getMaxInstanceId(imageId: Int, objId: Int): Int?

    @Insert
    suspend fun insertImageObject(entity: ImageObjectEntity)

    @Transaction
    suspend fun insertWithIncrement(imageId: Int, objId: Int, predScore: Double) {
        val max = getMaxInstanceId(imageId, objId) ?: 0
        val next = max + 1
        insertImageObject(ImageObjectEntity(imageId, objId, next, predScore))
    }


    @Query("SELECT Image_id FROM ImagesTable WHERE contentUri = :contentUri LIMIT 1")
    suspend fun getImageIdByUri(contentUri: String): Int?

    @Query("SELECT COUNT(*) > 0 FROM ImagesObjects WHERE Image_id = :imageId LIMIT 1")
    suspend fun hasDetections(imageId: Int): Boolean

    @Query("""
    SELECT 
        i.Image_id,
        i.name,
        i.contentUri,
        COUNT(DISTINCT CASE WHEN o.Obj_Title IN (:terms) THEN o.Obj_id END) AS matched_classes,
        COUNT(io.Image_id) AS total_objects,
        MAX(CASE WHEN o.Obj_Title IN (:terms) THEN io.pred_score ELSE 0.0 END) AS top_confidence
    FROM ImagesTable i
    LEFT JOIN ImagesObjects io ON i.Image_id = io.Image_id
    LEFT JOIN Objects o ON io.Obj_id = o.Obj_id
    GROUP BY i.Image_id
    ORDER BY 
        matched_classes DESC,          -- matching images rise to the top
        total_objects DESC,
        top_confidence DESC,
        i.Image_id DESC                -- non-matching images stay in newest-first order
""")
    suspend fun getSortedImages(terms: List<String>): List<ImageSearchResult>

    @Query("""
    SELECT Obj_Title AS _id, Obj_Title AS suggestion 
    FROM Objects 
    WHERE Obj_Title LIKE :prefix || '%' COLLATE NOCASE 
    ORDER BY Obj_Title 
    LIMIT 12
""")
    fun getSuggestions(prefix: String): android.database.Cursor
}