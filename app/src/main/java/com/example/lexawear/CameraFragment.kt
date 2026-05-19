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

    /** Identifies which flow launched the camera — affects where results are routed. */
    enum class Source { WRITE, CARE }

    companion object {
        /** Set by the caller before navigating here; read in [confirmResults] to route back. */
        var source: Source = Source.WRITE

        /**
         * Holds the last confirmed result map so MainActivity can read it after
         * the fragment is destroyed. Cleared by the caller after consumption.
         */
        var pendingResults: Map<String, String>? = null
    }

    private lateinit var previewView: PreviewView
    private lateinit var tvOverlay: TextView   // live label hint or post-capture summary
    private lateinit var tvMode: TextView      // current shot mode displayed to sighted users
    private lateinit var tvStatus: TextView    // status/error line also read by TalkBack
    private lateinit var btnCapture: Button
    private lateinit var btnCare: Button       // toggles mode OR becomes "Retake" after capture
    private lateinit var btnCancel: Button

    private var imageCapture: ImageCapture? = null
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    /** True when the user has switched to care-label shot mode. */
    private var isCareLabelMode = false

    /**
     * Guards the live-analysis loop. Set to false on capture to stop frame
     * processing while the still image is being analysed; restored on retake.
     */
    private var liveAnalysisActive = true

    private lateinit var visionAnalyzer: VisionAnalyzer
    private lateinit var careSymbolClassifier: CareSymbolClassifier

    /**
     * Accumulates fields across both shots (clothing + care label).
     * Passed as [pendingResults] when the user confirms.
     */
    private val accumulatedFields = mutableMapOf<String, String>()

    /** Requests CAMERA permission; starts camera on grant, shows error on denial. */
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
        // Context required so VisionAnalyzer can resolve string resources for labels.
        visionAnalyzer = VisionAnalyzer(careSymbolClassifier, requireContext())

        // If launched from the Scan tab, start straight in care-label mode.
        if (source == Source.CARE) {
            isCareLabelMode = true
            updateModeUI()
        }

        btnCapture.setOnClickListener { captureAndAnalyze() }
        // Toggle between clothing and care-label modes before first capture.
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

    /** Syncs all button labels and content descriptions to the current [isCareLabelMode] state. */
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

    /**
     * Binds Preview, ImageCapture, and ImageAnalysis use-cases to the lifecycle.
     * ImageAnalysis drives the live overlay hint; ImageCapture is used for the
     * still shot. Both share the same back camera selector.
     */
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
                // Drop frames if analyser is busy — prevents queue build-up on slow devices.
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        // Guard check prevents stale frames updating the overlay after capture.
                        if (liveAnalysisActive) {
                            val bitmap = imageProxy.toBitmap()
                            visionAnalyzer.analyzeLiveFrame(bitmap) { label ->
                                requireActivity().runOnUiThread {
                                    if (liveAnalysisActive) tvOverlay.text = label
                                }
                            }
                        }
                        // Must always close — failure to close blocks the camera pipeline.
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

    /**
     * Freezes live analysis, plays the shutter cue, then takes a still image.
     * The captured [ImageProxy] is converted to [Bitmap] and handed to [processCapture].
     */
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
                        // Restore live analysis so the user can try again.
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

    /** Routes the captured bitmap to the correct analyser based on current mode. */
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

        val announcements = mutableListOf<String>() // read aloud by TalkBack as a sequence
        val summaryParts  = mutableListOf<String>() // shown in tvOverlay and tvStatus

        // ── Type ──────────────────────────────────────────────────────────────
        val typeCode = result.fields["T"]
        if (typeCode != null) {
            // Resolve display name from language-neutral type code stored on the tag.
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
            // Confidence below threshold — prompt the user to set the type manually.
            announcements.add(getString(R.string.camera_type_low_confidence))
            summaryParts.add(getString(R.string.camera_type_low_confidence))
        }

        // ── Colour ────────────────────────────────────────────────────────────
        val colorHex = result.fields["CL"]
        if (colorHex != null) {
            // ColorPalette is the single source of truth for hex → name resolution.
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
            // Decode each care code to a human-readable string for the summary line.
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
        // Announce all fields in one TalkBack utterance, dot-separated for natural pacing.
        tvOverlay.announceForAccessibility(announcements.joinToString(". "))

        showResultActions()
    }

    // ── Result actions ────────────────────────────────────────────────────────

    /**
     * Reconfigures buttons after a successful capture: Capture → "Use results",
     * Care/Toggle → "Retake". Retake resets all state for another attempt.
     */
    private fun showResultActions() {
        btnCapture.text = getString(R.string.camera_btn_use_results)
        btnCapture.contentDescription = getString(R.string.camera_btn_use_results_description)
        btnCapture.isEnabled = true
        btnCapture.setOnClickListener { confirmResults() }

        btnCare.text = getString(R.string.camera_btn_retake)
        btnCare.contentDescription = getString(R.string.camera_btn_retake_description)
        btnCare.setOnClickListener {
            // Full reset — clear accumulated fields and restore pre-capture button state.
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

    /**
     * Publishes [accumulatedFields] to [pendingResults] and delegates routing
     * back to MainActivity, which knows the correct destination fragment.
     */
    private fun confirmResults() {
        pendingResults = accumulatedFields.toMap()
        (activity as? MainActivity)?.onCameraResults(accumulatedFields.toMap(), source)
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    /** Posts a status message on the main thread; also sets contentDescription for TalkBack. */
    private fun updateStatus(message: String) {
        requireActivity().runOnUiThread {
            tvStatus.text = message
            tvStatus.contentDescription = message
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Stop live analysis before executor shuts down to prevent post-destroy callbacks.
        liveAnalysisActive = false
        cameraExecutor.shutdown()
        careSymbolClassifier.close()
    }
}