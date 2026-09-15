package com.astra.camera

import android.content.res.Configuration
import android.util.Log
import android.view.View
import android.view.ViewGroup

/**
 * ViewGroup que rota sus hijos aplicando verdadera transformación de espacio.
 *
 * A diferencia de View.setRotation(), este grupo sobreescribe onMeasure para
 * intercambiar ancho/alto cuando el ángulo es 90° o 270°, de modo que el
 * padre reserva el espacio físico correcto.
 *
 * Nota: onLayout aplica rotación visual a los hijos via su atributo `rotation`.
 * Para que el layout funcione correctamente con rotaciones de 90°/270°, los
 * hijos deben tener layout_params que permitsn cambiar de ancho/alto (e.g.
 * MATCH_PARENT + wrap_content o weight).
 */
class RotateLayout(context: android.content.Context, attrs: android.util.AttributeSet?) :
    ViewGroup(context, attrs) {

    private var rotationDegrees: Float = 0f
        private set

    init {
        setWillNotDraw(false)
    }

    fun setRotationDegrees(degrees: Float) {
        val normalized = when (degrees.toInt() % 360) {
            in 0..45 || in 315..360 -> 0f
            in 45..135 -> 90f
            in 135..225 -> 180f
            else -> 270f
        }
        if (normalized != rotationDegrees) {
            rotationDegrees = normalized
            requestLayout()
            invalidate()
        }
    }

    fun getRotationDegrees(): Float = rotationDegrees

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        // Si hay rotación de 90° o 270°, intercambiar las dimensiones medidas
        // para que el padre reserve el espacio físico correcto.
        if (rotationDegrees % 180f == 90f) {
            val oldWidth = measuredWidth
            val oldHeight = measuredHeight
            // Medir hijos con specs intercambiadas para que respeten la rotación
            measureChildren(
                MeasureSpec.makeMeasureSpec(oldHeight, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(oldWidth, MeasureSpec.EXACTLY)
            )
            // Reportar dimensiones intercambiadas al padre
            setMeasuredDimension(oldHeight, oldWidth)
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        // Colocar niños en (0,0) con sus medidas actuales
        // La rotación visual se aplicará via el atributo rotation de cada child
        val count = childCount
        for (i in 0 until count) {
            val child = getChildAt(i)
            if (child.visibility != View.GONE) {
                child.layout(l, t, l + child.measuredWidth, t + child.measuredHeight)
            }
        }

        // Aplicar rotación visual a los hijos
        rotateChildren()
    }

    private fun rotateChildren() {
        val degrees = rotationDegrees
        if (degrees == 0f) return

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            child.rotation = degrees
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        requestLayout()
    }
}