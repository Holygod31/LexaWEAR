package com.example.lexawear

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import java.util.Locale

class NfcFragment : Fragment() {

    // ── Views ─────────────────────────────────────────────────────────────────

    private lateinit var tvStatus: TextView
    private lateinit var tvStepIndicator: TextView
    private lateinit var tvQuestion: TextView
    private lateinit var layoutFreeText: LinearLayout
    private lateinit var etStepInput: EditText
    private lateinit var btnMic: Button
    private lateinit var btnCamera: Button
    private lateinit var scrollOptions: ScrollView
    private lateinit var optionsContainer: LinearLayout
    private lateinit var layoutCustomSize: LinearLayout
    private lateinit var etCustomSize: EditText
    private lateinit var btnConfirmCustomSize: Button
    private lateinit var tvStepError: TextView
    private lateinit var btnBack: Button
    private lateinit var btnSkip: Button
    private lateinit var btnNext: Button
    private lateinit var layoutSummary: ScrollView
    private lateinit var tvSummary: TextView
    private lateinit var btnWrite: Button

    // ── State ─────────────────────────────────────────────────────────────────

    var pendingVisionResults: Map<String, String>? = null

    private var nfcAdapter: NfcAdapter? = null
    private var isWriting = false
    private var isEditingExistingTag = false

    // ── Steps definition ──────────────────────────────────────────────────────

    private val steps get() = listOf(
        Step("N",  getString(R.string.step_q_name),      getString(R.string.step_h_name)),
        Step("T",  getString(R.string.step_q_type),      getString(R.string.step_h_type)),
        Step("CL", getString(R.string.step_q_color),     getString(R.string.step_h_color)),
        Step("P",  getString(R.string.step_q_pattern),   getString(R.string.step_h_pattern)),
        Step("S",  getString(R.string.step_q_size),      getString(R.string.step_h_size)),
        Step("F",  getString(R.string.step_q_formality), getString(R.string.step_h_formality)),
        Step("SE", getString(R.string.step_q_season),    getString(R.string.step_h_season)),
        Step("M",  getString(R.string.step_q_material),  getString(R.string.step_h_material)),
        Step("W",  getString(R.string.step_q_wash),      getString(R.string.step_h_wash)),
        Step("D",  getString(R.string.step_q_dry),       getString(R.string.step_h_dry)),
        Step("I",  getString(R.string.step_q_iron),      getString(R.string.step_h_iron)),
        Step("B",  getString(R.string.step_q_bleach),    getString(R.string.step_h_bleach)),
        Step("C",  getString(R.string.step_q_dry_clean), getString(R.string.step_h_dry_clean)),
        Step("X",  getString(R.string.step_q_notes),     getString(R.string.step_h_notes))
    )

    private val answers = mutableMapOf<String, String>()
    private var currentStep = 0

    data class Step(val key: String, val question: String, val hint: String)

    // Keys that use option buttons instead of free-text input.
    // S (Size) is enumerated but also has a "custom" escape hatch.
    private val enumeratedKeys = setOf("T", "CL", "P", "S", "F", "SE", "W", "D", "I", "B", "C")

    // Free-text keys — use EditText + microphone.
    private val freeTextKeys = setOf("N", "M", "X")

    // ── Lookup tables (get() so getString() runs after fragment attach) ───────

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
        "C"  to getString(R.string.formality_casual),
        "B"  to getString(R.string.formality_business),
        "F"  to getString(R.string.formality_formal),
        "S"  to getString(R.string.formality_sport),
        "L"  to getString(R.string.formality_lounge),
        "SC" to getString(R.string.formality_smart_casual),
        "BC" to getString(R.string.formality_business_casual),
        "SF" to getString(R.string.formality_smart_formal)
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

    // Standard letter sizes shown as buttons on the Size step.
    // "CUSTOM" is a sentinel that reveals the free-text custom size field.
    private val sizeOptions get() = linkedMapOf(
        "XS"     to getString(R.string.size_xs),
        "S"      to getString(R.string.size_s),
        "M"      to getString(R.string.size_m),
        "L"      to getString(R.string.size_l),
        "XL"     to getString(R.string.size_xl),
        "XXL"    to getString(R.string.size_xxl),
        "XXXL"   to getString(R.string.size_xxxl),
        "OS"     to getString(R.string.one_size),
        "CUSTOM" to getString(R.string.size_custom)
    )

