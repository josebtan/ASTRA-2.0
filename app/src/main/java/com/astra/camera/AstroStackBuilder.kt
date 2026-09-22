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
 * puntos nítidos.
 *
 * Con [applyBiasCorrection] activado, se resta el bias maestro (ver
 * [BiasCalibrator]) de la imagen ya apilada, eliminando el ruido de patrón
 * fijo propio del sensor antes de cualquier otro ajuste.
 *
 * Con [boostContrast] activado, se estira el punto negro (basado en
 * luminancia) para separar más el fondo del cielo de las estrellas.
 *
 * Con [filterLightPollution] activado, se hace lo mismo pero de forma
 * INDEPENDIENTE por canal (R, G y B por separado). Esto no solo aumenta el
 * contraste: al normalizar cada canal a su propio "punto negro", neutraliza
 * el tinte de color parejo que la contaminación lumínica (sodio, LED, etc.)
 * añade sobre toda la imagen — el mismo principio que un filtro físico
 * anti-contaminación lumínica, aplicado por software tras el stacking.
 */
object AstroStackBuilder {

    private const val TAG = "AstroStackBuilder"
    private const val JPEG_QUALITY = 95

    // Percentil de brillo usado como "punto negro": en una foto nocturna,
    // la gran mayoría de los píxeles SON el fondo del cielo, así que este
    // umbral se adapta automáticamente a cuán oscuro/claro estaba el cielo
    // (contaminación lumínica, ISO usado, etc.) en vez de usar un valor fijo.
    private const val BACKGROUND_PERCENTILE = 0.35

