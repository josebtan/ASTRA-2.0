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
 * Con [alignStars] activado (recomendado), cada fotograma se desplaza en
 * X/Y antes de sumarse, según el resultado de [StarAligner], para corregir
 * pequeños movimientos de la cámara o de la rotación terrestre entre
 * fotogramas y evitar que las estrellas se vean como rayas en vez de
 * puntos nítidos. Sin alineación, se asume que la cámara estuvo
 * completamente fija (trípode) durante toda la secuencia.
 *
 * Con [boostContrast] activado, al final se aplica un "estiramiento" de
 * punto negro sobre la imagen ya apilada: se detecta el nivel de brillo
 * típico del fondo del cielo (la inmensa mayoría de los píxeles de una
 * foto nocturna) y se lleva ese nivel a negro puro, redistribuyendo el
 * resto del rango hacia arriba. El resultado es que el fondo se ve mucho
 * más oscuro y uniforme, y las estrellas (que ya estaban por encima de ese
 * nivel) resaltan con más contraste frente a él — la misma idea que el
 * ajuste de "niveles"/"curvas" que se usa en cualquier software de
 * astrofotografía tras el stacking.
 */
object AstroStackBuilder {

    private const val TAG = "AstroStackBuilder"
    private const val JPEG_QUALITY = 95

    // Percentil de luminancia usado como "punto negro" al aumentar el
    // contraste: en una foto nocturna, la gran mayoría de los píxeles SON
    // el fondo del cielo, así que este umbral se adapta automáticamente a
    // cuán oscuro/claro estaba el cielo (contaminación lumínica, ISO usado,
    // etc.) en vez de usar un valor fijo.
    private const val BACKGROUND_PERCENTILE = 0.35

    /**
     * Procesa el stacking en un hilo secundario y notifica el resultado en
     * el hilo principal a través de [onComplete]. Si [alignStars] es true,
     * antes de apilar se llama a [onAligning] (en el hilo principal) para
     * que la UI pueda mostrar un estado tipo "Alineando estrellas...".
     */
    fun buildStackAsync(
        frameFiles: List<File>,
        outputFile: File,
        alignStars: Boolean,
        boostContrast: Boolean,
        onAligning: () -> Unit,
        onComplete: (success: Boolean) -> Unit
    ) {
        Thread({
            val success = try {
                if (alignStars) {
                    Handler(Looper.getMainLooper()).post { onAligning() }
                }
                val offsets = if (alignStars) {
                    StarAligner.computeAlignmentOffsets(frameFiles)
                } else {
                    List(frameFiles.size) { StarAligner.Offset(0, 0) }
                }
                buildStack(frameFiles, outputFile, offsets, boostContrast)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error apilando las imágenes", e)
                outputFile.delete()
                false
            }
            Handler(Looper.getMainLooper()).post { onComplete(success) }
        }, "astra-stack-builder").start()
    }

