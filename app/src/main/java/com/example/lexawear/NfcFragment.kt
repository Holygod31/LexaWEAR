package com.example.lexawear

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.fragment.app.Fragment

class NfcFragment : Fragment() {

    private lateinit var tvStatus: TextView
    private lateinit var tvResult: TextView
    private lateinit var etName: EditText
    private lateinit var etNotes: EditText
    private lateinit var btnScan: Button
    private lateinit var btnWrite: Button

    private lateinit var spinnerMaterial: Spinner
    private lateinit var spinnerWash: Spinner
    private lateinit var spinnerDry: Spinner
    private lateinit var spinnerIron: Spinner
    private lateinit var spinnerBleach: Spinner
    private lateinit var spinnerDryClean: Spinner

    private var nfcAdapter: NfcAdapter? = null
    private var isScanning = false
    private var isWriting = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_nfc, container, false)

        // Bind views
        tvStatus = view.findViewById(R.id.tv_status)
        tvResult = view.findViewById(R.id.tv_result)
        etName = view.findViewById(R.id.et_name)
        etNotes = view.findViewById(R.id.et_notes)
        btnScan = view.findViewById(R.id.btn_scan)
        btnWrite = view.findViewById(R.id.btn_write)
        spinnerMaterial = view.findViewById(R.id.spinner_material)
        spinnerWash = view.findViewById(R.id.spinner_wash)
        spinnerDry = view.findViewById(R.id.spinner_dry)
        spinnerIron = view.findViewById(R.id.spinner_iron)
        spinnerBleach = view.findViewById(R.id.spinner_bleach)
        spinnerDryClean = view.findViewById(R.id.spinner_dryclean)

        // Setup spinners
        setupSpinner(spinnerMaterial, listOf(
            "Select material",
            "Cotton", "Polyester", "Wool", "Silk",
            "Linen", "Denim", "Synthetic", "Mixed", "Other"
        ))
        setupSpinner(spinnerWash, listOf(
            "Select wash temp",
            "30°", "40°", "60°",
            "Hand Wash", "Dry Clean Only", "Do Not Wash"
        ))
        setupSpinner(spinnerDry, listOf(
            "Select drying",
            "Tumble Dry", "Air Dry", "Flat Dry", "Do Not Dry"
        ))
        setupSpinner(spinnerIron, listOf(
            "Select ironing",
            "No Iron", "Low Heat", "Medium Heat", "High Heat"
        ))
        setupSpinner(spinnerBleach, listOf(
            "Select bleaching",
            "Allowed", "Not Allowed"
        ))
        setupSpinner(spinnerDryClean, listOf(
            "Select dry cleaning",
            "Yes", "No"
        ))

        // NFC check
        nfcAdapter = NfcAdapter.getDefaultAdapter(requireContext())
        when {
            nfcAdapter == null -> updateStatus("This device does not support NFC.")
            !nfcAdapter!!.isEnabled -> updateStatus("NFC is off. Please enable it in Settings.")
            else -> updateStatus("Ready to scan or write a tag.")
        }

        btnScan.setOnClickListener {
            isScanning = true
            isWriting = false
            updateStatus("Hold your phone to an NFC tag to read it.")
            tvStatus.announceForAccessibility("Hold your phone to an NFC tag to read it.")
        }

        btnWrite.setOnClickListener {
            val name = etName.text.toString().trim()
            if (name.isEmpty()) {
                updateStatus("Please enter an item name before writing.")
                tvStatus.announceForAccessibility("Please enter an item name before writing.")
                return@setOnClickListener
            }
            isWriting = true
            isScanning = false
            updateStatus("Hold your phone to an NFC tag to write to it.")
            tvStatus.announceForAccessibility("Hold your phone to an NFC tag to write to it.")
        }

        return view
    }

    private fun setupSpinner(spinner: Spinner, items: List<String>) {
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            items
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
    }

    fun onTagDiscovered(tag: Tag) {
        when {
            isScanning -> readTag(tag)
            isWriting -> writeTag(tag)
        }
    }

    private fun readTag(tag: Tag) {
        try {
            val ndef = Ndef.get(tag)
            if (ndef == null) {
                requireActivity().runOnUiThread {
                    updateStatus("Tag is empty or unreadable.")
                    tvStatus.announceForAccessibility("Tag is empty or unreadable.")
                }
                return
            }
            ndef.connect()
            val message = ndef.ndefMessage
            ndef.close()

            if (message == null) {
                requireActivity().runOnUiThread {
                    updateStatus("Tag is empty.")
                    tvStatus.announceForAccessibility("Tag is empty.")
                }
                return
            }

            // Decode our compact format
            val raw = message.records
                .filter { it.tnf == NdefRecord.TNF_WELL_KNOWN }
                .mapNotNull { String(it.payload).drop(3) }
                .joinToString("")

            val decoded = decodeTagData(raw)

            requireActivity().runOnUiThread {
                tvResult.text = decoded
                tvResult.contentDescription = "Tag content: $decoded"
                updateStatus("Tag read successfully.")
                tvStatus.announceForAccessibility("Tag read successfully. $decoded")
                isScanning = false
            }
        } catch (e: Exception) {
            requireActivity().runOnUiThread {
                updateStatus("Error reading tag: ${e.message}")
            }
        }
    }

    private fun writeTag(tag: Tag) {
        try {
            val encoded = encodeTagData()

            val ndef = Ndef.get(tag)
            if (ndef == null) {
                requireActivity().runOnUiThread {
                    updateStatus("This tag cannot be written to.")
                    tvStatus.announceForAccessibility("This tag cannot be written to.")
                }
                return
            }

            val record = NdefRecord.createTextRecord("en", encoded)
            val ndefMessage = NdefMessage(arrayOf(record))

            ndef.connect()
            ndef.writeNdefMessage(ndefMessage)
            ndef.close()

            requireActivity().runOnUiThread {
                updateStatus("Tag written successfully!")
                tvStatus.announceForAccessibility("Tag written successfully!")
                isWriting = false
            }
        } catch (e: Exception) {
            requireActivity().runOnUiThread {
                updateStatus("Error writing tag: ${e.message}")
            }
        }
    }

    // Compact encoding: saves space on the tag
    // Format: N:name|M:material|W:wash|D:dry|I:iron|B:bleach|C:dryclean|X:notes
    private fun encodeTagData(): String {
        val parts = mutableListOf<String>()

        val name = etName.text.toString().trim()
        if (name.isNotEmpty()) parts.add("N:$name")

        val material = selectedOrNull(spinnerMaterial)
        if (material != null) parts.add("M:$material")

        val wash = selectedOrNull(spinnerWash)
        if (wash != null) parts.add("W:$wash")

        val dry = selectedOrNull(spinnerDry)
        if (dry != null) parts.add("D:$dry")

        val iron = selectedOrNull(spinnerIron)
        if (iron != null) parts.add("I:$iron")

        val bleach = selectedOrNull(spinnerBleach)
        if (bleach != null) parts.add("B:$bleach")

        val dryClean = selectedOrNull(spinnerDryClean)
        if (dryClean != null) parts.add("C:$dryClean")

        val notes = etNotes.text.toString().trim()
        if (notes.isNotEmpty()) parts.add("X:$notes")

        return parts.joinToString("|")
    }

    // Decode compact format into readable text
    private fun decodeTagData(raw: String): String {
        val map = mapOf(
            "N" to "Item",
            "M" to "Material",
            "W" to "Wash",
            "D" to "Drying",
            "I" to "Ironing",
            "B" to "Bleaching",
            "C" to "Dry Clean",
            "X" to "Notes"
        )
        return raw.split("|")
            .mapNotNull { part ->
                val key = part.substringBefore(":")
                val value = part.substringAfter(":")
                val label = map[key] ?: return@mapNotNull null
                "$label: $value"
            }
            .joinToString("\n")
    }

    // Returns null if user left spinner on the first "Select..." option
    private fun selectedOrNull(spinner: Spinner): String? {
        val selected = spinner.selectedItem?.toString() ?: return null
        return if (selected.startsWith("Select")) null else selected
    }

    private fun updateStatus(message: String) {
        tvStatus.text = message
        tvStatus.contentDescription = "Status: $message"
    }
}