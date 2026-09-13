package com.astra.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Range
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import com.astra.camera.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Pantalla principal de la cámara.
 * Permite tomar fotos, usar un temporizador (3s / 10s), alternar el flash,
 * cambiar entre cámara frontal/trasera y elegir un modo de disparo desde un
 * menú colapsable: Manual (ISO, exposición, contraste, RAW), Timelapse y
 * Astrofotografía (exposición larga), cada uno con su propio submenú de
 * parámetros.
 *
 * La interfaz también se adapta a la orientación física del teléfono: los
 * controles rotan automáticamente para mantenerse legibles sin importar si
 * sostienes el teléfono en vertical u horizontal, y las fotos se guardan
 * con la orientación correcta en cualquiera de los dos casos.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null

    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var flashMode = ImageCapture.FLASH_MODE_OFF

    // 0 = temporizador apagado, valores en segundos
    private var timerSeconds = 0
    private var countDownTimer: CountDownTimer? = null

    // --- Menú de modos ---
    private var currentMode = CameraMode.NORMAL

    // --- Controles manuales (modo Manual) ---
    private var manualIso: Int? = null // null = automático
    private var exposureIndex = 0
    private var contrastValue = 0 // -50..50, se aplica al guardar la foto
    private var rawEnabled = false

    // --- Controles de astrofotografía (modo Astro) ---
    private var astroIso: Int? = null // null = automático
    private var astroExposureNs: Long? = null // null = automático
    private var astroNoiseReductionOff = false

    // --- Timelapse ---
    private var timelapseIntervalSeconds = 5
    private var timelapseTotalShots = 0 // 0 = infinito, hasta detener manualmente
    private var timelapseVideoFps = 10 // fotogramas por segundo del .mp4 final
    private var timelapseShotsTaken = 0
    private var isTimelapseRunning = false
    private var timelapseCaptureInFlight = false
    private val timelapseHandler = Handler(Looper.getMainLooper())
    private var timelapseRunnable: Runnable? = null
    // Carpeta temporal (caché de la app) donde se guardan los fotogramas de la
    // sesión de timelapse en curso, antes de convertirlos en un único video.
    private var timelapseFramesDir: File? = null

    // --- Overlay de progreso para capturas de exposición larga (Astro) ---
    private var captureProgressTimer: CountDownTimer? = null

    // --- Capacidades del sensor (dependen de la cámara frontal/trasera activa) ---
    private var isoRange: Range<Int>? = null
    private var exposureTimeRange: Range<Long>? = null
    private var manualSensorSupported = false
    private var rawSupported = false

    // --- Orientación física del teléfono ---
    private var currentRotation = Surface.ROTATION_0
    private lateinit var orientationEventListener: OrientationEventListener

    private lateinit var outputDirectory: File

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                Toast.makeText(this, getString(R.string.camera_permission_needed), Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        outputDirectory = getOutputDirectory(this)

        if (hasCameraPermission()) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        binding.btnCapture.setOnClickListener { onCaptureClicked() }
        binding.btnSwitchCamera.setOnClickListener { switchCamera() }
        binding.btnFlash.setOnClickListener { toggleFlash() }
        binding.btnTimer.setOnClickListener { cycleTimer() }
        binding.btnModes.setOnClickListener { toggleModesPanel() }
        binding.btnGallery.setOnClickListener {
            startActivity(Intent(this, GalleryActivity::class.java))
        }

        setupModesAccordion()
        setupManualSection()
        setupTimelapseSection()
        setupAstroSection()
        setupOrientationListener()
    }

    override fun onResume() {
        super.onResume()
        updateGalleryThumbnail()
        if (orientationEventListener.canDetectOrientation()) {
            orientationEventListener.enable()
        }
    }

    override fun onPause() {
        super.onPause()
        orientationEventListener.disable()
        if (isTimelapseRunning) stopTimelapse()
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
        }

        // En modo Astro se prioriza calidad sobre latencia, ya que la
        // exposición larga hace que la velocidad de disparo sea irrelevante.
        val captureMode = if (currentMode == CameraMode.ASTRO) {
            ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
        } else {
            ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
        }

        val captureBuilder = ImageCapture.Builder()
            .setFlashMode(flashMode)
            .setCaptureMode(captureMode)
            .setTargetRotation(currentRotation)

        // La exposición larga de Astro se aplica SOLO a la foto final (vía
        // Camera2Interop, a nivel del propio ImageCapture), nunca a la sesión
        // completa: si se aplicara a la vista previa, esta quedaría limitada
        // al mismo framerate que la exposición (ej. 1 frame cada 10s),
        // haciendo que la app entera se sienta lenta o congelada.
        if (currentMode == CameraMode.ASTRO) {
            applyAstroCaptureExtender(captureBuilder)
        }

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        try {
            provider.unbindAll()

            // Se enlaza primero sin RAW para poder consultar las capacidades
            // reales del sensor de esta cámara (frontal/trasera pueden diferir).
            val tempCapture = captureBuilder.build()
            val boundCamera = provider.bindToLifecycle(this, cameraSelector, preview, tempCapture)
            camera = boundCamera

            updateCameraCapabilities(boundCamera)

            imageCapture = if (rawEnabled && rawSupported) {
                provider.unbindAll()
                val rawCapture = captureBuilder.setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW).build()
                camera = provider.bindToLifecycle(this, cameraSelector, preview, rawCapture)
                rawCapture
            } else {
                tempCapture
            }

            applyManualCaptureOptions()
            applyExposure()
        } catch (e: Exception) {
            Log.e(TAG, "Error al enlazar los casos de uso de la cámara", e)
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun updateCameraCapabilities(camera: Camera) {
        val camera2Info = Camera2CameraInfo.from(camera.cameraInfo)
        val capabilities = camera2Info.getCameraCharacteristic(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
        )

        isoRange = camera2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        exposureTimeRange = camera2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        manualSensorSupported = isoRange != null &&
            capabilities?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true

        val captureCapabilities = ImageCapture.getImageCaptureCapabilities(camera.cameraInfo)
        rawSupported = captureCapabilities.supportedOutputFormats.contains(ImageCapture.OUTPUT_FORMAT_RAW)

        if (!manualSensorSupported) {
            manualIso = null
            astroIso = null
            astroExposureNs = null
        }
        if (!rawSupported) rawEnabled = false

        refreshIsoSeekBarBounds()
        refreshAstroSeekBarBounds()
    }

    /**
     * Aplica ISO manual (modo Manual) a la sesión de cámara completa —incluida
     * la vista previa—, ya que su tiempo de exposición es corto (1/100s) y no
     * afecta al framerate del preview. El modo Astro NO se aplica aquí: usa su
     * propio mecanismo (ver [applyAstroCaptureExtender]) para no ralentizar
     * la vista previa con su exposición larga.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyManualCaptureOptions() {
        val cam = camera ?: return
        val camera2Control = Camera2CameraControl.from(cam.cameraControl)

        val options = if (currentMode == CameraMode.MANUAL && manualIso != null && manualSensorSupported) {
            CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, manualIso)
                .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, DEFAULT_EXPOSURE_TIME_NS)
                .build()
        } else {
            CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                .build()
        }
        camera2Control.setCaptureRequestOptions(options)
    }

    /**
     * Aplica ISO alto + exposición larga únicamente a las peticiones de
     * captura de foto de [ImageCapture] (vía Camera2Interop.Extender sobre su
     * propio builder), no a la sesión de cámara completa. Así la vista previa
     * sigue en automático y fluida, y solo la foto final usa la exposición
     * larga configurada en el submenú de Astrofotografía.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyAstroCaptureExtender(builder: ImageCapture.Builder) {
        if (!manualSensorSupported) return
        val iso = astroIso ?: return
        val exposureNs = astroExposureNs ?: return

        val extender = Camera2Interop.Extender(builder)
        extender.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        extender.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
        extender.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
        extender.setCaptureRequestOption(
            CaptureRequest.NOISE_REDUCTION_MODE,
            if (astroNoiseReductionOff) {
                CaptureRequest.NOISE_REDUCTION_MODE_OFF
            } else {
                CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
            }
        )
    }

    private fun applyExposure() {
        camera?.cameraControl?.setExposureCompensationIndex(exposureIndex)
    }

    private fun switchCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        bindCameraUseCases()
    }

    private fun toggleFlash() {
        flashMode = when (flashMode) {
            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON
            ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_AUTO
            else -> ImageCapture.FLASH_MODE_OFF
        }
        imageCapture?.flashMode = flashMode
        updateFlashUi()
    }

    private fun updateFlashUi() {
        val (iconRes, label) = when (flashMode) {
            ImageCapture.FLASH_MODE_ON -> R.drawable.ic_flash_on to "ON"
            ImageCapture.FLASH_MODE_AUTO -> R.drawable.ic_flash_auto to "AUTO"
            else -> R.drawable.ic_flash_off to getString(R.string.timer_off)
        }
        binding.ivFlashIcon.setImageResource(iconRes)
        binding.tvFlashLabel.text = label
    }

    private fun cycleTimer() {
        timerSeconds = when (timerSeconds) {
            0 -> 3
            3 -> 10
            else -> 0
        }
        binding.tvTimerLabel.text = if (timerSeconds == 0) getString(R.string.timer_off) else "${timerSeconds}s"
    }

    // ============================================================
    // Menú de modos colapsable (acordeón: Manual / Timelapse / Astro)
    // ============================================================

    private fun toggleModesPanel() {
        val panelContainer = binding.scrollModesPanel
        val isVisible = panelContainer.visibility == View.VISIBLE
        animateContainer(binding.root)
        panelContainer.visibility = if (isVisible) View.GONE else View.VISIBLE
    }

    private fun setupModesAccordion() {
        binding.headerManual.setOnClickListener { selectMode(CameraMode.MANUAL) }
        binding.headerTimelapse.setOnClickListener { selectMode(CameraMode.TIMELAPSE) }
        binding.headerAstro.setOnClickListener { selectMode(CameraMode.ASTRO) }
        updateModesUi()
    }

    /**
     * Selecciona (o deselecciona, si ya estaba activo) un modo de disparo.
     * Solo un submenú de parámetros permanece expandido a la vez.
     */
    private fun selectMode(mode: CameraMode) {
        val previousMode = currentMode
        currentMode = if (currentMode == mode) CameraMode.NORMAL else mode

        if (isTimelapseRunning && currentMode != CameraMode.TIMELAPSE) {
            stopTimelapse()
        }

        updateModesUi()

        // El modo Astro necesita reenlazar la cámara (cambia el modo de
        // captura a MAXIMIZE_QUALITY); el resto de modos solo cambian las
        // opciones de captura sobre la cámara ya enlazada.
        if (previousMode == CameraMode.ASTRO || currentMode == CameraMode.ASTRO) {
            bindCameraUseCases()
        } else {
            applyManualCaptureOptions()
            applyExposure()
        }
    }

    private fun animateContainer(container: ViewGroup) {
        TransitionManager.beginDelayedTransition(container, AutoTransition().setDuration(200))
    }

    private fun updateModesUi() {
        animateContainer(binding.panelModes)

        binding.contentManual.visibility = if (currentMode == CameraMode.MANUAL) View.VISIBLE else View.GONE
        binding.contentTimelapse.visibility = if (currentMode == CameraMode.TIMELAPSE) View.VISIBLE else View.GONE
        binding.contentAstro.visibility = if (currentMode == CameraMode.ASTRO) View.VISIBLE else View.GONE

        binding.ivChevronManual.rotation = if (currentMode == CameraMode.MANUAL) 180f else 0f
        binding.ivChevronTimelapse.rotation = if (currentMode == CameraMode.TIMELAPSE) 180f else 0f
        binding.ivChevronAstro.rotation = if (currentMode == CameraMode.ASTRO) 180f else 0f

        val labelRes = when (currentMode) {
            CameraMode.NORMAL -> R.string.mode_normal_label
            CameraMode.MANUAL -> R.string.mode_manual_short
            CameraMode.TIMELAPSE -> R.string.mode_timelapse_short
            CameraMode.ASTRO -> R.string.mode_astro_short
        }
        binding.tvModesLabel.text = getString(labelRes)
        binding.ivModesIcon.alpha = if (currentMode == CameraMode.NORMAL) 0.7f else 1f
    }

    // --- Submenú Manual: ISO, exposición, contraste, RAW ---

    private fun setupManualSection() {
        binding.seekIso.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val range = isoRange
                updateIsoLabel(progress, range)
                if (!fromUser || range == null) return
                manualIso = if (progress == 0) null else range.lower + (progress - 1)
                applyManualCaptureOptions()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })

        binding.seekExposure.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val exposureState = camera?.cameraInfo?.exposureState
                val range = exposureState?.exposureCompensationRange ?: Range(0, 0)
                val step = exposureState?.exposureCompensationStep?.toDouble() ?: 0.0
                val index = range.lower + progress
                binding.tvExposureValue.text = String.format(Locale.US, "%+.1f", index * step)
                if (!fromUser) return
                exposureIndex = index
                applyExposure()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })

        binding.seekContrast.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val contrast = progress - 50
                binding.tvContrastValue.text = contrast.toString()
                if (fromUser) contrastValue = contrast
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })

        binding.switchRaw.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !rawSupported) {
                binding.switchRaw.isChecked = false
                Toast.makeText(this, getString(R.string.raw_not_supported), Toast.LENGTH_LONG).show()
                return@setOnCheckedChangeListener
            }
            rawEnabled = isChecked
            bindCameraUseCases()
        }

        binding.btnResetManual.setOnClickListener {
            manualIso = null
            exposureIndex = 0
            contrastValue = 0
            rawEnabled = false

            val exposureLower = camera?.cameraInfo?.exposureState?.exposureCompensationRange?.lower ?: 0

            binding.seekIso.progress = 0
            binding.seekExposure.progress = -exposureLower
            binding.seekContrast.progress = 50
            binding.switchRaw.isChecked = false

            applyManualCaptureOptions()
            applyExposure()
            bindCameraUseCases()
        }
    }

    private fun updateIsoLabel(progress: Int, range: Range<Int>?) {
        binding.tvIsoValue.text = if (progress == 0 || range == null) {
            getString(R.string.auto_label)
        } else {
            (range.lower + (progress - 1)).toString()
        }
    }

    /** Configura el rango del SeekBar de ISO según las capacidades reales del sensor activo. */
    private fun refreshIsoSeekBarBounds() {
        val range = isoRange
        if (!manualSensorSupported || range == null) {
            binding.tvIsoValue.text = getString(R.string.manual_not_supported)
            binding.seekIso.isEnabled = false
        } else {
            binding.seekIso.isEnabled = true
            val span = range.upper - range.lower
            binding.seekIso.max = span + 1 // 0 = AUTO, 1..span+1 = valor manual
            val progress = if (manualIso == null) 0 else (manualIso!! - range.lower) + 1
            binding.seekIso.progress = progress
            updateIsoLabel(progress, range)
        }

        val exposureState = camera?.cameraInfo?.exposureState
        val exposureRange = exposureState?.exposureCompensationRange ?: Range(0, 0)
        binding.seekExposure.max = exposureRange.upper - exposureRange.lower
        binding.seekExposure.progress = exposureIndex - exposureRange.lower

        binding.switchRaw.isEnabled = rawSupported
        binding.switchRaw.isChecked = rawEnabled
    }

    // --- Submenú Timelapse: intervalo, número de fotos, iniciar/detener ---

    private fun setupTimelapseSection() {
        binding.seekTimelapseInterval.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                timelapseIntervalSeconds = progress + 1 // 1..120 segundos
                binding.tvTimelapseInterval.text = "${timelapseIntervalSeconds}s"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
        binding.tvTimelapseInterval.text = "${timelapseIntervalSeconds}s"

        binding.seekTimelapseShots.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                timelapseTotalShots = progress // 0 = infinito
                binding.tvTimelapseShots.text = if (progress == 0) {
                    getString(R.string.timelapse_shots_infinite)
                } else {
                    progress.toString()
                }
                updateTimelapseStatus()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })

        binding.seekTimelapseFps.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                timelapseVideoFps = progress + 2 // 2..30 fps
                binding.tvTimelapseFps.text = "${timelapseVideoFps} fps"
                updateTimelapseStatus()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
        binding.tvTimelapseFps.text = "${timelapseVideoFps} fps"

        updateTimelapseStatus()
    }

    private fun toggleTimelapse() {
        if (isTimelapseRunning) stopTimelapse() else startTimelapse()
    }

    private fun startTimelapse() {
        if (imageCapture == null) return
        // Carpeta temporal en la caché de la app (no visible en la galería):
        // los fotogramas se guardan aquí y se borran en cuanto se genera el video.
        timelapseFramesDir = File(cacheDir, "astra_timelapse_${System.currentTimeMillis()}").apply { mkdirs() }
        isTimelapseRunning = true
        timelapseCaptureInFlight = false
        timelapseShotsTaken = 0
        binding.btnCapture.isSelected = true
        binding.timelapseInfoPill.visibility = View.VISIBLE
        updateTimelapseInfoPill()
        updateTimelapseStatus()
        captureNextTimelapseFrame()
    }

    /**
     * Captura un único fotograma del timelapse. A diferencia de [takePhoto],
     * este flujo SIEMPRE espera a que la captura asíncrona termine
     * (onImageSaved/onError) antes de decidir si programa el siguiente
     * fotograma o detiene la secuencia — así se evita generar el video antes
     * de que el último archivo haya terminado de escribirse.
     */
    private fun captureNextTimelapseFrame() {
        if (!isTimelapseRunning) return
        val capture = imageCapture
        val framesDir = timelapseFramesDir
        if (capture == null || framesDir == null) {
            stopTimelapse()
            return
        }

        timelapseCaptureInFlight = true
        val photoFile = File(framesDir, "frame_${String.format(Locale.US, "%05d", timelapseShotsTaken)}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Error al capturar fotograma de timelapse", exc)
                    onTimelapseFrameFinished()
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    onTimelapseFrameFinished()
                }
            }
        )
    }

    private fun onTimelapseFrameFinished() {
        timelapseCaptureInFlight = false
        timelapseShotsTaken++
        updateTimelapseInfoPill()

        if (!isTimelapseRunning) {
            // El usuario pidió detener mientras este fotograma se estaba
            // guardando: recién ahora que terminó de escribirse se genera
            // el video, así no se pierde el último fotograma.
            buildTimelapseVideoFromFrames()
            return
        }

        if (timelapseTotalShots != 0 && timelapseShotsTaken >= timelapseTotalShots) {
            stopTimelapse()
            return
        }

        val runnable = Runnable { captureNextTimelapseFrame() }
        timelapseRunnable = runnable
        timelapseHandler.postDelayed(runnable, timelapseIntervalSeconds * 1000L)
    }

    private fun stopTimelapse() {
        if (!isTimelapseRunning) return
        isTimelapseRunning = false
        timelapseRunnable?.let { timelapseHandler.removeCallbacks(it) }
        timelapseRunnable = null
        binding.btnCapture.isSelected = false
        binding.timelapseInfoPill.visibility = View.GONE
        updateTimelapseStatus()
        // Si hay una captura en curso, es [onTimelapseFrameFinished] quien
        // generará el video en cuanto esa foto termine de guardarse.
        if (!timelapseCaptureInFlight) {
            buildTimelapseVideoFromFrames()
        }
    }

    /**
     * Toma todos los fotogramas guardados en la carpeta temporal de la sesión
     * de timelapse que acaba de terminar, los convierte en un único video
     * (.mp4) y lo guarda en la galería de la app. La carpeta temporal se
     * elimina al terminar, haya tenido éxito o no.
     */
    private fun buildTimelapseVideoFromFrames() {
        val framesDir = timelapseFramesDir
        timelapseFramesDir = null

        val frameFiles = framesDir?.listFiles { f -> f.extension.equals("jpg", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?: emptyList()

        if (frameFiles.isEmpty()) {
            framesDir?.deleteRecursively()
            binding.tvTimelapseStatus.text = getString(R.string.timelapse_no_frames)
            return
        }

        binding.tvTimelapseStatus.text = getString(R.string.timelapse_building_video, frameFiles.size)

        val fpsUsed = timelapseVideoFps
        val videoFileName = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val outputVideo = File(outputDirectory, "ASTRA_TIMELAPSE_$videoFileName.mp4")

        TimelapseVideoBuilder.buildVideoAsync(frameFiles, outputVideo, fpsUsed) { success ->
            framesDir?.deleteRecursively()
            if (isFinishing || isDestroyed) return@buildVideoAsync

            if (success) {
                val seconds = frameFiles.size.toFloat() / fpsUsed
                val message = getString(R.string.timelapse_video_saved_with_duration, formatTimelapseDuration(seconds))
                binding.tvTimelapseStatus.text = message
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                updateGalleryThumbnail()
            } else {
                binding.tvTimelapseStatus.text = getString(R.string.timelapse_video_error)
                Toast.makeText(this, getString(R.string.timelapse_video_error), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateTimelapseStatus() {
        binding.tvTimelapseStatus.text = if (isTimelapseRunning) {
            getString(R.string.timelapse_status_running_hint)
        } else {
            getString(R.string.timelapse_status_idle)
        }
    }

    /**
     * Actualiza el "pill" que flota sobre la previsualización de cámara
     * mientras el timelapse está grabando: cuántas fotos van y la duración
     * estimada del video final (se recalcula en vivo con cada fotograma).
     */
    private fun updateTimelapseInfoPill() {
        binding.tvTimelapseInfoShots.text = getString(R.string.timelapse_info_shots, timelapseShotsTaken)

        val frameCountForDuration = if (timelapseTotalShots > 0) timelapseTotalShots else timelapseShotsTaken
        val seconds = frameCountForDuration.toFloat() / timelapseVideoFps
        binding.tvTimelapseInfoDuration.text =
            getString(R.string.timelapse_info_duration, formatTimelapseDuration(seconds))
    }

    private fun formatTimelapseDuration(seconds: Float): String {
        return if (seconds < 60) {
            String.format(Locale.US, "%.1f s", seconds)
        } else {
            String.format(Locale.US, "%d min %02d s", (seconds / 60).toInt(), (seconds % 60).toInt())
        }
    }

    // --- Submenú Astrofotografía: ISO alto + exposición larga ---

    private fun setupAstroSection() {
        binding.seekAstroIso.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val range = isoRange
                if (range == null) {
                    binding.tvAstroIso.text = getString(R.string.manual_not_supported)
                    return
                }
                val iso = range.lower + progress
                binding.tvAstroIso.text = iso.toString()
                if (fromUser) astroIso = iso
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

            // Solo se reenlaza la cámara al soltar el dedo (no en cada tick):
            // reenlazar en cada onProgressChanged sería lo que originalmente
            // causaba que la app se sintiera lenta mientras se arrastraba.
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                if (currentMode == CameraMode.ASTRO) bindCameraUseCases()
            }
        })

        binding.seekAstroExposure.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val range = exposureTimeRange
                if (range == null) {
                    binding.tvAstroExposure.text = getString(R.string.astro_not_supported)
                    return
                }
                val exposureNs = range.lower + progress * ASTRO_EXPOSURE_STEP_NS
                binding.tvAstroExposure.text = formatExposureSeconds(exposureNs)
                if (fromUser) astroExposureNs = exposureNs
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                if (currentMode == CameraMode.ASTRO) bindCameraUseCases()
            }
        })

        binding.switchAstroNoiseReduction.setOnCheckedChangeListener { _, isChecked ->
            // El switch representa "reducción de ruido activada"; internamente
            // astroNoiseReductionOff indica si hay que desactivarla (invertido).
            astroNoiseReductionOff = !isChecked
            if (currentMode == CameraMode.ASTRO) bindCameraUseCases()
        }
    }

    private fun formatExposureSeconds(exposureNs: Long): String {
        val seconds = exposureNs / 1_000_000_000.0
        return if (seconds < 1.0) {
            String.format(Locale.US, "1/%d s", (1.0 / seconds).toInt().coerceAtLeast(1))
        } else {
            String.format(Locale.US, "%.1f s", seconds)
        }
    }

    /** Configura el rango de los SeekBars de ISO y exposición del modo Astro. */
    private fun refreshAstroSeekBarBounds() {
        val iso = isoRange
        val exposure = exposureTimeRange

        if (!manualSensorSupported || iso == null) {
            binding.tvAstroIso.text = getString(R.string.manual_not_supported)
            binding.seekAstroIso.isEnabled = false
        } else {
            binding.seekAstroIso.isEnabled = true
            binding.seekAstroIso.max = iso.upper - iso.lower
            val initialIso = astroIso ?: (iso.lower + (iso.upper - iso.lower) * ASTRO_DEFAULT_ISO_FRACTION / 100)
            astroIso = initialIso
            binding.seekAstroIso.progress = initialIso - iso.lower
            binding.tvAstroIso.text = initialIso.toString()
        }

        if (!manualSensorSupported || exposure == null) {
            binding.tvAstroExposure.text = getString(R.string.astro_not_supported)
            binding.seekAstroExposure.isEnabled = false
        } else {
            binding.seekAstroExposure.isEnabled = true
            // Se limita a un máximo razonable de 30s para uso manual, aunque
            // el sensor soporte más, para mantener el SeekBar manejable.
            val cappedUpper = exposure.upper.coerceAtMost(ASTRO_MAX_EXPOSURE_NS)
            val steps = ((cappedUpper - exposure.lower) / ASTRO_EXPOSURE_STEP_NS).toInt().coerceAtLeast(1)
            binding.seekAstroExposure.max = steps
            val initialExposureNs = (astroExposureNs ?: ASTRO_DEFAULT_EXPOSURE_NS).coerceIn(exposure.lower, cappedUpper)
            astroExposureNs = initialExposureNs
            binding.seekAstroExposure.progress = ((initialExposureNs - exposure.lower) / ASTRO_EXPOSURE_STEP_NS).toInt()
            binding.tvAstroExposure.text = formatExposureSeconds(initialExposureNs)
        }
    }

    // ============================================================
    // Captura
    // ============================================================

    private fun onCaptureClicked() {
        if (currentMode == CameraMode.TIMELAPSE) {
            toggleTimelapse()
            return
        }
        if (timerSeconds == 0) {
            takePhoto()
        } else {
            startCountdown(timerSeconds)
        }
    }

    private fun startCountdown(seconds: Int) {
        binding.tvCountdown.visibility = View.VISIBLE
        countDownTimer?.cancel()
        countDownTimer = object : CountDownTimer(seconds * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val secondsLeft = (millisUntilFinished / 1000) + 1
                binding.tvCountdown.text = secondsLeft.toString()
            }

            override fun onFinish() {
                binding.tvCountdown.visibility = View.GONE
                takePhoto()
            }
        }.start()
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return

        val useRaw = rawEnabled && rawSupported
        val extension = if (useRaw) "dng" else "jpg"
        val fileName = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(System.currentTimeMillis())
        val photoFile = File(outputDirectory, "ASTRA_$fileName.$extension")

        // Si es una exposición larga de Astro, se muestra un overlay con el
        // tiempo restante mientras dura la captura física de la foto.
        val exposureNsForThisShot = astroExposureNs
        val isLongExposure = currentMode == CameraMode.ASTRO &&
            exposureNsForThisShot != null && exposureNsForThisShot >= LONG_EXPOSURE_THRESHOLD_NS
        if (isLongExposure) {
            showCaptureProgressOverlay(exposureNsForThisShot!! / 1_000_000L)
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Error al guardar la foto", exc)
                    if (isLongExposure) hideCaptureProgressOverlay()
                    Toast.makeText(this@MainActivity, getString(R.string.photo_error), Toast.LENGTH_SHORT).show()
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    if (isLongExposure) hideCaptureProgressOverlay()

                    if (!useRaw && contrastValue != 0) {
                        ImageProcessing.applyContrast(photoFile, contrastValue)
                    }
                    Toast.makeText(this@MainActivity, getString(R.string.photo_saved), Toast.LENGTH_SHORT).show()
                    updateGalleryThumbnail()
                }
            }
        )
    }

    // --- Overlay de progreso para exposiciones largas (Astro) ---

    private fun showCaptureProgressOverlay(durationMs: Long) {
        binding.progressCapture.visibility = View.VISIBLE
        binding.captureCountdownPill.visibility = View.VISIBLE
        binding.progressCapture.isIndeterminate = false
        binding.progressCapture.max = 1000
        binding.progressCapture.progress = 0
        binding.tvCaptureCountdown.text = formatExposureSeconds(durationMs * 1_000_000L)

        captureProgressTimer?.cancel()
        captureProgressTimer = object : CountDownTimer(durationMs, 100L) {
            override fun onTick(millisUntilFinished: Long) {
                val elapsed = durationMs - millisUntilFinished
                binding.progressCapture.progress = ((elapsed.toFloat() / durationMs) * 1000)
                    .toInt().coerceIn(0, 1000)
                binding.tvCaptureCountdown.text = formatExposureSeconds(millisUntilFinished * 1_000_000L)
            }

            override fun onFinish() {
                // La exposición terminó pero el archivo aún se está escribiendo/
                // procesando: se cambia a modo indeterminado hasta que llegue
                // onImageSaved/onError.
                binding.progressCapture.isIndeterminate = true
                binding.tvCaptureCountdown.text = getString(R.string.capture_processing)
            }
        }.start()
    }

    private fun hideCaptureProgressOverlay() {
        captureProgressTimer?.cancel()
        captureProgressTimer = null
        binding.progressCapture.visibility = View.GONE
        binding.captureCountdownPill.visibility = View.GONE
    }

    private fun updateGalleryThumbnail() {
        val lastMedia = outputDirectory.listFiles()
            ?.filter {
                it.extension.equals("jpg", ignoreCase = true) ||
                    it.extension.equals("dng", ignoreCase = true) ||
                    it.extension.equals("mp4", ignoreCase = true)
            }
            ?.maxByOrNull { it.lastModified() }

        when {
            lastMedia == null -> binding.btnGallery.setImageResource(R.drawable.ic_gallery_placeholder)
            lastMedia.extension.equals("jpg", ignoreCase = true) -> binding.btnGallery.setImageURI(Uri.fromFile(lastMedia))
            lastMedia.extension.equals("mp4", ignoreCase = true) -> {
                val thumb = MediaThumbnails.createVideoThumbnail(lastMedia)
                if (thumb != null) binding.btnGallery.setImageBitmap(thumb) else binding.btnGallery.setImageResource(R.drawable.ic_video_placeholder)
            }
            else -> {
                // Los archivos RAW (.dng) no se pueden decodificar como bitmap directamente.
                binding.btnGallery.setImageResource(R.drawable.ic_manual)
            }
        }
    }

    // ============================================================
    // Orientación adaptativa: la interfaz (y la foto resultante) se ajustan
    // tanto en vertical como en horizontal, hacia cualquiera de los dos lados.
    // ============================================================

    private fun setupOrientationListener() {
        orientationEventListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return

                val rotation = when {
                    orientation in 45 until 135 -> Surface.ROTATION_270
                    orientation in 135 until 225 -> Surface.ROTATION_180
                    orientation in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }

                if (rotation != currentRotation) {
                    currentRotation = rotation
                    imageCapture?.targetRotation = rotation
                    rotateControls(rotation)
                }
            }
        }
    }

    /**
     * Rota visualmente los controles (sin recomponer el layout) para que se
     * mantengan legibles sin importar si el teléfono está en vertical (arriba,
     * abajo) o en horizontal (hacia la izquierda o hacia la derecha).
     *
     * `Surface.ROTATION_*` representa la rotación aplicada por el sistema para
     * compensar el giro físico del teléfono, en dirección opuesta a dicho giro.
     * Por eso los controles deben rotar en la MISMA dirección que esa
     * compensación (no en la contraria) para verse derechos al usuario.
     */
    private fun rotateControls(rotation: Int) {
        val degrees = when (rotation) {
            Surface.ROTATION_90 -> 90f
            Surface.ROTATION_180 -> 180f
            Surface.ROTATION_270 -> -90f
            else -> 0f
        }

        val controls = listOf(
            binding.ivFlashIcon,
            binding.tvFlashLabel,
            binding.tvTimerLabel,
            binding.ivModesIcon,
            binding.tvModesLabel,
            binding.btnGallery,
            binding.btnSwitchCamera
        )

        controls.forEach { view ->
            view.animate().rotation(degrees).setDuration(250).start()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        countDownTimer?.cancel()
        captureProgressTimer?.cancel()
        stopTimelapse()
    }

    companion object {
        private const val TAG = "AstraCamera"

        // Tiempo de exposición por defecto (1/100s) usado al fijar un ISO manual,
        // ya que al desactivar la exposición automática también hay que fijar
        // manualmente el tiempo de exposición para no capturar cuadros negros.
        private const val DEFAULT_EXPOSURE_TIME_NS = 10_000_000L

        // --- Astrofotografía: pasos y límites del SeekBar de exposición larga ---
        private const val ASTRO_EXPOSURE_STEP_NS = 200_000_000L // 0.2s por paso
        private const val ASTRO_MAX_EXPOSURE_NS = 30_000_000_000L // tope de 30s
        private const val ASTRO_DEFAULT_EXPOSURE_NS = 4_000_000_000L // 4s por defecto
        private const val ASTRO_DEFAULT_ISO_FRACTION = 70 // % del rango de ISO disponible

        // A partir de este tiempo de exposición se muestra el overlay de
        // progreso (exposiciones más cortas no lo necesitan).
        private const val LONG_EXPOSURE_THRESHOLD_NS = 1_000_000_000L // 1s

        /**
         * Directorio propio de la app dentro del almacenamiento específico de la aplicación.
         * No requiere permisos de almacenamiento adicionales (scoped storage) y
         * se elimina automáticamente si el usuario desinstala la app.
         */
        fun getOutputDirectory(context: android.content.Context): File {
            val mediaDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)?.let {
                File(it, "ASTRA").apply { mkdirs() }
            }
            return if (mediaDir != null && mediaDir.exists()) mediaDir else context.filesDir
        }
    }
}
