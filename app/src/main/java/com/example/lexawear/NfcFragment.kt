package com.example.lexawear

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
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
import androidx.fragment.app.Fragment
import java.util.Locale

/**
 * NfcFragment — guided multi-step form for writing clothing data to NFC tags.
 *
 * Walks the user through fields (name → notes). Each step shows either a
 * free-text input or a list of option buttons depending on the field type.
 * Vision results from CameraFragment can pre-fill answers before the user
 * starts stepping through. On reaching the final step, a summary is shown
 * and the user taps "Write" then holds a tag to the device to commit.
 *
 * Material flow: MB (blend/single) → M (primary) → M2 (secondary, blend only).
 * M2 is hidden from the step list if MB == "S". Final tag stores M:CO or M:CO+PL.
 */
class NfcFragment : Fragment() {

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

    /**
     * Set by MainActivity after CameraFragment returns results.
     * Consumed in [onCreateView]; null after first use to avoid re-applying.
     */
    var pendingVisionResults: Map<String, String>? = null

    private var nfcAdapter: NfcAdapter? = null

    /** True while waiting for the user to hold a tag — gates [onTagDiscovered]. */
    private var isWriting = false

    /** True when a LexaWEAR tag was read and loaded for editing; triggers overwrite dialog on write. */
    private var isEditingExistingTag = false

    /**
     * Full ordered step list including material blend sub-steps.
     * MB = blend/single choice, M = primary material, M2 = secondary material.
     * get() property ensures string resources are resolved after fragment attach.
     */
    private val steps get() = listOf(
        Step("N",  getString(R.string.step_q_name),      getString(R.string.step_h_name)),
        Step("T",  getString(R.string.step_q_type),      getString(R.string.step_h_type)),
        Step("CL", getString(R.string.step_q_color),     getString(R.string.step_h_color)),
        Step("P",  getString(R.string.step_q_pattern),   getString(R.string.step_h_pattern)),
        Step("S",  getString(R.string.step_q_size),      getString(R.string.step_h_size)),
        Step("F",  getString(R.string.step_q_formality), getString(R.string.step_h_formality)),
        Step("SE", getString(R.string.step_q_season),    getString(R.string.step_h_season)),
        Step("MB", getString(R.string.step_q_material_blend), getString(R.string.step_h_material_blend)),
        Step("M",  getString(R.string.step_q_material),  getString(R.string.step_h_material)),
        Step("M2", getString(R.string.step_q_material2), getString(R.string.step_h_material2)),
        Step("W",  getString(R.string.step_q_wash),      getString(R.string.step_h_wash)),
        Step("D",  getString(R.string.step_q_dry),       getString(R.string.step_h_dry)),
        Step("I",  getString(R.string.step_q_iron),      getString(R.string.step_h_iron)),
        Step("B",  getString(R.string.step_q_bleach),    getString(R.string.step_h_bleach)),
        Step("C",  getString(R.string.step_q_dry_clean), getString(R.string.step_h_dry_clean)),
        Step("X",  getString(R.string.step_q_notes),     getString(R.string.step_h_notes))
    )

    /** Accumulated user answers keyed by field code (e.g. "T" → "JK"). */
    private val answers = mutableMapOf<String, String>()
    private var currentStep = 0

    /** Immutable value type for a single wizard step. */
    data class Step(val key: String, val question: String, val hint: String)

    /** Steps that show option buttons rather than a free-text field. */
    private val enumeratedKeys = setOf("T", "CL", "P", "S", "F", "SE", "MB", "M", "M2", "W", "D", "I", "B", "C")

    /**
     * Colour options sourced exclusively from [ColorPalette] — single source of truth.
     * get() property so string resources are resolved at call time, not init time.
     */
    private val colorOptions get(): LinkedHashMap<String, String> {
        val map = linkedMapOf<String, String>()
        ColorPalette.entries.forEach { entry -> map[entry.hex] = getString(entry.nameRes) }
        return map
    }

    // All lookup tables use get() — getString() requires attached context, not safe at init time.

