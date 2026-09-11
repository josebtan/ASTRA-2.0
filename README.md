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
  - Vista en cuadrícula de todas las fotos tomadas.
  - Visor a pantalla completa con opción de eliminar cada foto.

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
│       │   ├── MainActivity.kt        # Pantalla de cámara (preview, captura, temporizador, flash)
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
- SDK de Android 34 (compileSdk / targetSdk).
- minSdk 24 (Android 7.0+).
- Kotlin 1.9.24 / AGP 8.5.2.

## 🤖 Integración continua (CI)

Cada push o pull request a `main` dispara un workflow de **GitHub Actions** (`.github/workflows/android-build.yml`) que:

1. Configura JDK 17 y el Android SDK.
2. Compila la app en modo `debug` (`assembleDebug`).
3. Publica el `.apk` resultante como artefacto descargable desde la pestaña **Actions** del repositorio.

No se usa `gradlew` porque el wrapper binario no está versionado en el repo; en su lugar, el workflow instala Gradle 8.7 directamente en el runner mediante `gradle/actions/setup-gradle`.

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
