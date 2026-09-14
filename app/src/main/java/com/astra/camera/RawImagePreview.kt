package com.astra.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.File

/**
 * Extrae una vista previa visualizable de un archivo RAW (.dng).
 *
 * Decodificar por completo los píxeles crudos de un DNG (demosaicing)
 * requiere un procesamiento de imagen bastante más pesado que lo que
 * Android expone de forma sencilla, sobre todo en versiones más antiguas.
 * En cambio, prácticamente todo archivo DNG generado por una cámara
 * (incluidos los que produce CameraX/Camera2 mediante DngCreator) incluye
 * una vista previa JPEG embebida en sus metadatos EXIF, pensada exactamente
 * para este caso: mostrarla en una galería sin procesar el RAW completo.
 * Es el mismo enfoque que usan la mayoría de apps de galería y editores.
 */
object RawImagePreview {

    private const val TAG = "RawImagePreview"

    /** Devuelve un Bitmap con la vista previa del RAW, o null si no se pudo extraer ninguna. */
    fun loadPreview(file: File): Bitmap? {
        return try {
            val exif = ExifInterface(file.absolutePath)
            val thumbnail = if (exif.hasThumbnail()) exif.thumbnailBitmap else null
            thumbnail
                // Si el DNG no expone un thumbnail por el tag EXIF estándar,
                // se intenta una decodificación directa como último recurso:
                // en algunos dispositivos el propio BitmapFactory soporta
                // RAW de forma nativa (a partir de Android 9 aprox.).
                ?: BitmapFactory.decodeFile(file.absolutePath)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo extraer una vista previa de ${file.name}", e)
            null
        }
    }
}
