package com.astra.camera

import android.app.Dialog
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.view.Window
import android.widget.MediaController
import com.astra.camera.databinding.DialogVideoViewerBinding
import java.io.File

/**
 * Visor de video a pantalla completa (para los videos de timelapse) con
 * controles de reproducción estándar y opción de eliminarlo de la galería.
 */
class VideoViewerDialog(
    context: Context,
    private val file: File,
    private val onDeleted: () -> Unit
) : Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {

    private lateinit var binding: DialogVideoViewerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)

        binding = DialogVideoViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val mediaController = MediaController(context)
        mediaController.setAnchorView(binding.videoView)
        binding.videoView.setMediaController(mediaController)
        binding.videoView.setVideoURI(Uri.fromFile(file))
        binding.videoView.setOnPreparedListener { it.isLooping = true }
        binding.videoView.setOnClickListener {
            // VideoView no muestra los controles al tocar la pantalla por
            // defecto si no hay foco táctil previo; se fuerza aquí.
            mediaController.show()
        }
        binding.videoView.start()

        binding.btnClose.setOnClickListener { dismiss() }
        binding.btnDelete.setOnClickListener {
            binding.videoView.stopPlayback()
            file.delete()
            dismiss()
            onDeleted()
        }

        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onStop() {
        super.onStop()
        binding.videoView.stopPlayback()
    }
}
