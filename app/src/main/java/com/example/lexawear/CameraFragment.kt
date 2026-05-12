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
 *
 * Source is set by MainActivity before loading this fragment:
 *   CameraFragment.source = CameraFragment.Source.WRITE or Source.CARE
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

    // Accumulated results across both shots
    private val accumulatedFields = mutableMapOf<String, String>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera()
        else updateStatus("Camera permission is required for this feature.")
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

        // If source is CARE tab, start directly in care label mode
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
            tvMode.text = "Shot 2: Care Label"
            tvMode.contentDescription = "Care label scanning mode"
            tvOverlay.text = "Point camera at the care label inside the garment"
            btnCare.text = "Clothing"
            btnCare.contentDescription = "Switch back to clothing scanning mode"
            btnCapture.contentDescription = "Capture care label image"
        } else {
            tvMode.text = "Shot 1: Clothing"
            tvMode.contentDescription = "Clothing scanning mode"
            tvOverlay.text = "Point camera at the clothing item"
            btnCare.text = "Care\nLabel"
            btnCare.contentDescription = "Switch to care label scanning mode"
            btnCapture.contentDescription = "Capture clothing image and analyze"
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

            // Live analysis via ImageAnalysis
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
                    preview,
                    imageCapture,
                    imageAnalysis
                )
            } catch (e: Exception) {
                updateStatus("Camera error: ${e.message}")
            }

        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun captureAndAnalyze() {
        val capture = imageCapture ?: return
        liveAnalysisActive = false
        btnCapture.isEnabled = false
        updateStatus("Capturing…")

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
                        updateStatus("Capture failed: ${exception.message}")
                        btnCapture.isEnabled = true
                        liveAnalysisActive = true
                    }
                }
            }
        )
    }

    private fun processCapture(bitmap: Bitmap) {
        updateStatus("Analyzing…")

        if (isCareLabelMode) {
            visionAnalyzer.analyzeCareLabel(bitmap) { result ->
                requireActivity().runOnUiThread {
                    handleResult(result)
                }
            }
        } else {
            visionAnalyzer.analyzeClothing(bitmap) { result ->
                requireActivity().runOnUiThread {
                    handleResult(result)
                }
            }
        }
    }

    private fun handleResult(result: VisionAnalyzer.AnalysisResult) {
        // Merge into accumulated fields — new results overwrite old ones
        accumulatedFields.putAll(result.fields)

        val confidencePct = (result.confidence * 100).toInt()
        val fieldSummary = buildFieldSummary(result.fields)

        if (result.fields.isEmpty()) {
            updateStatus("Nothing recognized. Try again or adjust the angle.")
            tvOverlay.announceForAccessibility("Nothing recognized. Try adjusting the angle.")
            btnCapture.isEnabled = true
            liveAnalysisActive = true
            return
        }

        val modeLabel = if (result.fromCareLabel) "Care label" else "Clothing"
        updateStatus("$modeLabel detected ($confidencePct% confidence). $fieldSummary")
        tvOverlay.text = fieldSummary
        tvOverlay.announceForAccessibility("$modeLabel detected. $fieldSummary")

        // Show confirmation buttons
        showResultActions()
    }

    private fun buildFieldSummary(fields: Map<String, String>): String {
        val labelMap = mapOf(
            "T" to "Type", "CL" to "Color", "P" to "Pattern",
            "W" to "Wash", "D" to "Dry", "I" to "Iron",
            "B" to "Bleach", "C" to "Dry Clean"
        )
        val decodeMap = mapOf(
            "T"  to mapOf("SH" to "Shirt", "TS" to "T-Shirt", "JK" to "Jacket",
                "CT" to "Coat", "SW" to "Sweater", "HD" to "Hoodie",
                "BZ" to "Blazer", "SU" to "Suit", "VS" to "Vest",
                "DR" to "Dress", "UW" to "Underwear", "PT" to "Pants",
                "JN" to "Jeans", "ST" to "Shorts", "SK" to "Skirt", "SC" to "Socks"),
            "CL" to mapOf("212121" to "Black", "F5F5F5" to "White", "9E9E9E" to "Grey",
                "1A237E" to "Navy",  "2196F3" to "Blue",  "F44336" to "Red",
                "4CAF50" to "Green", "FFEB3B" to "Yellow","FF9800" to "Orange",
                "E91E63" to "Pink",  "9C27B0" to "Purple","795548" to "Brown",
                "D7CCC8" to "Beige", "FF5722" to "Multicolor"),
            "P"  to mapOf("P" to "Plain", "ST" to "Striped", "CH" to "Checkered",
                "PL" to "Plaid", "FL" to "Floral", "DT" to "Polka Dot",
                "GR" to "Graphic", "CM" to "Camouflage", "AN" to "Animal Print"),
            "W"  to mapOf("30" to "Wash 30°", "40" to "Wash 40°", "60" to "Wash 60°",
                "H" to "Hand wash", "N" to "Do not wash"),
            "D"  to mapOf("A" to "Air dry", "T" to "Tumble dry", "F" to "Flat dry", "N" to "Do not dry"),
            "I"  to mapOf("0" to "No iron", "1" to "Low heat", "2" to "Medium heat", "3" to "High heat"),
            "B"  to mapOf("1" to "Bleach OK", "0" to "No bleach"),
            "C"  to mapOf("1" to "Dry clean OK", "0" to "No dry clean")
        )

        return fields.entries.joinToString("  ·  ") { (key, code) ->
            val label = labelMap[key] ?: key
            val display = decodeMap[key]?.get(code) ?: code
            "$label: $display"
        }
    }

    private fun showResultActions() {
        // Replace Capture button with Use / Retake
        btnCapture.text = "Use Results"
        btnCapture.contentDescription = "Use these results and fill in the fields. Double tap to confirm."
        btnCapture.isEnabled = true
        btnCapture.setOnClickListener { confirmResults() }

        btnCare.text = "Retake"
        btnCare.contentDescription = "Retake the photo"
        btnCare.setOnClickListener {
            // Reset to capture mode
            liveAnalysisActive = true
            btnCapture.text = "📷  Capture"
            btnCapture.contentDescription = "Capture image and analyze clothing"
            btnCapture.setOnClickListener { captureAndAnalyze() }
            btnCare.text = if (isCareLabelMode) "Clothing" else "Care\nLabel"
            btnCare.setOnClickListener {
                isCareLabelMode = !isCareLabelMode
                updateModeUI()
            }
            updateModeUI()
            updateStatus("Ready. Tap Capture when ready.")
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