    private val typeCodeToName get() = linkedMapOf(
        "SH" to getString(R.string.type_shirt),    "TS" to getString(R.string.type_tshirt),
        "JK" to getString(R.string.type_jacket),   "CT" to getString(R.string.type_coat),
        "SW" to getString(R.string.type_sweater),  "HD" to getString(R.string.type_hoodie),
        "BZ" to getString(R.string.type_blazer),   "SU" to getString(R.string.type_suit),
        "VS" to getString(R.string.type_vest),     "DR" to getString(R.string.type_dress),
        "UW" to getString(R.string.type_underwear),"PT" to getString(R.string.type_pants),
        "JN" to getString(R.string.type_jeans),    "ST" to getString(R.string.type_shorts),
        "SK" to getString(R.string.type_skirt),    "SC" to getString(R.string.type_socks)
    )

    private val patternCodeToName get() = linkedMapOf(
        "P"  to getString(R.string.pattern_plain),      "ST" to getString(R.string.pattern_striped),
        "CH" to getString(R.string.pattern_checkered),  "PL" to getString(R.string.pattern_plaid),
        "FL" to getString(R.string.pattern_floral),     "DT" to getString(R.string.pattern_polkadot),
        "GR" to getString(R.string.pattern_graphic),    "CM" to getString(R.string.pattern_camouflage),
        "AN" to getString(R.string.pattern_animal)
    )

    private val formalitySingleToName get() = linkedMapOf(
        "C"  to getString(R.string.formality_casual),          "B"  to getString(R.string.formality_business),
        "F"  to getString(R.string.formality_formal),          "S"  to getString(R.string.formality_sport),
        "L"  to getString(R.string.formality_lounge),          "SC" to getString(R.string.formality_smart_casual),
        "BC" to getString(R.string.formality_business_casual), "SF" to getString(R.string.formality_smart_formal)
    )

    private val formalityComboToName get() = mapOf(
        "SC" to getString(R.string.formality_smart_casual),
        "BC" to getString(R.string.formality_business_casual),
        "SF" to getString(R.string.formality_smart_formal)
    )

    private val seasonSingleToName get() = linkedMapOf(
        "W"  to getString(R.string.season_winter), "SP" to getString(R.string.season_spring),
        "SU" to getString(R.string.season_summer), "A"  to getString(R.string.season_autumn),
        "AS" to getString(R.string.season_all)
    )

    private val seasonCodesByLength = listOf("AS", "SP", "SU", "W", "A")

    private val sizeOptions get() = linkedMapOf(
        "XS" to getString(R.string.size_xs),   "S"  to getString(R.string.size_s),
        "M"  to getString(R.string.size_m),    "L"  to getString(R.string.size_l),
        "XL" to getString(R.string.size_xl),   "XXL" to getString(R.string.size_xxl),
        "XXXL" to getString(R.string.size_xxxl),"OS" to getString(R.string.one_size),
        "CUSTOM" to getString(R.string.size_custom)
    )

    /**
     * Full material list used for primary material step.
     * Codes are language-neutral and stored on the NFC tag.
     */
    private val materialOptions get() = linkedMapOf(
        "CO" to getString(R.string.material_cotton),
        "WO" to getString(R.string.material_wool),
        "LI" to getString(R.string.material_linen),
        "SI" to getString(R.string.material_silk),
        "CA" to getString(R.string.material_cashmere),
        "DO" to getString(R.string.material_down),
        "PL" to getString(R.string.material_polyester),
        "NY" to getString(R.string.material_nylon),
        "AC" to getString(R.string.material_acrylic),
        "SP" to getString(R.string.material_spandex),
        "LE" to getString(R.string.material_leather),
        "DE" to getString(R.string.material_denim),
        "JE" to getString(R.string.material_jersey),
        "TW" to getString(R.string.material_tweed),
        "VE" to getString(R.string.material_velvet),
        "VI" to getString(R.string.material_viscose),
        "RA" to getString(R.string.material_rayon)
    )

    /**
     * Secondary material list — same as [materialOptions] but excludes the
     * already-selected primary material so the user can't pick the same one twice.
     */
    private val materialOptionsSecondary get(): LinkedHashMap<String, String> {
        val primary = answers["M"]
        return linkedMapOf<String, String>().also { map ->
            materialOptions.forEach { (code, name) ->
                if (code != primary) map[code] = name
            }
        }
    }