    /**
     * Procesa el stacking en un hilo secundario y notifica el resultado en
     * el hilo principal a través de [onComplete]. Si [alignStars] es true,
     * antes de apilar se llama a [onAligning] (en el hilo principal) para
     * que la UI pueda mostrar un estado tipo "Alineando estrellas...".
     */
    fun buildStackAsync(
        context: Context,
        frameFiles: List<File>,
        outputFile: File,
        alignStars: Boolean,
        applyBiasCorrection: Boolean,
        boostContrast: Boolean,
        filterLightPollution: Boolean,
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
                buildStack(
                    context, frameFiles, outputFile, offsets,
                    applyBiasCorrection, boostContrast, filterLightPollution
                )
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
        context: Context,
        frameFiles: List<File>,
        outputFile: File,
        offsets: List<StarAligner.Offset>,
        applyBiasCorrection: Boolean,
        boostContrast: Boolean,
        filterLightPollution: Boolean
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

        // El orden importa: primero se limpia el ruido propio del sensor
        // (bias), y solo DESPUÉS se estira el contraste/color sobre una
        // imagen ya más limpia — estirar antes de restar el bias
        // amplificaría también ese ruido de fondo.
        if (applyBiasCorrection) {
            val masterBias = BiasCalibrator.loadMasterBias(context, width, height)
            if (masterBias != null) {
                subtractBias(resultPixels, masterBias)
                masterBias.recycle()
            } else {
                Log.w(TAG, "Corrección de bias activada pero no hay un bias maestro válido para esta resolución, se omite")
            }
        }

        if (boostContrast) {
            applyLuminanceContrastBoost(resultPixels)
        }

        if (filterLightPollution) {
            applyPerChannelContrastBoost(resultPixels)
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

    /** Resta, píxel a píxel y canal a canal, el bias maestro de la imagen apilada. */
    private fun subtractBias(pixels: IntArray, bias: Bitmap) {
        val width = bias.width
        val height = bias.height
        val biasPixels = IntArray(width * height)
        bias.getPixels(biasPixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            val p = pixels[i]
            val bp = biasPixels[i]
            val r = ((p shr 16) and 0xFF) - ((bp shr 16) and 0xFF)
            val g = ((p shr 8) and 0xFF) - ((bp shr 8) and 0xFF)
            val b = (p and 0xFF) - (bp and 0xFF)
            pixels[i] = (0xFF shl 24) or
                (r.coerceIn(0, 255) shl 16) or
                (g.coerceIn(0, 255) shl 8) or
                b.coerceIn(0, 255)
        }
    }

    /**
     * Estira el punto negro de la imagen ya apilada para separar más el
     * fondo del cielo de las estrellas: detecta, vía un histograma de
     * luminancia, el nivel de brillo por debajo del cual cae el
     * [BACKGROUND_PERCENTILE] de los píxeles (una buena estimación del
     * "fondo del cielo" en una foto nocturna, ya que domina el histograma),
     * lo lleva a negro puro, y reescala el resto del rango (ese nivel..255)
     * a 0..255. Los tres canales se estiran IGUAL (mismo punto negro), así
     * que esto no cambia el balance de color, solo el contraste general.
     */
    private fun applyLuminanceContrastBoost(pixels: IntArray) {
        val pixelCount = pixels.size
        if (pixelCount == 0) return

        val histogram = IntArray(256)
        for (i in 0 until pixelCount) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            histogram[(r * 299 + g * 587 + b * 114) / 1000]++
        }

        val blackPoint = percentileLevel(histogram, pixelCount)
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

    /**
     * Filtro de contaminación lumínica: igual que [applyLuminanceContrastBoost],
     * pero calculando y aplicando el punto negro de forma INDEPENDIENTE en
     * cada canal (R, G, B por separado) en vez de un único punto negro
     * compartido. La contaminación lumínica (sodio, mercurio, LED urbano...)
     * tiñe el fondo del cielo con un color parejo en toda la imagen
     * (típicamente naranja/verdoso); al normalizar cada canal a su propio
     * fondo, ese tinte se neutraliza —el mismo principio que un filtro
     * físico anti-contaminación lumínica— además de aumentar el contraste.
     */
    private fun applyPerChannelContrastBoost(pixels: IntArray) {
        val pixelCount = pixels.size
        if (pixelCount == 0) return

        val histR = IntArray(256)
        val histG = IntArray(256)
        val histB = IntArray(256)
        for (i in 0 until pixelCount) {
            val p = pixels[i]
            histR[(p shr 16) and 0xFF]++
            histG[(p shr 8) and 0xFF]++
            histB[p and 0xFF]++
        }

        val blackR = percentileLevel(histR, pixelCount)
        val blackG = percentileLevel(histG, pixelCount)
        val blackB = percentileLevel(histB, pixelCount)

        // Si algún canal ya está prácticamente saturado (punto negro >= 250),
        // estirarlo distorsionaría el color en vez de mejorarlo: en ese caso
        // se deja ese canal sin tocar (rango completo).
        val rangeR = if (blackR in 1..249) (255 - blackR) else 0
        val rangeG = if (blackG in 1..249) (255 - blackG) else 0
        val rangeB = if (blackB in 1..249) (255 - blackB) else 0
        if (rangeR == 0 && rangeG == 0 && rangeB == 0) return

        for (i in 0 until pixelCount) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val newR = if (rangeR > 0) (((r - blackR).coerceAtLeast(0) * 255) / rangeR).coerceIn(0, 255) else r
            val newG = if (rangeG > 0) (((g - blackG).coerceAtLeast(0) * 255) / rangeG).coerceIn(0, 255) else g
            val newB = if (rangeB > 0) (((b - blackB).coerceAtLeast(0) * 255) / rangeB).coerceIn(0, 255) else b
            pixels[i] = (0xFF shl 24) or (newR shl 16) or (newG shl 8) or newB
        }
    }

    /** Nivel (0..255) por debajo del cual cae el [BACKGROUND_PERCENTILE] de los píxeles según [histogram]. */
    private fun percentileLevel(histogram: IntArray, pixelCount: Int): Int {
        val targetCount = (pixelCount * BACKGROUND_PERCENTILE).toInt()
        var cumulative = 0
        for (level in 0..255) {
            cumulative += histogram[level]
            if (cumulative >= targetCount) return level
        }
        return 255
    }
}
