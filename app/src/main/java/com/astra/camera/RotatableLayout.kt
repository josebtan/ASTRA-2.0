package com.astra.camera

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup

/**
 * Contenedor de un único hijo que lo rota visualmente mientras le reporta a
 * su propio padre el tamaño YA ROTADO (ancho y alto intercambiados cuando el
 * ángulo es 90° o -90°).
 *
 * Esto es lo que resuelve el recorte/solapamiento de raíz: aplicar
 * `View.rotation` directamente sobre una vista NO le avisa a su contenedor
 * que el espacio visual que ocupa cambió (rotation es puramente una
 * transformación de dibujo, no afecta el layout). Por eso una fila con
 * `wrap_content` calculada ANTES de rotar el valor se queda con el alto
 * "sin rotar", y el valor rotado termina recortado o solapando la fila
 * vecina. Este ViewGroup en cambio intercambia ancho/alto en `onMeasure`
 * cuando va a rotar 90°/-90°, así que el padre (la fila) sí reserva el
 * espacio real. Es la misma técnica clásica ("RotateLayout") que usan varias
 * apps de cámara de código abierto para orientar controles sin cambiar la
 * orientación de la Activity.
 *
 * IMPORTANTE (bug ya corregido una vez, dejarlo documentado para no
 * reintroducirlo): el setter de [angle] NUNCA debe condicionar su
 * `requestLayout()` a "si el valor cambió" (ej. `if (field != value) {...}`).
 * Android NUNCA mide ni coloca (measure/layout) una vista con
 * visibility=GONE. Si el ángulo se actualiza mientras el submenú que
 * contiene esta vista está oculto (el usuario giró el teléfono con otra
 * pestaña seleccionada), ese requestLayout() no tiene efecto todavía — eso
 * es correcto y esperado. El problema aparece DESPUÉS: al volver a mostrar
 * ese submenú, se reasigna el MISMO valor de angle (no cambió desde que se
 * guardó estando oculto), así que un guard por igualdad se saltaría el
 * requestLayout() justo la vez que sí hacía falta, dejando la vista con su
 * rotación por defecto (sin rotar) para siempre, aunque el campo interno
 * diga lo contrario. Por eso se pide layout SIEMPRE que se asigna `angle`,
 * sin excepción.
 */
class RotatableLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ViewGroup(context, attrs) {

    /** 0f, 90f, -90f o 180f — misma convención de signos que el resto de la app. */
    var angle: Float = 0f
        set(value) {
            field = value
            requestLayout()
        }

    private val isSideways: Boolean
        get() = angle == 90f || angle == -90f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val child = getChildAt(0)
        if (child == null) {
            setMeasuredDimension(0, 0)
            return
        }
        if (isSideways) {
            // Al hijo (sin rotar) se le da el spec de ancho como su alto
            // disponible y viceversa, ya que al rotarlo 90°/-90° sus ejes
            // quedan intercambiados frente al padre.
            measureChild(child, heightMeasureSpec, widthMeasureSpec)
            setMeasuredDimension(child.measuredHeight, child.measuredWidth)
        } else {
            measureChild(child, widthMeasureSpec, heightMeasureSpec)
            setMeasuredDimension(child.measuredWidth, child.measuredHeight)
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val child = getChildAt(0) ?: return
        val childWidth = child.measuredWidth
        val childHeight = child.measuredHeight
        // El hijo siempre se ubica centrado en SU PROPIO tamaño medido (sin
        // rotar); la rotación es puramente visual y se aplica a continuación.
        val left = (measuredWidth - childWidth) / 2
        val top = (measuredHeight - childHeight) / 2
        child.layout(left, top, left + childWidth, top + childHeight)
        child.pivotX = childWidth / 2f
        child.pivotY = childHeight / 2f
        child.rotation = angle
    }
}
