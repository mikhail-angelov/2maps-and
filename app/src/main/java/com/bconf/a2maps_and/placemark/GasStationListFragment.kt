package com.bconf.a2maps_and.placemark

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.bconf.a2maps_and.databinding.FragmentGasStationListBinding
import com.bconf.a2maps_and.navigation.NavigationViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class GasStationListFragment : Fragment() {

    private var _binding: FragmentGasStationListBinding? = null
    private val binding get() = _binding!!

    private val placemarkViewModel: PlacemarksViewModel by activityViewModels()
    private val navigationViewModel: NavigationViewModel by activityViewModels()
    private lateinit var placemarkAdapter: PlacemarkAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGasStationListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        placemarkAdapter = PlacemarkAdapter(
            onItemClicked = { item, _ ->
                val action = PlacemarksFragmentDirections.actionPlacemarksFragmentToMapFragment(
                    placemarkLat = item.placemark.latitude.toFloat(),
                    placemarkLng = item.placemark.longitude.toFloat(),
                    placemarkId = item.placemark.id
                )
                findNavController().navigate(action)
            }
        )

        binding.placemarksRecyclerView.apply {
            adapter = placemarkAdapter
            layoutManager = LinearLayoutManager(context)
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            navigationViewModel.lastKnownGpsLocation.collectLatest { location ->
                placemarkViewModel.updateCurrentLocation(location)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            placemarkViewModel.gasStationItems.collectLatest { displayItems ->
                placemarkAdapter.submitList(displayItems)
                binding.emptyView.isVisible = displayItems.isEmpty()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.placemarksRecyclerView.adapter = null
        _binding = null
    }
}
