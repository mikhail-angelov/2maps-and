package com.bconf.a2maps_and.placemark

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.bconf.a2maps_and.databinding.FragmentPlacemarksBinding
import com.bconf.a2maps_and.ui.viewmodel.NavigationViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class PlacemarksFragment : Fragment() {

    private var _binding: FragmentPlacemarksBinding? = null
    private val binding get() = _binding!!

    private lateinit var placemarkViewModel: PlacemarksViewModel
    private lateinit var navigationViewModel: NavigationViewModel
    private lateinit var placemarkAdapter: PlacemarkAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        placemarkViewModel = ViewModelProvider(this).get(PlacemarksViewModel::class.java)
        navigationViewModel = ViewModelProvider(this).get(NavigationViewModel::class.java) // Shared with Activity

        _binding = FragmentPlacemarksBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        placemarkAdapter = PlacemarkAdapter { placemark ->
            Toast.makeText(context, "Clicked: ${placemark.name}", Toast.LENGTH_SHORT).show()
            val action = PlacemarksFragmentDirections.actionPlacemarksFragmentToMapFragment(
                placemarkLat = placemark.latitude.toFloat(),
                placemarkLng = placemark.longitude.toFloat(),
                placemarkId = placemark.id
            )
            findNavController().navigate(action)
        }
        binding.placemarksRecyclerView.apply {
            adapter = placemarkAdapter
            layoutManager = LinearLayoutManager(context)
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            navigationViewModel.lastKnownGpsLocation.collectLatest { location ->
                Log.d("PlacemarksFragment", "Current location updated: $location")
                placemarkViewModel.updateCurrentLocation(location) // Pass location to PlacemarksViewModel
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            placemarkViewModel.displayItems.collectLatest { displayItems ->
                Log.d("PlacemarksFragment", "Updating adapter with ${displayItems.size} display items.")
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
