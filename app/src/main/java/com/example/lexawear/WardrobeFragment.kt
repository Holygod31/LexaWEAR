package com.example.lexawear

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.NdefRecord
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class WardrobeItem(
    val id: Long,
    var name: String,
    var type: String,         // code: SH, JK, etc.
    var color: String,        // hex: 2196F3, etc.
    var pattern: String,      // code: P, ST, etc.
    var size: String,         // code: M, OS, 32/32, 38, etc.
    var formality: String,    // code: C, SC, BC, etc.
    var season: String,       // code: W, WSU, AS, etc.
    var material: String,     // free text
    var wash: String,         // code: 30, 40, 60, H, N
    var dry: String,          // code: A, T, F, N
    var iron: String,         // code: 0, 1, 2, 3
    var bleach: String,       // code: 0, 1
    var dryClean: String,     // code: 0, 1
    var notes: String,        // free text
    var isLegacy: Boolean = false
)

class WardrobeFragment : Fragment() {

    var pendingAddName: String? = null
    var pendingFilters: Array<String>? = null
    var pendingRaw: String? = null

    private lateinit var tvStatus: TextView
    private lateinit var tvActiveFilters: TextView
    private lateinit var tvLegacyBanner: TextView
    private lateinit var btnScan: Button
    private lateinit var btnOpenFilters: Button
    private lateinit var btnVoiceFilter: Button
    private lateinit var listView: ListView

    private var nfcAdapter: NfcAdapter? = null
    private var isScanning = false
    private val items = mutableListOf<WardrobeItem>()
    private var filteredItems = mutableListOf<WardrobeItem>()
    private lateinit var adapter: WardrobeAdapter

    // Filter state stores CODES, not display names
    private var activeFilterTypeCode      = ""  // empty = "All Types"
    private var activeFilterColorHex      = ""
    private var activeFilterSeasonCode    = ""
    private var activeFilterFormalityCode = ""

    // -------- Lookup tables --------