    private val washOptions get() = linkedMapOf(
        "30" to getString(R.string.wash_30),
        "40" to getString(R.string.wash_40),
        "60" to getString(R.string.wash_60),
        "H"  to getString(R.string.wash_hand),
        "N"  to getString(R.string.wash_no)
    )

    private val dryOptions get() = linkedMapOf(
        "A" to getString(R.string.dry_air),
        "T" to getString(R.string.dry_tumble),
        "F" to getString(R.string.dry_flat),
        "N" to getString(R.string.dry_no)
    )

    private val ironOptions get() = linkedMapOf(
        "0" to getString(R.string.iron_no),
        "1" to getString(R.string.iron_low),
        "2" to getString(R.string.iron_medium),
        "3" to getString(R.string.iron_high)
    )

    private val yesNoOptions get() = linkedMapOf(
        "1" to getString(R.string.yes),
        "0" to getString(R.string.no)
    )

    /**
     * Returns the ordered map of code → display label for an enumerated step,
     * or null if the step uses free-text input.
     */
    private fun optionsForKey(key: String): LinkedHashMap<String, String>? = when (key) {
        "T"  -> typeCodeToName
        "CL" -> hexToName as LinkedHashMap<String, String>
        "P"  -> patternCodeToName
        "S"  -> sizeOptions
        "F"  -> formalitySingleToName
        "SE" -> seasonSingleToName
        "W"  -> washOptions
        "D"  -> dryOptions
        "I"  -> ironOptions
        "B"  -> yesNoOptions
        "C"  -> yesNoOptions
        else -> null
    }

