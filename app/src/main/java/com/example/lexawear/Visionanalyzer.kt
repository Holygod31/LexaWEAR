package com.example.lexawear

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * VisionAnalyzer — on-device analysis of clothing images and care labels.
 *
 * ── Clothing type detection — two-tier ───────────────────────────────────────
 *
 * Tier 1 (preferred): Custom TFLite clothing classifier.
 *   Drop `clothing_classifier.tflite` into `app/src/main/assets/` and update
 *   [CLOTHING_LABELS] to match your trained model's class output order.
 *   See `docs/clothing_classifier_training.md` for full training instructions.
 *
 * Tier 2 (fallback): ML Kit Image Labeling.
 *   Used automatically when `clothing_classifier.tflite` is absent.
 *   Less accurate for clothing but requires no training.
 *   Type is omitted when confidence < [TYPE_CONFIDENCE_THRESHOLD].
 *
 * ── Colour detection ─────────────────────────────────────────────────────────
 * K-means clustering (k=3) on a centre-cropped pixel sample.
 * Near-white and near-black pixels excluded to avoid background contamination.
 * Result mapped to [ColorPalette] — same palette the rest of the app uses.
 *
 * ── Pattern detection ────────────────────────────────────────────────────────
 * Heuristic edge-frequency analysis on a 64×64 downscaled grayscale copy.
 *
 * ── Care label ───────────────────────────────────────────────────────────────
 * ML Kit OCR + optional CareSymbolClassifier TFLite stub.
 */
