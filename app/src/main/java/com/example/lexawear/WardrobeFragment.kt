package com.example.lexawear

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.NdefRecord
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import org.json.JSONArray
import org.json.JSONObject

data class WardrobeItem(
    val id: Long,
    var name: String,
    var category: String,
    var color: String
)

class WardrobeFragment : Fragment() {

    var pendingAddName: String? = null
    private lateinit var tvStatus: TextView
    private lateinit var btnScan: Button
    private lateinit var listView: ListView

    private var nfcAdapter: NfcAdapter? = null
    private var isScanning = false
    private val items = mutableListOf<WardrobeItem>()
    private lateinit var adapter: WardrobeAdapter

    private val categories = listOf(
        "T-Shirt", "Shirt", "Jacket", "Coat", "Sweater",
        "Trousers", "Jeans", "Shorts", "Dress", "Skirt",
        "Shoes", "Accessory", "Other"
    )

    private val colors = listOf(
        "Red", "Blue", "Green", "Black", "White",
        "Grey", "Yellow", "Orange", "Purple", "Pink",
        "Brown", "Beige", "Navy", "Other"
    )

    private val colorMap = mapOf(
        "Red" to "#F44336", "Blue" to "#2196F3",
        "Green" to "#4CAF50", "Black" to "#212121",
        "White" to "#F5F5F5", "Grey" to "#9E9E9E",
        "Yellow" to "#FFEB3B", "Orange" to "#FF9800",
        "Purple" to "#9C27B0", "Pink" to "#E91E63",
        "Brown" to "#795548", "Beige" to "#D7CCC8",
        "Navy" to "#1A237E", "Other" to "#607D8B"
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_wardrobe, container, false)

        tvStatus = view.findViewById(R.id.tv_wardrobe_status)
        btnScan = view.findViewById(R.id.btn_wardrobe_scan)
        listView = view.findViewById(R.id.list_wardrobe)

        nfcAdapter = NfcAdapter.getDefaultAdapter(requireContext())

        loadItems()

        adapter = WardrobeAdapter()
        listView.adapter = adapter

