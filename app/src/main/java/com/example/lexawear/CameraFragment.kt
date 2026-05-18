package com.example.lexawear

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.ToneGenerator
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
 * Shot 1 (default): point at clothing item → detects type (if confident), color, pattern.
 * Shot 2 (care label): point at care label → reads wash/dry/iron/bleach/dryclean.
 *
 * Partial results are announced field-by-field via TalkBack so blind users
 * understand exactly what was and wasn't detected. If type confidence is too
 * low, a specific prompt asks the user to fill it in manually.
 *
 * An audio shutter cue (brief beep) plays on capture so blind users get
 * confirmation without needing to watch the screen.
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
        // Pass context so VisionAnalyzer can resolve string resources.
        visionAnalyzer = VisionAnalyzer(careSymbolClassifier, requireContext())

        if (source == Source.CARE) {
            isCareLabelMode = true
            updateModeUI()
        }

        btnCapture.setOnClickListener { captureAndAnalyze() }
        btnCare.setOnClickListener { isCareLabelMode = !isCareLabelMode; updateModeUI() }
        btnCancel.setOnClickListener { (activity as? MainActivity)?.onCameraCancel() }

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }

        return view
    }

    // ── Mode UI ───────────────────────────────────────────────────────────────

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

    // ── Camera setup ──────────────────────────────────────────────────────────

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build()
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

    // ── Capture flow ──────────────────────────────────────────────────────────

    private fun captureAndAnalyze() {
        val capture = imageCapture ?: return
        liveAnalysisActive = false
        btnCapture.isEnabled = false

        // Brief audio cue — confirms capture for blind users without screen feedback.
        playShutterCue()
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

    /**
     * Plays a brief 880 Hz beep as a shutter confirmation sound.
     * Fails silently — non-critical, device may be muted.
     */
    private fun playShutterCue() {
        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60)
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
        } catch (e: Exception) { /* non-critical */ }
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

    // ── Result handling ───────────────────────────────────────────────────────

    /**
     * Handles a vision result with field-by-field TalkBack announcements.
     *
     * Partial results are valid — e.g. colour detected but type not confident.
     * Each detected field is announced separately so the user knows exactly
     * what was found and what still needs manual input.
     */
    private fun handleResult(result: VisionAnalyzer.AnalysisResult) {
        accumulatedFields.putAll(result.fields)

        if (result.fields.isEmpty() && !result.typeLowConfidence) {
            updateStatus(getString(R.string.camera_nothing_recognized))
            tvOverlay.announceForAccessibility(
                getString(R.string.camera_nothing_recognized_accessibility)
            )
            btnCapture.isEnabled = true
            liveAnalysisActive = true
            return
        }

        val announcements = mutableListOf<String>()
        val summaryParts  = mutableListOf<String>()

        // ── Type ──────────────────────────────────────────────────────────────
        val typeCode = result.fields["T"]
        if (typeCode != null) {
            val typeName = getString(when (typeCode) {
                "SH" -> R.string.type_shirt;    "TS" -> R.string.type_tshirt
                "JK" -> R.string.type_jacket;   "CT" -> R.string.type_coat
                "SW" -> R.string.type_sweater;  "HD" -> R.string.type_hoodie
                "BZ" -> R.string.type_blazer;   "SU" -> R.string.type_suit
                "VS" -> R.string.type_vest;     "DR" -> R.string.type_dress
                "UW" -> R.string.type_underwear;"PT" -> R.string.type_pants
                "JN" -> R.string.type_jeans;    "ST" -> R.string.type_shorts
                "SK" -> R.string.type_skirt;    "SC" -> R.string.type_socks
                else -> R.string.field_type
            })
            val confidencePct = (result.confidence * 100).toInt()
            announcements.add(getString(R.string.camera_type_detected, typeName, confidencePct))
            summaryParts.add("${getString(R.string.field_type)}: $typeName ($confidencePct%)")
        } else if (result.typeLowConfidence) {
            announcements.add(getString(R.string.camera_type_low_confidence))
            summaryParts.add(getString(R.string.camera_type_low_confidence))
        }

        // ── Colour ────────────────────────────────────────────────────────────
        val colorHex = result.fields["CL"]
        if (colorHex != null) {
            val colorName = ColorPalette.nameForHex(colorHex, ::getString)
                ?: getString(ColorPalette.nearestEntryFromArgb(
                    try { android.graphics.Color.parseColor("#$colorHex") } catch (e: Exception) { 0 }
                ).nameRes)
            announcements.add(getString(R.string.camera_color_detected, colorName))
            summaryParts.add("${getString(R.string.field_color)}: $colorName")
        }

        // ── Pattern ───────────────────────────────────────────────────────────
        val patternCode = result.fields["P"]
        if (patternCode != null) {
            val patternName = getString(when (patternCode) {
                "P"  -> R.string.pattern_plain;       "ST" -> R.string.pattern_striped
                "CH" -> R.string.pattern_checkered;   "PL" -> R.string.pattern_plaid
                "FL" -> R.string.pattern_floral;      "DT" -> R.string.pattern_polkadot
                "GR" -> R.string.pattern_graphic;     "CM" -> R.string.pattern_camouflage
                "AN" -> R.string.pattern_animal;      else -> R.string.field_pattern
            })
            summaryParts.add("${getString(R.string.field_pattern)}: $patternName")
        }

        // ── Care label fields ─────────────────────────────────────────────────
        if (result.fromCareLabel) {
            result.fields.filterKeys { it in setOf("W", "D", "I", "B", "C") }
                .forEach { (key, code) ->
                    val label = when (key) {
                        "W" -> getString(R.string.field_wash)
                        "D" -> getString(R.string.field_drying)
                        "I" -> getString(R.string.field_ironing)
                        "B" -> getString(R.string.field_bleaching)
                        "C" -> getString(R.string.field_dry_clean)
                        else -> key
                    }
                    val decoded = when (key) {
                        "W" -> when (code) {
                            "30" -> getString(R.string.wash_30); "40" -> getString(R.string.wash_40)
                            "60" -> getString(R.string.wash_60); "H"  -> getString(R.string.wash_hand)
                            "N"  -> getString(R.string.wash_no); else -> code
                        }
                        "D" -> when (code) {
                            "A" -> getString(R.string.dry_air);   "T" -> getString(R.string.dry_tumble)
                            "F" -> getString(R.string.dry_flat);  "N" -> getString(R.string.dry_no); else -> code
                        }
                        "I" -> when (code) {
                            "0" -> getString(R.string.iron_no);     "1" -> getString(R.string.iron_low)
                            "2" -> getString(R.string.iron_medium); "3" -> getString(R.string.iron_high); else -> code
                        }
                        "B", "C" -> when (code) {
                            "1" -> getString(R.string.yes); "0" -> getString(R.string.no); else -> code
                        }
                        else -> code
                    }
                    summaryParts.add("$label: $decoded")
                }
        }

        val summaryText = summaryParts.joinToString("  ·  ")
        tvOverlay.text  = summaryText
        updateStatus(summaryText)
        tvOverlay.announceForAccessibility(announcements.joinToString(". "))

        showResultActions()
    }

    // ── Result actions ────────────────────────────────────────────────────────

    private fun showResultActions() {
        btnCapture.text = getString(R.string.camera_btn_use_results)
        btnCapture.contentDescription = getString(R.string.camera_btn_use_results_description)
        btnCapture.isEnabled = true
        btnCapture.setOnClickListener { confirmResults() }

        btnCare.text = getString(R.string.camera_btn_retake)
        btnCare.contentDescription = getString(R.string.camera_btn_retake_description)
        btnCare.setOnClickListener {
            liveAnalysisActive = true
            accumulatedFields.clear()
            btnCapture.text = getString(R.string.camera_btn_capture)
            btnCapture.contentDescription = getString(R.string.camera_btn_capture_description)
            btnCapture.setOnClickListener { captureAndAnalyze() }
            btnCare.text = if (isCareLabelMode) getString(R.string.camera_btn_clothing)
            else getString(R.string.camera_btn_care_label)
            btnCare.setOnClickListener { isCareLabelMode = !isCareLabelMode; updateModeUI() }
            updateModeUI()
            updateStatus(getString(R.string.camera_ready))
        }
    }

    private fun confirmResults() {
        pendingResults = accumulatedFields.toMap()
        (activity as? MainActivity)?.onCameraResults(accumulatedFields.toMap(), source)
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

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