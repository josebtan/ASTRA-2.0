# ASTRA 2.0

![Android CI](https://github.com/josebtan/ASTRA-2.0/actions/workflows/android-build.yml/badge.svg)

App de cámara para Android construida de forma nativa en Kotlin con [CameraX](https://developer.android.com/training/camerax).

Este README se irá actualizando a medida que avancemos con el desarrollo.

## ✅ Estado actual

Primera versión funcional: **app básica de cámara** con las funciones más comunes.

### Funciones incluidas

- 📷 **Captura de fotos** con cámara trasera o frontal.
- 🔄 **Cambio de cámara** (frontal / trasera).
- ⚡ **Flash** con 3 modos: apagado → encendido → automático.
- ⏱️ **Temporizador** con 3 estados: apagado → 3s → 10s, con cuenta regresiva visible en pantalla.
- 🖼️ **Galería propia**: las fotos se guardan y se listan desde un directorio exclusivo de la app (no se mezclan con la galería general del sistema).
  - Vista en **mosaico** (cuadrícula de 3 columnas, celdas cuadradas y espaciado parejo).
  - Visor a pantalla completa con opción de eliminar cada foto.
- 🔄 **Interfaz adaptativa a la orientación**: los controles rotan automáticamente para mantenerse legibles sin importar si sostienes el teléfono en vertical u horizontal (hacia cualquiera de los dos lados), y las fotos se guardan con la orientación correcta en todos los casos.
- 🗂️ **Menú de modos colapsable** (chip "Modos" en la barra superior): un panel tipo acordeón con tres modos de disparo, cada uno con su propio submenú de parámetros. Solo un modo puede estar activo (y expandido) a la vez; volver a pulsar su encabezado lo desactiva y regresa al modo Normal (automático).
  - **Manual**:
    - **ISO** manual (en dispositivos que lo soportan; si no, se indica y se usa automático).
    - **Exposición (EV)** — compensación de exposición estándar, funciona en la mayoría de los dispositivos.
    - **Contraste** — se aplica como post-procesado al guardar la foto (no es un parámetro de hardware estándar entre fabricantes).
    - **Captura en RAW (.dng)** — en dispositivos compatibles. Si el dispositivo no soporta RAW, se avisa y se usa JPEG automáticamente. Los archivos `.dng` se muestran en la galería usando la vista previa JPEG embebida en sus propios metadatos EXIF (estándar en cualquier DNG generado por cámara), con zoom igual que cualquier otra foto; el archivo original conserva todos los datos sin procesar.
  - **Timelapse**: intervalo configurable (1–120s) y número de fotos (o infinito, hasta detener manualmente). Con el modo activo, el botón de disparo inicia/detiene la secuencia (se pone en rojo mientras está en curso) y un texto de estado muestra el progreso.
  - **Astrofotografía**: pensado para fotografía nocturna con trípode. Permite fijar un ISO alto y un tiempo de exposición largo (hasta 30s, según lo que soporte el sensor) de forma independiente al modo Manual, con reducción de ruido activable/desactivable. Al disparar en este modo la app prioriza calidad sobre velocidad, por lo que la captura puede tardar varios segundos en completarse.

### 🎨 Diseño

- Los controles de **flash** y **temporizador** ahora son "chips" con icono + etiqueta de texto, para que su estado se entienda de un vistazo (OFF / ON / AUTO, OFF / 3s / 10s).
- Degradados sutiles arriba y abajo de la vista de cámara para que los controles se lean bien sobre cualquier escena (clara u oscura).
- Retroalimentación táctil (ripple) en todos los botones.

### Dónde se guardan las fotos

Las imágenes se almacenan en el almacenamiento específico de la app (scoped storage), por lo que **no requieren permisos adicionales de almacenamiento**, solo el permiso de cámara:

```
Android/data/com.astra.camera/files/Pictures/ASTRA/
```

Este directorio se elimina automáticamente si la app se desinstala.

## 🏗️ Estructura del proyecto

```
ASTRA-2.0/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/astra/camera/
│       │   ├── MainActivity.kt        # Pantalla de cámara (preview, captura, temporizador, flash, menú de modos)
│       │   ├── CameraMode.kt          # Enum de modos de disparo: Normal, Manual, Timelapse, Astro
│       │   ├── GalleryActivity.kt     # Pantalla de galería propia
│       │   ├── GalleryAdapter.kt      # Adaptador de la cuadrícula de fotos
│       │   └── ImageViewerDialog.kt   # Visor de foto a pantalla completa
│       └── res/
│           ├── layout/
│           ├── values/
│           └── drawable/
├── build.gradle.kts
└── settings.gradle.kts
```

## 🔧 Requisitos

- Android Studio (versión reciente, Koala o superior recomendado).
- SDK de Android 35 (compileSdk / targetSdk), requerido por CameraX 1.5.0 (que trae el soporte de captura RAW).
- minSdk 26 (Android 8.0+).
- Kotlin 1.9.24 / AGP 8.5.2.

## 🤖 Integración continua (CI)

El workflow `.github/workflows/android-build.yml` corre en cada push/PR a `main` y tiene 3 jobs:

1. **`test`** — Ejecuta los tests unitarios (`gradle testDebugUnitTest`) y sube el reporte como artefacto.
2. **`build-debug`** — Compila el APK en modo `debug` (`assembleDebug`) y lo sube como artefacto descargable. Corre siempre que `test` pasa.
3. **`build-release`** — Compila y **firma** el APK en modo `release` (`assembleRelease`) usando un keystore guardado como *secret* del repositorio, y lo sube como artefacto. Solo corre en pushes directos a `main` (no en PRs externos, para no exponer los secretos de firma).

No se usa `gradlew` porque el wrapper binario no está versionado en el repo; en su lugar, el workflow instala Gradle 8.7 directamente en el runner mediante `gradle/actions/setup-gradle`.

### 🔐 Firma del release

El keystore de firma **no está en el repositorio** (está en `.gitignore`). Se guardó cifrado como *GitHub Actions secret* en:

- `KEYSTORE_BASE64` — el archivo `.jks` codificado en base64
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

El workflow decodifica el keystore en un archivo temporal del runner solo durante el build, y lo borra al terminar.

⚠️ **Importante:** te compartí el archivo `astra-release.jks` original junto con sus contraseñas — guárdalo en un lugar seguro (gestor de contraseñas, backup cifrado). Si se pierde, **no podrás volver a firmar actualizaciones de la app con la misma identidad**, y en Google Play tendrías que publicar como una app nueva.

### Tests

Por ahora hay tests unitarios básicos en `app/src/test/java/com/astra/camera/BasicLogicTest.kt` que validan lógica simple (ciclo del temporizador, formato de nombre de archivo). Se irán ampliando a medida que crezca la app. Los tests instrumentados (con emulador) aún no están configurados — los podemos agregar más adelante si los necesitamos.

## ▶️ Cómo ejecutar

1. Clona el repositorio.
2. Ábrelo con Android Studio (se descargará automáticamente el Gradle Wrapper si falta).
3. Conecta un dispositivo físico o inicia un emulador con cámara habilitada.
4. Ejecuta la app (▶️). Al abrir por primera vez, pedirá permiso de cámara.

## 🗺️ Próximos pasos

Estos son posibles próximos pasos a definir juntos:

- [ ] Zoom con gestos de pinza.
- [ ] Grabación de video.
- [ ] Modo cuadrado / relación de aspecto configurable.
- [ ] Compartir foto directamente desde el visor.
- [ ] Ajustes de resolución/calidad.

---

_Este documento se actualizará con cada nueva funcionalidad añadida al proyecto._
