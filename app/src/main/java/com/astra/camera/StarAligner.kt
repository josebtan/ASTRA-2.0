package com.astra.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Alinea estrellas entre fotogramas antes de apilarlos (stacking), usando
 * correlación cruzada de "grueso a fino" (coarse-to-fine): la misma familia
 * de técnicas que usan las herramientas de registro de imágenes en general,
 * no solo software de astrofotografía.
 *
 * Por qué no un solo "centroide de brillo" (el enfoque anterior): un punto
 * promedio calculado sobre todos los píxeles brillantes de la imagen es
 * frágil — si distintas estrellas cruzan el umbral de brillo de un
 * fotograma a otro (por ruido, centelleo atmosférico, etc.), o si una sola
 * estrella muy brillante domina el cálculo, el "centroide" salta de forma
 * errática aunque la cámara no se haya movido en absoluto.
 *
 * En cambio, aquí se compara el patrón COMPLETO de brillo entre el
 * fotograma de referencia (el primero) y cada fotograma siguiente,
 * probando distintos desplazamientos (dx, dy) y quedándose con el que hace
 * que ambas imágenes se parezcan más (menor diferencia cuadrática media en
 * la zona donde se solapan). Esto es mucho más robusto porque usa toda la
 * información de la imagen (todas las estrellas, y su disposición relativa)
 * en vez de un único punto.
 *
 * Para que sea rápido, se hace en dos pasadas:
 * 1) Gruesa: una versión MUY reducida de la imagen (~60px), buscando en un
 *    rango amplio de desplazamientos. Da una estimación aproximada, barata.
 * 2) Fina: una versión más grande (~200px), afinando solo alrededor de la
 *    estimación gruesa (ya escalada), con un margen pequeño.
 *
 * Solo corrige traslación (X/Y), no rotación de campo — suficiente para
 * sesiones cortas o medianas con trípode, que es el caso de uso principal.
 */
object StarAligner {

    private const val TAG = "StarAligner"

    private const val COARSE_WIDTH = 60
    private const val COARSE_SEARCH_RANGE = 14

    private const val FINE_WIDTH = 200
    private const val FINE_SEARCH_MARGIN = 6

    // Al menos esta fracción de la imagen reducida debe seguir solapándose
    // tras el desplazamiento probado; si no, ese desplazamiento se descarta
    // por poco fiable (muy poca superposición para comparar en serio).
    private const val MIN_OVERLAP_FRACTION = 0.5

    // Si el desplazamiento final (ya a resolución completa) supera esto en
    // píxeles, se descarta como probable error (p. ej. una nube pasajera o
    // un objeto en movimiento dominando la comparación) y el fotograma
    // queda sin desplazar.
    private const val MAX_SHIFT_PX = 400

    data class Offset(val dx: Int, val dy: Int)

    private class GrayImage(val width: Int, val height: Int, val data: FloatArray)

    /**
     * Calcula, para cada fotograma (en el mismo orden en que se van a
     * apilar), el desplazamiento en X/Y -en píxeles de la imagen a
     * resolución COMPLETA- necesario para alinear sus estrellas con las del
     * primer fotograma (que se usa como referencia, desplazamiento (0,0)).
     *
     * Si un fotograma no puede analizarse, o el desplazamiento calculado es
     * irrazonablemente grande, se le asigna desplazamiento (0,0) en vez de
     * fallar todo el proceso.
     */
    fun computeAlignmentOffsets(frameFiles: List<File>): List<Offset> {
        if (frameFiles.isEmpty()) return emptyList()

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(frameFiles.first().absolutePath, bounds)
        val fullWidth = bounds.outWidth
        val fullHeight = bounds.outHeight
        if (fullWidth <= 0 || fullHeight <= 0) {
            return List(frameFiles.size) { Offset(0, 0) }
        }

        val offsets = ArrayList<Offset>(frameFiles.size)
        var refCoarse: GrayImage? = null
        var refFine: GrayImage? = null

        for ((index, file) in frameFiles.withIndex()) {
            if (index == 0) {
                refCoarse = decodeGrayscale(file, COARSE_WIDTH)
                refFine = decodeGrayscale(file, FINE_WIDTH)
                offsets.add(Offset(0, 0))
                continue
            }

            val coarse = decodeGrayscale(file, COARSE_WIDTH)
            val fine = decodeGrayscale(file, FINE_WIDTH)
            if (coarse == null || fine == null || refCoarse == null || refFine == null) {
                Log.w(TAG, "No se pudo decodificar ${file.name} para alinear, se deja sin desplazar")
                offsets.add(Offset(0, 0))
                continue
            }

            // Etapa 1: búsqueda gruesa, rango amplio, imagen muy pequeña.
            val coarseOffset = bestOffset(
                refCoarse, coarse,
                rangeX = COARSE_SEARCH_RANGE, rangeY = COARSE_SEARCH_RANGE
            )

            // Etapa 2: refina en una imagen más grande, alrededor de la
            // estimación gruesa (escalada), con un margen pequeño.
            val scaleUp = fine.width.toFloat() / coarse.width
            val centerX = (coarseOffset.first * scaleUp).roundToInt()
            val centerY = (coarseOffset.second * scaleUp).roundToInt()
            val fineOffset = bestOffset(
                refFine, fine,
                rangeX = FINE_SEARCH_MARGIN, rangeY = FINE_SEARCH_MARGIN,
                centerX = centerX, centerY = centerY
            )

            val scaleToFull = fullWidth.toFloat() / fine.width
            val dx = (fineOffset.first * scaleToFull).roundToInt()
            val dy = (fineOffset.second * scaleToFull).roundToInt()

            if (abs(dx) > MAX_SHIFT_PX || abs(dy) > MAX_SHIFT_PX) {
                Log.w(TAG, "Desplazamiento sospechosamente grande en ${file.name} (dx=$dx, dy=$dy), se ignora")
                offsets.add(Offset(0, 0))
            } else {
                offsets.add(Offset(dx, dy))
            }
        }

        Log.d(TAG, "Offsets de alineación calculados: $offsets")
        return offsets
    }

