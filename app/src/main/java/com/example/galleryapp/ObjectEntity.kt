package com.example.galleryapp

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.PrimaryKey
import androidx.room.Index

@Entity(
    tableName = "Objects",
    indices = [Index(value = ["Obj_Title"], unique = true)]
)
data class ObjectEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "Obj_id")
    val Obj_id: Int = 0,

    @ColumnInfo(name = "Obj_Title")
    val Obj_Title: String
)