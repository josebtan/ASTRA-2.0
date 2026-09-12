package com.astra.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
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
 * cambiar entre cámara frontal/trasera y acceder a la galería propia de ASTRA.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null

    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var flashMode = ImageCapture.FLASH_MODE_OFF

    // 0 = temporizador apagado, valores en segundos
    private var timerSeconds = 0
    private var countDownTimer: CountDownTimer? = null

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
        binding.btnGallery.setOnClickListener {
            startActivity(Intent(this, GalleryActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        updateGalleryThumbnail()
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

        imageCapture = ImageCapture.Builder()
            .setFlashMode(flashMode)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        try {
            provider.unbindAll()
            provider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
        } catch (e: Exception) {
            Log.e(TAG, "Error al enlazar los casos de uso de la cámara", e)
        }
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
        val photoFile = File(outputDirectory, "ASTRA_$fileName.jpg")

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
                    Toast.makeText(this@MainActivity, getString(R.string.photo_saved), Toast.LENGTH_SHORT).show()
                    updateGalleryThumbnail()
                }
            }
        )
    }

    private fun updateGalleryThumbnail() {
        val lastImage = outputDirectory.listFiles()
            ?.filter { it.extension.equals("jpg", ignoreCase = true) }
            ?.maxByOrNull { it.lastModified() }

        if (lastImage != null) {
            binding.btnGallery.setImageURI(Uri.fromFile(lastImage))
        } else {
            binding.btnGallery.setImageResource(R.drawable.ic_gallery_placeholder)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        countDownTimer?.cancel()
    }

    companion object {
        private const val TAG = "AstraCamera"

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
