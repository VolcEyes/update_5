package com.example.galleryapp

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

class FaceAdapter(
    private val context: Context,
    private val faces: List<FaceEntity>,
    private val selectedFaces: List<FaceEntity> = emptyList(), // <-- 1. Add this list!
    private val onClick: (FaceEntity) -> Unit,
    private val onLongClick: ((FaceEntity) -> Unit)? = null
) : RecyclerView.Adapter<FaceAdapter.FaceViewHolder>() {

    class FaceViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imageView: ImageView = view.findViewById(R.id.img_face_preview) // Make sure this matches your XML ID
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FaceViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.row_face_preview, parent, false)
        return FaceViewHolder(view)
    }

    override fun getItemCount() = faces.size

    override fun onBindViewHolder(holder: FaceViewHolder, position: Int) {
        val face = faces[position]

        Glide.with(context)
            .load(face.faceImagePath)
            .into(holder.imageView)

        // --- 2. VISUAL SELECTION LOGIC ---
        val isSelected = selectedFaces.contains(face)

        if (isSelected) {
            // Dim the image slightly and add a blue background/padding to act as a border
            holder.imageView.alpha = 0.6f
            holder.imageView.setBackgroundColor(Color.parseColor("#2196F3")) // Blue color
            holder.imageView.setPadding(8, 8, 8, 8) // 8 pixels of padding to reveal the blue background

            // Note: If you have a specific blue circle ImageView in your XML (like R.id.iv_blue_circle),
            // you would make it visible here instead: holder.blueCircle.visibility = View.VISIBLE
        } else {
            // Reset to normal state
            holder.imageView.alpha = 1.0f
            holder.imageView.setBackgroundColor(Color.TRANSPARENT)
            holder.imageView.setPadding(0, 0, 0, 0)
        }
        // ---------------------------------

        holder.itemView.setOnClickListener {
            onClick(face)
        }

        holder.itemView.setOnLongClickListener {
            if (onLongClick != null) {
                onLongClick.invoke(face)
                true
            } else {
                false
            }
        }
    }
}