    private val typeCodeToName = linkedMapOf(
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
    private val formalitySingleToName = linkedMapOf(
        "C" to "Casual", "B" to "Business", "F" to "Formal",
        "S" to "Sport", "L" to "Lounge"
    )

    private val seasonSingleToName = linkedMapOf(
        "W" to "Winter", "SP" to "Spring", "SU" to "Summer",
        "A" to "Autumn", "AS" to "All-Season"
    )
    private val seasonCodesByLength = listOf("AS", "SP", "SU", "W", "A")

    private val hexToName = linkedMapOf(
        "212121" to "Black",  "F5F5F5" to "White",
        "9E9E9E" to "Grey",   "1A237E" to "Navy",
        "2196F3" to "Blue",   "F44336" to "Red",
        "4CAF50" to "Green",  "FFEB3B" to "Yellow",
        "FF9800" to "Orange", "E91E63" to "Pink",
        "9C27B0" to "Purple", "795548" to "Brown",
        "D7CCC8" to "Beige",  "FF5722" to "Multicolor",
        "607D8B" to "Other"
    )

    // -------- Decoders --------

    private fun decodeType(code: String): String =
        typeCodeToName[code.uppercase()] ?: code

    private fun decodePattern(code: String): String =
        patternCodeToName[code.uppercase()] ?: code

    private fun decodeColor(hex: String): String =
        hexToName[hex.uppercase().trimStart('#')] ?: hex

    private fun decodeFormality(code: String): String {
        val v = code.uppercase()
        return formalityComboToName[v] ?: formalitySingleToName[v] ?: code
    }

    private fun decodeSeason(code: String): String {
        val v = code.uppercase()
        seasonSingleToName[v]?.let { return it }
        val parts = parseSeasonComponents(v) ?: return code
        return parts.joinToString("/") { seasonSingleToName[it] ?: it }
    }

    /** Parses a season code into its component codes. Returns null if invalid. */
    private fun parseSeasonComponents(code: String): List<String>? {
        val v = code.uppercase()
        val parts = mutableListOf<String>()
        var remaining = v
        while (remaining.isNotEmpty()) {
            val match = seasonCodesByLength.firstOrNull { remaining.startsWith(it) } ?: return null
            parts.add(match)
            remaining = remaining.removePrefix(match)
        }
        return parts
    }

    private fun decodeWash(code: String): String = when (code) {
        "30" -> "Wash at 30°"; "40" -> "Wash at 40°"; "60" -> "Wash at 60°"
        "H" -> "Hand wash"; "N" -> "Do not wash"
        else -> code
    }
    private fun decodeDry(code: String): String = when (code) {
        "A" -> "Air dry"; "T" -> "Tumble dry"; "F" -> "Flat dry"; "N" -> "Do not dry"
        else -> code
    }
    private fun decodeIron(code: String): String = when (code) {
        "0" -> "No iron"; "1" -> "Low heat"; "2" -> "Medium heat"; "3" -> "High heat"
        else -> code
    }
    private fun decodeYesNo(code: String): String = when (code) {
        "1" -> "Yes"; "0" -> "No"; else -> code
    }

    // -------- Legacy detection --------

    private fun isLegacyValue(key: String, value: String): Boolean {
        if (value.isEmpty()) return false
        return when (key) {
            "T"  -> typeCodeToName[value.uppercase()] == null
            "P"  -> patternCodeToName[value.uppercase()] == null
            "CL" -> hexToName[value.uppercase()] == null
            "F"  -> {
                val v = value.uppercase()
                formalityComboToName[v] == null && formalitySingleToName[v] == null
            }
            "SE" -> {
                val v = value.uppercase()
                seasonSingleToName[v] == null && parseSeasonComponents(v) == null
            }
            "W"  -> value !in setOf("30", "40", "60", "H", "N")
            "D"  -> value !in setOf("A", "T", "F", "N")
            "I"  -> value !in setOf("0", "1", "2", "3")
            "B"  -> value !in setOf("0", "1")
            "C"  -> value !in setOf("0", "1")
            else -> false
        }
    }

    private val voiceFilterLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()?.lowercase() ?: return@registerForActivityResult

            activeFilterTypeCode = when {
                "t-shirt"   in spoken || "tshirt" in spoken -> "TS"
                "shirt"     in spoken -> "SH"
                "jacket"    in spoken -> "JK"
                "coat"      in spoken -> "CT"
                "sweater"   in spoken || "jumper" in spoken || "pullover" in spoken -> "SW"
                "hoodie"    in spoken -> "HD"
                "blazer"    in spoken -> "BZ"
                "suit"      in spoken -> "SU"
                "vest"      in spoken -> "VS"
                "dress"     in spoken -> "DR"
                "underwear" in spoken -> "UW"
                "pants"     in spoken || "trouser" in spoken -> "PT"
                "jeans"     in spoken -> "JN"
                "shorts"    in spoken -> "ST"
                "skirt"     in spoken -> "SK"
                "sock"      in spoken -> "SC"
                else -> ""
            }
            activeFilterColorHex = when {
                "black"  in spoken -> "212121"
                "white"  in spoken -> "F5F5F5"
                "grey"   in spoken || "gray" in spoken -> "9E9E9E"
                "navy"   in spoken -> "1A237E"
                "blue"   in spoken -> "2196F3"
                "red"    in spoken -> "F44336"
                "green"  in spoken -> "4CAF50"
                "yellow" in spoken -> "FFEB3B"
                "orange" in spoken -> "FF9800"
                "pink"   in spoken -> "E91E63"
                "purple" in spoken -> "9C27B0"
                "brown"  in spoken -> "795548"
                "beige"  in spoken -> "D7CCC8"
                else -> ""
            }
            activeFilterSeasonCode = when {
                "spring" in spoken -> "SP"
                "summer" in spoken -> "SU"
                "autumn" in spoken || "fall" in spoken -> "A"
                "winter" in spoken -> "W"
                else -> ""
            }
            activeFilterFormalityCode = when {
                "smart" in spoken && "casual" in spoken    -> "SC"
                "business" in spoken && "casual" in spoken -> "BC"
                "smart" in spoken && "formal" in spoken    -> "SF"
                "casual"   in spoken -> "C"
                "business" in spoken -> "B"
                "formal"   in spoken -> "F"
                "sport"    in spoken || "athletic" in spoken || "gym" in spoken -> "S"
                "lounge"   in spoken || "sleep" in spoken -> "L"
                else -> ""
            }

            applyFilters()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_wardrobe, container, false)