    // ── Speech launcher ───────────────────────────────────────────────────────

    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spoken.isNullOrEmpty()) {
                etStepInput.setText(spoken)
                etStepInput.announceForAccessibility("${getString(R.string.you_said)} $spoken")
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_nfc, container, false)

        tvStatus             = view.findViewById(R.id.tv_status)
        tvStepIndicator      = view.findViewById(R.id.tv_step_indicator)
        tvQuestion           = view.findViewById(R.id.tv_question)
        layoutFreeText       = view.findViewById(R.id.layout_free_text)
        etStepInput          = view.findViewById(R.id.et_step_input)
        btnMic               = view.findViewById(R.id.btn_mic)
        btnCamera            = view.findViewById(R.id.btn_camera)
        scrollOptions        = view.findViewById(R.id.scroll_options)
        optionsContainer     = view.findViewById(R.id.options_container)
        layoutCustomSize     = view.findViewById(R.id.layout_custom_size)
        etCustomSize         = view.findViewById(R.id.et_custom_size)
        btnConfirmCustomSize = view.findViewById(R.id.btn_confirm_custom_size)
        tvStepError          = view.findViewById(R.id.tv_step_error)
        btnBack              = view.findViewById(R.id.btn_back)
        btnSkip              = view.findViewById(R.id.btn_skip)
        btnNext              = view.findViewById(R.id.btn_next)
        layoutSummary        = view.findViewById(R.id.layout_summary)
        tvSummary            = view.findViewById(R.id.tv_summary)
        btnWrite             = view.findViewById(R.id.btn_write)

        nfcAdapter = NfcAdapter.getDefaultAdapter(requireContext())
        when {
            nfcAdapter == null      -> updateStatus(getString(R.string.nfc_not_supported))
            !nfcAdapter!!.isEnabled -> updateStatus(getString(R.string.nfc_disabled))
            else                    -> updateStatus(getString(R.string.write_status_scan_or_answer))
        }

        showStep(0)

        // Pre-fill from camera vision results if available.
        pendingVisionResults?.let { results ->
            results.forEach { (key, value) ->
                if (steps.any { it.key == key }) answers[key] = value
            }
            pendingVisionResults = null
            updateStatus(getString(R.string.write_status_prefilled))
            tvStatus.announceForAccessibility(getString(R.string.write_status_prefilled))
            showStep(0)
        }

        // Next / Review button — only used for free-text steps.
        // Enumerated steps advance automatically on option tap.
        btnNext.setOnClickListener {
            val key = steps[currentStep].key

            if (key in enumeratedKeys) {
                // On enumerated steps, Next advances without requiring a selection
                // (user may want to skip after looking at options).
                answers.remove(key)
                clearStepError()
                advance()
                return@setOnClickListener
            }

            val input = etStepInput.text.toString().trim()

            if (key == "N" && input.isEmpty()) {
                showStepError(getString(R.string.write_error_name_required))
                return@setOnClickListener
            }

            if (input.isEmpty()) {
                answers.remove(key)
                clearStepError()
                advance()
                return@setOnClickListener
            }

            answers[key] = input
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
            etCustomSize.setText("")
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

        // Custom size confirmation — interprets the typed value and stores it.
        btnConfirmCustomSize.setOnClickListener {
            val raw = etCustomSize.text.toString().trim()
            if (raw.isEmpty()) {
                showStepError(getString(R.string.write_error_size_empty))
                return@setOnClickListener
            }
            val interpreted = interpretSize(raw, raw.lowercase())
            answers["S"] = interpreted
            clearStepError()
            layoutCustomSize.visibility = View.GONE
            highlightSelectedOption("S", interpreted)
            advance()
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

    // ── Step display ──────────────────────────────────────────────────────────

    /**
     * Renders the correct UI for the given step index.
     * Enumerated steps show a scrollable button list.
     * Free-text steps show the EditText + mic button.
     */
    private fun showStep(index: Int) {
        val step = steps[index]
        tvStepIndicator.text = getString(R.string.step_indicator, index + 1, steps.size)
        tvQuestion.text      = step.question

        clearStepError()
        layoutSummary.visibility = View.GONE
        btnWrite.visibility      = View.GONE
        layoutCustomSize.visibility = View.GONE

        btnNext.text = if (index == steps.size - 1) getString(R.string.btn_review)
        else getString(R.string.btn_next)

        btnBack.isEnabled = index > 0
        btnBack.alpha     = if (index > 0) 1f else 0.4f

        val canSkip = step.key != "N"
        btnSkip.isEnabled  = canSkip
        btnSkip.alpha      = if (canSkip) 1f else 0.4f
        btnSkip.visibility = if (canSkip) View.VISIBLE else View.INVISIBLE

        val options = optionsForKey(step.key)

        if (options != null) {
            // Enumerated step: show option buttons, hide free-text input.
            layoutFreeText.visibility = View.GONE
            scrollOptions.visibility  = View.VISIBLE
            buildOptionButtons(step.key, options)
        } else {
            // Free-text step: show EditText + mic, hide option buttons.
            layoutFreeText.visibility = View.VISIBLE
            scrollOptions.visibility  = View.GONE
            etStepInput.hint = step.hint
            val stored = answers[step.key] ?: ""
            etStepInput.setText(stored)
            etStepInput.requestFocus()
        }

        tvQuestion.announceForAccessibility(
            getString(R.string.step_indicator, index + 1, steps.size) + ". ${step.question}"
        )
        updateStatus(getString(R.string.write_status_answer))
    }

    /**
     * Inflates one button per option into [optionsContainer].
     * The currently selected value (if any) is highlighted.
     * Tapping a button stores the code and advances to the next step,
     * except for the "CUSTOM" sentinel on the Size step which reveals
     * the custom size input field instead.
     */
    private fun buildOptionButtons(key: String, options: LinkedHashMap<String, String>) {
        optionsContainer.removeAllViews()
        val selectedCode = answers[key]

        for ((code, label) in options) {
            val btn = Button(requireContext()).apply {
                text = label
                textSize = 17f
                isAllCaps = false
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 12) }
                layoutParams = params
                minHeight = (64 * resources.displayMetrics.density).toInt()
                contentDescription = label

                // Highlight the currently selected option.
                if (code == selectedCode) {
                    setTypeface(typeface, Typeface.BOLD)
                    contentDescription = "$label. ${getString(R.string.option_selected)}"
                }

                setOnClickListener {
                    if (key == "S" && code == "CUSTOM") {
                        // Reveal custom size input — don't advance yet.
                        layoutCustomSize.visibility = View.VISIBLE
                        etCustomSize.requestFocus()
                        etCustomSize.announceForAccessibility(getString(R.string.size_custom_prompt))
                    } else {
                        answers[key] = code
                        clearStepError()
                        advance()
                    }
                }
            }
            optionsContainer.addView(btn)
        }
    }

    /**
     * After a custom size is confirmed, highlights the matching standard button
     * if the interpreted code happens to be a standard letter size, then advances.
     * Called before [advance] in the custom size confirm handler.
     */
    private fun highlightSelectedOption(key: String, code: String) {
        for (i in 0 until optionsContainer.childCount) {
            val btn = optionsContainer.getChildAt(i) as? Button ?: continue
            val btnCode = (optionsForKey(key) ?: continue).entries
                .firstOrNull { it.value == btn.text }?.key ?: continue
            if (btnCode == code) {
                btn.setTypeface(btn.typeface, Typeface.BOLD)
                btn.contentDescription = "${btn.text}. ${getString(R.string.option_selected)}"
            }
        }
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    private fun advance() {
        if (currentStep < steps.size - 1) {
            currentStep++
            showStep(currentStep)
        } else {
            showSummary()
        }
    }

    // ── Error display ─────────────────────────────────────────────────────────

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

    // ── NFC tag handling ──────────────────────────────────────────────────────

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

    // ── Summary ───────────────────────────────────────────────────────────────

    private fun showSummary() {
        layoutSummary.visibility = View.VISIBLE
        btnWrite.visibility      = View.VISIBLE
        scrollOptions.visibility = View.GONE
        layoutFreeText.visibility = View.GONE
        btnNext.text             = getString(R.string.btn_next)

        val labelMap = mapOf(
            "N"  to getString(R.string.field_item),
            "T"  to getString(R.string.field_type),
            "CL" to getString(R.string.field_color),
            "P"  to getString(R.string.field_pattern),
            "S"  to getString(R.string.field_size),
            "F"  to getString(R.string.field_formality),
            "SE" to getString(R.string.field_season),
            "M"  to getString(R.string.field_material),
            "W"  to getString(R.string.field_wash),
            "D"  to getString(R.string.field_drying),
            "I"  to getString(R.string.field_ironing),
            "B"  to getString(R.string.field_bleaching),
            "C"  to getString(R.string.field_dry_clean),
            "X"  to getString(R.string.field_notes)
        )

        val summary = steps
            .filter { answers[it.key]?.isNotEmpty() == true }
            .joinToString("\n") { "${labelMap[it.key]}: ${decode(it.key, answers[it.key]!!)}" }

        tvSummary.text = summary
        tvSummary.contentDescription = "${getString(R.string.summary_description)}. $summary."
        tvSummary.announceForAccessibility("$summary.")
        updateStatus(getString(R.string.write_status_review))
    }

    // ── Tag write ─────────────────────────────────────────────────────────────

    private fun writeTag(tag: Tag) {
        try {
            val encoded        = encodeTagData()
            val record         = NdefRecord.createTextRecord("en", encoded)
            val ndefMessage    = NdefMessage(arrayOf(record))
            val ndef           = Ndef.get(tag)
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

    // ── Decode (for summary display) ──────────────────────────────────────────

    private fun decode(key: String, value: String): String = when (key) {
        "T"  -> typeCodeToName[value.uppercase()] ?: value
        "CL" -> hexToName[value.uppercase()] ?: value
        "P"  -> patternCodeToName[value.uppercase()] ?: value
        "F"  -> formalityComboToName[value.uppercase()]
            ?: formalitySingleToName[value.uppercase()] ?: value
        "SE" -> {
            val v = value.uppercase()
            seasonSingleToName[v] ?: run {
                val parts     = mutableListOf<String>()
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
            "A" -> getString(R.string.dry_air);    "T" -> getString(R.string.dry_tumble)
            "F" -> getString(R.string.dry_flat);   "N" -> getString(R.string.dry_no)
            else -> value
        }
        "I"  -> when (value) {
            "0" -> getString(R.string.iron_no);     "1" -> getString(R.string.iron_low)
            "2" -> getString(R.string.iron_medium); "3" -> getString(R.string.iron_high)
            else -> value
        }
        "B"  -> when (value) { "1" -> getString(R.string.yes); "0" -> getString(R.string.no); else -> value }
        "C"  -> when (value) { "1" -> getString(R.string.yes); "0" -> getString(R.string.no); else -> value }
        else -> value
    }

    // ── Size interpreter (kept for pendingVisionResults pre-fill + custom input) ──

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
        val out    = mutableListOf<String>()
        var i      = 0
        while (i < tokens.size) {
            val t      = tokens[i]
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

    // ── Status bar ────────────────────────────────────────────────────────────

    private fun updateStatus(message: String) {
        tvStatus.text = message
        tvStatus.contentDescription = "Status: $message"
    }
}