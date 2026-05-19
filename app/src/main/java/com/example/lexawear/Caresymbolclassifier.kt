package com.example.lexawear

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * CareSymbolClassifier — TFLite interface for laundry care symbol detection.
 *
 * Drop a trained model file named "care_symbols.tflite" into
 * app/src/main/assets/ and this class will automatically use it.
 *
 * Until the model file exists, all calls return an empty result silently.
 *
 * Expected model:
 *  - Input:  [1, 224, 224, 3] float32 normalised RGB bitmap
 *  - Output: [1, N] float32 confidence scores for N symbol classes
 *
 * Symbol classes (index → label) must match the order the model was trained on.
 * Update LABELS below to match your trained model's class order.
 *
 * Care symbol → LexaWEAR field mapping:
 *  Wash symbols  → W field (30, 40, 60, H, N)
 *  Dry symbols   → D field (A, T, F, N)
 *  Iron symbols  → I field (0, 1, 2, 3)
 *  Bleach        → B field (0, 1)
 *  Dry clean     → C field (0, 1)
 */
class CareSymbolClassifier(private val context: Context) {

    companion object {
        private const val MODEL_FILE = "care_symbols.tflite"
        private const val INPUT_SIZE = 224
        private const val CONFIDENCE_THRESHOLD = 0.6f

        /**
         * Maps symbol label names to their LexaWEAR (fieldKey, fieldCode) pairs.
         *
         * ⚠ CRITICAL: [LABELS] is derived from this map's key order. The order
         * here must match your trained model's class output order exactly.
         * A mismatch causes silent wrong-field predictions with no error.
         * Update after training — see `docs/care_symbols_training.md`.
         */
        val SYMBOL_TO_FIELD = mapOf(
            "wash_30"          to Pair("W", "30"),
            "wash_40"          to Pair("W", "40"),
            "wash_60"          to Pair("W", "60"),
            "wash_hand"        to Pair("W", "H"),
            "wash_none"        to Pair("W", "N"),
            "dry_air"          to Pair("D", "A"),
            "dry_tumble"       to Pair("D", "T"),
            "dry_flat"         to Pair("D", "F"),
            "dry_none"         to Pair("D", "N"),
            "iron_low"         to Pair("I", "1"),
            "iron_medium"      to Pair("I", "2"),
            "iron_high"        to Pair("I", "3"),
            "iron_none"        to Pair("I", "0"),
            "bleach_allowed"   to Pair("B", "1"),
            "bleach_none"      to Pair("B", "0"),
            "dryclean_allowed" to Pair("C", "1"),
            "dryclean_none"    to Pair("C", "0")
        )

        /**
         * Class labels in the order the model outputs them.
         * Derived from [SYMBOL_TO_FIELD] key order — do not define separately.
         * Index N here must correspond to output index N in the model.
         */
        val LABELS = SYMBOL_TO_FIELD.keys.toList()
    }

    private var interpreter: Interpreter? = null

    /** True when the model file was found and loaded successfully. */
    val isAvailable: Boolean get() = interpreter != null

    init {
        loadModel()
    }

    /**
     * Loads [MODEL_FILE] from assets if present.
     * Fails silently if the file is absent — expected state before model is trained.
     */
    private fun loadModel() {
        try {
            val assetManager = context.assets
            // Check existence before opening to avoid a noisy FileNotFoundException.
            val files = assetManager.list("") ?: return
            if (MODEL_FILE !in files) return

            val modelBuffer = loadModelFile()
            interpreter = Interpreter(modelBuffer)
        } catch (e: Exception) {
            // Silently degrade — app functions without the care symbol model.
            interpreter = null
        }
    }

    /**
     * Memory-maps [MODEL_FILE] from assets for zero-copy loading into the interpreter.
     * Only called after confirming the file exists in [loadModel].
     */
    private fun loadModelFile(): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(MODEL_FILE)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(
            FileChannel.MapMode.READ_ONLY,
            fileDescriptor.startOffset,
            fileDescriptor.declaredLength
        )
    }

    /**
     * Classifies care symbols in [bitmap] and returns a fieldKey → fieldCode map.
     *
     * Only symbols with confidence ≥ [CONFIDENCE_THRESHOLD] are included.
     * When multiple symbols map to the same field (e.g. two wash symbols), the
     * first one (lowest index) wins — output is iterated in index order.
     * Returns an empty map if the model is unavailable or all scores are too low.
     */
    fun classify(bitmap: Bitmap): Map<String, String> {
        val interp = interpreter ?: return emptyMap()
        if (LABELS.isEmpty()) return emptyMap()

        return try {
            val resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
            val input   = bitmapToFloatArray(resized)
            // Output shape: [1, LABELS.size] — one probability per class.
            val output  = Array(1) { FloatArray(LABELS.size) }

            interp.run(input, output)

            val results = mutableMapOf<String, String>()
            output[0].forEachIndexed { index, confidence ->
                if (confidence >= CONFIDENCE_THRESHOLD && index < LABELS.size) {
                    val label     = LABELS[index]
                    val fieldPair = SYMBOL_TO_FIELD[label]
                    if (fieldPair != null) {
                        // First-wins per field: if a field is already set by an earlier
                        // (lower-index) symbol, the later one is ignored.
                        if (results[fieldPair.first] == null) {
                            results[fieldPair.first] = fieldPair.second
                        }
                    }
                }
            }
            results
        } catch (e: Exception) {
            emptyMap()  // Inference error — degrade gracefully
        }
    }

    /**
     * Converts a [Bitmap] to the [1, H, W, 3] float array format expected by the model.
     * Pixel values normalised to [0.0, 1.0]. Channel order: R, G, B — must match
     * the channel order used during model training.
     */
    private fun bitmapToFloatArray(bitmap: Bitmap): Array<Array<Array<FloatArray>>> {
        val result = Array(1) { Array(INPUT_SIZE) { Array(INPUT_SIZE) { FloatArray(3) } } }
        for (y in 0 until INPUT_SIZE) {
            for (x in 0 until INPUT_SIZE) {
                val pixel = bitmap.getPixel(x, y)
                result[0][y][x][0] = ((pixel shr 16) and 0xFF) / 255f  // R
                result[0][y][x][1] = ((pixel shr 8)  and 0xFF) / 255f  // G
                result[0][y][x][2] = (pixel and 0xFF)           / 255f  // B
            }
        }
        return result
    }

    /** Releases the TFLite interpreter; call from the hosting fragment's onDestroyView. */
    fun close() {
        interpreter?.close()
        interpreter = null
    }
}