package com.astra.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import java.io.File
import java.io.FileOutputStream

/**
 * Aplica un ajuste de contraste a una imagen JPEG ya guardada en disco.
 *
 * No existe un control de hardware de "contraste" estándar y portable entre
 * fabricantes en la API de Camera2, así que este ajuste se aplica como
 * post-procesado sobre el archivo capturado.
 */
object ImageProcessing {

    /**
     * @param contrast valor entre -50 y 50. 0 significa sin cambios (no hace nada).
     */
    fun applyContrast(file: File, contrast: Int) {
        if (contrast == 0) return

        val original = BitmapFactory.decodeFile(file.absolutePath) ?: return

        // Escala el slider (-50..50) a un factor de contraste (0.5x .. 1.5x)
        val scale = 1f + (contrast / 100f)
        val translate = (1f - scale) * 128f

        val colorMatrix = ColorMatrix(
            floatArrayOf(
                scale, 0f, 0f, 0f, translate,
                0f, scale, 0f, 0f, translate,
                0f, 0f, scale, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )

        val output = Bitmap.createBitmap(original.width, original.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(colorMatrix)
        }
        canvas.drawBitmap(original, 0f, 0f, paint)

        FileOutputStream(file).use { out ->
            output.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }

        original.recycle()
        output.recycle()
    }
}
