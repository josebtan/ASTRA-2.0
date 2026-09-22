package com.astra.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Calibración de "bias": un fotograma de bias se toma con la exposición y el
 * ISO más bajos posibles, con el objetivo de la cámara tapado (sin luz
 * entrando), y captura únicamente el "ruido de patrón fijo" propio de la
 * electrónica de lectura del sensor — el mismo en todas las fotos que tome
 * ese sensor, independientemente de la escena. Promediando varios bias se
 * obtiene un "bias maestro" limpio, que luego se resta de las fotos
 * apiladas para eliminar ese ruido de fondo antes de cualquier otro
 * procesamiento.
 *
 * El bias maestro se guarda en el almacenamiento privado de la app (no en
 * la galería) como PNG sin pérdida — un JPEG comprimiría y alteraría
 * justamente el patrón de ruido fino que se necesita conservar exacto — y
 * persiste entre sesiones hasta que el usuario vuelve a calibrar.
 */
object BiasCalibrator {

    private const val TAG = "BiasCalibrator"
    private const val MASTER_BIAS_FILENAME = "master_bias.png"
    private const val MASTER_BIAS_TIMESTAMP_FILENAME = "master_bias_timestamp.txt"

    fun masterBiasFile(context: Context): File = File(context.filesDir, MASTER_BIAS_FILENAME)

    fun hasMasterBias(context: Context): Boolean = masterBiasFile(context).exists()

    /** Momento (epoch millis) en que se generó el bias maestro actual, o null si no hay ninguno. */
    fun masterBiasTimestamp(context: Context): Long? {
        val file = File(context.filesDir, MASTER_BIAS_TIMESTAMP_FILENAME)
        if (!file.exists()) return null
        return file.readText().trim().toLongOrNull()
    }

    fun clearMasterBias(context: Context) {
        masterBiasFile(context).delete()
        File(context.filesDir, MASTER_BIAS_TIMESTAMP_FILENAME).delete()
    }

    /**
     * Carga el bias maestro como Bitmap, solo si existe y coincide
     * exactamente en resolución con [width]x[height] (un bias calibrado con
     * otra cámara/resolución no sirve y podría corromper la imagen si se
     * aplicara igual). Devuelve null en cualquier otro caso.
     */
    fun loadMasterBias(context: Context, width: Int, height: Int): Bitmap? {
        val file = masterBiasFile(context)
        if (!file.exists()) return null
        val bitmap = try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo decodificar el bias maestro", e)
            null
        } ?: return null

        return if (bitmap.width == width && bitmap.height == height) {
            bitmap
        } else {
            Log.w(TAG, "El bias maestro (${bitmap.width}x${bitmap.height}) no coincide con la resolución actual (${width}x$height), se ignora")
            bitmap.recycle()
            null
        }
    }

    /**
     * Promedia [frameFiles] (fotogramas de bias, tomados con el objetivo
     * tapado) en un único bias maestro, y lo guarda. Se ejecuta en un hilo
     * secundario; [onComplete] se llama en el hilo principal.
     */
    fun buildMasterBiasAsync(
        context: Context,
        frameFiles: List<File>,
        onComplete: (success: Boolean) -> Unit
    ) {
        Thread({
            val success = try {
                buildMasterBias(context, frameFiles)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error generando el bias maestro", e)
                false
            }
            Handler(Looper.getMainLooper()).post { onComplete(success) }
        }, "astra-bias-builder").start()
    }

    private fun buildMasterBias(context: Context, frameFiles: List<File>) {
        require(frameFiles.isNotEmpty()) { "No hay fotogramas de bias para promediar" }

        val decodeOptions = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val first = BitmapFactory.decodeFile(frameFiles.first().absolutePath, decodeOptions)
            ?: throw IllegalStateException("No se pudo decodificar el primer fotograma de bias")
        val width = first.width
        val height = first.height
        val pixelCount = width * height

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
                bitmap.recycle()
                continue
            }
            accumulate(bitmap)
            framesUsed++
            bitmap.recycle()
        }

        require(framesUsed > 0) { "Ningún fotograma de bias pudo procesarse" }

        val resultPixels = IntArray(pixelCount)
        for (i in 0 until pixelCount) {
            val r = (sumR[i] / framesUsed).coerceIn(0, 255)
            val g = (sumG[i] / framesUsed).coerceIn(0, 255)
            val b = (sumB[i] / framesUsed).coerceIn(0, 255)
            resultPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        val resultBitmap = Bitmap.createBitmap(resultPixels, width, height, Bitmap.Config.ARGB_8888)
        try {
            FileOutputStream(masterBiasFile(context)).use { out ->
                // PNG (sin pérdida): un JPEG alteraría el patrón de ruido
                // fino que es justamente lo que se quiere conservar exacto.
                resultBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            File(context.filesDir, MASTER_BIAS_TIMESTAMP_FILENAME).writeText(System.currentTimeMillis().toString())
        } finally {
            resultBitmap.recycle()
        }

        Log.d(TAG, "Bias maestro generado con $framesUsed de ${frameFiles.size} fotogramas")
    }
}
