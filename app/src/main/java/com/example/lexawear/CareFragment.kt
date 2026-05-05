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
    private lateinit var btnAddToWardrobe: Button
    private lateinit var layoutResults: LinearLayout

    private var nfcAdapter: NfcAdapter? = null
    private var lastScannedRaw: String? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_care, container, false)

        tvStatus         = view.findViewById(R.id.tv_care_status)
        btnAddToWardrobe = view.findViewById(R.id.btn_add_to_wardrobe)
        layoutResults    = view.findViewById(R.id.layout_care_results)

        nfcAdapter = NfcAdapter.getDefaultAdapter(requireContext())

        when {
            nfcAdapter == null -> updateStatus("This device does not support NFC.")
            !nfcAdapter!!.isEnabled -> updateStatus("NFC is off. Please enable it in Settings.")
            else -> updateStatus("Hold your phone to a clothing tag.")
        }

        btnAddToWardrobe.setOnClickListener {
            val raw = lastScannedRaw ?: return@setOnClickListener
            (activity as? MainActivity)?.addToWardrobe(raw)
        }

        return view
    }

    fun onTagDiscovered(tag: Tag) {
        readTag(tag)
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

            val fields = parseTagData(raw)

            requireActivity().runOnUiThread {
                displayCareInfo(fields)

                btnAddToWardrobe.visibility = View.VISIBLE
                btnAddToWardrobe.contentDescription = "Add $name to wardrobe"

                val announcement = fields.entries.joinToString(". ") { "${it.key}: ${it.value}" }
                tvStatus.announceForAccessibility("Tag read. $announcement")
                updateStatus("Tag read successfully.")
            }

        } catch (e: Exception) {
            requireActivity().runOnUiThread {
                updateStatus("Error reading tag: ${e.message}")
            }
        }
    }

    private fun decodeValue(key: String, value: String): String {
        return when (key) {
            "CL" -> when (value.uppercase()) {
                "212121" -> "Black"
                "F5F5F5" -> "White"
                "9E9E9E" -> "Grey"
                "1A237E" -> "Navy"
                "2196F3" -> "Blue"
                "F44336" -> "Red"
                "4CAF50" -> "Green"
                "FFEB3B" -> "Yellow"
                "FF9800" -> "Orange"
                "E91E63" -> "Pink"
                "9C27B0" -> "Purple"
                "795548" -> "Brown"
                "D7CCC8" -> "Beige"
                "FF5722" -> "Multicolor"
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
            "S" -> when (value) {
                "OS" -> "One Size"
                else -> value
            }
            else -> value
        }
    }

    private fun parseTagData(raw: String): Map<String, String> {
        val labelMap = mapOf(
            "N"  to "Item",
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
        val result = linkedMapOf<String, String>()
        raw.split("|").forEach { part ->
            val key   = part.substringBefore(":")
            val value = part.substringAfter(":")
            val label = labelMap[key]
            if (label != null && value.isNotEmpty()) {
                result[label] = decodeValue(key, value)
            }
        }
        return result
    }

    private fun displayCareInfo(fields: Map<String, String>) {
        layoutResults.removeAllViews()

        if (fields.isEmpty()) {
            updateStatus("No care data found on this tag.")
            return
        }

        layoutResults.visibility = View.VISIBLE

        fields.forEach { (label, value) ->
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
                contentDescription = "$label: $value"
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

            val tvValue = TextView(requireContext()).apply {
                text = value
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