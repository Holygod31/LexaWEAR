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

    private data class Option(val code: String, val label: String)

    private val typeOptions get() = listOf(
        Option("",   getString(R.string.filter_all_types)),
        Option("SH", getString(R.string.type_shirt)),
        Option("TS", getString(R.string.type_tshirt)),
        Option("JK", getString(R.string.type_jacket)),
        Option("CT", getString(R.string.type_coat)),
        Option("SW", getString(R.string.type_sweater)),
        Option("HD", getString(R.string.type_hoodie)),
        Option("BZ", getString(R.string.type_blazer)),
        Option("SU", getString(R.string.type_suit)),
        Option("VS", getString(R.string.type_vest)),
        Option("DR", getString(R.string.type_dress)),
        Option("UW", getString(R.string.type_underwear)),
        Option("PT", getString(R.string.type_pants)),
        Option("JN", getString(R.string.type_jeans)),
        Option("ST", getString(R.string.type_shorts)),
        Option("SK", getString(R.string.type_skirt)),
        Option("SC", getString(R.string.type_socks))
    )

    private val colorOptions get() = listOf(
        Option("",       getString(R.string.filter_all_colors)),
        Option("212121", getString(R.string.color_black)),
        Option("F5F5F5", getString(R.string.color_white)),
        Option("9E9E9E", getString(R.string.color_grey)),
        Option("1A237E", getString(R.string.color_navy)),
        Option("2196F3", getString(R.string.color_blue)),
        Option("F44336", getString(R.string.color_red)),
        Option("4CAF50", getString(R.string.color_green)),
        Option("FFEB3B", getString(R.string.color_yellow)),
        Option("FF9800", getString(R.string.color_orange)),
        Option("E91E63", getString(R.string.color_pink)),
        Option("9C27B0", getString(R.string.color_purple)),
        Option("795548", getString(R.string.color_brown)),
        Option("D7CCC8", getString(R.string.color_beige)),
        Option("FF5722", getString(R.string.color_multicolor))
    )

    private val seasonOptions get() = listOf(
        Option("",   getString(R.string.filter_all_seasons)),
        Option("SP", getString(R.string.season_spring)),
        Option("SU", getString(R.string.season_summer)),
        Option("A",  getString(R.string.season_autumn)),
        Option("W",  getString(R.string.season_winter))
    )

    private val formalityOptions get() = listOf(
        Option("",   getString(R.string.filter_all_formality)),
        Option("C",  getString(R.string.formality_casual)),
        Option("SC", getString(R.string.formality_smart_casual)),
        Option("BC", getString(R.string.formality_business_casual)),
        Option("B",  getString(R.string.formality_business)),
        Option("SF", getString(R.string.formality_smart_formal)),
        Option("F",  getString(R.string.formality_formal)),
        Option("S",  getString(R.string.formality_sport)),
        Option("L",  getString(R.string.formality_lounge))
    )

    private var typeIndex      = 0
    private var colorIndex     = 0
    private var seasonIndex    = 0
    private var formalityIndex = 0

    // Pending codes stored by restoreFilters() if called before onCreateView.
    // Resolved to indices in onCreateView once getString() is safe to call.
    private var pendingTypeCode      = ""
    private var pendingColorCode     = ""
    private var pendingSeasonCode    = ""
    private var pendingFormalityCode = ""
    private var hasPendingRestore    = false

    /**
     * Called by MainActivity to restore active filter state when the tab is
     * opened. May be called before or after onCreateView, so we store the raw
     * codes and resolve them to indices lazily in onCreateView.
     */
    fun restoreFilters(
        typeCode: String,
        colorHex: String,
        seasonCode: String,
        formalityCode: String
    ) {
        pendingTypeCode      = typeCode
        pendingColorCode     = colorHex
        pendingSeasonCode    = seasonCode
        pendingFormalityCode = formalityCode
        hasPendingRestore    = true

        // If the fragment is already attached (tab revisited), apply immediately.
        if (isAdded) applyPendingRestore()
    }

    /** Resolves stored codes to list indices. Only safe to call after attach. */
    private fun applyPendingRestore() {
        typeIndex      = typeOptions.indexOfFirst {
            it.code.equals(pendingTypeCode, ignoreCase = true)
        }.takeIf { it >= 0 } ?: 0

        colorIndex     = colorOptions.indexOfFirst {
            it.code.equals(pendingColorCode, ignoreCase = true)
        }.takeIf { it >= 0 } ?: 0

        seasonIndex    = seasonOptions.indexOfFirst {
            it.code.equals(pendingSeasonCode, ignoreCase = true)
        }.takeIf { it >= 0 } ?: 0

        formalityIndex = formalityOptions.indexOfFirst {
            it.code.equals(pendingFormalityCode, ignoreCase = true)
        }.takeIf { it >= 0 } ?: 0

        hasPendingRestore = false
        updateAllDisplays()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_filter, container, false)

        tvTypeValue      = view.findViewById(R.id.tv_type_value)
        tvColorValue     = view.findViewById(R.id.tv_color_value)
        tvSeasonValue    = view.findViewById(R.id.tv_season_value)
        tvFormalityValue = view.findViewById(R.id.tv_formality_value)

        // Resolve any codes that arrived before the fragment was attached.
        if (hasPendingRestore) applyPendingRestore()

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