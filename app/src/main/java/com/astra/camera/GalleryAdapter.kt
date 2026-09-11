package com.astra.camera

import android.net.Uri
import android.view.LayoutInflater
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
        holder.binding.imageView.setImageURI(Uri.fromFile(file))
        holder.binding.root.setOnClickListener { onClick(file) }
    }

    override fun getItemCount() = images.size
}
