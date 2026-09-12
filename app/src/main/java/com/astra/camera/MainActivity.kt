package com.astra.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
import android.util.Range
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.astra.camera.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Pantalla principal de la cámara.
 * Permite tomar fotos, usar un temporizador (3s / 10s), alternar el flash,
 * cambiar entre cámara frontal/trasera, ajustar controles manuales
 * (ISO, exposición, contraste, RAW) y acceder a la galería propia de ASTRA.
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

    // --- Controles manuales ---
    private var manualIso: Int? = null // null = automático
    private var exposureIndex = 0
    private var contrastValue = 0 // -50..50, se aplica al guardar la foto
    private var rawEnabled = false

    private var isoRange: Range<Int>? = null
    private var isoSupported = false
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
        binding.btnManual.setOnClickListener { openManualControls() }
        binding.btnGallery.setOnClickListener {
            startActivity(Intent(this, GalleryActivity::class.java))
        }

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

        val captureBuilder = ImageCapture.Builder()
            .setFlashMode(flashMode)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetRotation(currentRotation)

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
        isoSupported = isoRange != null &&
            capabilities?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true

        val captureCapabilities = ImageCapture.getImageCaptureCapabilities(camera.cameraInfo)
        rawSupported = captureCapabilities.supportedOutputFormats.contains(ImageCapture.OUTPUT_FORMAT_RAW)

        if (!isoSupported) manualIso = null
        if (!rawSupported) rawEnabled = false
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyManualCaptureOptions() {
        val cam = camera ?: return
        val camera2Control = Camera2CameraControl.from(cam.cameraControl)

        if (manualIso != null && isoSupported) {
            val options = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, manualIso)
                .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, DEFAULT_EXPOSURE_TIME_NS)
                .build()
            camera2Control.setCaptureRequestOptions(options)
        } else {
            val options = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                .build()
            camera2Control.setCaptureRequestOptions(options)
        }
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

    // --- Controles manuales (ISO, exposición, contraste, RAW) ---

    private fun openManualControls() {
        val cam = camera
        val exposureState = cam?.cameraInfo?.exposureState
        val exposureRange = exposureState?.exposureCompensationRange ?: Range(0, 0)
        val exposureStep = exposureState?.exposureCompensationStep?.toDouble() ?: 0.0

        ManualControlsDialog(
            context = this,
            isoSupported = isoSupported,
            isoRange = isoRange,
            currentIso = manualIso,
            exposureRange = exposureRange,
            exposureStepValue = exposureStep,
            currentExposureIndex = exposureIndex,
            currentContrast = contrastValue,
            rawSupported = rawSupported,
            currentRawEnabled = rawEnabled,
            onIsoChanged = { iso ->
                manualIso = iso
                applyManualCaptureOptions()
            },
            onExposureChanged = { index ->
                exposureIndex = index
                applyExposure()
            },
            onContrastChanged = { contrast ->
                contrastValue = contrast
            },
            onRawToggled = { enabled ->
                if (enabled && !rawSupported) {
                    Toast.makeText(this, getString(R.string.raw_not_supported), Toast.LENGTH_LONG).show()
                } else {
                    rawEnabled = enabled
                    updateManualLabel()
                    bindCameraUseCases()
                }
            },
            onReset = {
                manualIso = null
                exposureIndex = 0
                contrastValue = 0
                rawEnabled = false
                updateManualLabel()
                applyManualCaptureOptions()
                applyExposure()
                bindCameraUseCases()
            }
        ).show()

        updateManualLabel()
    }

    private fun updateManualLabel() {
        val isActive = manualIso != null || exposureIndex != 0 || contrastValue != 0 || rawEnabled
        binding.tvManualLabel.text = if (rawEnabled) "RAW" else "PRO"
        binding.ivManualIcon.alpha = if (isActive) 1f else 0.7f
    }

    // --- Captura ---

    private fun onCaptureClicked() {
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

        val fileName = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val extension = if (rawEnabled && rawSupported) "dng" else "jpg"
        val photoFile = File(outputDirectory, "ASTRA_$fileName.$extension")
        val isRawCapture = extension == "dng"

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Error al guardar la foto", exc)
                    Toast.makeText(this@MainActivity, getString(R.string.photo_error), Toast.LENGTH_SHORT).show()
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    if (!isRawCapture && contrastValue != 0) {
                        ImageProcessing.applyContrast(photoFile, contrastValue)
                    }
                    Toast.makeText(this@MainActivity, getString(R.string.photo_saved), Toast.LENGTH_SHORT).show()
                    updateGalleryThumbnail()
                }
            }
        )
    }

    private fun updateGalleryThumbnail() {
        val lastImage = outputDirectory.listFiles()
            ?.filter { it.extension.equals("jpg", ignoreCase = true) || it.extension.equals("dng", ignoreCase = true) }
            ?.maxByOrNull { it.lastModified() }

        if (lastImage != null && lastImage.extension.equals("jpg", ignoreCase = true)) {
            binding.btnGallery.setImageURI(Uri.fromFile(lastImage))
        } else if (lastImage != null) {
            // Los archivos RAW (.dng) no se pueden decodificar como bitmap directamente.
            binding.btnGallery.setImageResource(R.drawable.ic_manual)
        } else {
            binding.btnGallery.setImageResource(R.drawable.ic_gallery_placeholder)
        }
    }

    // --- Orientación adaptativa ---

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
     */
    private fun rotateControls(rotation: Int) {
        val degrees = when (rotation) {
            Surface.ROTATION_90 -> -90f
            Surface.ROTATION_180 -> 180f
            Surface.ROTATION_270 -> 90f
            else -> 0f
        }

        val controls = listOf(
            binding.ivFlashIcon,
            binding.tvFlashLabel,
            binding.tvTimerLabel,
            binding.ivManualIcon,
            binding.tvManualLabel,
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
    }

    companion object {
        private const val TAG = "AstraCamera"

        // Tiempo de exposición por defecto (1/100s) usado al fijar un ISO manual,
        // ya que al desactivar la exposición automática también hay que fijar
        // manualmente el tiempo de exposición para no capturar cuadros negros.
        private const val DEFAULT_EXPOSURE_TIME_NS = 10_000_000L

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
