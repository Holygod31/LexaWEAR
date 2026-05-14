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
    private lateinit var btnShowOptions: Button
    private lateinit var btnMic: Button
    private lateinit var btnCamera: Button
    private lateinit var btnBack: Button
    private lateinit var btnSkip: Button
    private lateinit var btnNext: Button
    private lateinit var layoutSummary: ScrollView
    private lateinit var tvSummary: TextView
    private lateinit var btnWrite: Button

    var pendingVisionResults: Map<String, String>? = null

    private var nfcAdapter: NfcAdapter? = null
    private var isWriting = false
    private var isEditingExistingTag = false

    private val steps get() = listOf(
        Step("N",  getString(R.string.step_q_name),     getString(R.string.step_h_name)),
        Step("T",  getString(R.string.step_q_type),     getString(R.string.step_h_type)),
        Step("CL", getString(R.string.step_q_color),    getString(R.string.step_h_color)),
        Step("P",  getString(R.string.step_q_pattern),  getString(R.string.step_h_pattern)),
        Step("S",  getString(R.string.step_q_size),     getString(R.string.step_h_size)),
        Step("F",  getString(R.string.step_q_formality),getString(R.string.step_h_formality)),
        Step("SE", getString(R.string.step_q_season),   getString(R.string.step_h_season)),
        Step("M",  getString(R.string.step_q_material), getString(R.string.step_h_material)),
        Step("W",  getString(R.string.step_q_wash),     getString(R.string.step_h_wash)),
        Step("D",  getString(R.string.step_q_dry),      getString(R.string.step_h_dry)),
        Step("I",  getString(R.string.step_q_iron),     getString(R.string.step_h_iron)),
        Step("B",  getString(R.string.step_q_bleach),   getString(R.string.step_h_bleach)),
        Step("C",  getString(R.string.step_q_dry_clean),getString(R.string.step_h_dry_clean)),
        Step("X",  getString(R.string.step_q_notes),    getString(R.string.step_h_notes))
    )

    private val answers = mutableMapOf<String, String>()
    private var currentStep = 0

    data class Step(val key: String, val question: String, val hint: String)

    private val interpretedKeys = setOf("T", "CL", "P", "S", "F", "SE", "W", "D", "I", "B", "C")
    private val validatedKeys   = setOf("T", "CL", "P", "S", "F", "SE", "W", "D", "I", "B", "C")

    private val hexToName get() = mapOf(
        "212121" to getString(R.string.color_black),
        "F5F5F5" to getString(R.string.color_white),
        "9E9E9E" to getString(R.string.color_grey),
        "1A237E" to getString(R.string.color_navy),
        "2196F3" to getString(R.string.color_blue),
        "F44336" to getString(R.string.color_red),
        "4CAF50" to getString(R.string.color_green),
        "FFEB3B" to getString(R.string.color_yellow),
        "FF9800" to getString(R.string.color_orange),
        "E91E63" to getString(R.string.color_pink),
        "9C27B0" to getString(R.string.color_purple),
        "795548" to getString(R.string.color_brown),
        "D7CCC8" to getString(R.string.color_beige),
        "FF5722" to getString(R.string.color_multicolor),
        "607D8B" to getString(R.string.color_other)
    )

    private val typeCodeToName get() = linkedMapOf(
        "SH" to getString(R.string.type_shirt),
        "TS" to getString(R.string.type_tshirt),
        "JK" to getString(R.string.type_jacket),
        "CT" to getString(R.string.type_coat),
        "SW" to getString(R.string.type_sweater),
        "HD" to getString(R.string.type_hoodie),
        "BZ" to getString(R.string.type_blazer),
        "SU" to getString(R.string.type_suit),
        "VS" to getString(R.string.type_vest),
        "DR" to getString(R.string.type_dress),
        "UW" to getString(R.string.type_underwear),
        "PT" to getString(R.string.type_pants),
        "JN" to getString(R.string.type_jeans),
        "ST" to getString(R.string.type_shorts),
        "SK" to getString(R.string.type_skirt),
        "SC" to getString(R.string.type_socks)
    )

    private val patternCodeToName get() = linkedMapOf(
        "P"  to getString(R.string.pattern_plain),
        "ST" to getString(R.string.pattern_striped),
        "CH" to getString(R.string.pattern_checkered),
        "PL" to getString(R.string.pattern_plaid),
        "FL" to getString(R.string.pattern_floral),
        "DT" to getString(R.string.pattern_polkadot),
        "GR" to getString(R.string.pattern_graphic),
        "CM" to getString(R.string.pattern_camouflage),
        "AN" to getString(R.string.pattern_animal)
    )

    private val formalitySingleToName get() = linkedMapOf(
        "C" to getString(R.string.formality_casual),
        "B" to getString(R.string.formality_business),
        "F" to getString(R.string.formality_formal),
        "S" to getString(R.string.formality_sport),
        "L" to getString(R.string.formality_lounge)
    )

    private val formalityComboToName get() = mapOf(
        "SC" to getString(R.string.formality_smart_casual),
        "BC" to getString(R.string.formality_business_casual),
        "SF" to getString(R.string.formality_smart_formal)
    )

    private val seasonSingleToName get() = linkedMapOf(
        "W"  to getString(R.string.season_winter),
        "SP" to getString(R.string.season_spring),
        "SU" to getString(R.string.season_summer),
        "A"  to getString(R.string.season_autumn),
        "AS" to getString(R.string.season_all)
    )

    private val seasonCodesByLength = listOf("AS", "SP", "SU", "W", "A")

    private fun rejectionMessage(key: String): String = when (key) {
        "T"  -> getString(R.string.reject_type)
        "CL" -> getString(R.string.reject_color)
        "P"  -> getString(R.string.reject_pattern)
        "S"  -> getString(R.string.reject_size)
        "F"  -> getString(R.string.reject_formality)
        "SE" -> getString(R.string.reject_season)
        "W"  -> getString(R.string.reject_wash)
        "D"  -> getString(R.string.reject_dry)
        "I"  -> getString(R.string.reject_iron)
        "B"  -> getString(R.string.reject_yes_no)
        "C"  -> getString(R.string.reject_yes_no)
        else -> getString(R.string.reject_default)
    }

    private fun fullOptionsList(key: String): String? = when (key) {
        "T"  -> getString(R.string.options_type)
        "P"  -> getString(R.string.options_pattern)
        "CL" -> getString(R.string.options_color)
        else -> null
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
        btnShowOptions  = view.findViewById(R.id.btn_show_options)
        btnMic          = view.findViewById(R.id.btn_mic)
        btnCamera       = view.findViewById(R.id.btn_camera)
        btnBack         = view.findViewById(R.id.btn_back)
        btnSkip         = view.findViewById(R.id.btn_skip)
        btnNext         = view.findViewById(R.id.btn_next)
        layoutSummary   = view.findViewById(R.id.layout_summary)
        tvSummary       = view.findViewById(R.id.tv_summary)
        btnWrite        = view.findViewById(R.id.btn_write)

        nfcAdapter = NfcAdapter.getDefaultAdapter(requireContext())
        when {
            nfcAdapter == null      -> updateStatus(getString(R.string.nfc_not_supported))
            !nfcAdapter!!.isEnabled -> updateStatus(getString(R.string.nfc_disabled))
            else                    -> updateStatus(getString(R.string.write_status_scan_or_answer))
        }

        showStep(0)

        pendingVisionResults?.let { results ->
            results.forEach { (key, value) ->
                if (steps.any { it.key == key }) answers[key] = value
            }
            pendingVisionResults = null
            updateStatus(getString(R.string.write_status_prefilled))
            tvStatus.announceForAccessibility(getString(R.string.write_status_prefilled))
            showStep(0)
        }

        btnNext.setOnClickListener {
            val input = etStepInput.text.toString().trim()
            val key   = steps[currentStep].key

            if (key == "N" && input.isEmpty()) {
                showStepError(getString(R.string.write_error_name_required), key)
                return@setOnClickListener
            }

            if (input.isEmpty()) {
                answers.remove(key)
                clearStepError()
                advance()
                return@setOnClickListener
            }

            val processed = if (key in interpretedKeys) interpret(key, input) else input

            if (key in validatedKeys && key != "S" && processed.equals(input, ignoreCase = true)) {
                showStepError("${getString(R.string.write_error_not_recognized)} ${rejectionMessage(key)}", key)
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

        btnCamera.setOnClickListener {
            (activity as? MainActivity)?.openCamera(CameraFragment.Source.WRITE)
        }

        btnWrite.setOnClickListener {
            if (answers["N"].isNullOrEmpty()) {
                updateStatus(getString(R.string.write_status_needs_name))
                tvStatus.announceForAccessibility(getString(R.string.write_status_needs_name))
                return@setOnClickListener
            }
            if (isEditingExistingTag) {
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.dialog_overwrite_title))
                    .setMessage(getString(R.string.dialog_overwrite_message))
                    .setPositiveButton(getString(R.string.dialog_overwrite_confirm)) { _, _ ->
                        isWriting = true
                        updateStatus(getString(R.string.write_status_hold_overwrite))
                        tvStatus.announceForAccessibility(getString(R.string.write_status_hold_overwrite))
                    }
                    .setNegativeButton(getString(R.string.dialog_cancel), null)
                    .show()
            } else {
                isWriting = true
                updateStatus(getString(R.string.write_status_hold_tag))
                tvStatus.announceForAccessibility(getString(R.string.write_status_hold_tag))
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

    private fun showStepError(message: String, key: String = "") {
        tvStepError.text = message
        tvStepError.visibility = View.VISIBLE
        tvStepError.announceForAccessibility(message)

        val fullOptions = if (key.isNotEmpty()) fullOptionsList(key) else null
        if (fullOptions != null) {
            btnShowOptions.visibility = View.VISIBLE
            btnShowOptions.text = getString(R.string.btn_show_options)
            btnShowOptions.contentDescription = getString(R.string.btn_show_options_description)
            var expanded = false
            btnShowOptions.setOnClickListener {
                expanded = !expanded
                if (expanded) {
                    btnShowOptions.text = fullOptions
                    btnShowOptions.contentDescription = "$fullOptions. Double tap to collapse."
                    btnShowOptions.announceForAccessibility(fullOptions)
                } else {
                    btnShowOptions.text = getString(R.string.btn_show_options)
                    btnShowOptions.contentDescription = getString(R.string.btn_show_options_description)
                    btnShowOptions.announceForAccessibility(getString(R.string.options_collapsed))
                }
            }
        } else {
            btnShowOptions.visibility = View.GONE
        }

        etStepInput.requestFocus()
    }

    private fun clearStepError() {
        tvStepError.text = ""
        tvStepError.visibility = View.GONE
        btnShowOptions.visibility = View.GONE
        btnShowOptions.text = getString(R.string.btn_show_options)
    }

    fun onTagDiscovered(tag: Tag) {
        when {
            isWriting -> writeTag(tag)
            else      -> readTagForEdit(tag)
        }
    }

    private fun readTagForEdit(tag: Tag) {
        try {
            val ndef = Ndef.get(tag)

            if (ndef == null) {
                requireActivity().runOnUiThread {
                    updateStatus(getString(R.string.write_tag_empty_answer))
                    tvStatus.announceForAccessibility(getString(R.string.write_tag_empty_answer))
                }
                return
            }

            ndef.connect()
            val message = ndef.ndefMessage
            ndef.close()

            if (message == null) {
                requireActivity().runOnUiThread {
                    updateStatus(getString(R.string.write_tag_empty_answer))
                    tvStatus.announceForAccessibility(getString(R.string.write_tag_empty_answer))
                }
                return
            }

            val raw = message.records
                .filter { it.tnf == NdefRecord.TNF_WELL_KNOWN }
                .mapNotNull { String(it.payload).drop(3) }
                .joinToString("")

            if (!raw.contains("N:")) {
                requireActivity().runOnUiThread {
                    updateStatus(getString(R.string.tag_not_lexawear))
                    tvStatus.announceForAccessibility(getString(R.string.tag_not_lexawear))
                }
                return
            }

            requireActivity().runOnUiThread {
                val dialogMessage = if (answers.isNotEmpty())
                    getString(R.string.dialog_tag_found_load_discard)
                else
                    getString(R.string.dialog_tag_found_load)

                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.dialog_tag_found_title))
                    .setMessage(dialogMessage)
                    .setPositiveButton(getString(R.string.dialog_load)) { _, _ ->
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
                        updateStatus(getString(R.string.write_tag_loaded))
                        tvStatus.announceForAccessibility(getString(R.string.write_tag_loaded_accessibility))
                    }
                    .setNegativeButton(getString(R.string.dialog_cancel), null)
                    .show()
            }

        } catch (e: Exception) {
            requireActivity().runOnUiThread {
                updateStatus(getString(R.string.tag_error_reading, e.message))
            }
        }
    }

    // ── Interpreter (logic unchanged, no string changes needed) ──────────────

    private fun interpret(key: String, input: String): String {
        val s = input.lowercase()
            .replace("degrees", "").replace("degree",  "")
            .replace("don't", "do not").replace("dont", "do not")
            .replace("can't", "cannot").replace("cant", "cannot")
            .replace("cannot", "can not")
            .replace("won't", "will not").replace("wont", "will not")
            .replace("shouldn't", "should not").replace("shouldnt", "should not")
            .replace("doesn't", "does not").replace("doesnt", "does not")
            .replace("isn't", "is not").replace("isnt", "is not")
            .replace("never", "not").trim()

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
            "W"  -> when {
                isNegated && ("wash" in s || "water" in s) -> "N"
                "hand" in s                                -> "H"
                "60" in s || "sixty"  in s                 -> "60"
                "40" in s || "forty"  in s                 -> "40"
                "30" in s || "thirty" in s                 -> "30"
                else -> input
            }
            "D"  -> when {
                isNegated && "dry" in s -> "N"
                "tumble" in s           -> "T"
                "flat"   in s           -> "F"
                "air"    in s           -> "A"
                else -> input
            }
            "I"  -> when {
                isNegated                   -> "0"
                "high" in s                 -> "3"
                "medium" in s || "med" in s -> "2"
                "low" in s                  -> "1"
                else -> input
            }
            "B"  -> when {
                hasWord("yes") || "allow" in s || hasWord("ok") -> "1"
                isNegated                                       -> "0"
                else -> input
            }
            "C"  -> when {
                hasWord("yes") || hasWord("ok") -> "1"
                isNegated                       -> "0"
                else -> input
            }
            else -> input
        }
    }

    private fun interpretColor(rawInput: String, s: String): String = when {
        "black"  in s                -> "212121"
        "white"  in s                -> "F5F5F5"
        "grey"   in s || "gray" in s -> "9E9E9E"
        "navy"   in s                -> "1A237E"
        "blue"   in s                -> "2196F3"
        "red"    in s                -> "F44336"
        "green"  in s                -> "4CAF50"
        "yellow" in s                -> "FFEB3B"
        "orange" in s                -> "FF9800"
        "pink"   in s                -> "E91E63"
        "purple" in s                -> "9C27B0"
        "brown"  in s                -> "795548"
        "beige"  in s                -> "D7CCC8"
        "multi"  in s                -> "FF5722"
        else -> rawInput
    }

    private fun interpretType(rawInput: String, s: String): String = when {
        "t-shirt" in s || "tshirt" in s || "tee" in s       -> "TS"
        "shirt"     in s                                     -> "SH"
        "jacket"    in s                                     -> "JK"
        "coat"      in s                                     -> "CT"
        "sweater"   in s || "jumper" in s || "pullover" in s -> "SW"
        "hoodie"    in s                                     -> "HD"
        "blazer"    in s                                     -> "BZ"
        "suit"      in s                                     -> "SU"
        "vest"      in s                                     -> "VS"
        "dress"     in s                                     -> "DR"
        "underwear" in s                                     -> "UW"
        "trouser"   in s || "pants" in s                     -> "PT"
        "jeans"     in s                                     -> "JN"
        "shorts"    in s                                     -> "ST"
        "skirt"     in s                                     -> "SK"
        "sock"      in s                                     -> "SC"
        else -> rawInput
    }

    private fun interpretPattern(rawInput: String, s: String): String = when {
        "plain"   in s || "solid"   in s             -> "P"
        "stripe"  in s                               -> "ST"
        "check"   in s                               -> "CH"
        "plaid"   in s || "tartan"  in s             -> "PL"
        "floral"  in s || "flower"  in s             -> "FL"
        "polka"   in s || "dot"     in s             -> "DT"
        "graphic" in s || "print"   in s             -> "GR"
        "camo"    in s                               -> "CM"
        "animal"  in s || "leopard" in s || "zebra" in s -> "AN"
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
                "spring"         -> "SP"
                "summer"         -> "SU"
                "autumn", "fall" -> "A"
                "winter"         -> "W"
                else             -> continue
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
            .find(withDigits)?.let { return "${it.groupValues[1]}/${it.groupValues[2]}" }
        val letterPatterns = listOf(
            Regex("\\bxxxl\\b|triple\\s*(?:extra\\s*)?large|3xl") to "XXXL",
            Regex("\\bxxl\\b|double\\s*(?:extra\\s*)?large|2xl")  to "XXL",
            Regex("\\bxl\\b|extra\\s*large")                      to "XL",
            Regex("\\bxs\\b|extra\\s*small")                      to "XS",
            Regex("\\bl\\b|\\blarge\\b")                          to "L",
            Regex("\\bm\\b|\\bmedium\\b")                         to "M",
            Regex("\\bs\\b|\\bsmall\\b")                          to "S"
        )
        for ((pattern, code) in letterPatterns) {
            if (pattern.containsMatchIn(withDigits)) return code
        }
        Regex("\\b(\\d{2,3})\\b").find(withDigits)?.let { return it.groupValues[1] }
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
                val next    = tokens.getOrNull(i + 1)
                val onesVal = ones[next]
                if (onesVal != null && onesVal in 1..9) {
                    out.add((tenVal + onesVal).toString()); i += 2; continue
                }
                out.add(tenVal.toString()); i++; continue
            }
            val onesValStandalone = ones[t]
            if (onesValStandalone != null) {
                out.add(onesValStandalone.toString()); i++; continue
            }
            out.add(t); i++
        }
        return out.joinToString(" ")
    }

    private fun decode(key: String, value: String): String = when (key) {
        "T"  -> typeCodeToName[value.uppercase()] ?: value
        "CL" -> hexToName[value.uppercase()] ?: value
        "P"  -> patternCodeToName[value.uppercase()] ?: value
        "F"  -> formalityComboToName[value.uppercase()]
            ?: formalitySingleToName[value.uppercase()] ?: value
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
        "S"  -> if (value == "OS") getString(R.string.one_size) else value
        "W"  -> when (value) {
            "30" -> getString(R.string.wash_30); "40" -> getString(R.string.wash_40)
            "60" -> getString(R.string.wash_60); "H"  -> getString(R.string.wash_hand)
            "N"  -> getString(R.string.wash_no); else -> value
        }
        "D"  -> when (value) {
            "A" -> getString(R.string.dry_air);   "T" -> getString(R.string.dry_tumble)
            "F" -> getString(R.string.dry_flat);  "N" -> getString(R.string.dry_no)
            else -> value
        }
        "I"  -> when (value) {
            "0" -> getString(R.string.iron_no);   "1" -> getString(R.string.iron_low)
            "2" -> getString(R.string.iron_medium); "3" -> getString(R.string.iron_high)
            else -> value
        }
        "B"  -> when (value) { "1" -> getString(R.string.yes); "0" -> getString(R.string.no); else -> value }
        "C"  -> when (value) { "1" -> getString(R.string.yes); "0" -> getString(R.string.no); else -> value }
        else -> value
    }

    private fun showStep(index: Int) {
        val step = steps[index]
        tvStepIndicator.text = getString(R.string.step_indicator, index + 1, steps.size)
        tvQuestion.text      = step.question
        etStepInput.hint     = step.hint

        val stored = answers[step.key] ?: ""
        etStepInput.setText(
            if (step.key in interpretedKeys && stored.isNotEmpty())
                decode(step.key, stored)
            else stored
        )
        etStepInput.requestFocus()
        clearStepError()

        layoutSummary.visibility = View.GONE
        btnWrite.visibility      = View.GONE
        btnNext.text = if (index == steps.size - 1) getString(R.string.btn_review)
        else getString(R.string.btn_next)

        btnBack.isEnabled = index > 0
        btnBack.alpha     = if (index > 0) 1f else 0.4f

        val canSkip = step.key != "N"
        btnSkip.isEnabled  = canSkip
        btnSkip.alpha      = if (canSkip) 1f else 0.4f
        btnSkip.visibility = if (canSkip) View.VISIBLE else View.INVISIBLE

        tvQuestion.announceForAccessibility(
            getString(R.string.step_indicator, index + 1, steps.size) + ". ${step.question}"
        )
        updateStatus(getString(R.string.write_status_answer))
    }

    private fun showSummary() {
        layoutSummary.visibility = View.VISIBLE
        btnWrite.visibility      = View.VISIBLE
        btnNext.text             = getString(R.string.btn_next)

        val labelMap = mapOf(
            "N" to getString(R.string.field_item),      "T"  to getString(R.string.field_type),
            "CL" to getString(R.string.field_color),    "P"  to getString(R.string.field_pattern),
            "S"  to getString(R.string.field_size),     "F"  to getString(R.string.field_formality),
            "SE" to getString(R.string.field_season),   "M"  to getString(R.string.field_material),
            "W"  to getString(R.string.field_wash),     "D"  to getString(R.string.field_drying),
            "I"  to getString(R.string.field_ironing),  "B"  to getString(R.string.field_bleaching),
            "C"  to getString(R.string.field_dry_clean),"X"  to getString(R.string.field_notes)
        )

        val summary = steps
            .filter { answers[it.key]?.isNotEmpty() == true }
            .joinToString("\n") { "${labelMap[it.key]}: ${decode(it.key, answers[it.key]!!)}" }

        tvSummary.text = summary
        tvSummary.contentDescription = "${getString(R.string.summary_description)}. $summary."
        tvSummary.announceForAccessibility("$summary.")
        updateStatus(getString(R.string.write_status_review))
    }

    private fun writeTag(tag: Tag) {
        try {
            val encoded     = encodeTagData()
            val record      = NdefRecord.createTextRecord("en", encoded)
            val ndefMessage = NdefMessage(arrayOf(record))
            val ndef        = Ndef.get(tag)
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
                        updateStatus(getString(R.string.tag_cannot_write))
                        tvStatus.announceForAccessibility(getString(R.string.tag_cannot_write))
                    }
                    return
                }
            }

            requireActivity().runOnUiThread {
                updateStatus(getString(R.string.tag_written_success))
                tvStatus.announceForAccessibility(getString(R.string.tag_written_success))
                isWriting = false
                isEditingExistingTag = false
                answers.clear()
                currentStep = 0
                showStep(0)
            }

        } catch (e: Exception) {
            requireActivity().runOnUiThread {
                updateStatus(getString(R.string.tag_error_writing, e.message))
                isWriting = false
                isEditingExistingTag = false
            }
        }
    }

    private fun encodeTagData(): String = steps
        .filter { answers[it.key]?.isNotEmpty() == true }
        .joinToString("|") { "${it.key}:${answers[it.key]}" }

    private fun updateStatus(message: String) {
        tvStatus.text = message
        tvStatus.contentDescription = "Status: $message"
    }
}