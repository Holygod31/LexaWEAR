package com.example.lexawear

import android.app.Activity
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import java.util.Locale

class NfcFragment : Fragment() {

    private lateinit var tvStatus: TextView
    private lateinit var tvStepIndicator: TextView
    private lateinit var tvQuestion: TextView
    private lateinit var etStepInput: EditText
    private lateinit var tvStepError: TextView
    private lateinit var btnMic: Button
    private lateinit var btnBack: Button
    private lateinit var btnSkip: Button
    private lateinit var btnNext: Button
    private lateinit var layoutSummary: ScrollView
    private lateinit var tvSummary: TextView
    private lateinit var btnWrite: Button

    private var nfcAdapter: NfcAdapter? = null
    private var isWriting = false

    private val steps = listOf(
        Step("N",  "What is this item called?",          "e.g. Blue Winter Jacket"),
        Step("T",  "What type of clothing is this?",     "e.g. Jacket, Shirt, Pants"),
        Step("CL", "What color is it?",                  "e.g. Blue, Black, Multicolor"),
        Step("P",  "What pattern does it have?",         "e.g. Plain, Striped, Checkered"),
        Step("S",  "What is the size?",                  "e.g. M, L, XL, 32/32, 38"),
        Step("F",  "How formal is this item?",           "e.g. Casual, Smart Casual, Formal"),
        Step("SE", "What season is it for?",             "e.g. Winter, Summer, All-Season"),
        Step("M",  "What material is it made of?",       "e.g. Cotton, Wool, Polyester"),
        Step("W",  "What is the wash temperature?",      "e.g. 30, 40, 60, Hand Wash"),
        Step("D",  "How should it be dried?",            "e.g. Air, Tumble, Flat"),
        Step("I",  "What are the ironing instructions?", "e.g. No Iron, Low, High"),
        Step("B",  "Is bleaching allowed?",              "e.g. Yes, No"),
        Step("C",  "Can it be dry cleaned?",             "e.g. Yes, No"),
        Step("X",  "Any extra notes?",                   "e.g. Goes well with black pants")
    )

    private val answers = mutableMapOf<String, String>()
    private var currentStep = 0

    data class Step(val key: String, val question: String, val hint: String)

    // Keys whose interpret() output is the canonical stored form
    private val interpretedKeys = setOf("CL", "S", "W", "D", "I", "B", "C")

    // Keys that must successfully interpret to a known code; if interpret()
    // returns the original input (i.e. it didn't recognize anything),
    // the user is asked to rephrase. Skip is always available as a bailout.
    private val validatedKeys = setOf("CL", "S", "W", "D", "I", "B", "C")

    private val hexToName = mapOf(
        "212121" to "Black",  "F5F5F5" to "White",
        "9E9E9E" to "Grey",   "1A237E" to "Navy",
        "2196F3" to "Blue",   "F44336" to "Red",
        "4CAF50" to "Green",  "FFEB3B" to "Yellow",
        "FF9800" to "Orange", "E91E63" to "Pink",
        "9C27B0" to "Purple", "795548" to "Brown",
        "D7CCC8" to "Beige",  "FF5722" to "Multicolor",
        "607D8B" to "Other"
    )

