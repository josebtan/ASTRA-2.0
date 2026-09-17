package com.astra.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.util.Log
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Alinea estrellas entre fotogramas antes de apilarlos (stacking).
 *
 * Sin alineación, el "stacking" básico asume que la cámara estuvo
 * perfectamente fija durante toda la secuencia; cualquier movimiento del
 * trípode, o incluso la rotación de la Tierra durante sesiones largas, hace
 * que las estrellas se vean como rayas en vez de puntos nítidos al promediar.
 *
 * Esta implementación usa un método simple pero efectivo, común en stacking
 * básico: en una versión reducida de cada fotograma, encuentra el centroide
 * (centro de masa) ponderado por brillo de los píxeles que sobresalen
 * claramente del fondo (las estrellas son mucho más brillantes que el cielo
 * nocturno que las rodea). Comparando ese centroide contra el del primer
 * fotograma (la referencia), se obtiene cuánto desplazar cada fotograma en
 * X/Y para que sus estrellas queden alineadas con las del primero.
 *
 * No corrige rotación entre fotogramas (solo traslación X/Y), que es
 * suficiente para sesiones cortas/medianas con trípode; para sesiones muy
 * largas cerca del ecuador celeste, la rotación de campo puede seguir
 * causando algo de estela residual, igual que en cualquier alineación
 * simple por traslación.
 */
object StarAligner {

    private const val TAG = "StarAligner"

    // Las imágenes se reducen a este tamaño máximo de lado para calcular el
    // centroide: no hace falta resolución completa para encontrar "dónde
    // está la masa de estrellas", y así el análisis es rápido incluso con
    // fotos de muchos megapíxeles.
    private const val ANALYSIS_MAX_DIM = 500

    // Si el desplazamiento calculado supera esto (en píxeles, ya a
    // resolución completa), se descarta como probable error de detección
    // (p. ej. un avión o satélite cruzando el encuadre siendo más brillante
    // que las estrellas reales) y el fotograma se deja sin desplazar.
    private const val MAX_SHIFT_PX = 400

    data class Offset(val dx: Int, val dy: Int)

    /**
     * Calcula, para cada fotograma (en el mismo orden en que se van a
     * apilar), el desplazamiento en X/Y -en píxeles de la imagen a
     * resolución COMPLETA- necesario para alinear sus estrellas con las del
     * primer fotograma (que se usa como referencia, desplazamiento (0,0)).
     *
     * Si un fotograma no puede analizarse (no se decodifica, no se detectan
     * estrellas, o el desplazamiento calculado es irrazonablemente grande),
     * se le asigna desplazamiento (0,0) en vez de fallar todo el proceso.
     */
    fun computeAlignmentOffsets(frameFiles: List<File>): List<Offset> {
        if (frameFiles.isEmpty()) return emptyList()

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(frameFiles.first().absolutePath, bounds)
        val fullWidth = bounds.outWidth
        val fullHeight = bounds.outHeight
        if (fullWidth <= 0 || fullHeight <= 0) return frameFiles.map { Offset(0, 0) }

        var sampleSize = 1
        while (maxOf(fullWidth, fullHeight) / sampleSize > ANALYSIS_MAX_DIM * 2) {
            sampleSize *= 2
        }
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }

        var referenceCentroid: PointF? = null
        var scaleX = 1f
        var scaleY = 1f
        val offsets = ArrayList<Offset>(frameFiles.size)

        for ((index, file) in frameFiles.withIndex()) {
            val small = BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
            if (small == null) {
                Log.w(TAG, "No se pudo decodificar ${file.name} para alinear, se deja sin desplazar")
                offsets.add(Offset(0, 0))
                continue
            }

            val centroid = brightCentroidOf(small)
            if (index == 0 || referenceCentroid == null) {
                referenceCentroid = centroid
                scaleX = fullWidth.toFloat() / small.width
                scaleY = fullHeight.toFloat() / small.height
                offsets.add(Offset(0, 0))
            } else if (centroid == null) {
                Log.w(TAG, "No se detectaron estrellas en ${file.name}, se deja sin desplazar")
                offsets.add(Offset(0, 0))
            } else {
                val dx = ((referenceCentroid.x - centroid.x) * scaleX).roundToInt()
                val dy = ((referenceCentroid.y - centroid.y) * scaleY).roundToInt()
                if (kotlin.math.abs(dx) > MAX_SHIFT_PX || kotlin.math.abs(dy) > MAX_SHIFT_PX) {
                    Log.w(TAG, "Desplazamiento sospechosamente grande en ${file.name} (dx=$dx, dy=$dy), se ignora")
                    offsets.add(Offset(0, 0))
                } else {
                    offsets.add(Offset(dx, dy))
                }
            }
            small.recycle()
        }

        Log.d(TAG, "Offsets de alineación calculados: $offsets")
        return offsets
    }

    /**
     * Centroide ponderado por brillo de los píxeles que superan claramente
     * el brillo típico del fondo (media + 3 desviaciones estándar), o null
     * si no se detecta ningún píxel así de brillante (cielo completamente
     * uniforme, sin estrellas visibles).
     */
    private fun brightCentroidOf(bitmap: Bitmap): PointF? {
        val width = bitmap.width
        val height = bitmap.height
        val pixelCount = width * height
        val pixels = IntArray(pixelCount)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val luminance = FloatArray(pixelCount)
        var sum = 0.0
        for (i in 0 until pixelCount) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val l = 0.299f * r + 0.587f * g + 0.114f * b
            luminance[i] = l
            sum += l
        }
        val mean = sum / pixelCount
        var variance = 0.0
        for (i in 0 until pixelCount) {
            val diff = luminance[i] - mean
            variance += diff * diff
        }
        variance /= pixelCount
        val threshold = mean + 3.0 * sqrt(variance)

        var weightSum = 0.0
        var xSum = 0.0
        var ySum = 0.0
        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                val l = luminance[rowOffset + x]
                if (l > threshold) {
                    val weight = (l - threshold).toDouble()
                    weightSum += weight
                    xSum += x * weight
                    ySum += y * weight
                }
            }
        }

        if (weightSum <= 0.0) return null
        return PointF((xSum / weightSum).toFloat(), (ySum / weightSum).toFloat())
    }
}
