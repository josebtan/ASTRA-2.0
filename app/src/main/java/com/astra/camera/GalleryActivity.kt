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
            ?.filter {
                it.extension.equals("jpg", ignoreCase = true) ||
                    it.extension.equals("dng", ignoreCase = true) ||
                    it.extension.equals("mp4", ignoreCase = true)
            }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

        if (images.isEmpty()) {
            binding.tvEmpty.visibility = View.VISIBLE
            binding.recyclerView.visibility = View.GONE
        } else {
            binding.tvEmpty.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
        }

        val spanCount = 3
        val spacingPx = (2 * resources.displayMetrics.density).toInt()

        binding.recyclerView.layoutManager = GridLayoutManager(this, spanCount)
        binding.recyclerView.clipToPadding = false
        binding.recyclerView.setPadding(spacingPx, spacingPx, spacingPx, spacingPx)
        if (binding.recyclerView.itemDecorationCount == 0) {
            binding.recyclerView.addItemDecoration(GridSpacingItemDecoration(spanCount, spacingPx))
        }
        binding.recyclerView.adapter = GalleryAdapter(images) { file ->
            showMedia(file)
        }
    }

    private fun showMedia(file: File) {
        if (file.extension.equals("mp4", ignoreCase = true)) {
            VideoViewerDialog(this, file) {
                loadImages()
            }.show()
        } else {
            ImageViewerDialog(this, file) {
                loadImages()
            }.show()
        }
    }
}