        tvStatus        = view.findViewById(R.id.tv_wardrobe_status)
        tvActiveFilters = view.findViewById(R.id.tv_active_filters)
        tvLegacyBanner  = view.findViewById(R.id.tv_legacy_banner)
        btnScan         = view.findViewById(R.id.btn_wardrobe_scan)
        btnOpenFilters  = view.findViewById(R.id.btn_open_filters)
        btnVoiceFilter  = view.findViewById(R.id.btn_voice_filter)
        listView        = view.findViewById(R.id.list_wardrobe)

        nfcAdapter = NfcAdapter.getDefaultAdapter(requireContext())

        loadItems()
        updateLegacyBanner()

        filteredItems = items.toMutableList()
        adapter = WardrobeAdapter()
        listView.adapter = adapter

        when {
            nfcAdapter == null -> updateStatus("This device does not support NFC.")
            !nfcAdapter!!.isEnabled -> updateStatus("NFC is off. Please enable it in Settings.")
            else -> updateStatus("${items.size} item(s) in wardrobe. Scan a tag to add.")
        }

        btnOpenFilters.setOnClickListener {
            val filterFragment = FilterFragment().apply {
                restoreFilters(
                    activeFilterTypeCode, activeFilterColorHex,
                    activeFilterSeasonCode, activeFilterFormalityCode
                )
            }
            (activity as? MainActivity)?.loadFragment(filterFragment)
        }

