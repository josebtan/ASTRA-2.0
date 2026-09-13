package com.astra.camera

/**
 * Modos de disparo disponibles en el menú de modos colapsable.
 *
 * - [NORMAL]: automático, sin ningún ajuste manual aplicado al disparar.
 * - [MANUAL]: ISO, exposición (EV) y contraste manuales, con RAW opcional.
 * - [TIMELAPSE]: captura una secuencia de fotos a intervalos regulares.
 * - [ASTRO]: exposición larga + ISO alto pensados para fotografía nocturna,
 *   con reducción de ruido configurable.
 */
enum class CameraMode {
    NORMAL,
    MANUAL,
    TIMELAPSE,
    ASTRO
}
