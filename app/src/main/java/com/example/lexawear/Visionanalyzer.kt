package com.example.lexawear

import android.graphics.Bitmap
import android.graphics.Color
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * VisionAnalyzer — on-device analysis of clothing images and care labels.
 *
 * Clothing shot:
 *  - ML Kit Image Labeling  → clothing type code (T field)
 *  - Pixel analysis         → dominant color hex (CL field)
 *  - Heuristic analysis     → pattern code (P field)
 *
 * Care label shot:
 *  - ML Kit OCR             → wash/dry/iron/bleach/dryclean codes
 *  - CareSymbolClassifier   → same fields from symbol icons (if model available)
 *
 * All results are returned as a Map<String, String> keyed by LexaWEAR field codes.
 * Confidence is included so the caller can decide how to present results.
 */
class VisionAnalyzer(private val careSymbolClassifier: CareSymbolClassifier) {

    data class AnalysisResult(
        val fields: Map<String, String>,    // fieldKey → fieldCode
        val labels: List<String>,           // raw ML Kit labels for display
        val confidence: Float,              // 0..1 overall confidence
        val fromCareLabel: Boolean = false
    )

    // ── Clothing type mapping ─────────────────────────────────────────────────

    private val labelToTypeCode = mapOf(
        // Tops
        "shirt"        to "SH", "dress shirt"   to "SH",
        "t-shirt"      to "TS", "tshirt"         to "TS", "tee"       to "TS",
        "jacket"       to "JK", "denim jacket"   to "JK", "bomber"    to "JK",
        "coat"         to "CT", "overcoat"       to "CT", "trench"    to "CT",
        "sweater"      to "SW", "jumper"         to "SW", "pullover"  to "SW",
        "hoodie"       to "HD", "sweatshirt"     to "HD",
        "blazer"       to "BZ",
        "suit"         to "SU",
        "vest"         to "VS", "waistcoat"      to "VS",
        "dress"        to "DR", "gown"           to "DR",
        "underwear"    to "UW", "bra"            to "UW", "lingerie"  to "UW",
        "pants"        to "PT", "trousers"       to "PT", "chinos"    to "PT",
        "jeans"        to "JN", "denim"          to "JN",
        "shorts"       to "ST",
        "skirt"        to "SK",
        "socks"        to "SC", "sock"           to "SC",
        // Generic fallbacks
        "clothing"     to "",   "fashion"        to "",
        "textile"      to "",   "fabric"         to ""
    )

    // ── Color mapping ─────────────────────────────────────────────────────────

    private val colorHexMap = mapOf(
        "212121" to "Black",  "F5F5F5" to "White",
        "9E9E9E" to "Grey",   "1A237E" to "Navy",
        "2196F3" to "Blue",   "F44336" to "Red",
        "4CAF50" to "Green",  "FFEB3B" to "Yellow",
        "FF9800" to "Orange", "E91E63" to "Pink",
        "9C27B0" to "Purple", "795548" to "Brown",
        "D7CCC8" to "Beige",  "FF5722" to "Multicolor"
    )

    // Reference colors as RGB for nearest-match calculation
    private val referenceColors = mapOf(
        "212121" to Triple(33,  33,  33),   // Black
        "F5F5F5" to Triple(245, 245, 245),  // White
        "9E9E9E" to Triple(158, 158, 158),  // Grey
        "1A237E" to Triple(26,  35,  126),  // Navy
        "2196F3" to Triple(33,  150, 243),  // Blue
        "F44336" to Triple(244, 67,  54),   // Red
        "4CAF50" to Triple(76,  175, 80),   // Green
        "FFEB3B" to Triple(255, 235, 59),   // Yellow
        "FF9800" to Triple(255, 152, 0),    // Orange
        "E91E63" to Triple(233, 30,  99),   // Pink
        "9C27B0" to Triple(156, 39,  176),  // Purple
        "795548" to Triple(121, 85,  72),   // Brown
        "D7CCC8" to Triple(215, 204, 200),  // Beige
        "FF5722" to Triple(255, 87,  34)    // Multicolor (orange-red as proxy)
    )

