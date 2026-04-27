package com.example.lexawear

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment

class NfcFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val tv = TextView(requireContext()).apply {
            text = "NFC Tag Reader / Writer"
            textSize = 24f
            contentDescription = "NFC tag screen. Read or write clothing tag data."
            setPadding(48, 48, 48, 48)
        }
        return tv
    }
}