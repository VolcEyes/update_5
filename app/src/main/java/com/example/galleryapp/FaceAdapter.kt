package com.example.galleryapp

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.imageview.ShapeableImageView

class FaceAdapter(
    private val context: Context,
    private val faceList: List<FaceEntity>,
    private val onFaceSelected: (FaceEntity) -> Unit
) : RecyclerView.Adapter<FaceAdapter.FaceViewHolder>() {

    private var selectedPosition = -1

    class FaceViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imgFace: ShapeableImageView = view.findViewById(R.id.img_face_preview)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FaceViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.row_face_preview, parent, false)
        return FaceViewHolder(view)
    }

    override fun onBindViewHolder(holder: FaceViewHolder, position: Int) {
        val currentFace = faceList[position]

        // Load the saved cropped face from internal storage
        Glide.with(context)
            .load(currentFace.faceImagePath)
            .into(holder.imgFace)

        // Toggle the blue border
        if (selectedPosition == position) {
            holder.imgFace.strokeColor = ColorStateList.valueOf(Color.BLUE)
        } else {
            holder.imgFace.strokeColor = ColorStateList.valueOf(Color.TRANSPARENT)
        }

        // Handle clicks
        holder.itemView.setOnClickListener {
            val previousPosition = selectedPosition
            selectedPosition = holder.adapterPosition

            // Re-draw the old and new selections to update borders
            notifyItemChanged(previousPosition)
            notifyItemChanged(selectedPosition)

            onFaceSelected(currentFace)
        }
    }

    override fun getItemCount(): Int = faceList.size
}