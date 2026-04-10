package com.example.galleryapp

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import android.content.res.ColorStateList
import android.graphics.Color
import androidx.core.content.ContextCompat

class FaceAdapter(
    private val context: Context,
    private val faces: List<FaceEntity>,
    private val selectedFaces: MutableList<FaceEntity>,
    private val onClick: (FaceEntity) -> Unit,
    private val onLongClick: ((FaceEntity) -> Unit)? = null
) : RecyclerView.Adapter<FaceAdapter.FaceViewHolder>() {

    // 1. Declare the selection ring here inside the ViewHolder
    inner class FaceViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        // You can leave this as a standard ImageView here
        val ivFace: ImageView = view.findViewById(R.id.img_face_preview)

        // Find the new overlay ring
        val selectionRing: View = view.findViewById(R.id.iv_selection_ring)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FaceViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.row_face_preview, parent, false)
        return FaceViewHolder(view)
    }

    override fun onBindViewHolder(holder: FaceViewHolder, position: Int) {
        val face = faces[position]

        // Load the face image
        Glide.with(context).load(face.faceImagePath).into(holder.ivFace)

        // Toggle the visibility of the completely separate ring View
        if (selectedFaces.contains(face)) {
            holder.selectionRing.visibility = View.VISIBLE
        } else {
            holder.selectionRing.visibility = View.GONE
        }

        // Handle Clicks
        holder.itemView.setOnClickListener {
            onClick(face)
        }

        // Handle Long Clicks
        holder.itemView.setOnLongClickListener {
            onLongClick?.invoke(face)
            true
        }
    }

    override fun getItemCount(): Int = faces.size
}