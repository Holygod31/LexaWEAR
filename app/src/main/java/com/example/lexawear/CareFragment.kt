package com.example.lexawear

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.NdefRecord
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment

class CareFragment : Fragment() {

    private lateinit var tvStatus: TextView
    private lateinit var tvLegacyBanner: TextView
    private lateinit var btnAddToWardrobe: Button
    private lateinit var btnCamera: Button
    private lateinit var layoutResults: LinearLayout

    private var nfcAdapter: NfcAdapter? = null
    private var lastScannedRaw: String? = null

    var pendingVisionResults: Map<String, String>? = null

    private val encodedKeys = setOf("T", "CL", "P", "F", "SE", "W", "D", "I", "B", "C", "S")

    private val typeCodeToName get() = mapOf(
        "SH" to getString(R.string.type_shirt), "TS" to getString(R.string.type_tshirt),
        "JK" to getString(R.string.type_jacket), "CT" to getString(R.string.type_coat),
        "SW" to getString(R.string.type_sweater), "HD" to getString(R.string.type_hoodie),
        "BZ" to getString(R.string.type_blazer), "SU" to getString(R.string.type_suit),
        "VS" to getString(R.string.type_vest), "DR" to getString(R.string.type_dress),
        "UW" to getString(R.string.type_underwear), "PT" to getString(R.string.type_pants),
        "JN" to getString(R.string.type_jeans), "ST" to getString(R.string.type_shorts),
        "SK" to getString(R.string.type_skirt), "SC" to getString(R.string.type_socks)
    )

    private val patternCodeToName get() = mapOf(
        "P"  to getString(R.string.pattern_plain), "ST" to getString(R.string.pattern_striped),
        "CH" to getString(R.string.pattern_checkered), "PL" to getString(R.string.pattern_plaid),
        "FL" to getString(R.string.pattern_floral), "DT" to getString(R.string.pattern_polkadot),
        "GR" to getString(R.string.pattern_graphic), "CM" to getString(R.string.pattern_camouflage),
        "AN" to getString(R.string.pattern_animal)
    )

    private val formalityComboToName get() = mapOf(
        "SC" to getString(R.string.formality_smart_casual),
        "BC" to getString(R.string.formality_business_casual),
        "SF" to getString(R.string.formality_smart_formal)
    )
    private val formalitySingleToName get() = mapOf(
        "C" to getString(R.string.formality_casual), "B" to getString(R.string.formality_business),
        "F" to getString(R.string.formality_formal), "S" to getString(R.string.formality_sport),
        "L" to getString(R.string.formality_lounge)
    )