    /**
     * Decodifica [file] en escala de grises, reducida a un ancho aproximado
     * de [targetWidth] (con el alto ajustado proporcionalmente). Usa
     * inSampleSize primero para que la decodificación en sí ya sea barata,
     * y luego un reescalado fino al tamaño exacto buscado.
     */
    private fun decodeGrayscale(file: File, targetWidth: Int): GrayImage? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (bounds.outWidth / sampleSize > targetWidth * 2) sampleSize *= 2
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val sampled = BitmapFactory.decodeFile(file.absolutePath, decodeOptions) ?: return null

        val scale = targetWidth.toFloat() / sampled.width
        val targetHeight = (sampled.height * scale).roundToInt().coerceAtLeast(1)

        val scaled = if (sampled.width == targetWidth && sampled.height == targetHeight) {
            sampled
        } else {
            val resized = Bitmap.createScaledBitmap(sampled, targetWidth, targetHeight, true)
            if (resized !== sampled) sampled.recycle()
            resized
        }

        val width = scaled.width
        val height = scaled.height
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        scaled.recycle()

        val data = FloatArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            data[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }
        return GrayImage(width, height, data)
    }

    /**
     * Busca, dentro de [centerX]±[rangeX] y [centerY]±[rangeY], el
     * desplazamiento (dx, dy) que minimiza la diferencia cuadrática media
     * entre [reference] y [candidate] en su zona de solapamiento.
     */
    private fun bestOffset(
        reference: GrayImage,
        candidate: GrayImage,
        rangeX: Int,
        rangeY: Int,
        centerX: Int = 0,
        centerY: Int = 0
    ): Pair<Int, Int> {
        var bestScore = Double.MAX_VALUE
        var bestDx = centerX
        var bestDy = centerY

        for (dy in (centerY - rangeY)..(centerY + rangeY)) {
            for (dx in (centerX - rangeX)..(centerX + rangeX)) {
                val score = meanSquaredDiff(reference, candidate, dx, dy)
                if (score >= 0.0 && score < bestScore) {
                    bestScore = score
                    bestDx = dx
                    bestDy = dy
                }
            }
        }
        return bestDx to bestDy
    }

    /**
     * Diferencia cuadrática media entre [reference] y [candidate] cuando
     * este último se desplaza (dx, dy) respecto al primero, calculada solo
     * sobre la región donde ambas se solapan tras el desplazamiento.
     * Devuelve -1.0 si el solapamiento resultante es demasiado pequeño
     * para ser una comparación fiable.
     */
    private fun meanSquaredDiff(reference: GrayImage, candidate: GrayImage, dx: Int, dy: Int): Double {
        val width = reference.width
        val height = reference.height

        val xStart = maxOf(0, dx)
        val xEnd = minOf(width, width + dx)
        val yStart = maxOf(0, dy)
        val yEnd = minOf(height, height + dy)
        if (xStart >= xEnd || yStart >= yEnd) return -1.0

        var sum = 0.0
        var count = 0
        for (y in yStart until yEnd) {
            val srcY = y - dy
            val destRow = y * width
            val srcRow = srcY * width
            for (x in xStart until xEnd) {
                val srcX = x - dx
                val diff = reference.data[destRow + x] - candidate.data[srcRow + srcX]
                sum += diff * diff
                count++
            }
        }

        val minOverlap = (width * height * MIN_OVERLAP_FRACTION).toInt()
        if (count < minOverlap) return -1.0
        return sum / count
    }
}