    // ── Care label OCR mapping ────────────────────────────────────────────────

    private data class OcrRule(
        val pattern: Regex,
        val field: String,
        val code: String
    )

    private val ocrRules = listOf(
        // Wash
        OcrRule(Regex("do not wash|do not launder|no wash", RegexOption.IGNORE_CASE),       "W", "N"),
        OcrRule(Regex("hand\\s*wash|hand launder",           RegexOption.IGNORE_CASE),       "W", "H"),
        OcrRule(Regex("60\\s*°?c?|60\\s*degrees",           RegexOption.IGNORE_CASE),       "W", "60"),
        OcrRule(Regex("40\\s*°?c?|40\\s*degrees",           RegexOption.IGNORE_CASE),       "W", "40"),
        OcrRule(Regex("30\\s*°?c?|30\\s*degrees",           RegexOption.IGNORE_CASE),       "W", "30"),
        OcrRule(Regex("machine\\s*wash|machine\\s*launder",  RegexOption.IGNORE_CASE),       "W", "40"), // default machine wash
        // Dry
        OcrRule(Regex("do not dry|do not tumble",            RegexOption.IGNORE_CASE),       "D", "N"),
        OcrRule(Regex("tumble\\s*dry|machine\\s*dry",        RegexOption.IGNORE_CASE),       "D", "T"),
        OcrRule(Regex("flat\\s*dry|dry\\s*flat",             RegexOption.IGNORE_CASE),       "D", "F"),
        OcrRule(Regex("air\\s*dry|hang\\s*dry|line\\s*dry",  RegexOption.IGNORE_CASE),       "D", "A"),
        // Iron
        OcrRule(Regex("do not iron|no iron|no ironing",      RegexOption.IGNORE_CASE),       "I", "0"),
        OcrRule(Regex("iron.*high|high.*iron|hot.*iron",     RegexOption.IGNORE_CASE),       "I", "3"),
        OcrRule(Regex("iron.*medium|medium.*iron|warm.*iron",RegexOption.IGNORE_CASE),       "I", "2"),
        OcrRule(Regex("iron.*low|low.*iron|cool.*iron",      RegexOption.IGNORE_CASE),       "I", "1"),
        // Bleach
        OcrRule(Regex("do not bleach|no bleach|bleach.*not", RegexOption.IGNORE_CASE),       "B", "0"),
        OcrRule(Regex("bleach.*ok|bleach.*allowed|bleach.*permitted", RegexOption.IGNORE_CASE), "B", "1"),
        // Dry clean
        OcrRule(Regex("do not dry.?clean|no dry.?clean",     RegexOption.IGNORE_CASE),       "C", "0"),
        OcrRule(Regex("dry.?clean",                          RegexOption.IGNORE_CASE),       "C", "1")
    )

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Analyze a clothing item bitmap.
     * Returns type, color, pattern fields.
     */
    fun analyzeClothing(
        bitmap: Bitmap,
        onResult: (AnalysisResult) -> Unit
    ) {
        val image = InputImage.fromBitmap(bitmap, 0)
        val labeler = ImageLabeling.getClient(
            ImageLabelerOptions.Builder()
                .setConfidenceThreshold(0.5f)
                .build()
        )

        labeler.process(image)
            .addOnSuccessListener { labels ->
                val rawLabels = labels.map { it.text.lowercase() }
                val topConfidence = labels.firstOrNull()?.confidence ?: 0f

                // Type: find first label that maps to a clothing type code
                val typeCode = rawLabels.firstNotNullOfOrNull { label ->
                    labelToTypeCode.entries.firstOrNull { (key, _) ->
                        label.contains(key)
                    }?.value?.takeIf { it.isNotEmpty() }
                } ?: ""

                // Color: dominant pixel analysis
                val colorHex = extractDominantColor(bitmap)

                // Pattern: heuristic pixel analysis
                val patternCode = detectPattern(bitmap)

                val fields = mutableMapOf<String, String>()
                if (typeCode.isNotEmpty())  fields["T"]  = typeCode
                if (colorHex.isNotEmpty())  fields["CL"] = colorHex
                if (patternCode.isNotEmpty()) fields["P"] = patternCode

                onResult(AnalysisResult(
                    fields      = fields,
                    labels      = labels.map { "${it.text} (${(it.confidence * 100).toInt()}%)" },
                    confidence  = topConfidence
                ))
            }
            .addOnFailureListener {
                // Return what we can from pixel analysis alone
                val colorHex    = extractDominantColor(bitmap)
                val patternCode = detectPattern(bitmap)
                val fields = mutableMapOf<String, String>()
                if (colorHex.isNotEmpty())    fields["CL"] = colorHex
                if (patternCode.isNotEmpty()) fields["P"]  = patternCode
                onResult(AnalysisResult(fields, emptyList(), 0f))
            }
    }

