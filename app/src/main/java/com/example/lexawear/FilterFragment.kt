package com.example.lexawear

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment

class FilterFragment : Fragment() {

    private lateinit var tvTypeValue: TextView
    private lateinit var tvColorValue: TextView
    private lateinit var tvSeasonValue: TextView
    private lateinit var tvFormalityValue: TextView

    // Each option: code stored, label displayed. Empty code = "All X".
    private data class Option(val code: String, val label: String)

    private val typeOptions = listOf(
        Option("",   "All Types"),
        Option("SH", "Shirt"),
        Option("TS", "T-Shirt"),
        Option("JK", "Jacket"),
        Option("CT", "Coat"),
        Option("SW", "Sweater"),
        Option("HD", "Hoodie"),
        Option("BZ", "Blazer"),
        Option("SU", "Suit"),
        Option("VS", "Vest"),
        Option("DR", "Dress"),
        Option("UW", "Underwear"),
        Option("PT", "Pants"),
        Option("JN", "Jeans"),
        Option("ST", "Shorts"),
        Option("SK", "Skirt"),
        Option("SC", "Socks")
    )

    private val colorOptions = listOf(
        Option("",       "All Colors"),
        Option("212121", "Black"),
        Option("F5F5F5", "White"),
        Option("9E9E9E", "Grey"),
        Option("1A237E", "Navy"),
        Option("2196F3", "Blue"),
        Option("F44336", "Red"),
        Option("4CAF50", "Green"),
        Option("FFEB3B", "Yellow"),
        Option("FF9800", "Orange"),
        Option("E91E63", "Pink"),
        Option("9C27B0", "Purple"),
        Option("795548", "Brown"),
        Option("D7CCC8", "Beige"),
        Option("FF5722", "Multicolor")
    )

    private val seasonOptions = listOf(
        Option("",   "All Seasons"),
        Option("SP", "Spring"),
        Option("SU", "Summer"),
        Option("A",  "Autumn"),
        Option("W",  "Winter")
    )

    private val formalityOptions = listOf(
        Option("",   "All Formality"),
        Option("C",  "Casual"),
        Option("SC", "Smart Casual"),
        Option("BC", "Business Casual"),
        Option("B",  "Business"),
        Option("SF", "Smart Formal"),
        Option("F",  "Formal"),
        Option("S",  "Sport"),
        Option("L",  "Lounge")
    )

    private var typeIndex      = 0
    private var colorIndex     = 0
    private var seasonIndex    = 0
    private var formalityIndex = 0

    fun restoreFilters(typeCode: String, colorHex: String, seasonCode: String, formalityCode: String) {
        typeIndex      = typeOptions.indexOfFirst { it.code.equals(typeCode, ignoreCase = true) }.takeIf { it >= 0 } ?: 0
        colorIndex     = colorOptions.indexOfFirst { it.code.equals(colorHex, ignoreCase = true) }.takeIf { it >= 0 } ?: 0
        seasonIndex    = seasonOptions.indexOfFirst { it.code.equals(seasonCode, ignoreCase = true) }.takeIf { it >= 0 } ?: 0
        formalityIndex = formalityOptions.indexOfFirst { it.code.equals(formalityCode, ignoreCase = true) }.takeIf { it >= 0 } ?: 0
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_filter, container, false)

        tvTypeValue      = view.findViewById(R.id.tv_type_value)
        tvColorValue     = view.findViewById(R.id.tv_color_value)
        tvSeasonValue    = view.findViewById(R.id.tv_season_value)
        tvFormalityValue = view.findViewById(R.id.tv_formality_value)

        updateAllDisplays()

        setupCarousel(
            view.findViewById(R.id.btn_type_prev),
            view.findViewById(R.id.btn_type_next),
            tvTypeValue, typeOptions,
            { typeIndex }
        ) { typeIndex = it }

        setupCarousel(
            view.findViewById(R.id.btn_color_prev),
            view.findViewById(R.id.btn_color_next),
            tvColorValue, colorOptions,
            { colorIndex }
        ) { colorIndex = it }

        setupCarousel(
            view.findViewById(R.id.btn_season_prev),
            view.findViewById(R.id.btn_season_next),
            tvSeasonValue, seasonOptions,
            { seasonIndex }
        ) { seasonIndex = it }

        setupCarousel(
            view.findViewById(R.id.btn_formality_prev),
            view.findViewById(R.id.btn_formality_next),
            tvFormalityValue, formalityOptions,
            { formalityIndex }
        ) { formalityIndex = it }

        view.findViewById<Button>(R.id.btn_filter_apply).setOnClickListener {
            (activity as? MainActivity)?.applyFiltersFromFilter(
                typeOptions[typeIndex].code,
                colorOptions[colorIndex].code,
                seasonOptions[seasonIndex].code,
                formalityOptions[formalityIndex].code
            )
        }

        view.findViewById<Button>(R.id.btn_filter_clear).setOnClickListener {
            typeIndex = 0; colorIndex = 0; seasonIndex = 0; formalityIndex = 0
            updateAllDisplays()
            (activity as? MainActivity)?.applyFiltersFromFilter("", "", "", "")
        }

        return view
    }

    private fun setupCarousel(
        btnPrev: Button,
        btnNext: Button,
        tvValue: TextView,
        options: List<Option>,
        getIndex: () -> Int,
        setIndex: (Int) -> Unit
    ) {
        tvValue.text = options[getIndex()].label

        btnPrev.setOnClickListener {
            val newIndex = if (getIndex() == 0) options.size - 1 else getIndex() - 1
            setIndex(newIndex)
            tvValue.text = options[newIndex].label
            tvValue.announceForAccessibility(options[newIndex].label)
        }

        btnNext.setOnClickListener {
            val newIndex = (getIndex() + 1) % options.size
            setIndex(newIndex)
            tvValue.text = options[newIndex].label
            tvValue.announceForAccessibility(options[newIndex].label)
        }
    }

    private fun updateAllDisplays() {
        if (!::tvTypeValue.isInitialized) return
        tvTypeValue.text      = typeOptions[typeIndex].label
        tvColorValue.text     = colorOptions[colorIndex].label
        tvSeasonValue.text    = seasonOptions[seasonIndex].label
        tvFormalityValue.text = formalityOptions[formalityIndex].label
    }
}