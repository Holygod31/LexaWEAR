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
    private lateinit var btnMic: Button
    private lateinit var btnBack: Button
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
        Step("S",  "What is the size?",                  "e.g. M, L, XL, 32/32"),
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

    // Keys that need interpretation before storing
    private val interpretedKeys = setOf("W", "D", "I", "B", "C")

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
        btnMic          = view.findViewById(R.id.btn_mic)
        btnBack         = view.findViewById(R.id.btn_back)
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

            if (input.isNotEmpty()) {
                // Interpret if needed, otherwise store raw
                answers[key] = if (key in interpretedKeys) interpret(key, input) else input
            } else {
                answers.remove(key)
            }

            if (currentStep < steps.size - 1) {
                currentStep++
                showStep(currentStep)
            } else {
                showSummary()
            }
        }

        btnBack.setOnClickListener {
            if (currentStep > 0) {
                currentStep--
                showStep(currentStep)
            }
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

    // --- Interpreter ---

    private fun interpret(key: String, input: String): String {
        val s = input.lowercase()
            .replace("degrees", "")
            .replace("degree", "")
            .trim()

        return when (key) {
            "W" -> when {
                ("no" in s || "not" in s) && ("wash" in s || "water" in s) -> "N"
                "hand" in s -> "H"
                "60" in s || "sixty" in s   -> "60"
                "40" in s || "forty" in s   -> "40"
                "30" in s || "thirty" in s  -> "30"
                else -> input // keep raw if unrecognized
            }
            "D" -> when {
                ("no" in s || "not" in s) && ("dry" in s) -> "N"
                "tumble" in s -> "T"
                "flat" in s   -> "F"
                "air" in s    -> "A"
                else -> input
            }
            "I" -> when {
                "no" in s || "not" in s -> "0"
                "high" in s             -> "3"
                "medium" in s || "med" in s -> "2"
                "low" in s              -> "1"
                else -> input
            }
            "B" -> when {
                "yes" in s || "allow" in s || "ok" in s -> "1"
                "no" in s || "not" in s                 -> "0"
                else -> input
            }
            "C" -> when {
                "yes" in s || "ok" in s  -> "1"
                "no" in s || "not" in s  -> "0"
                else -> input
            }
            else -> input
        }
    }

    // --- Display decoder for summary screen ---

    private fun decode(key: String, value: String): String {
        return when (key) {
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
                "1" -> "Bleaching allowed"
                "0" -> "No bleaching"
                else -> value
            }
            "C" -> when (value) {
                "1" -> "Dry clean: Yes"
                "0" -> "Dry clean: No"
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

        // Show decoded value if going back to an interpreted field
        val stored = answers[step.key] ?: ""
        etStepInput.setText(
            if (step.key in interpretedKeys && stored.isNotEmpty())
                decode(step.key, stored)
            else stored
        )
        etStepInput.requestFocus()

        layoutSummary.visibility = View.GONE
        btnWrite.visibility = View.GONE
        btnNext.text = if (index == steps.size - 1) "Review" else "Next"
        btnBack.isEnabled = index > 0
        btnBack.alpha = if (index > 0) 1f else 0.4f

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

        // Decode interpreted fields for human-readable summary
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