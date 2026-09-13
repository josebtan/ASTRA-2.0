package com.astra.camera

import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * Genera miniaturas para los videos de timelapse guardados en la galería de
 * la app. Se usa la API clásica de [ThumbnailUtils] (funciona en todas las
 * versiones soportadas, minSdk 26 en adelante); está marcada como obsoleta a
 * partir de API 29 pero sigue funcionando y evita tener que ramificar el
 * código según la versión de Android.
 */
object MediaThumbnails {

    private const val TAG = "MediaThumbnails"

    @Suppress("DEPRECATION")
    fun createVideoThumbnail(file: File): Bitmap? {
        return try {
            ThumbnailUtils.createVideoThumbnail(file.absolutePath, MediaStore.Video.Thumbnails.MINI_KIND)
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo generar la miniatura del video ${file.name}", e)
            null
        }
    }
}