    private val seasonSingleToName get() = mapOf(
        "W"  to getString(R.string.season_winter), "SP" to getString(R.string.season_spring),
        "SU" to getString(R.string.season_summer), "A"  to getString(R.string.season_autumn),
        "AS" to getString(R.string.season_all)
    )
    private val seasonCodesByLength = listOf("AS", "SP", "SU", "W", "A")

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_care, container, false)

        tvStatus         = view.findViewById(R.id.tv_care_status)
        tvLegacyBanner   = view.findViewById(R.id.tv_legacy_banner)
        btnAddToWardrobe = view.findViewById(R.id.btn_add_to_wardrobe)
        btnCamera        = view.findViewById(R.id.btn_camera_care)
        layoutResults    = view.findViewById(R.id.layout_care_results)

        nfcAdapter = NfcAdapter.getDefaultAdapter(requireContext())

        when {
            nfcAdapter == null       -> updateStatus(getString(R.string.nfc_not_supported))
            !nfcAdapter!!.isEnabled  -> updateStatus(getString(R.string.nfc_disabled))
            else                     -> updateStatus(getString(R.string.care_status_default))
        }

        btnCamera.setOnClickListener {
            (activity as? MainActivity)?.openCamera(CameraFragment.Source.CARE)
        }

        pendingVisionResults?.let { results ->
            displayVisionResults(results)
            pendingVisionResults = null
        }

        btnAddToWardrobe.setOnClickListener {
            val raw = lastScannedRaw ?: run {
                (activity as? MainActivity)?.addToWardrobe("")
                return@setOnClickListener
            }
            (activity as? MainActivity)?.addToWardrobe(raw)
        }

        return view
    }

    fun onTagDiscovered(tag: Tag) {
        readTag(tag)
    }

    fun showAddToWardrobeForTutorial() {
        btnAddToWardrobe.visibility = View.VISIBLE
        btnAddToWardrobe.contentDescription = getString(R.string.btn_add_to_wardrobe)
    }

    fun displayVisionResults(fields: Map<String, String>) {
        if (fields.isEmpty()) {
            updateStatus(getString(R.string.care_camera_no_results))
            return
        }

        val labelMap = mapOf(
            "W" to getString(R.string.field_wash), "D" to getString(R.string.field_drying),
            "I" to getString(R.string.field_ironing), "B" to getString(R.string.field_bleaching),
            "C" to getString(R.string.field_dry_clean), "T" to getString(R.string.field_type),
            "CL" to getString(R.string.field_color), "P" to getString(R.string.field_pattern)
        )

        val fakeRaw = fields.entries.joinToString("|") { "${it.key}:${it.value}" }
        val parsed = parseTagData("N:Camera Result|$fakeRaw")

        requireActivity().runOnUiThread {
            displayCareInfo(parsed)
            updateStatus(getString(R.string.care_camera_results_status))
            tvStatus.announceForAccessibility(
                "Camera results. " + fields.entries.joinToString(". ") { (k, v) ->
                    "${labelMap[k] ?: k}: $v"
                }
            )
        }
    }

    private fun readTag(tag: Tag) {
        try {
            val ndef = Ndef.get(tag)

            if (ndef == null) {
                requireActivity().runOnUiThread {
                    updateStatus(getString(R.string.tag_empty_write))
                    tvStatus.announceForAccessibility(getString(R.string.tag_empty_write))
                }
                return
            }

            ndef.connect()
            val message = ndef.ndefMessage
            ndef.close()

            if (message == null) {
                requireActivity().runOnUiThread {
                    updateStatus(getString(R.string.tag_empty_write))
                    tvStatus.announceForAccessibility(getString(R.string.tag_empty_write))
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

            lastScannedRaw = raw

            val name = raw.split("|")
                .firstOrNull { it.startsWith("N:") }
                ?.removePrefix("N:")

            val parsed = parseTagData(raw)

            requireActivity().runOnUiThread {
                displayCareInfo(parsed)

                btnAddToWardrobe.visibility = View.VISIBLE
                btnAddToWardrobe.contentDescription =
                    getString(R.string.btn_add_named_description, name)

                val announcement = parsed.fields.entries.joinToString(". ") {
                    "${it.key}: ${it.value.display}"
                }
                val legacyNote = if (parsed.hasLegacy)
                    " ${getString(R.string.care_legacy_warning)}" else ""
                tvStatus.announceForAccessibility("Tag read. $announcement.$legacyNote")
                updateStatus(getString(R.string.tag_read_success))
            }

        } catch (e: Exception) {
            requireActivity().runOnUiThread {
                updateStatus(getString(R.string.tag_error_reading, e.message))
            }
        }
    }

    private data class FieldValue(val display: String, val isLegacy: Boolean)
    private data class ParsedTag(val fields: LinkedHashMap<String, FieldValue>, val hasLegacy: Boolean)

    private fun decodeValue(key: String, value: String): FieldValue {
        return when (key) {
            "T"  -> typeCodeToName[value.uppercase()]
                ?.let { FieldValue(it, false) } ?: FieldValue(value, true)
            "CL" -> {
                val name = when (value.uppercase()) {
                    "212121" -> getString(R.string.color_black)
                    "F5F5F5" -> getString(R.string.color_white)
                    "9E9E9E" -> getString(R.string.color_grey)
                    "1A237E" -> getString(R.string.color_navy)
                    "2196F3" -> getString(R.string.color_blue)
                    "F44336" -> getString(R.string.color_red)
                    "4CAF50" -> getString(R.string.color_green)
                    "FFEB3B" -> getString(R.string.color_yellow)
                    "FF9800" -> getString(R.string.color_orange)
                    "E91E63" -> getString(R.string.color_pink)
                    "9C27B0" -> getString(R.string.color_purple)
                    "795548" -> getString(R.string.color_brown)
                    "D7CCC8" -> getString(R.string.color_beige)
                    "FF5722" -> getString(R.string.color_multicolor)
                    "607D8B" -> getString(R.string.color_other)
                    else -> null
                }
                if (name != null) FieldValue(name, false) else FieldValue(value, true)
            }
            "P"  -> patternCodeToName[value.uppercase()]
                ?.let { FieldValue(it, false) } ?: FieldValue(value, true)
            "F"  -> {
                val v = value.uppercase()
                val name = formalityComboToName[v] ?: formalitySingleToName[v]
                if (name != null) FieldValue(name, false) else FieldValue(value, true)
            }
            "SE" -> decodeSeason(value)
            "S"  -> when (value) {
                "OS" -> FieldValue(getString(R.string.one_size), false)
                else -> FieldValue(value, false)
            }
            "W"  -> {
                val name = when (value) {
                    "30" -> getString(R.string.wash_30); "40" -> getString(R.string.wash_40)
                    "60" -> getString(R.string.wash_60); "H"  -> getString(R.string.wash_hand)
                    "N"  -> getString(R.string.wash_no); else -> null
                }
                if (name != null) FieldValue(name, false) else FieldValue(value, true)
            }
            "D"  -> {
                val name = when (value) {
                    "A" -> getString(R.string.dry_air);    "T" -> getString(R.string.dry_tumble)
                    "F" -> getString(R.string.dry_flat);   "N" -> getString(R.string.dry_no)
                    else -> null
                }
                if (name != null) FieldValue(name, false) else FieldValue(value, true)
            }
            "I"  -> {
                val name = when (value) {
                    "0" -> getString(R.string.iron_no);   "1" -> getString(R.string.iron_low)
                    "2" -> getString(R.string.iron_medium); "3" -> getString(R.string.iron_high)
                    else -> null
                }
                if (name != null) FieldValue(name, false) else FieldValue(value, true)
            }
            "B"  -> when (value) {
                "1" -> FieldValue(getString(R.string.yes), false)
                "0" -> FieldValue(getString(R.string.no), false)
                else -> FieldValue(value, true)
            }
            "C"  -> when (value) {
                "1" -> FieldValue(getString(R.string.yes), false)
                "0" -> FieldValue(getString(R.string.no), false)
                else -> FieldValue(value, true)
            }
            else -> FieldValue(value, false)
        }
    }

    private fun decodeSeason(value: String): FieldValue {
        val v = value.uppercase()
        seasonSingleToName[v]?.let { return FieldValue(it, false) }
        val parts = mutableListOf<String>()
        var remaining = v
        while (remaining.isNotEmpty()) {
            val match = seasonCodesByLength.firstOrNull { remaining.startsWith(it) }
                ?: return FieldValue(value, true)
            parts.add(seasonSingleToName[match] ?: return FieldValue(value, true))
            remaining = remaining.removePrefix(match)
        }
        return FieldValue(parts.joinToString("/"), false)
    }

    private fun parseTagData(raw: String): ParsedTag {
        val labelMap = mapOf(
            "N"  to getString(R.string.field_item),     "T"  to getString(R.string.field_type),
            "CL" to getString(R.string.field_color),    "P"  to getString(R.string.field_pattern),
            "S"  to getString(R.string.field_size),     "F"  to getString(R.string.field_formality),
            "SE" to getString(R.string.field_season),   "M"  to getString(R.string.field_material),
            "W"  to getString(R.string.field_wash),     "D"  to getString(R.string.field_drying),
            "I"  to getString(R.string.field_ironing),  "B"  to getString(R.string.field_bleaching),
            "C"  to getString(R.string.field_dry_clean),"X"  to getString(R.string.field_notes)
        )
        val result = linkedMapOf<String, FieldValue>()
        var anyLegacy = false
        raw.split("|").forEach { part ->
            val key   = part.substringBefore(":")
            val value = part.substringAfter(":")
            val label = labelMap[key]
            if (label != null && value.isNotEmpty()) {
                val decoded = decodeValue(key, value)
                if (decoded.isLegacy) anyLegacy = true
                result[label] = decoded
            }
        }
        return ParsedTag(result, anyLegacy)
    }

    private fun displayCareInfo(parsed: ParsedTag) {
        layoutResults.removeAllViews()

        if (parsed.fields.isEmpty()) {
            updateStatus(getString(R.string.care_no_data))
            return
        }

        layoutResults.visibility = View.VISIBLE

        if (parsed.hasLegacy) {
            tvLegacyBanner.text = getString(R.string.care_legacy_warning)
            tvLegacyBanner.contentDescription = getString(R.string.care_legacy_warning_description)
            tvLegacyBanner.visibility = View.VISIBLE
        } else {
            tvLegacyBanner.visibility = View.GONE
        }

        parsed.fields.forEach { (label, fv) ->
            val card = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(16, 20, 16, 20)
                background = ContextCompat.getDrawable(requireContext(), R.drawable.result_background)
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.setMargins(0, 0, 0, 12)
                layoutParams = params
                contentDescription = if (fv.isLegacy) "$label: ${fv.display}. Old format."
                else "$label: ${fv.display}"
            }

            val tvLabel = TextView(requireContext()).apply {
                text = label
                textSize = 15f
                setTextColor(0xFF607D8B.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.4f)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }

            val displayText = if (fv.isLegacy) "⚠ ${fv.display}" else fv.display
            val tvValue = TextView(requireContext()).apply {
                text = displayText
                textSize = 16f
                setTextColor(
                    if (isDarkMode()) requireContext().getColor(android.R.color.white)
                    else requireContext().getColor(android.R.color.black)
                )
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.6f)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }

            card.addView(tvLabel)
            card.addView(tvValue)
            layoutResults.addView(card)
        }
    }

    private fun isDarkMode(): Boolean {
        val nightModeFlags = resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    private fun updateStatus(message: String) {
        tvStatus.text = message
        tvStatus.contentDescription = "Status: $message"
    }
}