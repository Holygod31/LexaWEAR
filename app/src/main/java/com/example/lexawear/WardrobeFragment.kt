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
    var type: String,
    var color: String,
    var pattern: String,
    var size: String,
    var formality: String,
    var season: String,
    var material: String,
    var wash: String,
    var dry: String,
    var iron: String,
    var bleach: String,
    var dryClean: String,
    var notes: String,
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

    private var activeFilterTypeCode      = ""
    private var activeFilterColorHex      = ""
    private var activeFilterSeasonCode    = ""
    private var activeFilterFormalityCode = ""

    // ── Lookup tables ─────────────────────────────────────────────────────────

    private val typeCodeToName get() = linkedMapOf(
        "SH" to getString(R.string.type_shirt),   "TS" to getString(R.string.type_tshirt),
        "JK" to getString(R.string.type_jacket),  "CT" to getString(R.string.type_coat),
        "SW" to getString(R.string.type_sweater), "HD" to getString(R.string.type_hoodie),
        "BZ" to getString(R.string.type_blazer),  "SU" to getString(R.string.type_suit),
        "VS" to getString(R.string.type_vest),    "DR" to getString(R.string.type_dress),
        "UW" to getString(R.string.type_underwear),"PT" to getString(R.string.type_pants),
        "JN" to getString(R.string.type_jeans),   "ST" to getString(R.string.type_shorts),
        "SK" to getString(R.string.type_skirt),   "SC" to getString(R.string.type_socks)
    )

    private val patternCodeToName get() = mapOf(
        "P"  to getString(R.string.pattern_plain),     "ST" to getString(R.string.pattern_striped),
        "CH" to getString(R.string.pattern_checkered), "PL" to getString(R.string.pattern_plaid),
        "FL" to getString(R.string.pattern_floral),    "DT" to getString(R.string.pattern_polkadot),
        "GR" to getString(R.string.pattern_graphic),   "CM" to getString(R.string.pattern_camouflage),
        "AN" to getString(R.string.pattern_animal)
    )

    private val formalityComboToName get() = mapOf(
        "SC" to getString(R.string.formality_smart_casual),
        "BC" to getString(R.string.formality_business_casual),
        "SF" to getString(R.string.formality_smart_formal)
    )
    private val formalitySingleToName get() = linkedMapOf(
        "C" to getString(R.string.formality_casual), "B" to getString(R.string.formality_business),
        "F" to getString(R.string.formality_formal), "S" to getString(R.string.formality_sport),
        "L" to getString(R.string.formality_lounge)
    )

    private val seasonSingleToName get() = linkedMapOf(
        "W"  to getString(R.string.season_winter), "SP" to getString(R.string.season_spring),
        "SU" to getString(R.string.season_summer), "A"  to getString(R.string.season_autumn),
        "AS" to getString(R.string.season_all)
    )
    private val seasonCodesByLength = listOf("AS", "SP", "SU", "W", "A")

    // ── Decoders ──────────────────────────────────────────────────────────────

    private fun decodeType(code: String): String =
        typeCodeToName[code.uppercase()] ?: code

    private fun decodePattern(code: String): String =
        patternCodeToName[code.uppercase()] ?: code

    /**
     * Resolves a hex colour code to a localised name via ColorPalette.
     * Exact palette match → localised name.
     * Off-palette hex (legacy tags) → nearest palette colour name.
     */
    private fun decodeColor(hex: String): String {
        val clean = hex.uppercase().trimStart('#')
        ColorPalette.nameForHex(clean, ::getString)?.let { return it }
        return try {
            val argb = Color.parseColor("#$clean")
            getString(ColorPalette.nearestEntryFromArgb(argb).nameRes)
        } catch (e: Exception) { hex }
    }

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

    private fun parseSeasonComponents(code: String): List<String>? {
        val v = code.uppercase(); val parts = mutableListOf<String>(); var remaining = v
        while (remaining.isNotEmpty()) {
            val match = seasonCodesByLength.firstOrNull { remaining.startsWith(it) } ?: return null
            parts.add(match); remaining = remaining.removePrefix(match)
        }
        return parts
    }

    private fun decodeWash(code: String): String = when (code) {
        "30" -> getString(R.string.wash_30); "40" -> getString(R.string.wash_40)
        "60" -> getString(R.string.wash_60); "H"  -> getString(R.string.wash_hand)
        "N"  -> getString(R.string.wash_no); else -> code
    }
    private fun decodeDry(code: String): String = when (code) {
        "A" -> getString(R.string.dry_air);   "T" -> getString(R.string.dry_tumble)
        "F" -> getString(R.string.dry_flat);  "N" -> getString(R.string.dry_no); else -> code
    }
    private fun decodeIron(code: String): String = when (code) {
        "0" -> getString(R.string.iron_no);     "1" -> getString(R.string.iron_low)
        "2" -> getString(R.string.iron_medium); "3" -> getString(R.string.iron_high); else -> code
    }
    private fun decodeYesNo(code: String): String = when (code) {
        "1" -> getString(R.string.yes); "0" -> getString(R.string.no); else -> code
    }

    // ── Legacy detection ──────────────────────────────────────────────────────

    private fun isLegacyValue(key: String, value: String): Boolean {
        if (value.isEmpty()) return false
        return when (key) {
            "T"  -> typeCodeToName[value.uppercase()] == null
            "P"  -> patternCodeToName[value.uppercase()] == null
            // A colour is legacy only if it can't be parsed as hex at all.
            // Off-palette hex values are valid but will be nearest-matched.
            "CL" -> try { Color.parseColor("#${value.uppercase().trimStart('#')}"); false }
            catch (e: Exception) { true }
            "F"  -> { val v = value.uppercase(); formalityComboToName[v] == null && formalitySingleToName[v] == null }
            "SE" -> { val v = value.uppercase(); seasonSingleToName[v] == null && parseSeasonComponents(v) == null }
            "W"  -> value !in setOf("30","40","60","H","N")
            "D"  -> value !in setOf("A","T","F","N")
            "I"  -> value !in setOf("0","1","2","3")
            "B"  -> value !in setOf("0","1")
            "C"  -> value !in setOf("0","1")
            else -> false
        }
    }

    // ── Voice filter ──────────────────────────────────────────────────────────

    private val voiceFilterLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()?.lowercase() ?: return@registerForActivityResult

            activeFilterTypeCode = when {
                "t-shirt" in spoken || "tshirt" in spoken -> "TS"
                "shirt"     in spoken -> "SH"; "jacket"    in spoken -> "JK"
                "coat"      in spoken -> "CT"; "sweater"   in spoken || "jumper"   in spoken -> "SW"
                "hoodie"    in spoken -> "HD"; "blazer"    in spoken -> "BZ"
                "suit"      in spoken -> "SU"; "vest"      in spoken -> "VS"
                "dress"     in spoken -> "DR"; "underwear" in spoken -> "UW"
                "pants"     in spoken || "trouser" in spoken -> "PT"
                "jeans"     in spoken -> "JN"; "shorts"    in spoken -> "ST"
                "skirt"     in spoken -> "SK"; "sock"      in spoken -> "SC"
                else -> ""
            }

            // Map spoken colour to the nearest palette entry hex.
            activeFilterColorHex = when {
                "black"  in spoken -> "212121"; "white"  in spoken -> "F5F5F5"
                "grey"   in spoken || "gray" in spoken -> "9E9E9E"
                "navy"   in spoken -> "1A237E"; "blue"   in spoken -> "2196F3"
                "red"    in spoken -> "F44336"; "green"  in spoken -> "4CAF50"
                "yellow" in spoken -> "FFEB3B"; "orange" in spoken -> "FF9800"
                "pink"   in spoken -> "E91E63"; "purple" in spoken -> "9C27B0"
                "brown"  in spoken -> "795548"; "beige"  in spoken -> "D7CCC8"
                "burgundy" in spoken -> "880E4F"; "coral" in spoken -> "FF7043"
                "salmon"   in spoken -> "FF8A65"; "peach" in spoken -> "FFCC80"
                "rust"     in spoken -> "BF360C"; "mustard" in spoken -> "FFC107"
                "gold"     in spoken -> "FFD700"; "cream" in spoken -> "FFF8E1"
                "lime"     in spoken -> "CDDC39"; "olive" in spoken -> "827717"
                "mint"     in spoken -> "80CBC4"; "teal"  in spoken -> "009688"
                "sky"      in spoken -> "81D4FA"; "royal" in spoken -> "1565C0"
                "cobalt"   in spoken -> "1E88E5"; "turquoise" in spoken -> "00BCD4"
                "lavender" in spoken -> "E8EAF6"; "violet"    in spoken -> "7B1FA2"
                "plum"     in spoken -> "4A148C"; "charcoal"  in spoken -> "424242"
                "crimson"  in spoken -> "E53935"; "mauve"     in spoken -> "CE93D8"
                else -> ""
            }

            activeFilterSeasonCode = when {
                "spring" in spoken -> "SP"; "summer" in spoken -> "SU"
                "autumn" in spoken || "fall" in spoken -> "A"; "winter" in spoken -> "W"
                else -> ""
            }
            activeFilterFormalityCode = when {
                "smart" in spoken && "casual" in spoken    -> "SC"
                "business" in spoken && "casual" in spoken -> "BC"
                "smart" in spoken && "formal" in spoken    -> "SF"
                "casual"   in spoken -> "C"; "business" in spoken -> "B"
                "formal"   in spoken -> "F"
                "sport"    in spoken || "athletic" in spoken || "gym" in spoken -> "S"
                "lounge"   in spoken || "sleep"    in spoken -> "L"
                else -> ""
            }
            applyFilters()
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

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
        loadItems(); updateLegacyBanner()
        filteredItems = items.toMutableList()
        adapter = WardrobeAdapter(); listView.adapter = adapter

        when {
            nfcAdapter == null      -> updateStatus(getString(R.string.nfc_not_supported))
            !nfcAdapter!!.isEnabled -> updateStatus(getString(R.string.nfc_disabled))
            else -> updateStatus(getString(R.string.wardrobe_status_count_scan, items.size))
        }

        btnOpenFilters.setOnClickListener {
            val filterFragment = FilterFragment().apply {
                restoreFilters(activeFilterTypeCode, activeFilterColorHex,
                    activeFilterSeasonCode, activeFilterFormalityCode)
            }
            (activity as? MainActivity)?.loadFragment(filterFragment)
        }

        btnVoiceFilter.setOnClickListener {
            voiceFilterLauncher.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_filter_prompt))
            })
        }

        btnScan.setOnClickListener {
            isScanning = true
            updateStatus(getString(R.string.wardrobe_status_hold_tag))
            tvStatus.announceForAccessibility(getString(R.string.wardrobe_status_hold_tag_accessibility))
        }

        pendingAddName?.let { showAddDialog(it); pendingAddName = null }
        pendingRaw?.let { addItemFromRaw(it); pendingRaw = null }
        pendingFilters?.let { applyFilters(it[0], it[1], it[2], it[3]); pendingFilters = null }

        return view
    }

    // ── NFC ───────────────────────────────────────────────────────────────────

    fun onTagDiscovered(tag: Tag) {
        if (!isScanning) return
        try {
            val ndef = Ndef.get(tag) ?: run {
                requireActivity().runOnUiThread { updateStatus(getString(R.string.wardrobe_tag_empty)); isScanning = false }
                return
            }
            ndef.connect(); val message = ndef.ndefMessage; ndef.close()
            if (message == null) {
                requireActivity().runOnUiThread { updateStatus(getString(R.string.wardrobe_tag_write_first)); isScanning = false }
                return
            }
            val raw = message.records.filter { it.tnf == NdefRecord.TNF_WELL_KNOWN }
                .mapNotNull { String(it.payload).drop(3) }.joinToString("")
            if (!raw.contains("N:")) {
                requireActivity().runOnUiThread { updateStatus(getString(R.string.tag_not_lexawear)); isScanning = false }
                return
            }
            val item = buildItemFromRaw(raw)
            requireActivity().runOnUiThread { isScanning = false; showConfirmDialog(item) }
        } catch (e: Exception) {
            requireActivity().runOnUiThread { updateStatus(getString(R.string.tag_error_reading, e.message)); isScanning = false }
        }
    }

    fun addItemFromRaw(raw: String) { showConfirmDialog(buildItemFromRaw(raw)) }

    private fun buildItemFromRaw(raw: String): WardrobeItem {
        val tagData   = parseTagData(raw)
        val legacyKeys = setOf("T","P","CL","F","SE","W","D","I","B","C")
        val hasLegacy  = legacyKeys.any { isLegacyValue(it, tagData[it] ?: "") }
        return WardrobeItem(
            id        = System.currentTimeMillis(),
            name      = tagData["N"]  ?: getString(R.string.wardrobe_unknown_item),
            type      = tagData["T"]  ?: "", color     = tagData["CL"] ?: "",
            pattern   = tagData["P"]  ?: "", size      = tagData["S"]  ?: "",
            formality = tagData["F"]  ?: "", season    = tagData["SE"] ?: "",
            material  = tagData["M"]  ?: "", wash      = tagData["W"]  ?: "",
            dry       = tagData["D"]  ?: "", iron      = tagData["I"]  ?: "",
            bleach    = tagData["B"]  ?: "", dryClean  = tagData["C"]  ?: "",
            notes     = tagData["X"]  ?: "", isLegacy  = hasLegacy
        )
    }

    private fun parseTagData(raw: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        raw.split("|").forEach { part ->
            val key = part.substringBefore(":"); val value = part.substringAfter(":")
            if (value.isNotEmpty()) result[key] = value
        }
        return result
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────

    private fun showConfirmDialog(item: WardrobeItem) {
        val summary = listOf(
            getString(R.string.field_item)      to item.name,
            getString(R.string.field_type)      to decodeType(item.type),
            getString(R.string.field_color)     to decodeColor(item.color),
            getString(R.string.field_size)      to item.size,
            getString(R.string.field_season)    to decodeSeason(item.season),
            getString(R.string.field_formality) to decodeFormality(item.formality),
            getString(R.string.field_material)  to item.material
        ).filter { it.second.isNotEmpty() }.joinToString("\n") { "${it.first}: ${it.second}" }
        val message = if (item.isLegacy) "$summary\n\n${getString(R.string.care_legacy_warning)}" else summary
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.dialog_add_wardrobe_title)).setMessage(message)
            .setPositiveButton(getString(R.string.dialog_add)) { _, _ ->
                items.add(item); saveItems(); updateLegacyBanner(); applyFilters()
                updateStatus(getString(R.string.wardrobe_status_count, items.size))
                tvStatus.announceForAccessibility(getString(R.string.dialog_item_added_accessibility, item.name))
            }
            .setNegativeButton(getString(R.string.dialog_cancel), null).show()
    }

    fun showAddDialog(nameFromTag: String) {
        showConfirmDialog(WardrobeItem(id = System.currentTimeMillis(), name = nameFromTag,
            type = "", color = "", pattern = "", size = "", formality = "",
            season = "", material = "", wash = "", dry = "", iron = "",
            bleach = "", dryClean = "", notes = ""))
    }

    private fun showItemOptionsDialog(item: WardrobeItem) {
        val summary = listOf(
            getString(R.string.field_type)      to decodeType(item.type),
            getString(R.string.field_color)     to decodeColor(item.color),
            getString(R.string.field_pattern)   to decodePattern(item.pattern),
            getString(R.string.field_size)      to item.size,
            getString(R.string.field_season)    to decodeSeason(item.season),
            getString(R.string.field_formality) to decodeFormality(item.formality),
            getString(R.string.field_material)  to item.material,
            getString(R.string.field_wash)      to decodeWash(item.wash),
            getString(R.string.field_drying)    to decodeDry(item.dry),
            getString(R.string.field_ironing)   to decodeIron(item.iron),
            getString(R.string.field_bleaching) to decodeYesNo(item.bleach),
            getString(R.string.field_dry_clean) to decodeYesNo(item.dryClean),
            getString(R.string.field_notes)     to item.notes
        ).filter { it.second.isNotEmpty() }.joinToString("\n") { "${it.first}: ${it.second}" }
        val title = if (item.isLegacy) "⚠ ${item.name}" else item.name
        val body  = if (item.isLegacy) "$summary\n\n${getString(R.string.wardrobe_legacy_rewrite)}" else summary
        AlertDialog.Builder(requireContext())
            .setTitle(title).setMessage(body)
            .setPositiveButton(getString(R.string.dialog_delete_confirm)) { _, _ -> showDeleteDialog(item) }
            .setNegativeButton(getString(R.string.dialog_close), null).show()
    }

    private fun showDeleteDialog(item: WardrobeItem) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.dialog_delete_title))
            .setMessage(getString(R.string.dialog_delete_message, item.name))
            .setPositiveButton(getString(R.string.dialog_delete_confirm)) { _, _ ->
                items.remove(item); saveItems(); updateLegacyBanner(); applyFilters()
                updateStatus(getString(R.string.wardrobe_status_count, items.size))
                tvStatus.announceForAccessibility(getString(R.string.dialog_item_removed_accessibility, item.name))
            }
            .setNegativeButton(getString(R.string.dialog_cancel), null).show()
    }

    // ── Filtering ─────────────────────────────────────────────────────────────

    fun applyFilters(
        typeCode: String      = activeFilterTypeCode,
        colorHex: String      = activeFilterColorHex,
        seasonCode: String    = activeFilterSeasonCode,
        formalityCode: String = activeFilterFormalityCode
    ) {
        activeFilterTypeCode = typeCode; activeFilterColorHex = colorHex
        activeFilterSeasonCode = seasonCode; activeFilterFormalityCode = formalityCode

        filteredItems = items.filter { item ->
            if (item.isLegacy) return@filter true
            val typeOk      = typeCode.isEmpty()     || item.type.equals(typeCode, ignoreCase = true)
            val colorOk     = colorHex.isEmpty()     || item.color.equals(colorHex, ignoreCase = true)
            val seasonOk    = seasonCode.isEmpty()   || item.season.equals("AS", ignoreCase = true) ||
                    seasonContains(item.season, seasonCode)
            val formalityOk = formalityCode.isEmpty() || item.formality.equals(formalityCode, ignoreCase = true)
            typeOk && colorOk && seasonOk && formalityOk
        }.toMutableList()

        adapter.notifyDataSetChanged()

        val activeList = listOfNotNull(
            decodeType(typeCode).takeIf { typeCode.isNotEmpty() },
            decodeColor(colorHex).takeIf { colorHex.isNotEmpty() },
            decodeSeason(seasonCode).takeIf { seasonCode.isNotEmpty() },
            decodeFormality(formalityCode).takeIf { formalityCode.isNotEmpty() }
        )
        val filterText = if (activeList.isEmpty()) getString(R.string.wardrobe_no_filters)
        else getString(R.string.wardrobe_active_filters, activeList.joinToString(", "))
        tvActiveFilters.text = filterText
        tvActiveFilters.announceForAccessibility(
            "$filterText. ${getString(R.string.wardrobe_items_shown, filteredItems.size)}"
        )
        updateStatus(getString(R.string.wardrobe_status_shown, filteredItems.size))
    }

    private fun seasonContains(itemSeason: String, filterSeason: String): Boolean {
        val components = parseSeasonComponents(itemSeason) ?: return false
        return components.any { it.equals(filterSeason, ignoreCase = true) }
    }

    private fun updateLegacyBanner() {
        val legacyCount = items.count { it.isLegacy }
        if (legacyCount > 0) {
            val msg = getString(R.string.wardrobe_legacy_banner, legacyCount)
            tvLegacyBanner.text = msg
            tvLegacyBanner.contentDescription = getString(R.string.wardrobe_legacy_banner_description, legacyCount)
            tvLegacyBanner.visibility = View.VISIBLE
        } else {
            tvLegacyBanner.visibility = View.GONE
        }
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private fun saveItems() {
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(JSONObject().apply {
                put("id", item.id); put("name", item.name); put("type", item.type)
                put("color", item.color); put("pattern", item.pattern); put("size", item.size)
                put("formality", item.formality); put("season", item.season)
                put("material", item.material); put("wash", item.wash); put("dry", item.dry)
                put("iron", item.iron); put("bleach", item.bleach); put("dryClean", item.dryClean)
                put("notes", item.notes); put("isLegacy", item.isLegacy)
            })
        }
        requireContext().getSharedPreferences("wardrobe", Context.MODE_PRIVATE)
            .edit().putString("items", arr.toString()).apply()
    }

    private fun loadItems() {
        val prefs = requireContext().getSharedPreferences("wardrobe", Context.MODE_PRIVATE)
        val arr   = JSONArray(prefs.getString("items", "[]") ?: "[]")
        items.clear()
        for (i in 0 until arr.length()) {
            val obj  = arr.getJSONObject(i)
            val item = WardrobeItem(
                id = obj.getLong("id"), name = obj.optString("name"),
                type = obj.optString("type"), color = obj.optString("color"),
                pattern = obj.optString("pattern"), size = obj.optString("size"),
                formality = obj.optString("formality"), season = obj.optString("season"),
                material = obj.optString("material"), wash = obj.optString("wash"),
                dry = obj.optString("dry"), iron = obj.optString("iron"),
                bleach = obj.optString("bleach"), dryClean = obj.optString("dryClean"),
                notes = obj.optString("notes"), isLegacy = obj.optBoolean("isLegacy", false)
            )
            if (!obj.has("isLegacy")) {
                item.isLegacy = mapOf(
                    "T" to item.type, "P" to item.pattern, "CL" to item.color,
                    "F" to item.formality, "SE" to item.season, "W" to item.wash,
                    "D" to item.dry, "I" to item.iron, "B" to item.bleach, "C" to item.dryClean
                ).any { (k, v) -> isLegacyValue(k, v) }
            }
            items.add(item)
        }
    }

    private fun updateStatus(message: String) {
        tvStatus.text = message; tvStatus.contentDescription = "Status: $message"
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    inner class WardrobeAdapter : BaseAdapter() {
        override fun getCount() = filteredItems.size
        override fun getItem(pos: Int) = filteredItems[pos]
        override fun getItemId(pos: Int) = filteredItems[pos].id

        override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(requireContext())
                .inflate(R.layout.item_wardrobe, parent, false)
            val item          = filteredItems[pos]
            val tvName        = view.findViewById<TextView>(R.id.tv_item_name)
            val tvCategory    = view.findViewById<TextView>(R.id.tv_item_category)
            val colorDot      = view.findViewById<View>(R.id.view_color_dot)
            val typeName      = decodeType(item.type)
            val colorName     = decodeColor(item.color)
            val patternName   = decodePattern(item.pattern)
            val seasonName    = decodeSeason(item.season)
            val formalityName = decodeFormality(item.formality)

            tvName.text = if (item.isLegacy) "⚠ ${item.name}" else item.name
            tvCategory.text = listOfNotNull(
                typeName.takeIf { it.isNotEmpty() }, colorName.takeIf { it.isNotEmpty() },
                patternName.takeIf { it.isNotEmpty() },
                item.size.takeIf { it.isNotEmpty() }?.let { getString(R.string.size_label, it) },
                seasonName.takeIf { it.isNotEmpty() }, formalityName.takeIf { it.isNotEmpty() }
            ).joinToString(" · ")

            try {
                val hex = if (item.color.length == 6) "#${item.color}" else "#607D8B"
                colorDot.setBackgroundColor(Color.parseColor(hex))
            } catch (e: Exception) { colorDot.setBackgroundColor(Color.parseColor("#607D8B")) }

            view.contentDescription = "${if (item.isLegacy) "Old format. " else ""}${listOfNotNull(
                item.name, typeName.takeIf { it.isNotEmpty() }, colorName.takeIf { it.isNotEmpty() },
                item.size.takeIf { it.isNotEmpty() }?.let { getString(R.string.size_label, it) },
                seasonName.takeIf { it.isNotEmpty() }, formalityName.takeIf { it.isNotEmpty() }
            ).joinToString(", ")}. ${getString(R.string.item_double_tap)}"

            view.isClickable = true; view.isFocusable = true
            view.setOnClickListener { showItemOptionsDialog(item) }
            return view
        }
    }
}