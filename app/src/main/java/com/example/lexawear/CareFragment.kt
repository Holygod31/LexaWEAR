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
    private lateinit var layoutResults: LinearLayout

    private var nfcAdapter: NfcAdapter? = null
    private var lastScannedRaw: String? = null

    private val encodedKeys = setOf("T", "CL", "P", "F", "SE", "W", "D", "I", "B", "C", "S")

    private val typeCodeToName = mapOf(
        "SH" to "Shirt", "TS" to "T-Shirt", "JK" to "Jacket", "CT" to "Coat",
        "SW" to "Sweater", "HD" to "Hoodie", "BZ" to "Blazer", "SU" to "Suit",
        "VS" to "Vest", "DR" to "Dress", "UW" to "Underwear", "PT" to "Pants",
        "JN" to "Jeans", "ST" to "Shorts", "SK" to "Skirt", "SC" to "Socks"
    )

    private val patternCodeToName = mapOf(
        "P"  to "Plain", "ST" to "Striped", "CH" to "Checkered", "PL" to "Plaid",
        "FL" to "Floral", "DT" to "Polka Dot", "GR" to "Graphic",
        "CM" to "Camouflage", "AN" to "Animal Print"
    )

    private val formalityComboToName = mapOf(
        "SC" to "Smart Casual", "BC" to "Business Casual", "SF" to "Smart Formal"
    )
    private val formalitySingleToName = mapOf(
        "C" to "Casual", "B" to "Business", "F" to "Formal",
        "S" to "Sport", "L" to "Lounge"
    )

    private val seasonSingleToName = mapOf(
        "W" to "Winter", "SP" to "Spring", "SU" to "Summer",
        "A" to "Autumn", "AS" to "All-Season"
    )
    private val seasonCodesByLength = listOf("AS", "SP", "SU", "W", "A")

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_care, container, false)

        tvStatus         = view.findViewById(R.id.tv_care_status)
        tvLegacyBanner   = view.findViewById(R.id.tv_legacy_banner)
        btnAddToWardrobe = view.findViewById(R.id.btn_add_to_wardrobe)
        layoutResults    = view.findViewById(R.id.layout_care_results)

        nfcAdapter = NfcAdapter.getDefaultAdapter(requireContext())

        when {
            nfcAdapter == null -> updateStatus("This device does not support NFC.")
            !nfcAdapter!!.isEnabled -> updateStatus("NFC is off. Please enable it in Settings.")
            else -> updateStatus("Hold your phone to a clothing tag.")
        }

        // During tutorial step 2 the button is visible so the user can find and tap it.
        // addToWardrobe in MainActivity handles suppression of the actual navigation.
        btnAddToWardrobe.setOnClickListener {
            val raw = lastScannedRaw ?: run {
                // During tutorial the button may be shown without a real scan —
                // route through MainActivity which handles tutorial suppression.
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

    // Make Add to Wardrobe button visible for tutorial step 2
    // even if no tag was scanned yet.
    fun showAddToWardrobeForTutorial() {
        btnAddToWardrobe.visibility = View.VISIBLE
        btnAddToWardrobe.contentDescription = "Add to Wardrobe"
    }

    private fun readTag(tag: Tag) {
        try {
            val ndef = Ndef.get(tag)

            if (ndef == null) {
                requireActivity().runOnUiThread {
                    updateStatus("Tag is empty. Use the Write tab to add clothing info.")
                    tvStatus.announceForAccessibility("Tag is empty. Use the Write tab to add clothing info.")
                }
                return
            }

            ndef.connect()
            val message = ndef.ndefMessage
            ndef.close()

            if (message == null) {
                requireActivity().runOnUiThread {
                    updateStatus("Tag is empty. Use the Write tab to add clothing info.")
                    tvStatus.announceForAccessibility("Tag is empty. Use the Write tab to add clothing info.")
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

            lastScannedRaw = raw

            val name = raw.split("|")
                .firstOrNull { it.startsWith("N:") }
                ?.removePrefix("N:")

            val parsed = parseTagData(raw)

            requireActivity().runOnUiThread {
                displayCareInfo(parsed)

                btnAddToWardrobe.visibility = View.VISIBLE
                btnAddToWardrobe.contentDescription = "Add $name to wardrobe"

                val announcement = parsed.fields.entries.joinToString(". ") {
                    "${it.key}: ${it.value.display}"
                }
                val legacyNote = if (parsed.hasLegacy) " Old format detected. Consider rewriting this tag." else ""
                tvStatus.announceForAccessibility("Tag read. $announcement.$legacyNote")
                updateStatus("Tag read successfully.")
            }

        } catch (e: Exception) {
            requireActivity().runOnUiThread {
                updateStatus("Error reading tag: ${e.message}")
            }
        }
    }

    private data class FieldValue(val display: String, val isLegacy: Boolean)

    private data class ParsedTag(
        val fields: LinkedHashMap<String, FieldValue>,
        val hasLegacy: Boolean
    )

    private fun decodeValue(key: String, value: String): FieldValue {
        return when (key) {
            "T" -> typeCodeToName[value.uppercase()]
                ?.let { FieldValue(it, false) }
                ?: FieldValue(value, true)
            "CL" -> {
                val name = when (value.uppercase()) {
                    "212121" -> "Black";  "F5F5F5" -> "White"
                    "9E9E9E" -> "Grey";   "1A237E" -> "Navy"
                    "2196F3" -> "Blue";   "F44336" -> "Red"
                    "4CAF50" -> "Green";  "FFEB3B" -> "Yellow"
                    "FF9800" -> "Orange"; "E91E63" -> "Pink"
                    "9C27B0" -> "Purple"; "795548" -> "Brown"
                    "D7CCC8" -> "Beige";  "FF5722" -> "Multicolor"
                    "607D8B" -> "Other";  else -> null
                }
                if (name != null) FieldValue(name, false) else FieldValue(value, true)
            }
            "P" -> patternCodeToName[value.uppercase()]
                ?.let { FieldValue(it, false) }
                ?: FieldValue(value, true)
            "F" -> {
                val v = value.uppercase()
                val name = formalityComboToName[v] ?: formalitySingleToName[v]
                if (name != null) FieldValue(name, false) else FieldValue(value, true)
            }
            "SE" -> decodeSeason(value)
            "S" -> when (value) {
                "OS" -> FieldValue("One Size", false)
                else -> FieldValue(value, false)
            }
            "W" -> {
                val name = when (value) {
                    "30" -> "Wash at 30°"; "40" -> "Wash at 40°"
                    "60" -> "Wash at 60°"; "H" -> "Hand wash"
                    "N"  -> "Do not wash"; else -> null
                }
                if (name != null) FieldValue(name, false) else FieldValue(value, true)
            }
            "D" -> {
                val name = when (value) {
                    "A" -> "Air dry"; "T" -> "Tumble dry"
                    "F" -> "Flat dry"; "N" -> "Do not dry"
                    else -> null
                }
                if (name != null) FieldValue(name, false) else FieldValue(value, true)
            }
            "I" -> {
                val name = when (value) {
                    "0" -> "No iron"; "1" -> "Low heat"
                    "2" -> "Medium heat"; "3" -> "High heat"
                    else -> null
                }
                if (name != null) FieldValue(name, false) else FieldValue(value, true)
            }
            "B" -> when (value) {
                "1" -> FieldValue("Yes", false)
                "0" -> FieldValue("No", false)
                else -> FieldValue(value, true)
            }
            "C" -> when (value) {
                "1" -> FieldValue("Yes", false)
                "0" -> FieldValue("No", false)
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
            "N"  to "Item",    "T"  to "Type",     "CL" to "Color",
            "P"  to "Pattern", "S"  to "Size",      "F"  to "Formality",
            "SE" to "Season",  "M"  to "Material",  "W"  to "Wash",
            "D"  to "Drying",  "I"  to "Ironing",   "B"  to "Bleaching",
            "C"  to "Dry Clean", "X" to "Notes"
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
            updateStatus("No care data found on this tag.")
            return
        }

        layoutResults.visibility = View.VISIBLE

        if (parsed.hasLegacy) {
            tvLegacyBanner.text = "⚠ Old format detected. Consider rewriting this tag."
            tvLegacyBanner.contentDescription =
                "Warning. Old tag format detected. Consider rewriting this tag."
            tvLegacyBanner.visibility = View.VISIBLE
        } else {
            tvLegacyBanner.visibility = View.GONE
        }

        parsed.fields.forEach { (label, fv) ->
            val card = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(16, 20, 16, 20)
                background = ContextCompat.getDrawable(
                    requireContext(), R.drawable.result_background
                )
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.setMargins(0, 0, 0, 12)
                layoutParams = params
                contentDescription = if (fv.isLegacy)
                    "$label: ${fv.display}. Old format."
                else
                    "$label: ${fv.display}"
            }

            val tvLabel = TextView(requireContext()).apply {
                text = label
                textSize = 15f
                setTextColor(requireContext().getColor(
                    com.google.android.material.R.color.material_blue_grey_800
                ))
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