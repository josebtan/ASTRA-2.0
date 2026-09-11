package com.astra.camera

import android.app.Dialog
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.view.Window
import com.astra.camera.databinding.DialogImageViewerBinding
import java.io.File

/**
 * Visor de imagen a pantalla completa con opción de eliminar la foto
 * directamente del directorio propio de ASTRA.
 */
class ImageViewerDialog(
    context: Context,
    private val file: File,
    private val onDeleted: () -> Unit
) : Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {

    private lateinit var binding: DialogImageViewerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)

        binding = DialogImageViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.imageView.setImageURI(Uri.fromFile(file))

        binding.btnClose.setOnClickListener { dismiss() }
        binding.btnDelete.setOnClickListener {
            file.delete()
            dismiss()
            onDeleted()
        }

        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
}