    private val materialBlendOptions get() = linkedMapOf(
        "S" to getString(R.string.material_single),
        "B" to getString(R.string.material_blend)
    )

    private val washOptions get() = linkedMapOf(
        "30" to getString(R.string.wash_30), "40" to getString(R.string.wash_40),
        "60" to getString(R.string.wash_60), "H"  to getString(R.string.wash_hand),
        "N"  to getString(R.string.wash_no)
    )

    private val dryOptions get() = linkedMapOf(
        "A" to getString(R.string.dry_air),   "T" to getString(R.string.dry_tumble),
        "F" to getString(R.string.dry_flat),  "N" to getString(R.string.dry_no)
    )

    private val ironOptions get() = linkedMapOf(
        "0" to getString(R.string.iron_no),     "1" to getString(R.string.iron_low),
        "2" to getString(R.string.iron_medium), "3" to getString(R.string.iron_high)
    )

    private val yesNoOptions get() = linkedMapOf(
        "1" to getString(R.string.yes), "0" to getString(R.string.no)
    )

    /** Returns the option map for [key], or null if the step uses free-text input. */
    private fun optionsForKey(key: String): LinkedHashMap<String, String>? = when (key) {
        "T"  -> typeCodeToName;        "CL" -> colorOptions
        "P"  -> patternCodeToName;     "S"  -> sizeOptions
        "F"  -> formalitySingleToName; "SE" -> seasonSingleToName
        "MB" -> materialBlendOptions;  "M"  -> materialOptions
        "M2" -> materialOptionsSecondary
        "W"  -> washOptions;           "D"  -> dryOptions
        "I"  -> ironOptions;           "B"  -> yesNoOptions
        "C"  -> yesNoOptions
        else -> null
    }

