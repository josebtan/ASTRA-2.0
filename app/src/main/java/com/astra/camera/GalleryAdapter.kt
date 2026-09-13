package com.astra.camera

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.astra.camera.databinding.ItemGalleryImageBinding
import java.io.File

class GalleryAdapter(
    private val images: List<File>,
    private val onClick: (File) -> Unit
) : RecyclerView.Adapter<GalleryAdapter.ImageViewHolder>() {

    inner class ImageViewHolder(val binding: ItemGalleryImageBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageViewHolder {
        val binding = ItemGalleryImageBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ImageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ImageViewHolder, position: Int) {
        val file = images[position]
        when {
            file.extension.equals("dng", ignoreCase = true) -> {
                // Los archivos RAW no se pueden decodificar como bitmap normal.
                holder.binding.imageView.scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                holder.binding.imageView.setImageResource(R.drawable.ic_manual)
                holder.binding.ivPlayOverlay.visibility = View.GONE
            }
            file.extension.equals("mp4", ignoreCase = true) -> {
                holder.binding.imageView.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                val thumb = MediaThumbnails.createVideoThumbnail(file)
                if (thumb != null) {
                    holder.binding.imageView.setImageBitmap(thumb)
                } else {
                    holder.binding.imageView.setImageResource(R.drawable.ic_video_placeholder)
                }
                holder.binding.ivPlayOverlay.visibility = View.VISIBLE
            }
            else -> {
                holder.binding.imageView.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                holder.binding.imageView.setImageURI(Uri.fromFile(file))
                holder.binding.ivPlayOverlay.visibility = View.GONE
            }
        }
        holder.binding.root.setOnClickListener { onClick(file) }
    }

    override fun getItemCount() = images.size
}