    /**
     * Analyze a care label bitmap.
     * Returns wash, dry, iron, bleach, dry-clean fields from OCR + TFLite.
     */
    fun analyzeCareLabel(
        bitmap: Bitmap,
        onResult: (AnalysisResult) -> Unit
    ) {
        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val text = visionText.text
                val fields = mutableMapOf<String, String>()

                // Apply OCR rules — first match wins per field
                ocrRules.forEach { rule ->
                    if (rule.field !in fields && rule.pattern.containsMatchIn(text)) {
                        fields[rule.field] = rule.code
                    }
                }

                // TFLite symbol classifier (if model available)
                if (careSymbolClassifier.isAvailable) {
                    val symbolFields = careSymbolClassifier.classify(bitmap)
                    // OCR takes priority; symbols fill gaps
                    symbolFields.forEach { (k, v) ->
                        if (k !in fields) fields[k] = v
                    }
                }

                val confidence = if (fields.isNotEmpty()) 0.8f else 0.2f
                onResult(AnalysisResult(
                    fields       = fields,
                    labels       = listOf("OCR: ${text.take(80)}"),
                    confidence   = confidence,
                    fromCareLabel = true
                ))
            }
            .addOnFailureListener {
                // Try TFLite symbols only
                val fields = if (careSymbolClassifier.isAvailable)
                    careSymbolClassifier.classify(bitmap).toMutableMap()
                else
                    mutableMapOf()

                onResult(AnalysisResult(
                    fields        = fields,
                    labels        = emptyList(),
                    confidence    = if (fields.isNotEmpty()) 0.5f else 0f,
                    fromCareLabel = true
                ))
            }
    }

    /**
     * Quick live-preview analysis — lighter than full analyzeClothing.
     * Only returns a human-readable label string for the overlay.
     * Does not run pixel analysis (too slow per-frame).
     */
    fun analyzeLiveFrame(
        bitmap: Bitmap,
        onResult: (String) -> Unit
    ) {
        val image = InputImage.fromBitmap(bitmap, 0)
        val labeler = ImageLabeling.getClient(
            ImageLabelerOptions.Builder()
                .setConfidenceThreshold(0.55f)
                .build()
        )

        labeler.process(image)
            .addOnSuccessListener { labels ->
                if (labels.isEmpty()) {
                    onResult("Analyzing…")
                    return@addOnSuccessListener
                }
                val top = labels.take(3).joinToString("  ·  ") {
                    "${it.text} ${(it.confidence * 100).toInt()}%"
                }
                onResult(top)
            }
            .addOnFailureListener { onResult("Analyzing…") }
    }

    // ── Color extraction ──────────────────────────────────────────────────────

    /**
     * Sample a grid of pixels from the center region of the bitmap,
     * average the RGB values, then find the nearest LexaWEAR color.
     */
    fun extractDominantColor(bitmap: Bitmap): String {
        val w = bitmap.width
        val h = bitmap.height

        // Sample from center 60% to avoid backgrounds
        val startX = (w * 0.2).toInt()
        val startY = (h * 0.2).toInt()
        val endX   = (w * 0.8).toInt()
        val endY   = (h * 0.8).toInt()

        val stepX = maxOf(1, (endX - startX) / 20)
        val stepY = maxOf(1, (endY - startY) / 20)

        var rSum = 0L; var gSum = 0L; var bSum = 0L; var count = 0

        for (y in startY until endY step stepY) {
            for (x in startX until endX step stepX) {
                val pixel = bitmap.getPixel(x, y)
                rSum += Color.red(pixel)
                gSum += Color.green(pixel)
                bSum += Color.blue(pixel)
                count++
            }
        }

        if (count == 0) return ""

        val avgR = (rSum / count).toInt()
        val avgG = (gSum / count).toInt()
        val avgB = (bSum / count).toInt()

        return nearestColor(avgR, avgG, avgB)
    }

    private fun nearestColor(r: Int, g: Int, b: Int): String {
        var bestHex = ""
        var bestDist = Int.MAX_VALUE

        referenceColors.forEach { (hex, rgb) ->
            val dr = r - rgb.first
            val dg = g - rgb.second
            val db = b - rgb.third
            val dist = dr * dr + dg * dg + db * db
            if (dist < bestDist) {
                bestDist = dist
                bestHex = hex
            }
        }
        return bestHex
    }

    // ── Pattern detection ─────────────────────────────────────────────────────

    /**
     * Heuristic pattern detection based on pixel variance and edge analysis.
     *
     * - Very low variance → Plain (P)
     * - High horizontal edge frequency → Striped (ST)
     * - High both-axis edge frequency → Checkered (CH)
     * - High overall variance, no clear structure → Graphic (GR)
     * - Default → Plain (P)
     */
    fun detectPattern(bitmap: Bitmap): String {
        val w = bitmap.width
        val h = bitmap.height

        // Work on a small scaled-down version for speed
        val scale = 64
        val small = Bitmap.createScaledBitmap(bitmap, scale, scale, true)

        val pixels = Array(scale) { y ->
            IntArray(scale) { x ->
                val p = small.getPixel(x, y)
                (Color.red(p) + Color.green(p) + Color.blue(p)) / 3  // grayscale
            }
        }

        // Variance
        val flat = pixels.flatMap { it.toList() }
        val mean = flat.average()
        val variance = flat.map { (it - mean) * (it - mean) }.average()

        if (variance < 200) return "P"  // Very uniform → Plain

        // Horizontal edge count (stripes run horizontally)
        var hEdges = 0
        for (y in 1 until scale) {
            for (x in 0 until scale) {
                if (Math.abs(pixels[y][x] - pixels[y - 1][x]) > 30) hEdges++
            }
        }

        // Vertical edge count
        var vEdges = 0
        for (y in 0 until scale) {
            for (x in 1 until scale) {
                if (Math.abs(pixels[y][x] - pixels[y][x - 1]) > 30) vEdges++
            }
        }

        val totalPixels = scale * scale
        val hEdgeRatio = hEdges.toFloat() / totalPixels
        val vEdgeRatio = vEdges.toFloat() / totalPixels

        return when {
            hEdgeRatio > 0.3f && vEdgeRatio > 0.3f -> "CH"  // Checkered
            hEdgeRatio > 0.25f                      -> "ST"  // Striped (horizontal bands)
            vEdgeRatio > 0.25f                      -> "ST"  // Striped (vertical bands)
            variance > 1500                         -> "GR"  // High variance → Graphic
            else                                    -> "P"   // Default plain
        }
    }
}