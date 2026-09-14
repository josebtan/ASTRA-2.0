package com.astra.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Apila ("stacking") una secuencia de fotos JPEG tomadas con la misma
 * exposición, promediando el valor de cada píxel entre todos los
 * fotogramas. Al promediar, el ruido aleatorio de cada foto (muy presente
 * en exposiciones largas de astrofotografía) se reduce, mientras que la
 * señal real de la escena (estrellas, paisaje) se mantiene, ya que es
 * consistente entre fotogramas.
 *
 * No realiza alineación entre fotogramas: asume que la cámara estuvo
 * completamente fija (trípode) durante toda la secuencia, tal como se
 * indica al usuario en el submenú de Astro. Es la misma limitación de
 * cualquier "stacking" básico sin seguimiento de estrellas.
 */
object AstroStackBuilder {

    private const val TAG = "AstroStackBuilder"
    private const val JPEG_QUALITY = 95

    /**
     * Procesa el stacking en un hilo secundario y notifica el resultado en
     * el hilo principal a través de [onComplete].
     */
    fun buildStackAsync(
        frameFiles: List<File>,
        outputFile: File,
        onComplete: (success: Boolean) -> Unit
    ) {
        Thread({
            val success = try {
                buildStack(frameFiles, outputFile)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error apilando las imágenes", e)
                outputFile.delete()
                false
            }
            Handler(Looper.getMainLooper()).post { onComplete(success) }
        }, "astra-stack-builder").start()
    }

    private fun buildStack(frameFiles: List<File>, outputFile: File) {
        require(frameFiles.isNotEmpty()) { "No hay fotogramas para apilar" }
        Log.d(TAG, "Apilando ${frameFiles.size} fotogramas -> ${outputFile.absolutePath}")

        val decodeOptions = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }

        val first = BitmapFactory.decodeFile(frameFiles.first().absolutePath, decodeOptions)
            ?: throw IllegalStateException("No se pudo decodificar el primer fotograma")
        val width = first.width
        val height = first.height
        val pixelCount = width * height

        // Acumuladores de suma por canal. Un Int alcanza sobradamente: el
        // máximo posible es 255 * número de fotos, muy por debajo del límite
        // de Int incluso con cientos de fotogramas.
        val sumR = IntArray(pixelCount)
        val sumG = IntArray(pixelCount)
        val sumB = IntArray(pixelCount)
        val pixels = IntArray(pixelCount)

        fun accumulate(bitmap: Bitmap) {
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            for (i in 0 until pixelCount) {
                val p = pixels[i]
                sumR[i] += (p shr 16) and 0xFF
                sumG[i] += (p shr 8) and 0xFF
                sumB[i] += p and 0xFF
            }
        }

        var framesUsed = 0
        accumulate(first)
        framesUsed++
        first.recycle()

        for (index in 1 until frameFiles.size) {
            val file = frameFiles[index]
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
            if (bitmap == null) {
                Log.w(TAG, "No se pudo decodificar ${file.name}, se omite")
                continue
            }
            if (bitmap.width != width || bitmap.height != height) {
                Log.w(TAG, "Resolución distinta en ${file.name} (${bitmap.width}x${bitmap.height} vs ${width}x$height), se omite")
                bitmap.recycle()
                continue
            }
            accumulate(bitmap)
            framesUsed++
            bitmap.recycle()
        }

        require(framesUsed > 0) { "Ningún fotograma pudo procesarse" }

        val resultPixels = IntArray(pixelCount)
        for (i in 0 until pixelCount) {
            val r = (sumR[i] / framesUsed).coerceIn(0, 255)
            val g = (sumG[i] / framesUsed).coerceIn(0, 255)
            val b = (sumB[i] / framesUsed).coerceIn(0, 255)
            resultPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        val resultBitmap = Bitmap.createBitmap(resultPixels, width, height, Bitmap.Config.ARGB_8888)
        try {
            FileOutputStream(outputFile).use { out ->
                resultBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
        } finally {
            resultBitmap.recycle()
        }

        Log.d(TAG, "Stack generado correctamente con $framesUsed de ${frameFiles.size} fotogramas")
    }
}