class VisionAnalyzer(
    private val careSymbolClassifier: CareSymbolClassifier,
    private val context: Context? = null  // required for string resources and asset loading
) {

    data class AnalysisResult(
        val fields: Map<String, String>,
        val labels: List<String>,       // human-readable debug labels (not stored on tag)
        val confidence: Float,
        val fromCareLabel: Boolean = false,
        /** True if type was seen but below confidence threshold — prompt manual entry. */
        val typeLowConfidence: Boolean = false
    )

    // ── Thresholds ────────────────────────────────────────────────────────────

    /** Minimum ML Kit label confidence to accept a clothing type (fallback path). */
    private val TYPE_CONFIDENCE_THRESHOLD = 0.60f

    /**
     * Minimum TFLite softmax probability to accept a type from the custom model.
     * Slightly lower than the ML Kit threshold because the custom model is more
     * specialised and produces sharper distributions.
     */
    private val TFLITE_TYPE_THRESHOLD = 0.55f

    /** Minimum cluster fraction lead to accept the dominant k-means cluster as-is. */
    private val CLUSTER_DOMINANCE_THRESHOLD = 0.15f

    // ── Custom TFLite clothing classifier ────────────────────────────────────

    /**
     * Class labels in the EXACT ORDER your trained model outputs them.
     *
     * ⚠ CRITICAL: This list must match the class output order of your trained
     * model. Mismatch causes silent wrong-type predictions with no error.
     * Update after training to match `train_clothing_classifier.py` output.
     *
     * Default order matches the DeepFashion2-based training script in
     * `tools/train_clothing_classifier.py`. If you trained on a different
     * dataset or class mapping, update this list accordingly.
     *
     * See `docs/clothing_classifier_training.md` → "Updating CLOTHING_LABELS".
     */
    val CLOTHING_LABELS = listOf(
        "SH",  // shirt / dress shirt
        "TS",  // t-shirt / top
        "JK",  // jacket / bomber / denim jacket
        "CT",  // coat / overcoat / trench
        "SW",  // sweater / jumper / pullover / knitwear
        "HD",  // hoodie / sweatshirt
        "BZ",  // blazer
        "SU",  // suit
        "VS",  // vest / waistcoat
        "DR",  // dress / gown / skirt
        "PT",  // pants / trousers / chinos
        "JN",  // jeans / denim
        "ST",  // shorts
        "UW",  // underwear / lingerie / bra
        "SC"   // socks / hosiery
    )

    private val TFLITE_MODEL_FILE = "clothing_classifier.tflite"

    /**
     * Input image size expected by the model (width = height = this value).
     * MobileNetV2 default is 224. Update if you use a different backbone.
     */
    private val TFLITE_INPUT_SIZE = 224

    private var tfliteInterpreter: Interpreter? = null

    /** True when the custom TFLite model is loaded and ready to use. */
    val isCustomModelAvailable: Boolean get() = tfliteInterpreter != null

    init {
        tfliteInterpreter = loadTfliteModel()
    }

    /**
     * Attempts to load [TFLITE_MODEL_FILE] from assets.
     * Returns null silently if the file is absent — this is the expected state
     * before the model has been trained and dropped in.
     */
    private fun loadTfliteModel(): Interpreter? {
        return try {
            val ctx = context ?: return null
            val files = ctx.assets.list("") ?: return null
            if (TFLITE_MODEL_FILE !in files) return null

            val afd         = ctx.assets.openFd(TFLITE_MODEL_FILE)
            val inputStream = FileInputStream(afd.fileDescriptor)
            val fileChannel = inputStream.channel
            // Memory-map the model file for zero-copy loading into the interpreter.
            val model: java.nio.MappedByteBuffer = fileChannel.map(
                FileChannel.MapMode.READ_ONLY,
                afd.startOffset,
                afd.declaredLength
            )
            Interpreter(model)
        } catch (e: Exception) {
            null  // Fail silently — app works without the custom model
        }
    }

    /** Releases the TFLite interpreter; call from the hosting fragment's onDestroyView. */
    fun close() {
        tfliteInterpreter?.close()
        tfliteInterpreter = null
    }

    // ── Clothing type mapping (ML Kit fallback path) ──────────────────────────

    /**
     * Maps ML Kit free-text labels to LexaWEAR type codes.
     * Generic clothing words (e.g. "clothing", "fabric") map to "" so they don't
     * produce a false type match but do count as a clothing detection.
     */
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
        "clothing"     to "",   "fashion"       to "",   // generic — no type code
        "textile"      to "",   "fabric"        to ""    // generic — no type code
    )

    // ── Care label OCR rules ──────────────────────────────────────────────────

    private data class OcrRule(val pattern: Regex, val field: String, val code: String)

    /**
     * Ordered OCR matching rules for care label text.
     * First-match wins per field — more specific patterns (e.g. "do not wash")
     * must appear before general ones (e.g. "machine wash") to avoid wrong codes.
     */
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
     * Analyse a clothing item bitmap.
     *
     * Dispatches to [analyzeClothingWithTflite] if the custom model is loaded,
     * otherwise falls back to [analyzeClothingWithMlKit].
     * Returns type (if confidence ≥ threshold), colour (k-means), pattern fields.
     * Sets [AnalysisResult.typeLowConfidence] when type was detected but not confident.
     */
    fun analyzeClothing(bitmap: Bitmap, onResult: (AnalysisResult) -> Unit) {
        if (isCustomModelAvailable) {
            analyzeClothingWithTflite(bitmap, onResult)
        } else {
            analyzeClothingWithMlKit(bitmap, onResult)
        }
    }

    /**
     * Tier 1: Custom TFLite clothing classifier.
     *
     * Pre-processes the bitmap to [TFLITE_INPUT_SIZE]×[TFLITE_INPUT_SIZE],
     * normalises pixels to [0, 1], runs inference, applies softmax to convert
     * raw logits to probabilities, then maps the top class index to a LexaWEAR
     * type code via [CLOTHING_LABELS].
     *
     * Falls back to [analyzeClothingWithMlKit] on any exception.
     */
    private fun analyzeClothingWithTflite(bitmap: Bitmap, onResult: (AnalysisResult) -> Unit) {
        try {
            val interpreter = tfliteInterpreter ?: run {
                analyzeClothingWithMlKit(bitmap, onResult); return
            }

            // Pre-process: resize to model input size, normalise pixels to [0.0, 1.0].
            val resized     = Bitmap.createScaledBitmap(bitmap, TFLITE_INPUT_SIZE, TFLITE_INPUT_SIZE, true)
            val inputBuffer = ByteBuffer.allocateDirect(4 * TFLITE_INPUT_SIZE * TFLITE_INPUT_SIZE * 3)
                .apply { order(ByteOrder.nativeOrder()) }

            for (y in 0 until TFLITE_INPUT_SIZE) {
                for (x in 0 until TFLITE_INPUT_SIZE) {
                    val pixel = resized.getPixel(x, y)
                    // Channel order: R, G, B — must match training pre-processing.
                    inputBuffer.putFloat(Color.red(pixel)   / 255f)
                    inputBuffer.putFloat(Color.green(pixel) / 255f)
                    inputBuffer.putFloat(Color.blue(pixel)  / 255f)
                }
            }

            // Run inference — output shape is [1, CLOTHING_LABELS.size].
            val outputBuffer = Array(1) { FloatArray(CLOTHING_LABELS.size) }
            interpreter.run(inputBuffer, outputBuffer)
            val scores = outputBuffer[0]

            // Softmax: subtract max score first for numerical stability before exp().
            val maxScore  = scores.max()
            val expScores = scores.map { Math.exp((it - maxScore).toDouble()).toFloat() }
            val sumExp    = expScores.sum()
            val probs     = expScores.map { it / sumExp }

            val topIndex      = probs.indices.maxByOrNull { probs[it] } ?: 0
            val topProb       = probs[topIndex]
            val topCode       = CLOTHING_LABELS.getOrElse(topIndex) { "" }

            val typeCode          = if (topProb >= TFLITE_TYPE_THRESHOLD && topCode.isNotEmpty()) topCode else ""
            // Low confidence: type was seen but not reliable enough to pre-fill.
            val typeLowConfidence = topCode.isNotEmpty() && topProb < TFLITE_TYPE_THRESHOLD

            val colorHex    = extractDominantColor(bitmap)
            val patternCode = detectPattern(bitmap)

            val fields = mutableMapOf<String, String>()
            if (typeCode.isNotEmpty())    fields["T"]  = typeCode
            if (colorHex.isNotEmpty())    fields["CL"] = colorHex
            if (patternCode.isNotEmpty()) fields["P"]  = patternCode

            onResult(AnalysisResult(
                fields            = fields,
                labels            = listOf("TFLite: $topCode (${(topProb * 100).toInt()}%)"),
                confidence        = topProb,
                typeLowConfidence = typeLowConfidence
            ))

        } catch (e: Exception) {
            // TFLite inference failed — fall back to ML Kit rather than surfacing an error.
            analyzeClothingWithMlKit(bitmap, onResult)
        }
    }

    /**
     * Tier 2: ML Kit Image Labeling fallback.
     * Used when custom TFLite model is absent or throws during inference.
     * Maps ML Kit free-text labels to type codes via [labelToTypeCode].
     * Colour and pattern are still extracted even if no type is matched.
     */
    private fun analyzeClothingWithMlKit(bitmap: Bitmap, onResult: (AnalysisResult) -> Unit) {
        val image   = InputImage.fromBitmap(bitmap, 0)
        val labeler = ImageLabeling.getClient(
            // 0.4f threshold to cast a wide net — type confidence filter applied below.
            ImageLabelerOptions.Builder().setConfidenceThreshold(0.4f).build()
        )

        labeler.process(image)
            .addOnSuccessListener { labels ->
                val rawLabels     = labels.map { it.text.lowercase() }
                val topConfidence = labels.firstOrNull()?.confidence ?: 0f

                // Find the first label that maps to a non-empty type code.
                val matchedTypeCode = rawLabels.firstNotNullOfOrNull { label ->
                    labelToTypeCode.entries.firstOrNull { (key, _) ->
                        label.contains(key)
                    }?.value?.takeIf { it.isNotEmpty() }
                }

                val typeCode          = if (topConfidence >= TYPE_CONFIDENCE_THRESHOLD) matchedTypeCode ?: "" else ""
                // Low confidence: a clothing type was matched but overall confidence too low.
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
                // ML Kit failed entirely — still return colour/pattern if available.
                val colorHex    = extractDominantColor(bitmap)
                val patternCode = detectPattern(bitmap)
                val fields = mutableMapOf<String, String>()
                if (colorHex.isNotEmpty())    fields["CL"] = colorHex
                if (patternCode.isNotEmpty()) fields["P"]  = patternCode
                onResult(AnalysisResult(fields, emptyList(), 0f))
            }
    }

    /**
     * Analyse a care label bitmap.
     *
     * Primary: ML Kit OCR matched against [ocrRules] (first-match per field).
     * Secondary: [CareSymbolClassifier] fills any fields OCR didn't populate.
     * Returns wash / dry / iron / bleach / dry-clean fields where found.
     */
    fun analyzeCareLabel(bitmap: Bitmap, onResult: (AnalysisResult) -> Unit) {
        val image      = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val text   = visionText.text
                val fields = mutableMapOf<String, String>()
                // First-match per field — ocrRules ordering is load-bearing (specific before general).
                ocrRules.forEach { rule ->
                    if (rule.field !in fields && rule.pattern.containsMatchIn(text)) {
                        fields[rule.field] = rule.code
                    }
                }
                // Symbol classifier fills fields OCR couldn't read (e.g. no text on label).
                if (careSymbolClassifier.isAvailable) {
                    careSymbolClassifier.classify(bitmap).forEach { (k, v) ->
                        if (k !in fields) fields[k] = v  // OCR result takes precedence
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
                // OCR failed — fall back to symbol classifier only if available.
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
     * Lightweight live-preview analysis for the camera overlay hint.
     * Uses ML Kit only — no pixel analysis — to keep frame processing fast.
     * Returns a human-readable label string (not an [AnalysisResult]);
     * result is never stored or written to a tag.
     */
    fun analyzeLiveFrame(bitmap: Bitmap, onResult: (String) -> Unit) {
        val image   = InputImage.fromBitmap(bitmap, 0)
        val labeler = ImageLabeling.getClient(
            // Higher threshold than full analysis — only show confident hints.
            ImageLabelerOptions.Builder().setConfidenceThreshold(0.55f).build()
        )
        labeler.process(image)
            .addOnSuccessListener { labels ->
                if (labels.isEmpty()) {
                    onResult(context?.getString(R.string.vision_analyzing) ?: "…")
                    return@addOnSuccessListener
                }
                // Top 3 labels formatted as "Label N%  ·  Label N%  ·  Label N%".
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
     *  3. Skip near-white (brightness > 235) and near-black (brightness < 20).
     *  4. Run k-means for 10 iterations.
     *  5. Pick largest cluster if it leads by ≥ [CLUSTER_DOMINANCE_THRESHOLD],
     *     otherwise blend the top-2 clusters by member count.
     *  6. Map the winning RGB to the nearest [ColorPalette] entry.
     *
     * Falls back to [simpleAverageColor] on error or fewer than 10 valid samples.
     */
    fun extractDominantColor(bitmap: Bitmap): String {
        return try {
            val w      = bitmap.width;  val h      = bitmap.height
            val startX = (w * 0.2).toInt(); val endX = (w * 0.8).toInt()
            val startY = (h * 0.2).toInt(); val endY = (h * 0.8).toInt()
            val stepX  = maxOf(1, (endX - startX) / 20)
            val stepY  = maxOf(1, (endY - startY) / 20)

            val samples = mutableListOf<Triple<Int, Int, Int>>()
            for (y in startY until endY step stepY) {
                for (x in startX until endX step stepX) {
                    val p = bitmap.getPixel(x, y)
                    val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
                    // Exclude near-black (<20) and near-white (>235) to avoid background bias.
                    if ((r + g + b) / 3 in 20..235) samples.add(Triple(r, g, b))
                }
            }

            if (samples.size < 10) return simpleAverageColor(bitmap)

            val clusters = kMeans(samples, k = 3, iterations = 10)
            val sorted   = clusters.sortedByDescending { it.size }
            val total    = samples.size.toFloat()

            val dominantFraction = sorted[0].size / total
            val secondFraction   = if (sorted.size > 1) sorted[1].size / total else 0f

            // If the dominant cluster leads clearly, use it alone; otherwise blend top-2.
            val (r, g, b) = if (dominantFraction - secondFraction >= CLUSTER_DOMINANCE_THRESHOLD) {
                sorted[0].centroid
            } else {
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

    /**
     * Holds a cluster's centroid RGB and its member pixels.
     * Centroid is updated in-place each iteration.
     */
    private data class Cluster(
        var centroid: Triple<Int, Int, Int>,
        val members: MutableList<Triple<Int, Int, Int>> = mutableListOf()
    ) { val size get() = members.size }

    /**
     * Simple k-means implementation for RGB pixel clustering.
     * Seeds using a spread-initialisation strategy (not random) for
     * deterministic results: first centroid is the median sample,
     * subsequent centroids are the samples farthest from existing ones.
     */
    private fun kMeans(samples: List<Triple<Int, Int, Int>>, k: Int, iterations: Int): List<Cluster> {
        if (samples.size <= k) return samples.map { Cluster(it, mutableListOf(it)) }

        // Seed: start at median sample, then pick farthest remaining samples.
        val centroids = mutableListOf(samples[samples.size / 2])
        while (centroids.size < k) {
            val farthest = samples.maxByOrNull { s -> centroids.minOf { c -> rgbDistanceSq(s, c) } } ?: break
            if (!centroids.contains(farthest)) centroids.add(farthest) else break
        }
        // Fallback: fill remaining slots with evenly-spaced samples if seeding stalled.
        while (centroids.size < k) centroids.add(samples[(samples.size * centroids.size) / k])

        var clusters = centroids.map { Cluster(it) }
        repeat(iterations) {
            // E-step: assign each sample to its nearest centroid.
            clusters.forEach { it.members.clear() }
            samples.forEach { s -> clusters.minByOrNull { rgbDistanceSq(s, it.centroid) }!!.members.add(s) }
            // M-step: recompute each centroid as the mean of its members.
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

    /** Squared Euclidean distance in RGB space — avoids sqrt for comparison-only use. */
    private fun rgbDistanceSq(a: Triple<Int, Int, Int>, b: Triple<Int, Int, Int>): Int {
        val dr = a.first - b.first; val dg = a.second - b.second; val db = a.third - b.third
        return dr * dr + dg * dg + db * db
    }

    /**
     * Simple centre-crop average colour — used when k-means can't run
     * (fewer than 10 valid samples or unexpected exception).
     */
    private fun simpleAverageColor(bitmap: Bitmap): String {
        val w = bitmap.width; val h = bitmap.height
        val startX = (w * 0.2).toInt(); val endX = (w * 0.8).toInt()
        val startY = (h * 0.2).toInt(); val endY = (h * 0.8).toInt()
        val stepX  = maxOf(1, (endX - startX) / 20); val stepY = maxOf(1, (endY - startY) / 20)
        var rSum = 0L; var gSum = 0L; var bSum = 0L; var count = 0
        for (y in startY until endY step stepY) {
            for (x in startX until endX step stepX) {
                val p = bitmap.getPixel(x, y)
                rSum += Color.red(p); gSum += Color.green(p); bSum += Color.blue(p); count++
            }
        }
        if (count == 0) return ""
        return ColorPalette.nearestEntry((rSum / count).toInt(), (gSum / count).toInt(), (bSum / count).toInt()).hex
    }

    // ── Pattern detection ─────────────────────────────────────────────────────

    /**
     * Heuristic pattern detection from edge frequency on a 64×64 greyscale image.
     *
     * Low variance → plain ("P").
     * High horizontal + vertical edges → checkered ("CH").
     * High horizontal or vertical edges alone → striped ("ST").
     * High variance with no dominant edge direction → graphic print ("GR").
     * Otherwise → plain ("P").
     *
     * Thresholds are empirical — retune with real garment images if needed.
     */
    fun detectPattern(bitmap: Bitmap): String {
        val scale = 64
        val small = Bitmap.createScaledBitmap(bitmap, scale, scale, true)
        // Convert to greyscale intensity grid for edge analysis.
        val pixels = Array(scale) { y ->
            IntArray(scale) { x ->
                val p = small.getPixel(x, y)
                (Color.red(p) + Color.green(p) + Color.blue(p)) / 3
            }
        }
        val flat     = pixels.flatMap { it.toList() }
        val mean     = flat.average()
        val variance = flat.map { (it - mean) * (it - mean) }.average()
        // Low variance → uniform colour → plain.
        if (variance < 200) return "P"

        // Count pixels where neighbour delta exceeds edge threshold (30 intensity units).
        var hEdges = 0
        for (y in 1 until scale) for (x in 0 until scale) if (Math.abs(pixels[y][x] - pixels[y-1][x]) > 30) hEdges++
        var vEdges = 0
        for (y in 0 until scale) for (x in 1 until scale) if (Math.abs(pixels[y][x] - pixels[y][x-1]) > 30) vEdges++

        val total = (scale * scale).toFloat()
        return when {
            hEdges / total > 0.3f && vEdges / total > 0.3f -> "CH"  // grid pattern
            hEdges / total > 0.25f                         -> "ST"  // horizontal stripes
            vEdges / total > 0.25f                         -> "ST"  // vertical stripes
            variance > 1500                                -> "GR"  // high variance, no clear edges
            else                                           -> "P"
        }
    }
}