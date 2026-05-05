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

    private val typeOptions = listOf(
        "All Types", "Shirt", "T-Shirt", "Sweater", "Jacket", "Coat",
        "Pants", "Shorts", "Dress", "Skirt", "Underwear", "Socks", "Other"
    )
    private val colorOptions = listOf(
        "All Colors", "Black", "White", "Grey", "Navy", "Blue",
        "Red", "Green", "Yellow", "Orange", "Pink",
        "Purple", "Brown", "Beige", "Multicolor"
    )
    private val seasonOptions = listOf(
        "All Seasons", "Spring", "Summer", "Autumn", "Winter", "All-Season"
    )
    private val formalityOptions = listOf(
        "All Formality", "Casual", "Smart Casual", "Formal"
    )

    private var typeIndex      = 0
    private var colorIndex     = 0
    private var seasonIndex    = 0
    private var formalityIndex = 0

    fun restoreFilters(type: String, color: String, season: String, formality: String) {
        typeIndex      = typeOptions.indexOf(type).takeIf { it >= 0 } ?: 0
        colorIndex     = colorOptions.indexOf(color).takeIf { it >= 0 } ?: 0
        seasonIndex    = seasonOptions.indexOf(season).takeIf { it >= 0 } ?: 0
        formalityIndex = formalityOptions.indexOf(formality).takeIf { it >= 0 } ?: 0
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
                typeOptions[typeIndex],
                colorOptions[colorIndex],
                seasonOptions[seasonIndex],
                formalityOptions[formalityIndex]
            )
        }

        view.findViewById<Button>(R.id.btn_filter_clear).setOnClickListener {
            typeIndex = 0; colorIndex = 0; seasonIndex = 0; formalityIndex = 0
            updateAllDisplays()
            (activity as? MainActivity)?.applyFiltersFromFilter(
                typeOptions[0], colorOptions[0],
                seasonOptions[0], formalityOptions[0]
            )
        }

        return view
    }

    private fun setupCarousel(
        btnPrev: Button,
        btnNext: Button,
        tvValue: TextView,
        options: List<String>,
        getIndex: () -> Int,
        setIndex: (Int) -> Unit
    ) {
        tvValue.text = options[getIndex()]

        btnPrev.setOnClickListener {
            val newIndex = if (getIndex() == 0) options.size - 1 else getIndex() - 1
            setIndex(newIndex)
            tvValue.text = options[newIndex]
            tvValue.announceForAccessibility(options[newIndex])
        }

        btnNext.setOnClickListener {
            val newIndex = (getIndex() + 1) % options.size
            setIndex(newIndex)
            tvValue.text = options[newIndex]
            tvValue.announceForAccessibility(options[newIndex])
        }
    }

    private fun updateAllDisplays() {
        if (!::tvTypeValue.isInitialized) return
        tvTypeValue.text      = typeOptions[typeIndex]
        tvColorValue.text     = colorOptions[colorIndex]
        tvSeasonValue.text    = seasonOptions[seasonIndex]
        tvFormalityValue.text = formalityOptions[formalityIndex]
    }
}