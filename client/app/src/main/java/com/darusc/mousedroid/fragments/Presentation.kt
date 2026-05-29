package com.darusc.mousedroid.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.darusc.mousedroid.R
import com.darusc.mousedroid.databinding.FragmentPresentationBinding
import com.darusc.mousedroid.layouts.Keycode
import com.darusc.mousedroid.viewmodels.KeyboardViewModel

class Presentation : Fragment() {

    private lateinit var binding: FragmentPresentationBinding
    private val keyboardViewModel: KeyboardViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = DataBindingUtil.inflate(inflater, R.layout.fragment_presentation, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnPreviousSlide.setOnClickListener { keyboardViewModel.sendKey(Keycode.KEY_LEFT) }
        binding.btnNextSlide.setOnClickListener { keyboardViewModel.sendKey(Keycode.KEY_RIGHT) }
        binding.btnStartPresentation.setOnClickListener { keyboardViewModel.sendKey(Keycode.KEY_F5) }
        binding.btnExitPresentation.setOnClickListener { keyboardViewModel.sendKey(Keycode.KEY_ESC) }
        binding.btnBlankPresentation.setOnClickListener { keyboardViewModel.sendKey(Keycode.KEY_B) }
        binding.btnPointer.setOnClickListener { keyboardViewModel.sendKey(Keycode.KEY_L) }
    }
}
