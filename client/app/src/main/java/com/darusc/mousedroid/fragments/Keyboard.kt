package com.darusc.mousedroid.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.darusc.mousedroid.R
import com.darusc.mousedroid.databinding.FragmentKeyboardBinding
import com.darusc.mousedroid.layouts.Keycode
import com.darusc.mousedroid.viewmodels.KeyboardViewModel

class Keyboard : Fragment() {

    private lateinit var binding: FragmentKeyboardBinding
    private val viewModel: KeyboardViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = DataBindingUtil.inflate(inflater, R.layout.fragment_keyboard, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnCopy.setOnClickListener { viewModel.sendKey(Keycode.KEY_C, Keycode.MOD_LEFT_CTRL) }
        binding.btnPaste.setOnClickListener { viewModel.sendKey(Keycode.KEY_V, Keycode.MOD_LEFT_CTRL) }
        binding.btnUndo.setOnClickListener { viewModel.sendKey(Keycode.KEY_Z, Keycode.MOD_LEFT_CTRL) }
        binding.btnSelectAll.setOnClickListener { viewModel.sendKey(Keycode.KEY_A, Keycode.MOD_LEFT_CTRL) }
        binding.btnTab.setOnClickListener { viewModel.sendKey(Keycode.KEY_TAB) }
        binding.btnEsc.setOnClickListener { viewModel.sendKey(Keycode.KEY_ESC) }
        binding.btnAltTab.setOnClickListener { viewModel.sendKey(Keycode.KEY_TAB, Keycode.MOD_LEFT_ALT) }
        binding.btnClose.setOnClickListener { viewModel.sendKey(Keycode.KEY_F4, Keycode.MOD_LEFT_ALT) }
        binding.btnEnter.setOnClickListener { viewModel.sendKey(Keycode.KEY_ENTER) }

        binding.btnUp.setOnClickListener { viewModel.sendKey(Keycode.KEY_UP) }
        binding.btnDown.setOnClickListener { viewModel.sendKey(Keycode.KEY_DOWN) }
        binding.btnLeft.setOnClickListener { viewModel.sendKey(Keycode.KEY_LEFT) }
        binding.btnRight.setOnClickListener { viewModel.sendKey(Keycode.KEY_RIGHT) }
    }
}
