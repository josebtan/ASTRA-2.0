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
import android.util.Range
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

    // La foto de la cámara puede tener 12MP o más (p. ej. 4032x3024), una
    // resolución que muchísimos encoders de hardware simplemente no soportan
    // para video (a diferencia de fotos). Se limita el lado largo del video
    // final a este valor, de sobra para un timelapse y compatible con
    // prácticamente cualquier dispositivo Android.
    private const val MAX_VIDEO_DIMENSION = 1280

    /**
     * Construye el video en un hilo secundario y notifica el resultado en el
     * hilo principal a través de [onComplete]. Si falla, [onComplete] recibe
     * además un mensaje con la etapa y la excepción exactas (se registra
     * también en Logcat con el tag [TAG]), para poder diagnosticar el fallo
     * sin necesidad de reproducirlo con un depurador conectado.
     */
    fun buildVideoAsync(
        frameFiles: List<File>,
        outputFile: File,
        frameRate: Int,
        onComplete: (success: Boolean, errorDetail: String?) -> Unit
    ) {
        Thread({
            var errorDetail: String? = null
            val success = try {
                buildVideo(frameFiles, outputFile, frameRate)
                true
            } catch (e: Exception) {
                errorDetail = "${e.javaClass.simpleName}: ${e.message}"
                Log.e(TAG, "Error generando el video del timelapse ($errorDetail)", e)
                outputFile.delete()
                false
            }
            Handler(Looper.getMainLooper()).post { onComplete(success, errorDetail) }
        }, "astra-timelapse-encoder").start()
    }

    private fun buildVideo(frameFiles: List<File>, outputFile: File, frameRate: Int) {
        require(frameFiles.isNotEmpty()) { "No hay fotogramas para generar el video" }
        Log.d(TAG, "Iniciando build: ${frameFiles.size} fotogramas, ${frameRate}fps solicitados -> ${outputFile.absolutePath}")

        // Se usa el tamaño del primer fotograma para calcular la resolución
        // del video, pero SIEMPRE escalada a un tamaño razonable para video
        // (ver [MAX_VIDEO_DIMENSION]): usar la resolución completa de la
        // foto (a menudo 12MP+) es la causa más común de que el encoder
        // falle al configurarse en muchos dispositivos.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(frameFiles.first().absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) {
            "No se pudo leer la resolución del primer fotograma (${frameFiles.first().absolutePath}); " +
                "puede que el archivo esté corrupto o incompleto"
        }
        Log.d(TAG, "Fotograma original: ${bounds.outWidth}x${bounds.outHeight}")

        val codecInfo = try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .firstOrNull { info -> info.isEncoder && info.supportedTypes.any { it.equals(MIME_TYPE, ignoreCase = true) } }
                ?: throw IllegalStateException("El dispositivo no reporta ningún encoder para $MIME_TYPE")
        } catch (e: Exception) {
            throw IllegalStateException("Fallo al listar encoders del dispositivo: ${e.message}", e)
        }
        Log.d(TAG, "Encoder elegido: ${codecInfo.name}")

        val videoCapabilities = try {
            codecInfo.getCapabilitiesForType(MIME_TYPE).videoCapabilities
        } catch (e: Exception) {
            Log.w(TAG, "No se pudieron leer las capacidades de video del encoder, se continúa sin validarlas", e)
            null
        }
        if (videoCapabilities != null) {
            Log.d(
                TAG,
                "Capacidades del encoder: anchos=${videoCapabilities.supportedWidths}, " +
                    "altos=${videoCapabilities.supportedHeights}, bitrates=${videoCapabilities.bitrateRange}"
            )
        }

        val (width, height) = resolveEncodeSize(bounds.outWidth, bounds.outHeight, videoCapabilities)
        val colorFormat = selectColorFormat(codecInfo)
        val safeFrameRate = clampFrameRate(frameRate, width, height, videoCapabilities)
        Log.d(
            TAG,
            "Resolución final: ${width}x$height, colorFormat=$colorFormat, frameRate=$safeFrameRate " +
                "(soportado=${videoCapabilities?.isSizeSupported(width, height)})"
        )

        val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, safeFrameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
        }

        val encoder = try {
            MediaCodec.createEncoderByType(MIME_TYPE)
        } catch (e: Exception) {
            throw IllegalStateException("MediaCodec.createEncoderByType($MIME_TYPE) falló: ${e.message}", e)
        }
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            encoder.release()
            throw IllegalStateException(
                "encoder.configure() falló con formato=$format (resolución ${width}x$height, " +
                    "colorFormat=$colorFormat): ${e.message}",
                e
            )
        }
        try {
            encoder.start()
        } catch (e: Exception) {
            encoder.release()
            throw IllegalStateException("encoder.start() falló: ${e.message}", e)
        }
        Log.d(TAG, "Encoder configurado e iniciado correctamente")

        val muxer = try {
            MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (e: Exception) {
            encoder.stop()
            encoder.release()
            throw IllegalStateException("No se pudo crear el MediaMuxer en ${outputFile.absolutePath}: ${e.message}", e)
        }
        var trackIndex = -1
        var muxerStarted = false
        val bufferInfo = MediaCodec.BufferInfo()
        val frameDurationUs = 1_000_000L / safeFrameRate
        var presentationTimeUs = 0L

        fun drainEncoder(blockUntilEos: Boolean) {
            var waitAttempts = 0
            while (true) {
                val outIndex = encoder.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!blockUntilEos) return
                        waitAttempts++
                        if (waitAttempts > 500) {
                            // ~5s sin recibir el buffer de fin de stream: el
                            // encoder está atascado. Mejor abortar con un
                            // error claro que colgar la app indefinidamente
                            // (esto es justo lo que causaba que el timelapse
                            // se quedara para siempre en "generando video").
                            throw IllegalStateException(
                                "El encoder nunca devolvió el buffer de salida con BUFFER_FLAG_END_OF_STREAM " +
                                    "tras $waitAttempts intentos (~5s); posible cuelgue del encoder de hardware"
                            )
                        }
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        trackIndex = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                        Log.d(TAG, "Muxer iniciado con formato: ${encoder.outputFormat}")
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
                            } else if (bufferInfo.size != 0) {
                                // No debería pasar (el muxer siempre se inicia con
                                // INFO_OUTPUT_FORMAT_CHANGED antes del primer dato),
                                // pero si pasa, se pierde ese fotograma silenciosamente
                                // sin esto: se deja constancia en el log.
                                Log.w(TAG, "Datos codificados descartados: el muxer aún no había iniciado")
                            }
                        }
                        encoder.releaseOutputBuffer(outIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }

        try {
            for ((frameIndex, file) in frameFiles.withIndex()) {
                val bitmap = decodeAndFitBitmap(file, width, height)
                if (bitmap == null) {
                    Log.w(TAG, "Fotograma $frameIndex (${file.name}) no se pudo decodificar, se omite")
                    continue
                }
                val frameBytes = bitmapToYuv(bitmap, width, height, colorFormat)
                bitmap.recycle()

                var queued = false
                var waitAttempts = 0
                while (!queued) {
                    val inputIndex = encoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = encoder.getInputBuffer(inputIndex)
                            ?: throw IllegalStateException("encoder.getInputBuffer($inputIndex) devolvió null en el fotograma $frameIndex")
                        inputBuffer.clear()
                        inputBuffer.put(frameBytes)
                        encoder.queueInputBuffer(inputIndex, 0, frameBytes.size, presentationTimeUs, 0)
                        presentationTimeUs += frameDurationUs
                        queued = true
                    } else {
                        waitAttempts++
                        if (waitAttempts > 500) {
                            // ~5s sin conseguir un input buffer libre: el encoder
                            // está atascado, mejor abortar con un error claro que
                            // colgar la app indefinidamente.
                            throw IllegalStateException(
                                "El encoder no liberó ningún input buffer tras $waitAttempts intentos " +
                                    "(fotograma $frameIndex de ${frameFiles.size})"
                            )
                        }
                    }
                    drainEncoder(false)
                }
                if (frameIndex % 20 == 0) {
                    Log.d(TAG, "Codificado fotograma $frameIndex de ${frameFiles.size}")
                }
            }
            // Fin del stream: en modo ByteBuffer (sin Surface de entrada) esto
            // se señala encolando un último buffer de entrada VACÍO con el
            // flag BUFFER_FLAG_END_OF_STREAM — signalEndOfInputStream() no es
            // válido aquí porque es exclusivo del modo con Surface.
            var eosQueued = false
            var eosWaitAttempts = 0
            while (!eosQueued) {
                val inputIndex = encoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                if (inputIndex >= 0) {
                    encoder.queueInputBuffer(inputIndex, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    eosQueued = true
                    Log.d(TAG, "Buffer de fin de stream encolado")
                } else {
                    eosWaitAttempts++
                    if (eosWaitAttempts > 500) {
                        throw IllegalStateException("El encoder no liberó ningún input buffer para señalar el fin del stream")
                    }
                }
                drainEncoder(false)
            }
            drainEncoder(true)
            Log.d(TAG, "Codificación completa, muxerStarted=$muxerStarted")
            if (!muxerStarted) {
                throw IllegalStateException(
                    "El muxer nunca llegó a iniciarse (no se recibió INFO_OUTPUT_FORMAT_CHANGED); " +
                        "el archivo de salida quedaría vacío/corrupto"
                )
            }
        } finally {
            try {
                encoder.stop()
            } catch (e: Exception) {
                Log.w(TAG, "encoder.stop() lanzó una excepción (se ignora, ya se procesó lo necesario)", e)
            }
            encoder.release()
            if (muxerStarted) {
                try {
                    muxer.stop()
                } catch (e: Exception) {
                    Log.w(TAG, "muxer.stop() lanzó una excepción", e)
                }
            }
            muxer.release()
        }
    }

    /**
     * Calcula la resolución final del video: escala la foto original para
     * que su lado largo no supere [MAX_VIDEO_DIMENSION] (manteniendo la
     * proporción), redondea a múltiplos de 16 (alineación de macrobloque
     * que casi todos los encoders de hardware requieren) y, si el encoder
     * expone sus capacidades reales, ajusta el resultado a un tamaño que
     * el propio dispositivo confirme como soportado.
     */
    private fun resolveEncodeSize(
        originalWidth: Int,
        originalHeight: Int,
        capabilities: MediaCodecInfo.VideoCapabilities?
    ): Pair<Int, Int> {
        val scale = MAX_VIDEO_DIMENSION.toFloat() / maxOf(originalWidth, originalHeight)
        val targetWidth = if (scale < 1f) (originalWidth * scale).toInt() else originalWidth
        val targetHeight = if (scale < 1f) (originalHeight * scale).toInt() else originalHeight

        var width = alignTo16(targetWidth)
        var height = alignTo16(targetHeight)

        if (capabilities != null) {
            width = capabilities.supportedWidths.clamp(width)
            height = capabilities.supportedHeights.clamp(height)
            width = alignTo16(width)
            height = alignTo16(height)
            // Si aun así el par (width, height) no es válido para este
            // encoder en particular, se reduce gradualmente hasta que lo sea.
            var attempts = 0
            while (!capabilities.isSizeSupported(width, height) && attempts < 6) {
                width = alignTo16((width * 0.85f).toInt())
                height = alignTo16((height * 0.85f).toInt())
                attempts++
            }
        }

        return Pair(width.coerceAtLeast(16), height.coerceAtLeast(16))
    }

    private fun alignTo16(value: Int): Int = (value / 16).coerceAtLeast(1) * 16

    private fun Range<Int>.clamp(value: Int): Int = value.coerceIn(lower, upper)

    /**
     * Ajusta el framerate elegido por el usuario al rango que el encoder
     * realmente soporta para la resolución final calculada. Si no se puede
     * consultar (algunos dispositivos no lo exponen para todos los tamaños),
     * se deja el valor original tal cual.
     */
    private fun clampFrameRate(
        requestedFps: Int,
        width: Int,
        height: Int,
        capabilities: MediaCodecInfo.VideoCapabilities?
    ): Int {
        if (capabilities == null) return requestedFps
        return try {
            val range: Range<Double> = capabilities.getSupportedFrameRatesFor(width, height)
            requestedFps.toDouble().coerceIn(range.lower, range.upper).toInt().coerceAtLeast(1)
        } catch (e: IllegalArgumentException) {
            requestedFps
        }
    }

    /** Elige un formato de color YUV420 soportado por el codificador H.264 del dispositivo. */
    private fun selectColorFormat(codecInfo: MediaCodecInfo): Int {
        val supported = codecInfo.getCapabilitiesForType(MIME_TYPE)?.colorFormats ?: intArrayOf()
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