        btnVoiceFilter.setOnClickListener {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Describe what you're looking for")
            }
            voiceFilterLauncher.launch(intent)
        }

        btnScan.setOnClickListener {
            isScanning = true
            updateStatus("Hold your phone to a clothing tag.")
            tvStatus.announceForAccessibility("Hold your phone to a clothing tag to add it to your wardrobe.")
        }

        pendingAddName?.let {
            showAddDialog(it)
            pendingAddName = null
        }

        pendingRaw?.let {
            addItemFromRaw(it)
            pendingRaw = null
        }

        pendingFilters?.let {
            applyFilters(it[0], it[1], it[2], it[3])
            pendingFilters = null
        }

        return view
    }

    fun onTagDiscovered(tag: Tag) {
        if (!isScanning) return

        try {
            val ndef = Ndef.get(tag) ?: run {
                requireActivity().runOnUiThread {
                    updateStatus("Tag is empty or unreadable.")
                    isScanning = false
                }
                return
            }

            ndef.connect()
            val message = ndef.ndefMessage
            ndef.close()

            if (message == null) {
                requireActivity().runOnUiThread {
                    updateStatus("Tag is empty. Write to it first using the Write tab.")
                    isScanning = false
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
                    isScanning = false
                }
                return
            }

            val item = buildItemFromRaw(raw)

            requireActivity().runOnUiThread {
                isScanning = false
                showConfirmDialog(item)
            }

        } catch (e: Exception) {
            requireActivity().runOnUiThread {
                updateStatus("Error reading tag: ${e.message}")
                isScanning = false
            }
        }
    }

    fun addItemFromRaw(raw: String) {
        showConfirmDialog(buildItemFromRaw(raw))
    }

    private fun buildItemFromRaw(raw: String): WardrobeItem {
        val tagData = parseTagData(raw)
        val legacyKeys = setOf("T", "P", "CL", "F", "SE", "W", "D", "I", "B", "C")
        val hasLegacy = legacyKeys.any { isLegacyValue(it, tagData[it] ?: "") }
        return WardrobeItem(
            id        = System.currentTimeMillis(),
            name      = tagData["N"]  ?: "Unknown Item",
            type      = tagData["T"]  ?: "",
            color     = tagData["CL"] ?: "",
            pattern   = tagData["P"]  ?: "",
            size      = tagData["S"]  ?: "",
            formality = tagData["F"]  ?: "",
            season    = tagData["SE"] ?: "",
            material  = tagData["M"]  ?: "",
            wash      = tagData["W"]  ?: "",
            dry       = tagData["D"]  ?: "",
            iron      = tagData["I"]  ?: "",
            bleach    = tagData["B"]  ?: "",
            dryClean  = tagData["C"]  ?: "",
            notes     = tagData["X"]  ?: "",
            isLegacy  = hasLegacy
        )
    }

    private fun parseTagData(raw: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        raw.split("|").forEach { part ->
            val key   = part.substringBefore(":")
            val value = part.substringAfter(":")
            if (value.isNotEmpty()) result[key] = value
        }
        return result
    }

    private fun showConfirmDialog(item: WardrobeItem) {
        val summary = listOf(
            "Name"      to item.name,
            "Type"      to decodeType(item.type),
            "Color"     to decodeColor(item.color),
            "Size"      to item.size,
            "Season"    to decodeSeason(item.season),
            "Formality" to decodeFormality(item.formality),
            "Material"  to item.material
        ).filter { it.second.isNotEmpty() }
            .joinToString("\n") { "${it.first}: ${it.second}" }

        AlertDialog.Builder(requireContext())
            .setTitle("Add to Wardrobe?")
            .setMessage(if (item.isLegacy) "$summary\n\n⚠ Old format detected. Consider rewriting this tag." else summary)
            .setPositiveButton("Add") { _, _ ->
                items.add(item)
                saveItems()
                updateLegacyBanner()
                applyFilters()
                updateStatus("${items.size} item(s) in wardrobe.")
                tvStatus.announceForAccessibility("${item.name} added to wardrobe.")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun showAddDialog(nameFromTag: String) {
        val item = WardrobeItem(
            id        = System.currentTimeMillis(),
            name      = nameFromTag,
            type      = "", color = "", pattern = "",
            size      = "", formality = "", season = "",
            material  = "", wash = "", dry = "", iron = "",
            bleach    = "", dryClean = "", notes = ""
        )
        showConfirmDialog(item)
    }

    private fun showItemOptionsDialog(item: WardrobeItem) {
        val summary = listOf(
            "Type"      to decodeType(item.type),
            "Color"     to decodeColor(item.color),
            "Pattern"   to decodePattern(item.pattern),
            "Size"      to item.size,
            "Season"    to decodeSeason(item.season),
            "Formality" to decodeFormality(item.formality),
            "Material"  to item.material,
            "Wash"      to decodeWash(item.wash),
            "Dry"       to decodeDry(item.dry),
            "Iron"      to decodeIron(item.iron),
            "Bleach"    to decodeYesNo(item.bleach),
            "Dry Clean" to decodeYesNo(item.dryClean),
            "Notes"     to item.notes
        ).filter { it.second.isNotEmpty() }
            .joinToString("\n") { "${it.first}: ${it.second}" }

        val title = if (item.isLegacy) "⚠ ${item.name}" else item.name
        val body = if (item.isLegacy) "$summary\n\n⚠ Old format. Rewriting this tag is recommended." else summary

        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(body)
            .setPositiveButton("Delete") { _, _ -> showDeleteDialog(item) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showDeleteDialog(item: WardrobeItem) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete Item")
            .setMessage("Remove \"${item.name}\" from your wardrobe?")
            .setPositiveButton("Delete") { _, _ ->
                items.remove(item)
                saveItems()
                updateLegacyBanner()
                applyFilters()
                updateStatus("${items.size} item(s) in wardrobe.")
                tvStatus.announceForAccessibility("${item.name} removed from wardrobe.")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Filter codes ("" means no filter for that field).
     * Legacy items always pass all filters.
     * All-season items always pass the season filter.
     */
    fun applyFilters(
        typeCode: String      = activeFilterTypeCode,
        colorHex: String      = activeFilterColorHex,
        seasonCode: String    = activeFilterSeasonCode,
        formalityCode: String = activeFilterFormalityCode
    ) {
        activeFilterTypeCode      = typeCode
        activeFilterColorHex      = colorHex
        activeFilterSeasonCode    = seasonCode
        activeFilterFormalityCode = formalityCode

        filteredItems = items.filter { item ->
            if (item.isLegacy) return@filter true

            val typeOk = typeCode.isEmpty() ||
                    item.type.equals(typeCode, ignoreCase = true)

            val colorOk = colorHex.isEmpty() ||
                    item.color.equals(colorHex, ignoreCase = true)

            val seasonOk = seasonCode.isEmpty() ||
                    item.season.equals("AS", ignoreCase = true) ||
                    seasonContains(item.season, seasonCode)

            val formalityOk = formalityCode.isEmpty() ||
                    item.formality.equals(formalityCode, ignoreCase = true)

            typeOk && colorOk && seasonOk && formalityOk
        }.toMutableList()

        adapter.notifyDataSetChanged()

        val activeList = listOfNotNull(
            decodeType(typeCode).takeIf { typeCode.isNotEmpty() },
            decodeColor(colorHex).takeIf { colorHex.isNotEmpty() },
            decodeSeason(seasonCode).takeIf { seasonCode.isNotEmpty() },
            decodeFormality(formalityCode).takeIf { formalityCode.isNotEmpty() }
        )

        val filterText = if (activeList.isEmpty()) "No filters active"
        else "Filters: ${activeList.joinToString(", ")}"

        tvActiveFilters.text = filterText
        tvActiveFilters.announceForAccessibility(
            "$filterText. ${filteredItems.size} items shown."
        )
        updateStatus("${filteredItems.size} item(s) shown.")
    }

    /** True if itemSeason's component codes include filterSeason. */
    private fun seasonContains(itemSeason: String, filterSeason: String): Boolean {
        val components = parseSeasonComponents(itemSeason) ?: return false
        return components.any { it.equals(filterSeason, ignoreCase = true) }
    }

    private fun updateLegacyBanner() {
        val legacyCount = items.count { it.isLegacy }
        if (legacyCount > 0) {
            val msg = "⚠ $legacyCount item(s) use the old format. Consider rewriting them."
            tvLegacyBanner.text = msg
            tvLegacyBanner.contentDescription = "Warning. $msg"
            tvLegacyBanner.visibility = View.VISIBLE
        } else {
            tvLegacyBanner.visibility = View.GONE
        }
    }

    private fun saveItems() {
        val arr = JSONArray()
        items.forEach { item ->
            val obj = JSONObject()
            obj.put("id",        item.id)
            obj.put("name",      item.name)
            obj.put("type",      item.type)
            obj.put("color",     item.color)
            obj.put("pattern",   item.pattern)
            obj.put("size",      item.size)
            obj.put("formality", item.formality)
            obj.put("season",    item.season)
            obj.put("material",  item.material)
            obj.put("wash",      item.wash)
            obj.put("dry",       item.dry)
            obj.put("iron",      item.iron)
            obj.put("bleach",    item.bleach)
            obj.put("dryClean",  item.dryClean)
            obj.put("notes",     item.notes)
            obj.put("isLegacy",  item.isLegacy)
            arr.put(obj)
        }
        requireContext()
            .getSharedPreferences("wardrobe", Context.MODE_PRIVATE)
            .edit()
            .putString("items", arr.toString())
            .apply()
    }

    private fun loadItems() {
        val prefs = requireContext()
            .getSharedPreferences("wardrobe", Context.MODE_PRIVATE)
        val json = prefs.getString("items", "[]") ?: "[]"
        val arr = JSONArray(json)
        items.clear()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val item = WardrobeItem(
                id        = obj.getLong("id"),
                name      = obj.optString("name"),
                type      = obj.optString("type"),
                color     = obj.optString("color"),
                pattern   = obj.optString("pattern"),
                size      = obj.optString("size"),
                formality = obj.optString("formality"),
                season    = obj.optString("season"),
                material  = obj.optString("material"),
                wash      = obj.optString("wash"),
                dry       = obj.optString("dry"),
                iron      = obj.optString("iron"),
                bleach    = obj.optString("bleach"),
                dryClean  = obj.optString("dryClean"),
                notes     = obj.optString("notes"),
                isLegacy  = obj.optBoolean("isLegacy", false)
            )
            // Re-evaluate legacy on load (catches items saved before isLegacy existed)
            if (!obj.has("isLegacy")) {
                val keys = mapOf(
                    "T" to item.type, "P" to item.pattern, "CL" to item.color,
                    "F" to item.formality, "SE" to item.season,
                    "W" to item.wash, "D" to item.dry, "I" to item.iron,
                    "B" to item.bleach, "C" to item.dryClean
                )
                item.isLegacy = keys.any { (k, v) -> isLegacyValue(k, v) }
            }
            items.add(item)
        }
    }

    private fun updateStatus(message: String) {
        tvStatus.text = message
        tvStatus.contentDescription = "Status: $message"
    }

    inner class WardrobeAdapter : BaseAdapter() {
        override fun getCount() = filteredItems.size
        override fun getItem(pos: Int) = filteredItems[pos]
        override fun getItemId(pos: Int) = filteredItems[pos].id

        override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(requireContext())
                .inflate(R.layout.item_wardrobe, parent, false)

            val item = filteredItems[pos]

            val tvName     = view.findViewById<TextView>(R.id.tv_item_name)
            val tvCategory = view.findViewById<TextView>(R.id.tv_item_category)
            val colorDot   = view.findViewById<View>(R.id.view_color_dot)

            val typeName      = decodeType(item.type)
            val colorName     = decodeColor(item.color)
            val patternName   = decodePattern(item.pattern)
            val seasonName    = decodeSeason(item.season)
            val formalityName = decodeFormality(item.formality)

            tvName.text = if (item.isLegacy) "⚠ ${item.name}" else item.name

            tvCategory.text = listOfNotNull(
                typeName.takeIf { it.isNotEmpty() },
                colorName.takeIf { it.isNotEmpty() },
                patternName.takeIf { it.isNotEmpty() },
                item.size.takeIf { it.isNotEmpty() }?.let { "Size $it" },
                seasonName.takeIf { it.isNotEmpty() },
                formalityName.takeIf { it.isNotEmpty() }
            ).joinToString(" · ")

            try {
                val hex = if (item.color.length == 6) "#${item.color}" else "#607D8B"
                colorDot.setBackgroundColor(Color.parseColor(hex))
            } catch (e: Exception) {
                colorDot.setBackgroundColor(Color.parseColor("#607D8B"))
            }

            val descPrefix = if (item.isLegacy) "Old format. " else ""
            view.contentDescription = descPrefix + listOfNotNull(
                item.name,
                typeName.takeIf { it.isNotEmpty() },
                colorName.takeIf { it.isNotEmpty() },
                item.size.takeIf { it.isNotEmpty() }?.let { "size $it" },
                seasonName.takeIf { it.isNotEmpty() },
                formalityName.takeIf { it.isNotEmpty() }
            ).joinToString(", ") + ". Double tap for details."

            view.isClickable = true
            view.isFocusable = true
            view.setOnClickListener { showItemOptionsDialog(item) }

            return view
        }
    }
}