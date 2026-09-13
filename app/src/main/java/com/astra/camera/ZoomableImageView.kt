package com.astra.camera

import android.content.Context
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.min

/**
 * [android.widget.ImageView] con zoom táctil para la galería de ASTRA:
 * - Pellizcar (pinch) para acercar/alejar, entre 1x y [MAX_SCALE].
 * - Doble toque para alternar entre 1x y un zoom intermedio.
 * - Arrastrar con un dedo para desplazarse por la imagen ampliada.
 *
 * Usa [ScaleType.MATRIX] internamente y gestiona su propia matriz de
 * transformación; no requiere ninguna librería externa.
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val drawMatrix = Matrix()
    private val matrixValues = FloatArray(9)

    private var currentScale = MIN_SCALE
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDragging = false

    private val scaleGestureDetector = ScaleGestureDetector(context, ScaleListener())
    private val gestureDetector = GestureDetector(context, GestureListener())

    /**
     * Permite desactivar el zoom (por ejemplo, cuando se muestra un ícono de
     * marcador de posición en vez de una foto real, como con los archivos RAW).
     * Al desactivarlo se usa el comportamiento estándar de ImageView.
     */
    var zoomEnabled: Boolean = true
        set(value) {
            field = value
            scaleType = if (value) ScaleType.MATRIX else ScaleType.CENTER_INSIDE
            if (value) resetMatrix()
        }

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!zoomEnabled) return super.onTouchEvent(event)

        scaleGestureDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                isDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1 && currentScale > MIN_SCALE + 0.01f) {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    drawMatrix.postTranslate(dx, dy)
                    constrainTranslation()
                    imageMatrix = drawMatrix
                    lastTouchX = event.x
                    lastTouchY = event.y
                    isDragging = true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
            }
        }
        // El padre (ScrollView/ViewPager, etc.) no debe interceptar el gesto
        // mientras se está desplazando o haciendo zoom sobre la imagen.
        parent?.requestDisallowInterceptTouchEvent(currentScale > MIN_SCALE + 0.01f || isDragging)
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (zoomEnabled) resetMatrix()
    }

    override fun setImageDrawable(drawable: android.graphics.drawable.Drawable?) {
        super.setImageDrawable(drawable)
        if (zoomEnabled) resetMatrix()
    }

    override fun setImageResource(resId: Int) {
        super.setImageResource(resId)
        if (zoomEnabled) resetMatrix()
    }

    override fun setImageURI(uri: android.net.Uri?) {
        super.setImageURI(uri)
        if (zoomEnabled) resetMatrix()
    }

    /** Ajusta la imagen centrada tipo "fit center" y resetea el zoom a 1x. */
    private fun resetMatrix() {
        val drawableRef = drawable ?: return
        if (width == 0 || height == 0) return

        val drawableWidth = drawableRef.intrinsicWidth.toFloat()
        val drawableHeight = drawableRef.intrinsicHeight.toFloat()
        if (drawableWidth <= 0f || drawableHeight <= 0f) return

        val scale = min(width / drawableWidth, height / drawableHeight)
        val dx = (width - drawableWidth * scale) / 2f
        val dy = (height - drawableHeight * scale) / 2f

        drawMatrix.reset()
        drawMatrix.postScale(scale, scale)
        drawMatrix.postTranslate(dx, dy)
        imageMatrix = drawMatrix
        currentScale = MIN_SCALE
    }

    /** Evita que, al hacer zoom o arrastrar, la imagen se salga de los límites visibles. */
    private fun constrainTranslation() {
        val drawableRef = drawable ?: return
        drawMatrix.getValues(matrixValues)

        val scaleX = matrixValues[Matrix.MSCALE_X]
        val drawableWidth = drawableRef.intrinsicWidth * scaleX
        val drawableHeight = drawableRef.intrinsicHeight * scaleX
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        val fixedTransX = if (drawableWidth <= viewWidth) {
            (viewWidth - drawableWidth) / 2f
        } else {
            matrixValues[Matrix.MTRANS_X].coerceIn(viewWidth - drawableWidth, 0f)
        }

        val fixedTransY = if (drawableHeight <= viewHeight) {
            (viewHeight - drawableHeight) / 2f
        } else {
            matrixValues[Matrix.MTRANS_Y].coerceIn(viewHeight - drawableHeight, 0f)
        }

        matrixValues[Matrix.MTRANS_X] = fixedTransX
        matrixValues[Matrix.MTRANS_Y] = fixedTransY
        drawMatrix.setValues(matrixValues)
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val newScale = (currentScale * detector.scaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)
            val factor = if (currentScale == 0f) 1f else newScale / currentScale
            currentScale = newScale
            drawMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
            constrainTranslation()
            imageMatrix = drawMatrix
            return true
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            val targetScale = if (currentScale > MIN_SCALE + 0.01f) MIN_SCALE else DOUBLE_TAP_SCALE
            val factor = targetScale / currentScale
            currentScale = targetScale
            if (targetScale == MIN_SCALE) {
                resetMatrix()
            } else {
                drawMatrix.postScale(factor, factor, e.x, e.y)
                constrainTranslation()
                imageMatrix = drawMatrix
            }
            return true
        }

        override fun onDown(e: MotionEvent): Boolean = true
    }

    companion object {
        private const val MIN_SCALE = 1f
        private const val MAX_SCALE = 5f
        private const val DOUBLE_TAP_SCALE = 2.5f
    }
}