    /** Launches system speech recognition; result is placed into [etStepInput]. */
    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrEmpty()) {
                etStepInput.setText(spoken)
                etStepInput.announceForAccessibility("${getString(R.string.you_said)} $spoken")
            }
        }
    }

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

        // Apply camera/vision pre-fill before the user starts stepping through.
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
            val key = steps[currentStep].key
            if (key in enumeratedKeys) { answers.remove(key); clearStepError(); advance(); return@setOnClickListener }
            val input = etStepInput.text.toString().trim()
            if (key == "N" && input.isEmpty()) { showStepError(getString(R.string.write_error_name_required)); return@setOnClickListener }
            if (input.isEmpty()) { answers.remove(key); clearStepError(); advance(); return@setOnClickListener }
            answers[key] = input; clearStepError(); advance()
        }

        btnBack.setOnClickListener {
            if (currentStep > 0) {
                clearStepError()
                currentStep--
                // Skip back over M2 if not a blend — keep stepping back until a visible step.
                if (steps[currentStep].key == "M2" && answers["MB"] != "B") currentStep--
                showStep(currentStep)
            }
        }

        btnSkip.setOnClickListener {
            val key = steps[currentStep].key
            // Skipping MB clears both material answers and skips M and M2.
            if (key == "MB") {
                answers.remove("MB"); answers.remove("M"); answers.remove("M2")
                skipToAfterMaterial(); return@setOnClickListener
            }
            answers.remove(key)
            etStepInput.setText(""); etCustomSize.setText("")
            clearStepError(); advance()
        }

        btnMic.setOnClickListener {
            speechLauncher.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PROMPT, steps[currentStep].question)
            })
        }

        btnCamera.setOnClickListener {
            (activity as? MainActivity)?.openCamera(CameraFragment.Source.WRITE)
        }

        btnConfirmCustomSize.setOnClickListener {
            val raw = etCustomSize.text.toString().trim()
            if (raw.isEmpty()) { showStepError(getString(R.string.write_error_size_empty)); return@setOnClickListener }
            answers["S"] = interpretSize(raw, raw.lowercase())
            clearStepError(); layoutCustomSize.visibility = View.GONE
            highlightSelectedOption("S", answers["S"]!!); advance()
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
                    .setNegativeButton(getString(R.string.dialog_cancel), null).show()
            } else {
                isWriting = true
                updateStatus(getString(R.string.write_status_hold_tag))
                tvStatus.announceForAccessibility(getString(R.string.write_status_hold_tag))
            }
        }

        return view
    }

    // ── Step rendering ────────────────────────────────────────────────────────

    /**
     * Renders a single wizard step.
     * M2 is skipped automatically if MB == "S" (single material, no blend).
     */
    private fun showStep(index: Int) {
        val step = steps[index]

        // Auto-skip M2 if not a blend — jump forward or back as needed.
        if (step.key == "M2" && answers["MB"] != "B") {
            if (index > currentStep) advance() else { currentStep--; showStep(currentStep) }
            return
        }

        tvStepIndicator.text = getString(R.string.step_indicator, index + 1, steps.size)
        tvQuestion.text      = step.question
        clearStepError()
        layoutSummary.visibility = View.GONE; btnWrite.visibility = View.GONE
        layoutCustomSize.visibility = View.GONE
        btnNext.text = if (index == steps.size - 1) getString(R.string.btn_review) else getString(R.string.btn_next)
        btnBack.isEnabled = index > 0; btnBack.alpha = if (index > 0) 1f else 0.4f
        val canSkip = step.key != "N"
        btnSkip.isEnabled = canSkip; btnSkip.alpha = if (canSkip) 1f else 0.4f
        btnSkip.visibility = if (canSkip) View.VISIBLE else View.INVISIBLE
        val options = optionsForKey(step.key)
        if (options != null) {
            layoutFreeText.visibility = View.GONE; scrollOptions.visibility = View.VISIBLE
            buildOptionButtons(step.key, options)
        } else {
            layoutFreeText.visibility = View.VISIBLE; scrollOptions.visibility = View.GONE
            etStepInput.hint = step.hint; etStepInput.setText(answers[step.key] ?: "")
            etStepInput.requestFocus()
        }
        tvQuestion.announceForAccessibility(
            getString(R.string.step_indicator, index + 1, steps.size) + ". ${step.question}"
        )
        updateStatus(getString(R.string.write_status_answer))
    }

    /**
     * Builds option buttons for a step.
     * When MB is selected as "S" (single), M2 is skipped in [advance].
     * When MB is selected as "B" (blend), M2 is shown after M.
     */
    private fun buildOptionButtons(key: String, options: LinkedHashMap<String, String>) {
        optionsContainer.removeAllViews()
        val selectedCode = answers[key]
        for ((code, label) in options) {
            val btn = Button(requireContext()).apply {
                text = label; textSize = 17f; isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 12) }
                minHeight = (64 * resources.displayMetrics.density).toInt()
                contentDescription = if (code == selectedCode) "$label. ${getString(R.string.option_selected)}" else label
                if (code == selectedCode) setTypeface(typeface, Typeface.BOLD)
                setOnClickListener {
                    when {
                        key == "S" && code == "CUSTOM" -> {
                            layoutCustomSize.visibility = View.VISIBLE; etCustomSize.requestFocus()
                            etCustomSize.announceForAccessibility(getString(R.string.size_custom_prompt))
                        }
                        key == "MB" -> {
                            answers["MB"] = code
                            // Clear secondary material if switching back to single.
                            if (code == "S") answers.remove("M2")
                            clearStepError(); advance()
                        }
                        else -> { answers[key] = code; clearStepError(); advance() }
                    }
                }
            }
            optionsContainer.addView(btn)
        }
    }

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

    /**
     * Moves to the next step, skipping M2 if not a blend.
     * On reaching the last step, shows the summary.
     */
    private fun advance() {
        if (currentStep < steps.size - 1) {
            currentStep++
            // Skip M2 if single material was chosen.
            if (steps[currentStep].key == "M2" && answers["MB"] != "B") currentStep++
            if (currentStep < steps.size) showStep(currentStep) else showSummary()
        } else {
            showSummary()
        }
    }

    /** Jumps past all three material steps — used when MB is skipped entirely. */
    private fun skipToAfterMaterial() {
        currentStep = steps.indexOfFirst { it.key == "W" }
        clearStepError(); showStep(currentStep)
    }

    private fun showStepError(message: String) {
        tvStepError.text = message; tvStepError.visibility = View.VISIBLE
        tvStepError.announceForAccessibility(message); etStepInput.requestFocus()
    }

    private fun clearStepError() { tvStepError.text = ""; tvStepError.visibility = View.GONE }

    // ── NFC ───────────────────────────────────────────────────────────────────

    /** Entry point called by [MainActivity] when an NFC tag is detected. */
    fun onTagDiscovered(tag: Tag) { if (isWriting) writeTag(tag) else readTagForEdit(tag) }

    private fun readTagForEdit(tag: Tag) {
        try {
            val ndef = Ndef.get(tag)
            if (ndef == null) {
                requireActivity().runOnUiThread {
                    updateStatus(getString(R.string.write_tag_empty_answer))
                    tvStatus.announceForAccessibility(getString(R.string.write_tag_empty_answer))
                }; return
            }
            ndef.connect(); val message = ndef.ndefMessage; ndef.close()
            if (message == null) {
                requireActivity().runOnUiThread {
                    updateStatus(getString(R.string.write_tag_empty_answer))
                    tvStatus.announceForAccessibility(getString(R.string.write_tag_empty_answer))
                }; return
            }
            val raw = message.records.filter { it.tnf == NdefRecord.TNF_WELL_KNOWN }
                .mapNotNull { String(it.payload).drop(3) }.joinToString("")
            if (!raw.contains("N:")) {
                requireActivity().runOnUiThread {
                    updateStatus(getString(R.string.tag_not_lexawear))
                    tvStatus.announceForAccessibility(getString(R.string.tag_not_lexawear))
                }; return
            }
            requireActivity().runOnUiThread {
                val msg = if (answers.isNotEmpty()) getString(R.string.dialog_tag_found_load_discard)
                else getString(R.string.dialog_tag_found_load)
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.dialog_tag_found_title)).setMessage(msg)
                    .setPositiveButton(getString(R.string.dialog_load)) { _, _ ->
                        answers.clear()
                        raw.split("|").forEach { part ->
                            val k = part.substringBefore(":"); val v = part.substringAfter(":")
                            if (v.isNotEmpty() && steps.any { it.key == k }) answers[k] = v
                        }
                        // Reconstruct MB and M2 from stored M field if it contains a blend.
                        val mVal = answers["M"]
                        if (mVal != null && mVal.contains("+")) {
                            val parts = mVal.split("+")
                            answers["MB"] = "B"
                            answers["M"]  = parts[0]
                            answers["M2"] = parts[1]
                        } else if (mVal != null) {
                            answers["MB"] = "S"
                        }
                        isEditingExistingTag = true; currentStep = 0; showStep(0)
                        updateStatus(getString(R.string.write_tag_loaded))
                        tvStatus.announceForAccessibility(getString(R.string.write_tag_loaded_accessibility))
                    }
                    .setNegativeButton(getString(R.string.dialog_cancel), null).show()
            }
        } catch (e: Exception) {
            requireActivity().runOnUiThread { updateStatus(getString(R.string.tag_error_reading, e.message)) }
        }
    }

    private fun showSummary() {
        layoutSummary.visibility = View.VISIBLE; btnWrite.visibility = View.VISIBLE
        scrollOptions.visibility = View.GONE; layoutFreeText.visibility = View.GONE
        btnNext.text = getString(R.string.btn_next)
        val labelMap = mapOf(
            "N"  to getString(R.string.field_item),      "T"  to getString(R.string.field_type),
            "CL" to getString(R.string.field_color),     "P"  to getString(R.string.field_pattern),
            "S"  to getString(R.string.field_size),      "F"  to getString(R.string.field_formality),
            "SE" to getString(R.string.field_season),    "M"  to getString(R.string.field_material),
            "W"  to getString(R.string.field_wash),      "D"  to getString(R.string.field_drying),
            "I"  to getString(R.string.field_ironing),   "B"  to getString(R.string.field_bleaching),
            "C"  to getString(R.string.field_dry_clean), "X"  to getString(R.string.field_notes)
        )
        // MB and M2 are internal — show combined material in summary under M label.
        val summarySteps = steps.filter { it.key !in setOf("MB", "M2") }
        val summary = summarySteps.filter { answers[it.key]?.isNotEmpty() == true }
            .joinToString("\n") { "${labelMap[it.key]}: ${decode(it.key, answers[it.key]!!)}" }
        tvSummary.text = summary
        tvSummary.contentDescription = "${getString(R.string.summary_description)}. $summary."
        tvSummary.announceForAccessibility("$summary.")
        updateStatus(getString(R.string.write_status_review))
    }

    private fun writeTag(tag: Tag) {
        try {
            val ndefMessage = NdefMessage(arrayOf(NdefRecord.createTextRecord("en", encodeTagData())))
            val ndef = Ndef.get(tag); val ndefFormatable = NdefFormatable.get(tag)
            when {
                ndef != null -> { ndef.connect(); ndef.writeNdefMessage(ndefMessage); ndef.close() }
                ndefFormatable != null -> { ndefFormatable.connect(); ndefFormatable.format(ndefMessage); ndefFormatable.close() }
                else -> {
                    requireActivity().runOnUiThread {
                        updateStatus(getString(R.string.tag_cannot_write))
                        tvStatus.announceForAccessibility(getString(R.string.tag_cannot_write))
                    }; return
                }
            }
            requireActivity().runOnUiThread {
                updateStatus(getString(R.string.tag_written_success))
                tvStatus.announceForAccessibility(getString(R.string.tag_written_success))
                isWriting = false; isEditingExistingTag = false; answers.clear(); currentStep = 0; showStep(0)
            }
        } catch (e: Exception) {
            requireActivity().runOnUiThread {
                updateStatus(getString(R.string.tag_error_writing, e.message))
                isWriting = false; isEditingExistingTag = false
            }
        }
    }

    /**
     * Serialises answers into the pipe-separated tag string.
     * M and M2 are combined into a single M field: M:CO or M:CO+PL.
     * MB is internal only — not written to the tag.
     */
    private fun encodeTagData(): String {
        val encoded = mutableListOf<String>()
        steps.filter { it.key !in setOf("MB", "M2") }
            .filter { answers[it.key]?.isNotEmpty() == true }
            .forEach { step ->
                if (step.key == "M") {
                    val primary = answers["M"] ?: return@forEach
                    val secondary = answers["M2"]
                    val value = if (!secondary.isNullOrEmpty()) "$primary+$secondary" else primary
                    encoded.add("M:$value")
                } else {
                    encoded.add("${step.key}:${answers[step.key]}")
                }
            }
        return encoded.joinToString("|")
    }

    /**
     * Decodes a stored code to a display string for the summary screen.
     * M field handles both single (CO) and blend (CO+PL) formats.
     */
    private fun decode(key: String, value: String): String = when (key) {
        "T"  -> typeCodeToName[value.uppercase()] ?: value
        "CL" -> ColorPalette.nameForHex(value, ::getString)
            ?: getString(ColorPalette.nearestEntryFromArgb(
                try { Color.parseColor("#$value") } catch (e: Exception) { 0 }
            ).nameRes)
        "P"  -> patternCodeToName[value.uppercase()] ?: value
        "F"  -> formalityComboToName[value.uppercase()] ?: formalitySingleToName[value.uppercase()] ?: value
        "SE" -> {
            val v = value.uppercase()
            seasonSingleToName[v] ?: run {
                val parts = mutableListOf<String>(); var remaining = v
                while (remaining.isNotEmpty()) {
                    val match = seasonCodesByLength.firstOrNull { remaining.startsWith(it) } ?: return@run value
                    parts.add(seasonSingleToName[match] ?: return@run value)
                    remaining = remaining.removePrefix(match)
                }
                parts.joinToString("/")
            }
        }
        "S"  -> if (value == "OS") getString(R.string.one_size) else value
        "M"  -> {
            // Decode single or blend material for display.
            if (value.contains("+")) {
                val parts = value.split("+")
                val p1 = materialOptions[parts[0]] ?: parts[0]
                val p2 = materialOptions[parts[1]] ?: parts[1]
                "$p1 + $p2"
            } else {
                materialOptions[value] ?: value
            }
        }
        "W"  -> when (value) {
            "30" -> getString(R.string.wash_30); "40" -> getString(R.string.wash_40)
            "60" -> getString(R.string.wash_60); "H"  -> getString(R.string.wash_hand)
            "N"  -> getString(R.string.wash_no); else -> value
        }
        "D"  -> when (value) {
            "A" -> getString(R.string.dry_air);   "T" -> getString(R.string.dry_tumble)
            "F" -> getString(R.string.dry_flat);  "N" -> getString(R.string.dry_no); else -> value
        }
        "I"  -> when (value) {
            "0" -> getString(R.string.iron_no);     "1" -> getString(R.string.iron_low)
            "2" -> getString(R.string.iron_medium); "3" -> getString(R.string.iron_high); else -> value
        }
        "B"  -> when (value) { "1" -> getString(R.string.yes); "0" -> getString(R.string.no); else -> value }
        "C"  -> when (value) { "1" -> getString(R.string.yes); "0" -> getString(R.string.no); else -> value }
        else -> value
    }

    private fun interpretSize(rawInput: String, normalized: String): String {
        val s = normalized.replace("-", " ").replace(",", " ")
        if (listOf("one size","free size","onesize","freesize").any { it in s }) return "OS"
        if (Regex("\\bos\\b").containsMatchIn(s)) return "OS"
        val w = wordsToDigits(s)
        Regex("(\\d{2,3})\\s*(?:/|x|by)\\s*(\\d{2,3})").find(w)
            ?.let { return "${it.groupValues[1]}/${it.groupValues[2]}" }
        listOf(
            Regex("\\bxxxl\\b|triple\\s*(?:extra\\s*)?large|3xl") to "XXXL",
            Regex("\\bxxl\\b|double\\s*(?:extra\\s*)?large|2xl")  to "XXL",
            Regex("\\bxl\\b|extra\\s*large")                      to "XL",
            Regex("\\bxs\\b|extra\\s*small")                      to "XS",
            Regex("\\bl\\b|\\blarge\\b")                          to "L",
            Regex("\\bm\\b|\\bmedium\\b")                         to "M",
            Regex("\\bs\\b|\\bsmall\\b")                          to "S"
        ).forEach { (pattern, code) -> if (pattern.containsMatchIn(w)) return code }
        Regex("\\b(\\d{2,3})\\b").find(w)?.let { return it.groupValues[1] }
        return rawInput
    }

    private fun wordsToDigits(input: String): String {
        val ones = mapOf("zero" to 0,"one" to 1,"two" to 2,"three" to 3,"four" to 4,
            "five" to 5,"six" to 6,"seven" to 7,"eight" to 8,"nine" to 9,"ten" to 10,
            "eleven" to 11,"twelve" to 12,"thirteen" to 13,"fourteen" to 14,"fifteen" to 15,
            "sixteen" to 16,"seventeen" to 17,"eighteen" to 18,"nineteen" to 19)
        val tens = mapOf("twenty" to 20,"thirty" to 30,"forty" to 40,"fifty" to 50,
            "sixty" to 60,"seventy" to 70,"eighty" to 80,"ninety" to 90)
        val tokens = input.split(Regex("\\s+")).toMutableList()
        val out = mutableListOf<String>(); var i = 0
        while (i < tokens.size) {
            val t = tokens[i]; val tenVal = tens[t]
            if (tenVal != null) {
                val onesVal = ones[tokens.getOrNull(i + 1)]
                if (onesVal != null && onesVal in 1..9) { out.add((tenVal + onesVal).toString()); i += 2; continue }
                out.add(tenVal.toString()); i++; continue
            }
            val onesVal = ones[t]
            if (onesVal != null) { out.add(onesVal.toString()); i++; continue }
            out.add(t); i++
        }
        return out.joinToString(" ")
    }

    /** Posts a status message and sets contentDescription for TalkBack. */
    private fun updateStatus(message: String) {
        tvStatus.text = message; tvStatus.contentDescription = "Status: $message"
    }
}