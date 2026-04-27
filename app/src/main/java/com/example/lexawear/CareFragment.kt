package com.example.lexawear

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment

class CareFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val tv = TextView(requireContext()).apply {
            text = "Care Instructions"
            textSize = 24f
            contentDescription = "Care instructions screen. Scan an NFC tag to read care info."
            setPadding(48, 48, 48, 48)
        }
        return tv
    }
}