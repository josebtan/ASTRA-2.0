package com.astra.camera

import android.app.Dialog
import android.content.Context
import android.util.Range
import android.view.Window
import android.widget.SeekBar
import com.astra.camera.databinding.DialogManualControlsBinding

/**
 * Panel de controles manuales de cámara: ISO, compensación de exposición (EV),
 * contraste (aplicado al guardar la foto) y captura en formato RAW (.dng).
 *
 * No aplica los ajustes directamente: solo reporta los cambios a través de
 * los callbacks para que [MainActivity] decida cómo aplicarlos a la cámara.
 */
class ManualControlsDialog(
    context: Context,
    private val isoSupported: Boolean,
    private val isoRange: Range<Int>?,
    private val currentIso: Int?,
    private val exposureRange: Range<Int>,
    private val exposureStepValue: Double,
    private val currentExposureIndex: Int,
    private val currentContrast: Int,
    private val rawSupported: Boolean,
    private val currentRawEnabled: Boolean,
    private val onIsoChanged: (Int?) -> Unit,
    private val onExposureChanged: (Int) -> Unit,
    private val onContrastChanged: (Int) -> Unit,
    private val onRawToggled: (Boolean) -> Unit,
    private val onReset: () -> Unit
) : Dialog(context) {

    private lateinit var binding: DialogManualControlsBinding

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)

        binding = DialogManualControlsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupIso()
        setupExposure()
        setupContrast()
        setupRaw()

        binding.btnCloseManual.setOnClickListener { dismiss() }
        binding.btnReset.setOnClickListener {
            onReset()
            dismiss()
        }
    }

    private fun setupIso() {
        if (!isoSupported || isoRange == null) {
            binding.tvIsoValue.text = context.getString(R.string.manual_not_supported)
            binding.seekIso.isEnabled = false
            return
        }

        val span = isoRange.upper - isoRange.lower
        binding.seekIso.max = span + 1 // 0 = AUTO, 1..span+1 = valor manual

        val initialProgress = if (currentIso == null) 0 else (currentIso - isoRange.lower) + 1
        binding.seekIso.progress = initialProgress
        updateIsoLabel(initialProgress)

        binding.seekIso.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                updateIsoLabel(progress)
                if (!fromUser) return
                val isoValue = if (progress == 0) null else isoRange.lower + (progress - 1)
                onIsoChanged(isoValue)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
    }

    private fun updateIsoLabel(progress: Int) {
        binding.tvIsoValue.text = if (progress == 0) {
            context.getString(R.string.auto_label)
        } else {
            (isoRange!!.lower + (progress - 1)).toString()
        }
    }

    private fun setupExposure() {
        val span = exposureRange.upper - exposureRange.lower
        binding.seekExposure.max = span

        val initialProgress = currentExposureIndex - exposureRange.lower
        binding.seekExposure.progress = initialProgress
        updateExposureLabel(initialProgress)

        binding.seekExposure.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                updateExposureLabel(progress)
                if (!fromUser) return
                onExposureChanged(exposureRange.lower + progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
    }

    private fun updateExposureLabel(progress: Int) {
        val index = exposureRange.lower + progress
        val ev = index * exposureStepValue
        binding.tvExposureValue.text = String.format("%+.1f", ev)
    }

    private fun setupContrast() {
        // El SeekBar va de 0 a 100; se traduce a un rango de -50 a +50.
        val initialProgress = currentContrast + 50
        binding.seekContrast.progress = initialProgress
        binding.tvContrastValue.text = currentContrast.toString()

        binding.seekContrast.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val contrast = progress - 50
                binding.tvContrastValue.text = contrast.toString()
                if (fromUser) onContrastChanged(contrast)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
    }

    private fun setupRaw() {
        binding.switchRaw.isChecked = currentRawEnabled
        if (!rawSupported) {
            binding.switchRaw.isEnabled = false
        }
        binding.switchRaw.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !rawSupported) {
                binding.switchRaw.isChecked = false
                return@setOnCheckedChangeListener
            }
            onRawToggled(isChecked)
        }
    }
}
