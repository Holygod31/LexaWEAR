package com.example.lexawear

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * VisionAnalyzer — on-device analysis of clothing images and care labels.
 *
 * Clothing shot:
 *  - ML Kit Image Labeling   → clothing type code (T field), only if confidence ≥ threshold
 *  - K-means pixel clustering → dominant color hex via ColorPalette (CL field)
 *  - Heuristic edge analysis  → pattern code (P field)
 *
 * Care label shot:
 *  - ML Kit OCR             → wash/dry/iron/bleach/dryclean codes
 *  - CareSymbolClassifier   → same fields from symbol icons (if model available)
 *
 * Design principles:
 *  - Confident silence beats a confident wrong answer.
 *    Type is omitted when ML Kit confidence < TYPE_CONFIDENCE_THRESHOLD.
 *    The typeLowConfidence flag is set so CameraFragment can prompt the user.
 *  - Colour uses k-means clustering (k=3) on a centre-cropped pixel sample
 *    to find the dominant colour, ignoring background and shadow pixels.
 *  - Partial results (colour only, no type) are fully valid and returned.
 */
class VisionAnalyzer(
    private val careSymbolClassifier: CareSymbolClassifier,
    private val context: Context? = null
) {

    data class AnalysisResult(
        val fields: Map<String, String>,
        val labels: List<String>,
        val confidence: Float,
        val fromCareLabel: Boolean = false,
        /** True if type was detected but confidence was below threshold. */
        val typeLowConfidence: Boolean = false
    )

    // ── Thresholds ────────────────────────────────────────────────────────────

    /** Minimum ML Kit label confidence to accept a clothing type. */
    private val TYPE_CONFIDENCE_THRESHOLD = 0.60f

    /**
     * Minimum fraction lead the dominant k-means cluster must have over
     * the second cluster to be accepted as dominant. If below this, the
     * top-2 clusters are blended instead.
     */
    private val CLUSTER_DOMINANCE_THRESHOLD = 0.15f

    // ── Clothing type mapping ─────────────────────────────────────────────────

    private val labelToTypeCode = mapOf(
        "shirt"        to "SH", "dress shirt"   to "SH",
        "t-shirt"      to "TS", "tshirt"        to "TS", "tee"       to "TS",
        "jacket"       to "JK", "denim jacket"  to "JK", "bomber"    to "JK",
        "coat"         to "CT", "overcoat"      to "CT", "trench"    to "CT",
        "sweater"      to "SW", "jumper"        to "SW", "pullover"  to "SW",
        "hoodie"       to "HD", "sweatshirt"    to "HD",
        "blazer"       to "BZ",
        "suit"         to "SU",
        "vest"         to "VS", "waistcoat"     to "VS",
        "dress"        to "DR", "gown"          to "DR",
        "underwear"    to "UW", "bra"           to "UW", "lingerie"  to "UW",
        "pants"        to "PT", "trousers"      to "PT", "chinos"    to "PT",
        "jeans"        to "JN", "denim"         to "JN",
        "shorts"       to "ST",
        "skirt"        to "SK",
        "socks"        to "SC", "sock"          to "SC",
        "clothing"     to "",   "fashion"       to "",
        "textile"      to "",   "fabric"        to ""
    )

    // ── Care label OCR rules ──────────────────────────────────────────────────

    private data class OcrRule(val pattern: Regex, val field: String, val code: String)

    private val ocrRules = listOf(
        OcrRule(Regex("do not wash|do not launder|no wash",               RegexOption.IGNORE_CASE), "W", "N"),
        OcrRule(Regex("hand\\s*wash|hand launder",                        RegexOption.IGNORE_CASE), "W", "H"),
        OcrRule(Regex("60\\s*°?c?|60\\s*degrees",                        RegexOption.IGNORE_CASE), "W", "60"),
        OcrRule(Regex("40\\s*°?c?|40\\s*degrees",                        RegexOption.IGNORE_CASE), "W", "40"),
        OcrRule(Regex("30\\s*°?c?|30\\s*degrees",                        RegexOption.IGNORE_CASE), "W", "30"),
        OcrRule(Regex("machine\\s*wash|machine\\s*launder",               RegexOption.IGNORE_CASE), "W", "40"),
        OcrRule(Regex("do not dry|do not tumble",                         RegexOption.IGNORE_CASE), "D", "N"),
        OcrRule(Regex("tumble\\s*dry|machine\\s*dry",                     RegexOption.IGNORE_CASE), "D", "T"),
        OcrRule(Regex("flat\\s*dry|dry\\s*flat",                          RegexOption.IGNORE_CASE), "D", "F"),
        OcrRule(Regex("air\\s*dry|hang\\s*dry|line\\s*dry",               RegexOption.IGNORE_CASE), "D", "A"),
        OcrRule(Regex("do not iron|no iron|no ironing",                   RegexOption.IGNORE_CASE), "I", "0"),
        OcrRule(Regex("iron.*high|high.*iron|hot.*iron",                  RegexOption.IGNORE_CASE), "I", "3"),
        OcrRule(Regex("iron.*medium|medium.*iron|warm.*iron",             RegexOption.IGNORE_CASE), "I", "2"),
        OcrRule(Regex("iron.*low|low.*iron|cool.*iron",                   RegexOption.IGNORE_CASE), "I", "1"),
        OcrRule(Regex("do not bleach|no bleach|bleach.*not",              RegexOption.IGNORE_CASE), "B", "0"),
        OcrRule(Regex("bleach.*ok|bleach.*allowed|bleach.*permitted",     RegexOption.IGNORE_CASE), "B", "1"),
        OcrRule(Regex("do not dry.?clean|no dry.?clean",                  RegexOption.IGNORE_CASE), "C", "0"),
        OcrRule(Regex("dry.?clean",                                       RegexOption.IGNORE_CASE), "C", "1")
    )

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Analyze a clothing item bitmap.
     * Returns type (if confidence ≥ threshold), color (k-means), and pattern fields.
     * When type confidence is below threshold, [AnalysisResult.typeLowConfidence] is
     * set so CameraFragment can prompt the user to fill it in manually.
     */
    fun analyzeClothing(bitmap: Bitmap, onResult: (AnalysisResult) -> Unit) {
        val image   = InputImage.fromBitmap(bitmap, 0)
        val labeler = ImageLabeling.getClient(
            // Lower gate here — we want candidates, threshold is applied below.
            ImageLabelerOptions.Builder().setConfidenceThreshold(0.4f).build()
        )

        labeler.process(image)
            .addOnSuccessListener { labels ->
                val rawLabels     = labels.map { it.text.lowercase() }
                val topConfidence = labels.firstOrNull()?.confidence ?: 0f

                val matchedTypeCode = rawLabels.firstNotNullOfOrNull { label ->
                    labelToTypeCode.entries.firstOrNull { (key, _) ->
                        label.contains(key)
                    }?.value?.takeIf { it.isNotEmpty() }
                }

                val typeCode          = if (topConfidence >= TYPE_CONFIDENCE_THRESHOLD) matchedTypeCode ?: "" else ""
                val typeLowConfidence = matchedTypeCode != null && topConfidence < TYPE_CONFIDENCE_THRESHOLD

                val colorHex    = extractDominantColor(bitmap)
                val patternCode = detectPattern(bitmap)

                val fields = mutableMapOf<String, String>()
                if (typeCode.isNotEmpty())    fields["T"]  = typeCode
                if (colorHex.isNotEmpty())    fields["CL"] = colorHex
                if (patternCode.isNotEmpty()) fields["P"]  = patternCode

                onResult(AnalysisResult(
                    fields            = fields,
                    labels            = labels.map { "${it.text} (${(it.confidence * 100).toInt()}%)" },
                    confidence        = topConfidence,
                    typeLowConfidence = typeLowConfidence
                ))
            }
            .addOnFailureListener {
                // ML Kit failed — return colour and pattern from pixel analysis only.
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
     * Returns wash, dry, iron, bleach, dry-clean fields from OCR + TFLite stub.
     */
    fun analyzeCareLabel(bitmap: Bitmap, onResult: (AnalysisResult) -> Unit) {
        val image      = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val text   = visionText.text
                val fields = mutableMapOf<String, String>()
                ocrRules.forEach { rule ->
                    if (rule.field !in fields && rule.pattern.containsMatchIn(text)) {
                        fields[rule.field] = rule.code
                    }
                }
                if (careSymbolClassifier.isAvailable) {
                    careSymbolClassifier.classify(bitmap).forEach { (k, v) ->
                        if (k !in fields) fields[k] = v
                    }
                }
                onResult(AnalysisResult(
                    fields        = fields,
                    labels        = listOf("OCR: ${text.take(80)}"),
                    confidence    = if (fields.isNotEmpty()) 0.8f else 0.2f,
                    fromCareLabel = true
                ))
            }
            .addOnFailureListener {
                val fields = if (careSymbolClassifier.isAvailable)
                    careSymbolClassifier.classify(bitmap).toMutableMap()
                else mutableMapOf()
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
     * Returns a human-readable label string for the overlay; no pixel analysis.
     */
    fun analyzeLiveFrame(bitmap: Bitmap, onResult: (String) -> Unit) {
        val image   = InputImage.fromBitmap(bitmap, 0)
        val labeler = ImageLabeling.getClient(
            ImageLabelerOptions.Builder().setConfidenceThreshold(0.55f).build()
        )
        labeler.process(image)
            .addOnSuccessListener { labels ->
                if (labels.isEmpty()) {
                    onResult(context?.getString(R.string.vision_analyzing) ?: "…")
                    return@addOnSuccessListener
                }
                onResult(labels.take(3).joinToString("  ·  ") {
                    "${it.text} ${(it.confidence * 100).toInt()}%"
                })
            }
            .addOnFailureListener { onResult(context?.getString(R.string.vision_analyzing) ?: "…") }
    }

    // ── Colour extraction — k-means ───────────────────────────────────────────

    /**
     * Extracts the dominant colour from a bitmap using k-means clustering (k=3).
     *
     * Steps:
     *  1. Centre-crop to the inner 60% to discard background/shadow edges.
     *  2. Sample a grid of pixels (up to ~400 samples for speed).
     *  3. Skip near-white (brightness > 235) and near-black (brightness < 20)
     *     pixels to avoid background and shadow contamination.
     *  4. Run k-means for 10 iterations.
     *  5. Pick the largest cluster as dominant, unless the lead over second
     *     cluster is < CLUSTER_DOMINANCE_THRESHOLD — then blend top-2.
     *  6. Map the winning RGB to the nearest [ColorPalette] entry.
     *
     * Falls back to [simpleAverageColor] on any error or insufficient samples.
     * Returns the palette hex code, or empty string on total failure.
     */
    fun extractDominantColor(bitmap: Bitmap): String {
        return try {
            val w      = bitmap.width
            val h      = bitmap.height
            val startX = (w * 0.2).toInt()
            val startY = (h * 0.2).toInt()
            val endX   = (w * 0.8).toInt()
            val endY   = (h * 0.8).toInt()
            val stepX  = maxOf(1, (endX - startX) / 20)
            val stepY  = maxOf(1, (endY - startY) / 20)

            val samples = mutableListOf<Triple<Int, Int, Int>>()
            for (y in startY until endY step stepY) {
                for (x in startX until endX step stepX) {
                    val p          = bitmap.getPixel(x, y)
                    val r          = Color.red(p)
                    val g          = Color.green(p)
                    val b          = Color.blue(p)
                    val brightness = (r + g + b) / 3
                    // Skip near-white and near-black — likely background or shadow.
                    if (brightness in 20..235) samples.add(Triple(r, g, b))
                }
            }

            if (samples.size < 10) return simpleAverageColor(bitmap)

            val clusters = kMeans(samples, k = 3, iterations = 10)
            val sorted   = clusters.sortedByDescending { it.size }
            val total    = samples.size.toFloat()

            val dominantFraction = sorted[0].size / total
            val secondFraction   = if (sorted.size > 1) sorted[1].size / total else 0f

            val (r, g, b) = if (dominantFraction - secondFraction >= CLUSTER_DOMINANCE_THRESHOLD) {
                sorted[0].centroid
            } else {
                // Clusters too close — blend top-2 by weight.
                val w1 = sorted[0].size; val w2 = sorted[1].size; val wt = (w1 + w2).toFloat()
                Triple(
                    ((sorted[0].centroid.first  * w1 + sorted[1].centroid.first  * w2) / wt).toInt(),
                    ((sorted[0].centroid.second * w1 + sorted[1].centroid.second * w2) / wt).toInt(),
                    ((sorted[0].centroid.third  * w1 + sorted[1].centroid.third  * w2) / wt).toInt()
                )
            }

            ColorPalette.nearestEntry(r, g, b).hex

        } catch (e: Exception) {
            simpleAverageColor(bitmap)
        }
    }

    // ── K-means ───────────────────────────────────────────────────────────────

    private data class Cluster(
        var centroid: Triple<Int, Int, Int>,
        val members: MutableList<Triple<Int, Int, Int>> = mutableListOf()
    ) {
        val size get() = members.size
    }

    /**
     * Lloyd's k-means on RGB triples with k++ initialisation.
     * k++ spreads initial centroids for more stable results than random init.
     */
    private fun kMeans(
        samples: List<Triple<Int, Int, Int>>,
        k: Int,
        iterations: Int
    ): List<Cluster> {
        if (samples.size <= k) return samples.map { Cluster(it, mutableListOf(it)) }

        // k++ init — first centroid is the middle sample, each subsequent
        // is chosen as the sample farthest from all existing centroids.
        val centroids = mutableListOf(samples[samples.size / 2])
        while (centroids.size < k) {
            val farthest = samples.maxByOrNull { s ->
                centroids.minOf { c -> rgbDistanceSq(s, c) }
            } ?: break
            if (!centroids.contains(farthest)) centroids.add(farthest) else break
        }
        // Pad with evenly-spaced samples if k++ didn't yield enough unique centroids.
        while (centroids.size < k) {
            centroids.add(samples[(samples.size * centroids.size) / k])
        }

        var clusters = centroids.map { Cluster(it) }

        repeat(iterations) {
            clusters.forEach { it.members.clear() }
            samples.forEach { s ->
                clusters.minByOrNull { rgbDistanceSq(s, it.centroid) }!!.members.add(s)
            }
            clusters.forEach { cluster ->
                if (cluster.members.isNotEmpty()) {
                    val n = cluster.members.size.toFloat()
                    cluster.centroid = Triple(
                        (cluster.members.sumOf { it.first }  / n).toInt(),
                        (cluster.members.sumOf { it.second } / n).toInt(),
                        (cluster.members.sumOf { it.third }  / n).toInt()
                    )
                }
            }
        }
        return clusters
    }

    private fun rgbDistanceSq(a: Triple<Int, Int, Int>, b: Triple<Int, Int, Int>): Int {
        val dr = a.first - b.first; val dg = a.second - b.second; val db = a.third - b.third
        return dr * dr + dg * dg + db * db
    }

    /** Simple fallback: average all centre-crop pixels and map to palette. */
    private fun simpleAverageColor(bitmap: Bitmap): String {
        val w = bitmap.width; val h = bitmap.height
        val startX = (w * 0.2).toInt(); val endX = (w * 0.8).toInt()
        val startY = (h * 0.2).toInt(); val endY = (h * 0.8).toInt()
        val stepX  = maxOf(1, (endX - startX) / 20)
        val stepY  = maxOf(1, (endY - startY) / 20)
        var rSum = 0L; var gSum = 0L; var bSum = 0L; var count = 0
        for (y in startY until endY step stepY) {
            for (x in startX until endX step stepX) {
                val p = bitmap.getPixel(x, y)
                rSum += Color.red(p); gSum += Color.green(p); bSum += Color.blue(p); count++
            }
        }
        if (count == 0) return ""
        return ColorPalette.nearestEntry(
            (rSum / count).toInt(), (gSum / count).toInt(), (bSum / count).toInt()
        ).hex
    }

    // ── Pattern detection ─────────────────────────────────────────────────────

    /**
     * Heuristic pattern detection based on pixel variance and edge frequency
     * on a 64×64 downscaled grayscale copy of the bitmap.
     *
     * - Very low variance (< 200)               → Plain (P)
     * - High horizontal edge ratio (> 0.25)     → Striped (ST)
     * - High both-axis edge ratio (> 0.3 each)  → Checkered (CH)
     * - High variance (> 1500), no structure    → Graphic (GR)
     * - Default                                 → Plain (P)
     */
    fun detectPattern(bitmap: Bitmap): String {
        val scale = 64
        val small = Bitmap.createScaledBitmap(bitmap, scale, scale, true)

        val pixels = Array(scale) { y ->
            IntArray(scale) { x ->
                val p = small.getPixel(x, y)
                (Color.red(p) + Color.green(p) + Color.blue(p)) / 3
            }
        }

        val flat     = pixels.flatMap { it.toList() }
        val mean     = flat.average()
        val variance = flat.map { (it - mean) * (it - mean) }.average()

        if (variance < 200) return "P"

        var hEdges = 0
        for (y in 1 until scale) {
            for (x in 0 until scale) {
                if (Math.abs(pixels[y][x] - pixels[y - 1][x]) > 30) hEdges++
            }
        }
        var vEdges = 0
        for (y in 0 until scale) {
            for (x in 1 until scale) {
                if (Math.abs(pixels[y][x] - pixels[y][x - 1]) > 30) vEdges++
            }
        }

        val total      = (scale * scale).toFloat()
        val hEdgeRatio = hEdges / total
        val vEdgeRatio = vEdges / total

        return when {
            hEdgeRatio > 0.3f && vEdgeRatio > 0.3f -> "CH"
            hEdgeRatio > 0.25f                      -> "ST"
            vEdgeRatio > 0.25f                      -> "ST"
            variance > 1500                         -> "GR"
            else                                    -> "P"
        }
    }
}