    // Per-key example phrasing shown in the error message
    private val rejectionHelp = mapOf(
        "CL" to "Try a color name like blue, black, or red.",
        "S"  to "Try a size like medium, large, 32 by 32, or 38.",
        "W"  to "Try 30, 40, 60, hand wash, or do not wash.",
        "D"  to "Try air dry, tumble dry, flat dry, or do not dry.",
        "I"  to "Try no iron, low, medium, or high.",
        "B"  to "Try yes or no.",
        "C"  to "Try yes or no."
    )

    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spoken.isNullOrEmpty()) {
                etStepInput.setText(spoken)
                etStepInput.announceForAccessibility("You said: $spoken")
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_nfc, container, false)

        tvStatus        = view.findViewById(R.id.tv_status)
        tvStepIndicator = view.findViewById(R.id.tv_step_indicator)
        tvQuestion      = view.findViewById(R.id.tv_question)
        etStepInput     = view.findViewById(R.id.et_step_input)
        tvStepError     = view.findViewById(R.id.tv_step_error)
        btnMic          = view.findViewById(R.id.btn_mic)
        btnBack         = view.findViewById(R.id.btn_back)
        btnSkip         = view.findViewById(R.id.btn_skip)
        btnNext         = view.findViewById(R.id.btn_next)
        layoutSummary   = view.findViewById(R.id.layout_summary)
        tvSummary       = view.findViewById(R.id.tv_summary)
        btnWrite        = view.findViewById(R.id.btn_write)

        nfcAdapter = NfcAdapter.getDefaultAdapter(requireContext())
        when {
            nfcAdapter == null -> updateStatus("This device does not support NFC.")
            !nfcAdapter!!.isEnabled -> updateStatus("NFC is off. Please enable it in Settings.")
            else -> updateStatus("Answer each question to tag this item.")
        }

        showStep(0)

        btnNext.setOnClickListener {
            val input = etStepInput.text.toString().trim()
            val key = steps[currentStep].key

            // Name is required.
            if (key == "N" && input.isEmpty()) {
                showStepError("Please enter a name for this item.")
                return@setOnClickListener
            }

            if (input.isEmpty()) {
                // Empty + non-required = treat as skip (no answer stored).
                answers.remove(key)
                clearStepError()
                advance()
                return@setOnClickListener
            }

            val processed = if (key in interpretedKeys) interpret(key, input) else input

            // Reject-and-reprompt: validated fields must produce a known code.
            // If interpret() returned the original input untouched, it didn't recognize anything.
            if (key in validatedKeys && processed.equals(input, ignoreCase = true)) {
                val help = rejectionHelp[key] ?: "Please rephrase your answer."
                showStepError("Sorry, I didn't catch that. $help")
                return@setOnClickListener
            }

            answers[key] = processed
            clearStepError()
            advance()
        }

        btnBack.setOnClickListener {
            if (currentStep > 0) {
                clearStepError()
                currentStep--
                showStep(currentStep)
            }
        }

        btnSkip.setOnClickListener {
            val key = steps[currentStep].key
            answers.remove(key)
            etStepInput.setText("")
            clearStepError()
            advance()
        }

        btnMic.setOnClickListener {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PROMPT, steps[currentStep].question)
            }
            speechLauncher.launch(intent)
        }

        btnWrite.setOnClickListener {
            if (answers["N"].isNullOrEmpty()) {
                updateStatus("Please go back and enter an item name.")
                tvStatus.announceForAccessibility("Please go back and enter an item name.")
                return@setOnClickListener
            }
            isWriting = true
            updateStatus("Hold your phone to an NFC tag to write to it.")
            tvStatus.announceForAccessibility("Hold your phone to an NFC tag to write to it.")
        }

        return view
    }

    private fun advance() {
        if (currentStep < steps.size - 1) {
            currentStep++
            showStep(currentStep)
        } else {
            showSummary()
        }
    }

    private fun showStepError(message: String) {
        tvStepError.text = message
        tvStepError.visibility = View.VISIBLE
        tvStepError.announceForAccessibility(message)
        etStepInput.requestFocus()
    }

    private fun clearStepError() {
        tvStepError.text = ""
        tvStepError.visibility = View.GONE
    }

    private fun interpret(key: String, input: String): String {
        val s = input.lowercase()
            .replace("degrees", "")
            .replace("degree", "")
            // Expand negation contractions before keyword matching
            .replace("don't",     "do not")
            .replace("dont",      "do not")
            .replace("can't",     "cannot")
            .replace("cant",      "cannot")
            .replace("cannot",    "can not")
            .replace("won't",     "will not")
            .replace("wont",      "will not")
            .replace("shouldn't", "should not")
            .replace("shouldnt",  "should not")
            .replace("doesn't",   "does not")
            .replace("doesnt",    "does not")
            .replace("isn't",     "is not")
            .replace("isnt",      "is not")
            .replace("never",     "not")
            .trim()

        fun hasWord(word: String): Boolean =
            Regex("\\b${Regex.escape(word)}\\b").containsMatchIn(s)

        val isNegated = hasWord("no") || hasWord("not")

        return when (key) {
            "CL" -> when {
                "black"  in s -> "212121"
                "white"  in s -> "F5F5F5"
                "grey"   in s || "gray" in s -> "9E9E9E"
                "navy"   in s -> "1A237E"
                "blue"   in s -> "2196F3"
                "red"    in s -> "F44336"
                "green"  in s -> "4CAF50"
                "yellow" in s -> "FFEB3B"
                "orange" in s -> "FF9800"
                "pink"   in s -> "E91E63"
                "purple" in s -> "9C27B0"
                "brown"  in s -> "795548"
                "beige"  in s -> "D7CCC8"
                "multi"  in s -> "FF5722"
                else -> input
            }
            "S" -> interpretSize(input, s)
            "W" -> when {
                isNegated && ("wash" in s || "water" in s) -> "N"
                "hand" in s -> "H"
                "60" in s || "sixty"  in s -> "60"
                "40" in s || "forty"  in s -> "40"
                "30" in s || "thirty" in s -> "30"
                else -> input
            }
            "D" -> when {
                isNegated && "dry" in s -> "N"
                "tumble" in s -> "T"
                "flat"   in s -> "F"
                "air"    in s -> "A"
                else -> input
            }
            "I" -> when {
                isNegated                   -> "0"
                "high" in s                 -> "3"
                "medium" in s || "med" in s -> "2"
                "low" in s                  -> "1"
                else -> input
            }
            "B" -> when {
                hasWord("yes") || "allow" in s || hasWord("ok") -> "1"
                isNegated                                       -> "0"
                else -> input
            }
            "C" -> when {
                hasWord("yes") || hasWord("ok") -> "1"
                isNegated                       -> "0"
                else -> input
            }
            else -> input
        }
    }

    /**
     * Size interpreter.
     * Recognized patterns (in priority order):
     *   - One-size markers       -> "OS"
     *   - Letter sizes           -> "XS", "S", "M", "L", "XL", "XXL", "XXXL"
     *   - Waist/length pairs     -> "W/L" e.g. "32/32"
     *   - Bare numeric           -> "38", "40", etc.
     * If nothing matches, returns the original input (caller will reject).
     * No unit conversion is attempted — what the user says is what gets stored.
     */
    private fun interpretSize(rawInput: String, normalized: String): String {
        val s = normalized
            .replace("-", " ")
            .replace(",", " ")

        // 1. One-size markers
        val oneSizePatterns = listOf("one size", "free size", "onesize", "freesize")
        if (oneSizePatterns.any { it in s }) return "OS"
        if (Regex("\\bos\\b").containsMatchIn(s)) return "OS"

        // 2. Word-number normalization for spoken pant sizes
        //    e.g. "thirty two by thirty two" -> "32 by 32"
        val withDigits = wordsToDigits(s)

        // 3. Waist/length pairs:
        //    "32/32", "32x32", "32 x 32", "32 by 32"
        Regex("(\\d{2,3})\\s*(?:/|x|by)\\s*(\\d{2,3})")
            .find(withDigits)
            ?.let { return "${it.groupValues[1]}/${it.groupValues[2]}" }

        // 4. Letter sizes — check longest first so "extra large" beats "large".
        //    Spoken forms: "extra extra large", "double extra large", etc.
        val letterPatterns = listOf(
            // (regex, canonical code)
            Regex("\\bxxxl\\b|triple\\s*(?:extra\\s*)?large|3xl") to "XXXL",
            Regex("\\bxxl\\b|double\\s*(?:extra\\s*)?large|2xl") to "XXL",
            Regex("\\bxl\\b|extra\\s*large")                    to "XL",
            Regex("\\bxs\\b|extra\\s*small")                    to "XS",
            Regex("\\bl\\b|\\blarge\\b")                        to "L",
            Regex("\\bm\\b|\\bmedium\\b")                       to "M",
            Regex("\\bs\\b|\\bsmall\\b")                        to "S"
        )
        for ((pattern, code) in letterPatterns) {
            if (pattern.containsMatchIn(withDigits)) return code
        }

        // 5. Bare numeric size — extract first 2–3 digit number
        Regex("\\b(\\d{2,3})\\b")
            .find(withDigits)
            ?.let { return it.groupValues[1] }

        // Nothing matched
        return rawInput
    }

    /**
     * Convert spoken number words to digits within a string.
     * Handles 0-99 in compound forms like "thirty two", "forty five".
     */
    private fun wordsToDigits(input: String): String {
        val ones = mapOf(
            "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4,
            "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
            "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13,
            "fourteen" to 14, "fifteen" to 15, "sixteen" to 16,
            "seventeen" to 17, "eighteen" to 18, "nineteen" to 19
        )
        val tens = mapOf(
            "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
            "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90
        )

        val tokens = input.split(Regex("\\s+")).toMutableList()
        val out = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            val tenVal = tens[t]
            if (tenVal != null) {
                val next = tokens.getOrNull(i + 1)
                val onesVal = ones[next]
                if (onesVal != null && onesVal in 1..9) {
                    out.add((tenVal + onesVal).toString())
                    i += 2
                    continue
                }
                out.add(tenVal.toString())
                i++
                continue
            }
            val onesValStandalone = ones[t]
            if (onesValStandalone != null) {
                out.add(onesValStandalone.toString())
                i++
                continue
            }
            out.add(t)
            i++
        }
        return out.joinToString(" ")
    }

    private fun decode(key: String, value: String): String {
        return when (key) {
            "CL" -> hexToName[value.uppercase()] ?: value
            "S" -> when (value) {
                "OS" -> "One Size"
                else -> value
            }
            "W" -> when (value) {
                "30" -> "Wash at 30°"
                "40" -> "Wash at 40°"
                "60" -> "Wash at 60°"
                "H"  -> "Hand wash"
                "N"  -> "Do not wash"
                else -> value
            }
            "D" -> when (value) {
                "A" -> "Air dry"
                "T" -> "Tumble dry"
                "F" -> "Flat dry"
                "N" -> "Do not dry"
                else -> value
            }
            "I" -> when (value) {
                "0" -> "No iron"
                "1" -> "Low heat"
                "2" -> "Medium heat"
                "3" -> "High heat"
                else -> value
            }
            "B" -> when (value) {
                "1" -> "Yes"
                "0" -> "No"
                else -> value
            }
            "C" -> when (value) {
                "1" -> "Yes"
                "0" -> "No"
                else -> value
            }
            else -> value
        }
    }

    private fun showStep(index: Int) {
        val step = steps[index]
        tvStepIndicator.text = "Step ${index + 1} of ${steps.size}"
        tvQuestion.text = step.question
        etStepInput.hint = step.hint

        val stored = answers[step.key] ?: ""
        etStepInput.setText(
            if (step.key in interpretedKeys && stored.isNotEmpty())
                decode(step.key, stored)
            else stored
        )
        etStepInput.requestFocus()
        clearStepError()

        layoutSummary.visibility = View.GONE
        btnWrite.visibility = View.GONE
        btnNext.text = if (index == steps.size - 1) "Review" else "Next"
        btnBack.isEnabled = index > 0
        btnBack.alpha = if (index > 0) 1f else 0.4f

        // Skip is available on every step except Name (which is required).
        val canSkip = step.key != "N"
        btnSkip.isEnabled = canSkip
        btnSkip.alpha = if (canSkip) 1f else 0.4f
        btnSkip.visibility = if (canSkip) View.VISIBLE else View.INVISIBLE

        tvQuestion.announceForAccessibility(
            "Step ${index + 1} of ${steps.size}. ${step.question}"
        )
        updateStatus("Answer each question to tag this item.")
    }

    private fun showSummary() {
        layoutSummary.visibility = View.VISIBLE
        btnWrite.visibility = View.VISIBLE
        btnNext.text = "Next"

        val labelMap = mapOf(
            "N"  to "Name",
            "T"  to "Type",
            "CL" to "Color",
            "P"  to "Pattern",
            "S"  to "Size",
            "F"  to "Formality",
            "SE" to "Season",
            "M"  to "Material",
            "W"  to "Wash",
            "D"  to "Drying",
            "I"  to "Ironing",
            "B"  to "Bleaching",
            "C"  to "Dry Clean",
            "X"  to "Notes"
        )

        val summary = steps
            .filter { answers[it.key]?.isNotEmpty() == true }
            .joinToString("\n") {
                "${labelMap[it.key]}: ${decode(it.key, answers[it.key]!!)}"
            }

        tvSummary.text = summary
        tvSummary.contentDescription = "Summary. $summary. Tap Write to Tag to continue."
        tvSummary.announceForAccessibility("Summary. $summary. Tap Write to Tag to save.")
        updateStatus("Review your answers, then tap Write to Tag.")
    }

    fun onTagDiscovered(tag: Tag) {
        if (isWriting) writeTag(tag)
    }

    private fun writeTag(tag: Tag) {
        try {
            val encoded = encodeTagData()
            val record = NdefRecord.createTextRecord("en", encoded)
            val ndefMessage = NdefMessage(arrayOf(record))

            val ndef = Ndef.get(tag)
            val ndefFormatable = NdefFormatable.get(tag)

            when {
                ndef != null -> {
                    ndef.connect()
                    ndef.writeNdefMessage(ndefMessage)
                    ndef.close()
                }
                ndefFormatable != null -> {
                    ndefFormatable.connect()
                    ndefFormatable.format(ndefMessage)
                    ndefFormatable.close()
                }
                else -> {
                    requireActivity().runOnUiThread {
                        updateStatus("This tag cannot be written to.")
                        tvStatus.announceForAccessibility("This tag cannot be written to.")
                    }
                    return
                }
            }

            requireActivity().runOnUiThread {
                updateStatus("Tag written successfully!")
                tvStatus.announceForAccessibility("Tag written successfully!")
                isWriting = false
                answers.clear()
                currentStep = 0
                showStep(0)
            }

        } catch (e: Exception) {
            requireActivity().runOnUiThread {
                updateStatus("Error writing tag: ${e.message}")
                isWriting = false
            }
        }
    }

    private fun encodeTagData(): String {
        return steps
            .filter { answers[it.key]?.isNotEmpty() == true }
            .joinToString("|") { "${it.key}:${answers[it.key]}" }
    }

    private fun updateStatus(message: String) {
        tvStatus.text = message
        tvStatus.contentDescription = "Status: $message"
    }
}