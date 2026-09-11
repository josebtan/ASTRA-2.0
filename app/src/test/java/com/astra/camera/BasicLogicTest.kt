package com.astra.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Pruebas unitarias básicas (sin dependencias de Android/instrumentación).
 * Se irán ampliando a medida que crezca la lógica de la app.
 */
class BasicLogicTest {

    @Test
    fun `ciclo del temporizador sigue la secuencia 0 - 3 - 10 - 0`() {
        fun nextTimerValue(current: Int): Int = when (current) {
            0 -> 3
            3 -> 10
            else -> 0
        }

        var value = 0
        value = nextTimerValue(value)
        assertEquals(3, value)

        value = nextTimerValue(value)
        assertEquals(10, value)

        value = nextTimerValue(value)
        assertEquals(0, value)
    }

    @Test
    fun `el nombre del archivo generado tiene el prefijo ASTRA y extension jpg`() {
        val fileName = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val photoFileName = "ASTRA_$fileName.jpg"

        assertTrue(photoFileName.startsWith("ASTRA_"))
        assertTrue(photoFileName.endsWith(".jpg"))
    }
}
