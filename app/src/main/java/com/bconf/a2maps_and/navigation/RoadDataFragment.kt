package com.bconf.a2maps_and.navigation

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bconf.a2maps_and.R
import com.bconf.a2maps_and.databinding.FragmentRoadDataBinding
import kotlinx.coroutines.launch
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class RoadDataFragment : Fragment() {
    private val model: RoadDataViewModel by viewModels()
    private val importer = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { model.import(it) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FragmentRoadDataBinding.inflate(inflater, container, false).root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentRoadDataBinding.bind(view)
        binding.importRoadData.setOnClickListener { importer.launch(arrayOf("*/*")) }
        binding.downloadRoadData.setOnClickListener { chooseRegions() }
        binding.cancelRoadDataDownload.setOnClickListener { model.cancelDownload() }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { state ->
                    binding.importRoadData.isEnabled = !state.busy
                    binding.downloadRoadData.isEnabled = !state.busy && state.regions.isNotEmpty()
                    binding.roadDataRegion.text = state.regions.firstOrNull()?.let {
                        getString(R.string.road_data_current_region, it.name)
                    } ?: getString(R.string.location_unknown)
                    val progress = state.progress
                    binding.cancelRoadDataDownload.isVisible = progress != null
                    binding.roadDataProgress.isVisible = progress != null
                    binding.roadDataProgress.isIndeterminate = progress == null || progress.total <= 0
                    if (progress != null && progress.total > 0) {
                        binding.roadDataProgress.progress = (progress.received * 100 / progress.total).toInt().coerceIn(0, 100)
                    }
                    binding.roadDataStatus.text = when {
                        progress != null -> if (progress.total > 0) getString(R.string.road_data_downloading,
                            progress.name, progress.received / 1048576.0, progress.total / 1048576.0)
                            else getString(R.string.road_data_connecting, progress.name)
                        state.busy -> getString(R.string.road_data_importing)
                        else -> state.message.orEmpty()
                    }
                    binding.roadDataFiles.text = if (state.files.isEmpty()) getString(R.string.road_data_empty)
                        else state.files.joinToString("\n\n") { "${it.name} · ${it.bytes / (1024 * 1024)} MB" }
                }
            }
        }
    }

    private fun chooseRegions() {
        val regions = model.state.value.regions
        if (regions.isEmpty()) return
        val selected = BooleanArray(regions.size) { it == 0 }
        val labels = regions.map { region ->
            val direction = when (region.direction) {
                RoadDataDirection.CURRENT -> R.string.road_region_current
                RoadDataDirection.NORTH -> R.string.road_region_north
                RoadDataDirection.NORTH_EAST -> R.string.road_region_north_east
                RoadDataDirection.EAST -> R.string.road_region_east
                RoadDataDirection.SOUTH_EAST -> R.string.road_region_south_east
                RoadDataDirection.SOUTH -> R.string.road_region_south
                RoadDataDirection.SOUTH_WEST -> R.string.road_region_south_west
                RoadDataDirection.WEST -> R.string.road_region_west
                RoadDataDirection.NORTH_WEST -> R.string.road_region_north_west
            }
            getString(direction) + " · " + region.name
        }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.road_data_choose_regions)
            .setMultiChoiceItems(labels, selected) { _, index, checked -> selected[index] = checked }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.road_data_download) { _, _ ->
                model.download(regions.filterIndexed { index, _ -> selected[index] }.map { it.name })
            }.show()
    }
}
