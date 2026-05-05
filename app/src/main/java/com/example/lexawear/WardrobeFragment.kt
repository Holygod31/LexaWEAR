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
    var notes: String
)

class WardrobeFragment : Fragment() {

    var pendingAddName: String? = null
    var pendingFilters: Array<String>? = null

    private lateinit var tvStatus: TextView
    private lateinit var tvActiveFilters: TextView
    private lateinit var btnScan: Button
    private lateinit var btnOpenFilters: Button
    private lateinit var btnVoiceFilter: Button
    private lateinit var listView: ListView

    private var nfcAdapter: NfcAdapter? = null
    private var isScanning = false
    private val items = mutableListOf<WardrobeItem>()
    private var filteredItems = mutableListOf<WardrobeItem>()
    private lateinit var adapter: WardrobeAdapter

    private var activeFilterType      = "All Types"
    private var activeFilterColor     = "All Colors"
    private var activeFilterSeason    = "All Seasons"
    private var activeFilterFormality = "All Formality"

    private val colorMap = mapOf(
        "Red" to "#F44336", "Blue" to "#2196F3",
        "Green" to "#4CAF50", "Black" to "#212121",
        "White" to "#F5F5F5", "Grey" to "#9E9E9E",
        "Yellow" to "#FFEB3B", "Orange" to "#FF9800",
        "Purple" to "#9C27B0", "Pink" to "#E91E63",
        "Brown" to "#795548", "Beige" to "#D7CCC8",
        "Navy" to "#1A237E", "Multicolor" to "#FF5722",
        "Other" to "#607D8B"
    )

    private val voiceFilterLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()?.lowercase() ?: return@registerForActivityResult

            activeFilterType = when {
                "jacket" in spoken -> "Jacket"
                "shirt" in spoken -> "Shirt"
                "t-shirt" in spoken || "tshirt" in spoken -> "T-Shirt"
                "sweater" in spoken -> "Sweater"
                "coat" in spoken -> "Coat"
                "pants" in spoken || "trousers" in spoken -> "Pants"
                "shorts" in spoken -> "Shorts"
                "dress" in spoken -> "Dress"
                "skirt" in spoken -> "Skirt"
                else -> "All Types"
            }
            activeFilterColor = when {
                "black" in spoken -> "Black"
                "white" in spoken -> "White"
                "grey" in spoken || "gray" in spoken -> "Grey"
                "navy" in spoken -> "Navy"
                "blue" in spoken -> "Blue"
                "red" in spoken -> "Red"
                "green" in spoken -> "Green"
                "yellow" in spoken -> "Yellow"
                "orange" in spoken -> "Orange"
                "pink" in spoken -> "Pink"
                "purple" in spoken -> "Purple"
                "brown" in spoken -> "Brown"
                "beige" in spoken -> "Beige"
                else -> "All Colors"
            }
            activeFilterSeason = when {
                "spring" in spoken -> "Spring"
                "summer" in spoken -> "Summer"
                "autumn" in spoken || "fall" in spoken -> "Autumn"
                "winter" in spoken -> "Winter"
                "all season" in spoken || "all-season" in spoken -> "All-Season"
                else -> "All Seasons"
            }
            activeFilterFormality = when {
                "formal" in spoken && "smart" !in spoken -> "Formal"
                "smart" in spoken -> "Smart Casual"
                "casual" in spoken -> "Casual"
                else -> "All Formality"
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
        btnScan         = view.findViewById(R.id.btn_wardrobe_scan)
        btnOpenFilters  = view.findViewById(R.id.btn_open_filters)
        btnVoiceFilter  = view.findViewById(R.id.btn_voice_filter)
        listView        = view.findViewById(R.id.list_wardrobe)

        nfcAdapter = NfcAdapter.getDefaultAdapter(requireContext())

        loadItems()

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
                    activeFilterType, activeFilterColor,
                    activeFilterSeason, activeFilterFormality
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

            val tagData = parseTagData(raw)
            val item = WardrobeItem(
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
                notes     = tagData["X"]  ?: ""
            )

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
            "Type"      to item.type,
            "Color"     to item.color,
            "Size"      to item.size,
            "Season"    to item.season,
            "Formality" to item.formality,
            "Material"  to item.material
        ).filter { it.second.isNotEmpty() }
            .joinToString("\n") { "${it.first}: ${it.second}" }

        AlertDialog.Builder(requireContext())
            .setTitle("Add to Wardrobe?")
            .setMessage(summary)
            .setPositiveButton("Add") { _, _ ->
                items.add(item)
                saveItems()
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
            "Type"      to item.type,
            "Color"     to item.color,
            "Size"      to item.size,
            "Season"    to item.season,
            "Formality" to item.formality,
            "Material"  to item.material,
            "Wash"      to item.wash,
            "Dry"       to item.dry,
            "Iron"      to item.iron,
            "Notes"     to item.notes
        ).filter { it.second.isNotEmpty() }
            .joinToString("\n") { "${it.first}: ${it.second}" }

        AlertDialog.Builder(requireContext())
            .setTitle(item.name)
            .setMessage(summary)
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
                applyFilters()
                updateStatus("${items.size} item(s) in wardrobe.")
                tvStatus.announceForAccessibility("${item.name} removed from wardrobe.")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun applyFilters(
        type: String = activeFilterType,
        color: String = activeFilterColor,
        season: String = activeFilterSeason,
        formality: String = activeFilterFormality
    ) {
        activeFilterType      = type
        activeFilterColor     = color
        activeFilterSeason    = season
        activeFilterFormality = formality

        filteredItems = items.filter { item ->
            (type == "All Types"          || item.type.equals(type, ignoreCase = true)) &&
                    (color == "All Colors"        || item.color.equals(color, ignoreCase = true)) &&
                    (season == "All Seasons"      || item.season.equals(season, ignoreCase = true)) &&
                    (formality == "All Formality" || item.formality.equals(formality, ignoreCase = true))
        }.toMutableList()

        adapter.notifyDataSetChanged()

        val activeList = listOf(
            type.takeIf { it != "All Types" },
            color.takeIf { it != "All Colors" },
            season.takeIf { it != "All Seasons" },
            formality.takeIf { it != "All Formality" }
        ).filterNotNull()

        val filterText = if (activeList.isEmpty()) "No filters active"
        else "Filters: ${activeList.joinToString(", ")}"

        tvActiveFilters.text = filterText
        tvActiveFilters.announceForAccessibility(
            "$filterText. ${filteredItems.size} items shown."
        )
        updateStatus("${filteredItems.size} item(s) shown.")
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
            items.add(WardrobeItem(
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
                notes     = obj.optString("notes")
            ))
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

            tvName.text = item.name
            tvCategory.text = listOf(item.type, item.color, item.size)
                .filter { it.isNotEmpty() }
                .joinToString(" · ")

            val hexColor = colorMap[item.color] ?: "#607D8B"
            colorDot.setBackgroundColor(Color.parseColor(hexColor))

            view.contentDescription =
                "${item.name}, ${item.type}, ${item.color}, size ${item.size}. Double tap for details."
            view.isClickable = true
            view.isFocusable = true
            view.setOnClickListener { showItemOptionsDialog(item) }

            return view
        }
    }
}