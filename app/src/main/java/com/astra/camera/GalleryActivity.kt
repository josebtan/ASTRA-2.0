package com.astra.camera

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.astra.camera.databinding.ActivityGalleryBinding
import java.io.File

/**
 * Muestra en una cuadrícula todas las fotos guardadas en el directorio
 * propio de ASTRA (Android/data/com.astra.camera/files/Pictures/ASTRA).
 */
class GalleryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGalleryBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        loadImages()
    }

    override fun onResume() {
        super.onResume()
        loadImages()
    }

    private fun loadImages() {
        val outputDirectory = MainActivity.getOutputDirectory(this)
        val images = outputDirectory.listFiles()
            ?.filter { it.extension.equals("jpg", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

        if (images.isEmpty()) {
            binding.tvEmpty.visibility = View.VISIBLE
            binding.recyclerView.visibility = View.GONE
        } else {
            binding.tvEmpty.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
        }

        binding.recyclerView.layoutManager = GridLayoutManager(this, 3)
        binding.recyclerView.adapter = GalleryAdapter(images) { file ->
            showImageViewer(file)
        }
    }

    private fun showImageViewer(file: File) {
        ImageViewerDialog(this, file) {
            loadImages()
        }.show()
    }
}
