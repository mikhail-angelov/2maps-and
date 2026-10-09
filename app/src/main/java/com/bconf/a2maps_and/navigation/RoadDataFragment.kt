package com.bconf.a2maps_and.navigation

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bconf.a2maps_and.R
import com.bconf.a2maps_and.databinding.FragmentRoadDataBinding
import kotlinx.coroutines.launch

class RoadDataFragment : Fragment() {
    private val model: RoadDataViewModel by viewModels()
    private val importer = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { model.import(it) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FragmentRoadDataBinding.inflate(inflater, container, false).root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentRoadDataBinding.bind(view)
        binding.importRoadData.setOnClickListener { importer.launch(arrayOf("*/*")) }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { state ->
                    binding.importRoadData.isEnabled = !state.importing
                    binding.roadDataStatus.text = if (state.importing) getString(R.string.road_data_importing) else state.message.orEmpty()
                    binding.roadDataFiles.text = if (state.files.isEmpty()) getString(R.string.road_data_empty)
                        else state.files.joinToString("\n\n") { "${it.name} · ${it.bytes / (1024 * 1024)} MB" }
                }
            }
        }
    }
}