        when {
            nfcAdapter == null -> updateStatus("This device does not support NFC.")
            !nfcAdapter!!.isEnabled -> updateStatus("NFC is off. Please enable it in Settings.")
            else -> updateStatus("${items.size} item(s) in wardrobe. Scan a tag to add.")
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

            val name = raw.split("|")
                .firstOrNull { it.startsWith("N:") }
                ?.removePrefix("N:") ?: "Unknown Item"

            requireActivity().runOnUiThread {
                isScanning = false
                showAddDialog(name)
            }

        } catch (e: Exception) {
            requireActivity().runOnUiThread {
                updateStatus("Error reading tag: ${e.message}")
                isScanning = false
            }
        }
    }

    fun showAddDialog(nameFromTag: String) {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(android.R.layout.simple_list_item_1, null)

        var selectedCategory = categories[0]
        var selectedColor = colors[0]

        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }

        val etName = EditText(requireContext()).apply {
            setText(nameFromTag)
            hint = "Item name"
            textSize = 16f
            contentDescription = "Item name"
        }

        val tvCategory = TextView(requireContext()).apply {
            text = "Category"
            textSize = 14f
            setPadding(0, 16, 0, 4)
        }

        val spinnerCategory = Spinner(requireContext())
        val catAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            categories
        )
        catAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerCategory.adapter = catAdapter
        spinnerCategory.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                selectedCategory = categories[pos]
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        val tvColor = TextView(requireContext()).apply {
            text = "Color"
            textSize = 14f
            setPadding(0, 16, 0, 4)
        }

        val spinnerColor = Spinner(requireContext())
        val colorAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            colors
        )
        colorAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerColor.adapter = colorAdapter
        spinnerColor.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                selectedColor = colors[pos]
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        layout.addView(etName)
        layout.addView(tvCategory)
        layout.addView(spinnerCategory)
        layout.addView(tvColor)
        layout.addView(spinnerColor)

        AlertDialog.Builder(requireContext())
            .setTitle("Add to Wardrobe")
            .setView(layout)
            .setPositiveButton("Add") { _, _ ->
                val name = etName.text.toString().trim().ifEmpty { nameFromTag }
                val item = WardrobeItem(
                    id = System.currentTimeMillis(),
                    name = name,
                    category = selectedCategory,
                    color = selectedColor
                )
                items.add(item)
                saveItems()
                adapter.notifyDataSetChanged()
                updateStatus("${items.size} item(s) in wardrobe.")
                tvStatus.announceForAccessibility("$name added to wardrobe.")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun showAddDialogPublic(name: String) {
        showAddDialog(name)
    }

    private fun showItemOptionsDialog(item: WardrobeItem) {
        AlertDialog.Builder(requireContext())
            .setTitle(item.name)
            .setMessage("${item.category} · ${item.color}")
            .setPositiveButton("Edit") { _, _ ->
                showEditDialog(item)
            }
            .setNegativeButton("Delete") { _, _ ->
                showDeleteDialog(item)
            }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun showEditDialog(item: WardrobeItem) {
        var selectedCategory = item.category
        var selectedColor = item.color

        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }

        val etName = EditText(requireContext()).apply {
            setText(item.name)
            hint = "Item name"
            textSize = 16f
            contentDescription = "Item name"
        }

        val tvCategory = TextView(requireContext()).apply {
            text = "Category"
            textSize = 14f
            setPadding(0, 16, 0, 4)
        }

        val spinnerCategory = Spinner(requireContext())
        val catAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            categories
        )
        catAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerCategory.adapter = catAdapter
        spinnerCategory.setSelection(categories.indexOf(item.category).takeIf { it >= 0 } ?: 0)
        spinnerCategory.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                selectedCategory = categories[pos]
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        val tvColor = TextView(requireContext()).apply {
            text = "Color"
            textSize = 14f
            setPadding(0, 16, 0, 4)
        }

        val spinnerColor = Spinner(requireContext())
        val colorAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            colors
        )
        colorAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerColor.adapter = colorAdapter
        spinnerColor.setSelection(colors.indexOf(item.color).takeIf { it >= 0 } ?: 0)
        spinnerColor.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                selectedColor = colors[pos]
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        layout.addView(etName)
        layout.addView(tvCategory)
        layout.addView(spinnerCategory)
        layout.addView(tvColor)
        layout.addView(spinnerColor)

        AlertDialog.Builder(requireContext())
            .setTitle("Edit Item")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                item.name = etName.text.toString().trim().ifEmpty { item.name }
                item.category = selectedCategory
                item.color = selectedColor
                saveItems()
                adapter.notifyDataSetChanged()
                updateStatus("${items.size} item(s) in wardrobe.")
                tvStatus.announceForAccessibility("${item.name} updated.")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteDialog(item: WardrobeItem) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete Item")
            .setMessage("Remove \"${item.name}\" from your wardrobe?")
            .setPositiveButton("Delete") { _, _ ->
                items.remove(item)
                saveItems()
                adapter.notifyDataSetChanged()
                updateStatus("${items.size} item(s) in wardrobe.")
                tvStatus.announceForAccessibility("${item.name} removed from wardrobe.")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- Persistence ---

    private fun saveItems() {
        val arr = JSONArray()
        items.forEach { item ->
            val obj = JSONObject()
            obj.put("id", item.id)
            obj.put("name", item.name)
            obj.put("category", item.category)
            obj.put("color", item.color)
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
            items.add(
                WardrobeItem(
                    id = obj.getLong("id"),
                    name = obj.getString("name"),
                    category = obj.getString("category"),
                    color = obj.getString("color")
                )
            )
        }
    }

    private fun updateStatus(message: String) {
        tvStatus.text = message
        tvStatus.contentDescription = "Status: $message"
    }

    // --- Adapter ---

    inner class WardrobeAdapter : BaseAdapter() {

        override fun getCount() = items.size
        override fun getItem(pos: Int) = items[pos]
        override fun getItemId(pos: Int) = items[pos].id

        override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(requireContext())
                .inflate(R.layout.item_wardrobe, parent, false)

            val item = items[pos]

            val tvName = view.findViewById<TextView>(R.id.tv_item_name)
            val tvCategory = view.findViewById<TextView>(R.id.tv_item_category)
            val colorDot = view.findViewById<View>(R.id.view_color_dot)

            tvName.text = item.name
            tvCategory.text = "${item.category} · ${item.color}"

            val hexColor = colorMap[item.color] ?: "#607D8B"
            colorDot.setBackgroundColor(Color.parseColor(hexColor))

            // Full item accessibility description
            view.contentDescription =
                "${item.name}, ${item.category}, ${item.color}. Double tap to edit or delete."
            view.isClickable = true
            view.isFocusable = true

            // Single tap or TalkBack double tap → show options dialog
            view.setOnClickListener {
                showItemOptionsDialog(item)
            }

            return view
        }
    }
}