    private fun buildStack(
        frameFiles: List<File>,
        outputFile: File,
        offsets: List<StarAligner.Offset>,
        boostContrast: Boolean
    ) {
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
        // Cuántos fotogramas contribuyeron a cada píxel. Con alineación, no
        // todos los píxeles reciben aporte de todos los fotogramas: los
        // bordes de un fotograma desplazado quedan fuera de encuadre, así
        // que no se puede dividir simplemente por el total de fotogramas.
        val countPerPixel = IntArray(pixelCount)
        val pixels = IntArray(pixelCount)

        fun accumulate(bitmap: Bitmap, offsetX: Int, offsetY: Int) {
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            if (offsetX == 0 && offsetY == 0) {
                // Camino rápido, sin remuestreo de coordenadas.
                for (i in 0 until pixelCount) {
                    val p = pixels[i]
                    sumR[i] += (p shr 16) and 0xFF
                    sumG[i] += (p shr 8) and 0xFF
                    sumB[i] += p and 0xFF
                    countPerPixel[i]++
                }
                return
            }
            for (y in 0 until height) {
                val srcY = y - offsetY
                if (srcY < 0 || srcY >= height) continue
                val destRow = y * width
                val srcRow = srcY * width
                for (x in 0 until width) {
                    val srcX = x - offsetX
                    if (srcX < 0 || srcX >= width) continue
                    val p = pixels[srcRow + srcX]
                    val destIndex = destRow + x
                    sumR[destIndex] += (p shr 16) and 0xFF
                    sumG[destIndex] += (p shr 8) and 0xFF
                    sumB[destIndex] += p and 0xFF
                    countPerPixel[destIndex]++
                }
            }
        }

        var framesUsed = 0
        val firstOffset = offsets.getOrElse(0) { StarAligner.Offset(0, 0) }
        accumulate(first, firstOffset.dx, firstOffset.dy)
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
            val offset = offsets.getOrElse(index) { StarAligner.Offset(0, 0) }
            accumulate(bitmap, offset.dx, offset.dy)
            framesUsed++
            bitmap.recycle()
        }

        require(framesUsed > 0) { "Ningún fotograma pudo procesarse" }

        val resultPixels = IntArray(pixelCount)
        for (i in 0 until pixelCount) {
            // Se divide por el conteo REAL de ese píxel, no por framesUsed:
            // en los bordes de fotogramas desplazados, algunos píxeles
            // reciben aporte de menos fotogramas que el centro de la imagen.
            val count = countPerPixel[i].coerceAtLeast(1)
            val r = (sumR[i] / count).coerceIn(0, 255)
            val g = (sumG[i] / count).coerceIn(0, 255)
            val b = (sumB[i] / count).coerceIn(0, 255)
            resultPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        if (boostContrast) {
            applyBackgroundContrastBoost(resultPixels)
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

    /**
     * Estira el punto negro de la imagen ya apilada para separar más el
     * fondo del cielo de las estrellas: detecta, vía un histograma de
     * luminancia, el nivel de brillo por debajo del cual cae el
     * [BACKGROUND_PERCENTILE] de los píxeles (una buena estimación del
     * "fondo del cielo" en una foto nocturna, ya que domina el histograma),
     * lo lleva a negro puro, y reescala el resto del rango (ese nivel..255)
     * a 0..255. Los píxeles de fondo se oscurecen y se vuelven más
     * uniformes; las estrellas, que ya estaban por encima de ese nivel,
     * quedan más brillantes en comparación.
     */
    private fun applyBackgroundContrastBoost(pixels: IntArray) {
        val pixelCount = pixels.size
        if (pixelCount == 0) return

        val histogram = IntArray(256)
        for (i in 0 until pixelCount) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val luminance = (r * 299 + g * 587 + b * 114) / 1000
            histogram[luminance]++
        }

        val targetCount = (pixelCount * BACKGROUND_PERCENTILE).toInt()
        var cumulative = 0
        var blackPoint = 0
        for (level in 0..255) {
            cumulative += histogram[level]
            if (cumulative >= targetCount) {
                blackPoint = level
                break
            }
        }

        // Si el punto negro calculado es prácticamente 0 (cielo ya muy
        // oscuro y limpio) o cubre casi todo el rango (imagen ya muy
        // brillante/sobreexpuesta), el estiramiento no aportaría nada útil
        // o directamente arruinaría la imagen: se deja tal cual.
        if (blackPoint <= 0 || blackPoint >= 250) return

        val range = (255 - blackPoint).coerceAtLeast(1)
        for (i in 0 until pixelCount) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val newR = (((r - blackPoint).coerceAtLeast(0) * 255) / range).coerceIn(0, 255)
            val newG = (((g - blackPoint).coerceAtLeast(0) * 255) / range).coerceIn(0, 255)
            val newB = (((b - blackPoint).coerceAtLeast(0) * 255) / range).coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (newR shl 16) or (newG shl 8) or newB
        }
    }
}
