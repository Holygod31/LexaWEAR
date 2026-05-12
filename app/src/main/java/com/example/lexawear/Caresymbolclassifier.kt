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
 *  - Input:  [1, 224, 224, 3] float32 normalized RGB bitmap
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

        // Update this list to match your trained model's class order exactly.
        // Format: label → (fieldKey, fieldCode)
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

        val LABELS = SYMBOL_TO_FIELD.keys.toList()
    }

    private var interpreter: Interpreter? = null
    val isAvailable: Boolean get() = interpreter != null

    init {
        loadModel()
    }

    private fun loadModel() {
        try {
            val assetManager = context.assets
            // Check if model file exists before trying to load
            val files = assetManager.list("") ?: return
            if (MODEL_FILE !in files) return

            val modelBuffer = loadModelFile()
            interpreter = Interpreter(modelBuffer)
        } catch (e: Exception) {
            // Model not available — silently degrade
            interpreter = null
        }
    }

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
     * Classify care symbols in a bitmap.
     * Returns a map of fieldKey → fieldCode for all detected symbols
     * above the confidence threshold.
     * Returns empty map if model is unavailable or confidence is too low.
     */
    fun classify(bitmap: Bitmap): Map<String, String> {
        val interp = interpreter ?: return emptyMap()
        if (LABELS.isEmpty()) return emptyMap()

        return try {
            val resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
            val input = bitmapToFloatArray(resized)
            val output = Array(1) { FloatArray(LABELS.size) }

            interp.run(input, output)

            val results = mutableMapOf<String, String>()
            output[0].forEachIndexed { index, confidence ->
                if (confidence >= CONFIDENCE_THRESHOLD && index < LABELS.size) {
                    val label = LABELS[index]
                    val fieldPair = SYMBOL_TO_FIELD[label]
                    if (fieldPair != null) {
                        // Only set if not already set by a higher-confidence symbol
                        // for the same field
                        val existing = results[fieldPair.first]
                        if (existing == null) {
                            results[fieldPair.first] = fieldPair.second
                        }
                    }
                }
            }
            results
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /**
     * Convert bitmap to normalized float array [1, H, W, 3].
     * Pixel values normalized to [0, 1].
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

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}