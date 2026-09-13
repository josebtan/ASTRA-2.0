package com.astra.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File

/**
 * Genera un video MP4 a partir de una lista ordenada de fotogramas JPEG,
 * usando [MediaCodec] (codificador H.264 por hardware/software del propio
 * dispositivo) y [MediaMuxer], sin depender de ninguna librería externa
 * (ni FFmpeg ni similares).
 *
 * Es un encoder simple basado en ByteBuffers (no usa Surface/OpenGL), por lo
 * que cada fotograma se convierte manualmente a YUV420 antes de entregarlo
 * al codificador. Es más lento que un pipeline con Surface, pero para un
 * timelapse (decenas/cientos de fotos, no miles) es más que suficiente y
 * evita la complejidad adicional de EGL/GLES.
 */
object TimelapseVideoBuilder {

    private const val TAG = "TimelapseVideoBuilder"
    private const val MIME_TYPE = "video/avc"
    private const val BIT_RATE = 6_000_000
    private const val I_FRAME_INTERVAL = 1
    private const val DEQUEUE_TIMEOUT_US = 10_000L

    /**
     * Construye el video en un hilo secundario y notifica el resultado en el
     * hilo principal a través de [onComplete].
     */
    fun buildVideoAsync(
        frameFiles: List<File>,
        outputFile: File,
        frameRate: Int,
        onComplete: (success: Boolean) -> Unit
    ) {
        Thread({
            val success = try {
                buildVideo(frameFiles, outputFile, frameRate)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error generando el video del timelapse", e)
                outputFile.delete()
                false
            }
            Handler(Looper.getMainLooper()).post { onComplete(success) }
        }, "astra-timelapse-encoder").start()
    }

    private fun buildVideo(frameFiles: List<File>, outputFile: File, frameRate: Int) {
        require(frameFiles.isNotEmpty()) { "No hay fotogramas para generar el video" }

        // Se usa el tamaño del primer fotograma como resolución del video;
        // el resto se escala a este tamaño si por algún motivo difiere.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(frameFiles.first().absolutePath, bounds)
        // El encoder H.264 requiere dimensiones pares.
        val width = bounds.outWidth - (bounds.outWidth % 2)
        val height = bounds.outHeight - (bounds.outHeight % 2)
        require(width > 0 && height > 0) { "Resolución de fotograma inválida" }

        val colorFormat = selectColorFormat()

        val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
        }

        val encoder = MediaCodec.createEncoderByType(MIME_TYPE)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIndex = -1
        var muxerStarted = false
        val bufferInfo = MediaCodec.BufferInfo()
        val frameDurationUs = 1_000_000L / frameRate
        var presentationTimeUs = 0L

        fun drainEncoder(endOfStream: Boolean) {
            if (endOfStream) encoder.signalEndOfInputStream()
            while (true) {
                val outIndex = encoder.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        trackIndex = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    outIndex >= 0 -> {
                        val encodedData = encoder.getOutputBuffer(outIndex)
                        if (encodedData != null) {
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                bufferInfo.size = 0
                            }
                            if (bufferInfo.size != 0 && muxerStarted) {
                                encodedData.position(bufferInfo.offset)
                                encodedData.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                            }
                        }
                        encoder.releaseOutputBuffer(outIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }

        try {
            for (file in frameFiles) {
                val bitmap = decodeAndFitBitmap(file, width, height) ?: continue
                val frameBytes = bitmapToYuv(bitmap, width, height, colorFormat)
                bitmap.recycle()

                var queued = false
                while (!queued) {
                    val inputIndex = encoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = encoder.getInputBuffer(inputIndex)
                        inputBuffer?.clear()
                        inputBuffer?.put(frameBytes)
                        encoder.queueInputBuffer(inputIndex, 0, frameBytes.size, presentationTimeUs, 0)
                        presentationTimeUs += frameDurationUs
                        queued = true
                    }
                    drainEncoder(false)
                }
            }
            drainEncoder(true)
        } finally {
            encoder.stop()
            encoder.release()
            if (muxerStarted) muxer.stop()
            muxer.release()
        }
    }

    /** Elige un formato de color YUV420 soportado por el codificador H.264 del dispositivo. */
    private fun selectColorFormat(): Int {
        val codecInfo = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .firstOrNull { info -> info.isEncoder && info.supportedTypes.any { it.equals(MIME_TYPE, ignoreCase = true) } }
        val supported = codecInfo?.getCapabilitiesForType(MIME_TYPE)?.colorFormats ?: intArrayOf()
        return when {
            supported.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) ->
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
            supported.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) ->
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
            else -> MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        }
    }

    private fun decodeAndFitBitmap(file: File, targetWidth: Int, targetHeight: Int): Bitmap? {
        val original = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        if (original.width == targetWidth && original.height == targetHeight) return original
        val scaled = Bitmap.createScaledBitmap(original, targetWidth, targetHeight, true)
        if (scaled !== original) original.recycle()
        return scaled
    }

    /**
     * Convierte un Bitmap ARGB_8888 al formato de color indicado:
     * - [MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar] (NV12): plano Y seguido de un plano UV intercalado.
     * - Cualquier otro caso (Planar/Flexible): I420, plano Y, luego plano U, luego plano V.
     */
    private fun bitmapToYuv(bitmap: Bitmap, width: Int, height: Int, colorFormat: Int): ByteArray {
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val frameSize = width * height
        val yuv = ByteArray(frameSize + frameSize / 2)
        val useSemiPlanar = colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar

        var yIndex = 0
        var uIndex = frameSize
        var vIndex = frameSize + frameSize / 4
        var uvIndex = frameSize // solo para semi-planar (NV12: U y V intercalados)

        var pixelIndex = 0
        for (row in 0 until height) {
            for (col in 0 until width) {
                val pixel = pixels[pixelIndex]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yuv[yIndex++] = y.coerceIn(0, 255).toByte()

                if (row % 2 == 0 && col % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    if (useSemiPlanar) {
                        yuv[uvIndex++] = u.coerceIn(0, 255).toByte()
                        yuv[uvIndex++] = v.coerceIn(0, 255).toByte()
                    } else {
                        yuv[uIndex++] = u.coerceIn(0, 255).toByte()
                        yuv[vIndex++] = v.coerceIn(0, 255).toByte()
                    }
                }
                pixelIndex++
            }
        }
        return yuv
    }
}
