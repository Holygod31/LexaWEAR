package com.example.lexawear

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment

class WardrobeFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val tv = TextView(requireContext()).apply {
            text = "Wardrobe"
            textSize = 24f
            contentDescription = "Wardrobe screen. Add, edit or delete clothing items."
            setPadding(48, 48, 48, 48)
        }
        return tv
    }
}