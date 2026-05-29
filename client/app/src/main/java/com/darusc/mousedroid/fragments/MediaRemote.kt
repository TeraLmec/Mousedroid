package com.darusc.mousedroid.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.darusc.mousedroid.R
import com.darusc.mousedroid.databinding.FragmentMediaRemoteBinding
import com.darusc.mousedroid.viewmodels.TouchpadViewModel

class MediaRemote : Fragment() {

    private lateinit var binding: FragmentMediaRemoteBinding
    private val viewModel: TouchpadViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = DataBindingUtil.inflate(inflater, R.layout.fragment_media_remote, container, false)
        binding.viewmodel = viewModel
        binding.lifecycleOwner = viewLifecycleOwner
        return binding.root
    }
}
