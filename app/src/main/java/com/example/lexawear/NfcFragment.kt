package com.example.lexawear

import android.app.Activity
import android.app.AlertDialog
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
    private var isEditingExistingTag = false

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

    private val interpretedKeys = setOf("T", "CL", "P", "S", "F", "SE", "W", "D", "I", "B", "C")
    private val validatedKeys   = setOf("T", "CL", "P", "S", "F", "SE", "W", "D", "I", "B", "C")

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

    private val typeCodeToName = linkedMapOf(
        "SH" to "Shirt", "TS" to "T-Shirt", "JK" to "Jacket", "CT" to "Coat",
        "SW" to "Sweater", "HD" to "Hoodie", "BZ" to "Blazer", "SU" to "Suit",
        "VS" to "Vest", "DR" to "Dress", "UW" to "Underwear", "PT" to "Pants",
        "JN" to "Jeans", "ST" to "Shorts", "SK" to "Skirt", "SC" to "Socks"
    )

    private val patternCodeToName = linkedMapOf(
        "P"  to "Plain", "ST" to "Striped", "CH" to "Checkered", "PL" to "Plaid",
        "FL" to "Floral", "DT" to "Polka Dot", "GR" to "Graphic",
        "CM" to "Camouflage", "AN" to "Animal Print"
    )

    private val formalitySingleToName = linkedMapOf(
        "C" to "Casual", "B" to "Business", "F" to "Formal",
        "S" to "Sport", "L" to "Lounge"
    )

    private val formalityComboToName = mapOf(
        "SC" to "Smart Casual", "BC" to "Business Casual", "SF" to "Smart Formal"
    )

    private val seasonSingleToName = linkedMapOf(
        "W" to "Winter", "SP" to "Spring", "SU" to "Summer",
        "A" to "Autumn", "AS" to "All-Season"
    )

    private val seasonCodesByLength = listOf("AS", "SP", "SU", "W", "A")

    private fun rejectionMessage(key: String): String = when (key) {
        "T"  -> "Try a top like shirt, jacket, or sweater, or a bottom like pants, jeans, or skirt — and more."
        "CL" -> "Try black, white, grey, navy, blue, red, green, yellow, orange, pink, purple, brown, beige, or multicolor."
        "P"  -> "Try plain, striped, checkered, plaid, floral, polka dot — and more."
        "S"  -> "Try a size like medium, large, 32 by 32, or 38."
        "F"  -> "Try casual, business, formal, sport, lounge, smart casual, business casual, or smart formal."
        "SE" -> "Try winter, spring, summer, autumn, or all-season. You can combine seasons, like spring and summer."
        "W"  -> "Try 30, 40, 60, hand wash, or do not wash."
        "D"  -> "Try air dry, tumble dry, flat dry, or do not dry."
        "I"  -> "Try no iron, low, medium, or high."
        "B"  -> "Try yes or no."
        "C"  -> "Try yes or no."
        else -> "Please rephrase your answer."
    }

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
            else -> updateStatus("Scan a tag to edit it, or answer each question to write a new one.")
        }

        showStep(0)

        btnNext.setOnClickListener {
            val input = etStepInput.text.toString().trim()
            val key = steps[currentStep].key

            if (key == "N" && input.isEmpty()) {
                showStepError("Please enter a name for this item.")
                return@setOnClickListener
            }

            if (input.isEmpty()) {
                answers.remove(key)
                clearStepError()
                advance()
                return@setOnClickListener
            }

            val processed = if (key in interpretedKeys) interpret(key, input) else input

            // Size is free-form: skip equality check so direct codes like "XL" are accepted.
            if (key in validatedKeys && key != "S" && processed.equals(input, ignoreCase = true)) {
                showStepError("Sorry, I didn't catch that. ${rejectionMessage(key)}")
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
            if (isEditingExistingTag) {
                AlertDialog.Builder(requireContext())
                    .setTitle("Overwrite Tag?")
                    .setMessage("This will replace all existing data on the tag. Continue?")
                    .setPositiveButton("Overwrite") { _, _ ->
                        isWriting = true
                        updateStatus("Hold your phone to the tag to overwrite it.")
                        tvStatus.announceForAccessibility("Hold your phone to the tag to overwrite it.")
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                isWriting = true
                updateStatus("Hold your phone to an NFC tag to write to it.")
                tvStatus.announceForAccessibility("Hold your phone to an NFC tag to write to it.")
            }
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
        tvStepError.announceForAccessibility("Options shown below. $message")
        etStepInput.requestFocus()
    }

    private fun clearStepError() {
        tvStepError.text = ""
        tvStepError.visibility = View.GONE
    }

    fun onTagDiscovered(tag: Tag) {
        when {
            isWriting -> writeTag(tag)
            else -> readTagForEdit(tag)
        }
    }

    private fun readTagForEdit(tag: Tag) {
        try {
            val ndef = Ndef.get(tag)

            if (ndef == null) {
                requireActivity().runOnUiThread {
                    updateStatus("Tag is empty. Answer each question to write a new one.")
                    tvStatus.announceForAccessibility("Tag is empty. Answer each question to write a new one.")
                }
                return
            }

            ndef.connect()
            val message = ndef.ndefMessage
            ndef.close()

            if (message == null) {
                requireActivity().runOnUiThread {
                    updateStatus("Tag is empty. Answer each question to write a new one.")
                    tvStatus.announceForAccessibility("Tag is empty. Answer each question to write a new one.")
                }
                return
            }

            val raw = message.records
                .filter { it.tnf == NdefRecord.TNF_WELL_KNOWN }
                .mapNotNull { String(it.payload).drop(3) }
                .joinToString("")

            if (!raw.contains("N:")) {
                requireActivity().runOnUiThread {
                    updateStatus("This tag was not written by LexaWEAR.")
                    tvStatus.announceForAccessibility("This tag was not written by LexaWEAR.")
                }
                return
            }

            requireActivity().runOnUiThread {
                val hasProgress = answers.isNotEmpty()
                val dialogMessage = if (hasProgress)
                    "Load this tag for editing? This will discard your current progress."
                else
                    "Load this tag for editing?"

                AlertDialog.Builder(requireContext())
                    .setTitle("Tag Found")
                    .setMessage(dialogMessage)
                    .setPositiveButton("Load") { _, _ ->
                        answers.clear()
                        raw.split("|").forEach { part ->
                            val key   = part.substringBefore(":")
                            val value = part.substringAfter(":")
                            if (value.isNotEmpty() && steps.any { it.key == key }) {
                                answers[key] = value
                            }
                        }
                        isEditingExistingTag = true
                        currentStep = 0
                        showStep(0)
                        updateStatus("Tag loaded. Edit any field, then tap Write to Tag.")
                        tvStatus.announceForAccessibility("Tag loaded for editing.")
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }

        } catch (e: Exception) {
            requireActivity().runOnUiThread {
                updateStatus("Error reading tag: ${e.message}")
            }
        }
    }

    private fun interpret(key: String, input: String): String {
        val s = input.lowercase()
            .replace("degrees", "")
            .replace("degree", "")
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
            "T"  -> interpretType(input, s)
            "CL" -> interpretColor(input, s)
            "P"  -> interpretPattern(input, s)
            "S"  -> interpretSize(input, s)
            "F"  -> interpretFormality(input, s)
            "SE" -> interpretSeason(input, s)
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

    private fun interpretColor(rawInput: String, s: String): String = when {
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
        else -> rawInput
    }

    private fun interpretType(rawInput: String, s: String): String = when {
        "t-shirt" in s || "tshirt" in s || "tee" in s -> "TS"
        "shirt"     in s -> "SH"
        "jacket"    in s -> "JK"
        "coat"      in s -> "CT"
        "sweater"   in s || "jumper" in s || "pullover" in s -> "SW"
        "hoodie"    in s -> "HD"
        "blazer"    in s -> "BZ"
        "suit"      in s -> "SU"
        "vest"      in s -> "VS"
        "dress"     in s -> "DR"
        "underwear" in s -> "UW"
        "trouser"   in s || "pants" in s -> "PT"
        "jeans"     in s -> "JN"
        "shorts"    in s -> "ST"
        "skirt"     in s -> "SK"
        "sock"      in s -> "SC"
        else -> rawInput
    }

    private fun interpretPattern(rawInput: String, s: String): String = when {
        "plain"    in s || "solid"   in s -> "P"
        "stripe"   in s                   -> "ST"
        "check"    in s                   -> "CH"
        "plaid"    in s || "tartan" in s  -> "PL"
        "floral"   in s || "flower" in s  -> "FL"
        "polka"    in s || "dot"    in s  -> "DT"
        "graphic"  in s || "print"  in s  -> "GR"
        "camo"     in s                   -> "CM"
        "animal"   in s || "leopard" in s || "zebra" in s -> "AN"
        else -> rawInput
    }

    private fun interpretFormality(rawInput: String, s: String): String {
        val hasSmart    = "smart"    in s
        val hasBusiness = "business" in s
        val hasCasual   = "casual"   in s
        val hasFormal   = "formal"   in s
        val hasSport    = "sport"    in s || "athletic" in s || "gym" in s
        val hasLounge   = "lounge"   in s || "sleep" in s || "pyjama" in s || "pajama" in s

        return when {
            hasSmart && hasCasual    -> "SC"
            hasBusiness && hasCasual -> "BC"
            hasSmart && hasFormal    -> "SF"
            hasCasual                -> "C"
            hasBusiness              -> "B"
            hasFormal                -> "F"
            hasSport                 -> "S"
            hasLounge                -> "L"
            else -> rawInput
        }
    }

    private fun interpretSeason(rawInput: String, s: String): String {
        if ("all" in s || "year-round" in s || "year round" in s || "any season" in s) return "AS"
        val mentions = mutableListOf<String>()
        val pattern = Regex("\\b(spring|summer|autumn|fall|winter)\\b")
        for (m in pattern.findAll(s)) {
            val code = when (m.value) {
                "spring" -> "SP"
                "summer" -> "SU"
                "autumn", "fall" -> "A"
                "winter" -> "W"
                else -> continue
            }
            if (code !in mentions) mentions.add(code)
        }
        return if (mentions.isEmpty()) rawInput else mentions.joinToString("")
    }

    private fun interpretSize(rawInput: String, normalized: String): String {
        val s = normalized.replace("-", " ").replace(",", " ")

        val oneSizePatterns = listOf("one size", "free size", "onesize", "freesize")
        if (oneSizePatterns.any { it in s }) return "OS"
        if (Regex("\\bos\\b").containsMatchIn(s)) return "OS"

        val withDigits = wordsToDigits(s)

        Regex("(\\d{2,3})\\s*(?:/|x|by)\\s*(\\d{2,3})")
            .find(withDigits)
            ?.let { return "${it.groupValues[1]}/${it.groupValues[2]}" }

        val letterPatterns = listOf(
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

        Regex("\\b(\\d{2,3})\\b")
            .find(withDigits)
            ?.let { return it.groupValues[1] }

        return rawInput
    }

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
            "T"  -> typeCodeToName[value.uppercase()] ?: value
            "CL" -> hexToName[value.uppercase()] ?: value
            "P"  -> patternCodeToName[value.uppercase()] ?: value
            "F"  -> formalityComboToName[value.uppercase()]
                ?: formalitySingleToName[value.uppercase()]
                ?: value
            "SE" -> {
                val v = value.uppercase()
                seasonSingleToName[v] ?: run {
                    val parts = mutableListOf<String>()
                    var remaining = v
                    while (remaining.isNotEmpty()) {
                        val match = seasonCodesByLength.firstOrNull {
                            remaining.startsWith(it)
                        } ?: return@run value
                        parts.add(seasonSingleToName[match] ?: return@run value)
                        remaining = remaining.removePrefix(match)
                    }
                    parts.joinToString("/")
                }
            }
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
            "N"  to "Name", "T"  to "Type", "CL" to "Color",
            "P"  to "Pattern", "S"  to "Size", "F"  to "Formality",
            "SE" to "Season", "M"  to "Material", "W"  to "Wash",
            "D"  to "Drying", "I"  to "Ironing", "B"  to "Bleaching",
            "C"  to "Dry Clean", "X"  to "Notes"
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
                isEditingExistingTag = false
                answers.clear()
                currentStep = 0
                showStep(0)
            }

        } catch (e: Exception) {
            requireActivity().runOnUiThread {
                updateStatus("Error writing tag: ${e.message}")
                isWriting = false
                isEditingExistingTag = false
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