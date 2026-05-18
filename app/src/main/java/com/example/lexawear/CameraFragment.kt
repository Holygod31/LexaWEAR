package com.example.lexawear

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * CameraFragment — two-shot clothing + care label camera flow.
 *
 * Shot 1 (default): point at clothing item → detects type, color, pattern
 * Shot 2 (care label): point at care label → reads wash/dry/iron/bleach/dryclean
 *
 * Results are passed back to MainActivity which routes them to the
 * originating fragment (NfcFragment or CareFragment).
 */
class CameraFragment : Fragment() {

    enum class Source { WRITE, CARE }

    companion object {
        var source: Source = Source.WRITE
        var pendingResults: Map<String, String>? = null
    }

    private lateinit var previewView: PreviewView
    private lateinit var tvOverlay: TextView
    private lateinit var tvMode: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnCapture: Button
    private lateinit var btnCare: Button
    private lateinit var btnCancel: Button

    private var imageCapture: ImageCapture? = null
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var isCareLabelMode = false
    private var liveAnalysisActive = true

    private lateinit var visionAnalyzer: VisionAnalyzer
    private lateinit var careSymbolClassifier: CareSymbolClassifier

    private val accumulatedFields = mutableMapOf<String, String>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera()
        else updateStatus(getString(R.string.camera_permission_required))
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_camera, container, false)

        previewView = view.findViewById(R.id.camera_preview)
        tvOverlay   = view.findViewById(R.id.tv_camera_overlay)
        tvMode      = view.findViewById(R.id.tv_camera_mode)
        tvStatus    = view.findViewById(R.id.tv_camera_status)
        btnCapture  = view.findViewById(R.id.btn_camera_capture)
        btnCare     = view.findViewById(R.id.btn_camera_care)
        btnCancel   = view.findViewById(R.id.btn_camera_cancel)

        careSymbolClassifier = CareSymbolClassifier(requireContext())
        visionAnalyzer = VisionAnalyzer(careSymbolClassifier)

        if (source == Source.CARE) {
            isCareLabelMode = true
            updateModeUI()
        }

        btnCapture.setOnClickListener { captureAndAnalyze() }

        btnCare.setOnClickListener {
            isCareLabelMode = !isCareLabelMode
            updateModeUI()
        }

        btnCancel.setOnClickListener {
            (activity as? MainActivity)?.onCameraCancel()
        }

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }

        return view
    }

    private fun updateModeUI() {
        if (isCareLabelMode) {
            tvMode.text = getString(R.string.camera_mode_care_label)
            tvMode.contentDescription = getString(R.string.camera_mode_care_label_description)
            tvOverlay.text = getString(R.string.camera_overlay_care_label)
            btnCare.text = getString(R.string.camera_btn_clothing)
            btnCare.contentDescription = getString(R.string.camera_btn_clothing_description)
            btnCapture.contentDescription = getString(R.string.camera_capture_care_description)
        } else {
            tvMode.text = getString(R.string.camera_mode_clothing)
            tvMode.contentDescription = getString(R.string.camera_mode_clothing_description)
            tvOverlay.text = getString(R.string.camera_overlay_clothing)
            btnCare.text = getString(R.string.camera_btn_care_label)
            btnCare.contentDescription = getString(R.string.camera_btn_care_label_description)
            btnCapture.contentDescription = getString(R.string.camera_capture_clothing_description)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        if (liveAnalysisActive) {
                            val bitmap = imageProxy.toBitmap()
                            visionAnalyzer.analyzeLiveFrame(bitmap) { label ->
                                requireActivity().runOnUiThread {
                                    if (liveAnalysisActive) tvOverlay.text = label
                                }
                            }
                        }
                        imageProxy.close()
                    }
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    viewLifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview, imageCapture, imageAnalysis
                )
            } catch (e: Exception) {
                updateStatus(getString(R.string.camera_error, e.message))
            }

        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun captureAndAnalyze() {
        val capture = imageCapture ?: return
        liveAnalysisActive = false
        btnCapture.isEnabled = false
        updateStatus(getString(R.string.camera_capturing))

        capture.takePicture(
            ContextCompat.getMainExecutor(requireContext()),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bitmap = image.toBitmap()
                    image.close()
                    processCapture(bitmap)
                }

                override fun onError(exception: ImageCaptureException) {
                    requireActivity().runOnUiThread {
                        updateStatus(getString(R.string.camera_capture_failed, exception.message))
                        btnCapture.isEnabled = true
                        liveAnalysisActive = true
                    }
                }
            }
        )
    }

    private fun processCapture(bitmap: Bitmap) {
        updateStatus(getString(R.string.camera_analyzing))
        if (isCareLabelMode) {
            visionAnalyzer.analyzeCareLabel(bitmap) { result ->
                requireActivity().runOnUiThread { handleResult(result) }
            }
        } else {
            visionAnalyzer.analyzeClothing(bitmap) { result ->
                requireActivity().runOnUiThread { handleResult(result) }
            }
        }
    }

    private fun handleResult(result: VisionAnalyzer.AnalysisResult) {
        accumulatedFields.putAll(result.fields)

        if (result.fields.isEmpty()) {
            updateStatus(getString(R.string.camera_nothing_recognized))
            tvOverlay.announceForAccessibility(getString(R.string.camera_nothing_recognized_accessibility))
            btnCapture.isEnabled = true
            liveAnalysisActive = true
            return
        }

        val confidencePct = (result.confidence * 100).toInt()
        val fieldSummary  = buildFieldSummary(result.fields)
        val modeLabel     = if (result.fromCareLabel)
            getString(R.string.camera_mode_label_care)
        else
            getString(R.string.camera_mode_label_clothing)

        updateStatus(getString(R.string.camera_detected_status, modeLabel, confidencePct, fieldSummary))
        tvOverlay.text = fieldSummary
        tvOverlay.announceForAccessibility(
            getString(R.string.camera_detected_accessibility, modeLabel, fieldSummary)
        )

        showResultActions()
    }

    /**
     * Builds a human-readable summary of detected fields using localised
     * field labels and decoded values from strings.xml — no hardcoded strings.
     */
    private fun buildFieldSummary(fields: Map<String, String>): String {
        val labelMap = mapOf(
            "T"  to getString(R.string.field_type),
            "CL" to getString(R.string.field_color),
            "P"  to getString(R.string.field_pattern),
            "W"  to getString(R.string.field_wash),
            "D"  to getString(R.string.field_drying),
            "I"  to getString(R.string.field_ironing),
            "B"  to getString(R.string.field_bleaching),
            "C"  to getString(R.string.field_dry_clean)
        )
        val typeMap = mapOf(
            "SH" to getString(R.string.type_shirt),   "TS" to getString(R.string.type_tshirt),
            "JK" to getString(R.string.type_jacket),  "CT" to getString(R.string.type_coat),
            "SW" to getString(R.string.type_sweater), "HD" to getString(R.string.type_hoodie),
            "BZ" to getString(R.string.type_blazer),  "SU" to getString(R.string.type_suit),
            "VS" to getString(R.string.type_vest),    "DR" to getString(R.string.type_dress),
            "UW" to getString(R.string.type_underwear),"PT" to getString(R.string.type_pants),
            "JN" to getString(R.string.type_jeans),   "ST" to getString(R.string.type_shorts),
            "SK" to getString(R.string.type_skirt),   "SC" to getString(R.string.type_socks)
        )
        val washMap = mapOf(
            "30" to getString(R.string.wash_30), "40" to getString(R.string.wash_40),
            "60" to getString(R.string.wash_60), "H"  to getString(R.string.wash_hand),
            "N"  to getString(R.string.wash_no)
        )
        val dryMap = mapOf(
            "A" to getString(R.string.dry_air),   "T" to getString(R.string.dry_tumble),
            "F" to getString(R.string.dry_flat),  "N" to getString(R.string.dry_no)
        )
        val ironMap = mapOf(
            "0" to getString(R.string.iron_no),     "1" to getString(R.string.iron_low),
            "2" to getString(R.string.iron_medium), "3" to getString(R.string.iron_high)
        )
        val yesNoMap = mapOf("1" to getString(R.string.yes), "0" to getString(R.string.no))

        return fields.entries.joinToString("  ·  ") { (key, code) ->
            val label = labelMap[key] ?: key
            val display = when (key) {
                "T"  -> typeMap[code]
                "CL" -> ColorPalette.nameForHex(code, ::getString)
                "P"  -> when (code) {
                    "P"  -> getString(R.string.pattern_plain)
                    "ST" -> getString(R.string.pattern_striped)
                    "CH" -> getString(R.string.pattern_checkered)
                    "PL" -> getString(R.string.pattern_plaid)
                    "FL" -> getString(R.string.pattern_floral)
                    "DT" -> getString(R.string.pattern_polkadot)
                    "GR" -> getString(R.string.pattern_graphic)
                    "CM" -> getString(R.string.pattern_camouflage)
                    "AN" -> getString(R.string.pattern_animal)
                    else -> null
                }
                "W"  -> washMap[code]
                "D"  -> dryMap[code]
                "I"  -> ironMap[code]
                "B"  -> yesNoMap[code]
                "C"  -> yesNoMap[code]
                else -> null
            } ?: code
            "$label: $display"
        }
    }

    private fun showResultActions() {
        btnCapture.text = getString(R.string.camera_btn_use_results)
        btnCapture.contentDescription = getString(R.string.camera_btn_use_results_description)
        btnCapture.isEnabled = true
        btnCapture.setOnClickListener { confirmResults() }

        btnCare.text = getString(R.string.camera_btn_retake)
        btnCare.contentDescription = getString(R.string.camera_btn_retake_description)
        btnCare.setOnClickListener {
            liveAnalysisActive = true
            btnCapture.text = getString(R.string.camera_btn_capture)
            btnCapture.contentDescription = getString(R.string.camera_btn_capture_description)
            btnCapture.setOnClickListener { captureAndAnalyze() }
            btnCare.text = if (isCareLabelMode)
                getString(R.string.camera_btn_clothing)
            else
                getString(R.string.camera_btn_care_label)
            btnCare.setOnClickListener {
                isCareLabelMode = !isCareLabelMode
                updateModeUI()
            }
            updateModeUI()
            updateStatus(getString(R.string.camera_ready))
        }
    }

    private fun confirmResults() {
        pendingResults = accumulatedFields.toMap()
        (activity as? MainActivity)?.onCameraResults(accumulatedFields.toMap(), source)
    }

    private fun updateStatus(message: String) {
        requireActivity().runOnUiThread {
            tvStatus.text = message
            tvStatus.contentDescription = message
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        liveAnalysisActive = false
        cameraExecutor.shutdown()
        careSymbolClassifier.close()
    }
}