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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class PlacemarksFragment : Fragment() {

    private var _binding: FragmentPlacemarksBinding? = null
    private val binding get() = _binding!! // This property is only valid between onCreateView and onDestroyView.

    private lateinit var placemarkViewModel: PlacemarksViewModel

    private lateinit var placemarkAdapter: PlacemarkAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        placemarkViewModel = ViewModelProvider(this).get(PlacemarksViewModel::class.java)
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
            // Handle item click - e.g., navigate to a detail screen or show on map
            Toast.makeText(context, "Clicked: ${placemark.name}", Toast.LENGTH_SHORT).show()

            // Use Safe Args to create the navigation action with arguments
            val action = PlacemarksFragmentDirections.actionPlacemarksFragmentToMapFragment(
                placemarkLat = placemark.latitude.toFloat(),
                placemarkLng = placemark.longitude.toFloat(),
                placemarkId = placemark.id // Assuming your Placemark has an 'id'
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
            placemarkViewModel.placemarks.collectLatest { placemarks ->
                Log.d("PlacemarksFragment", "Updating adapter with ${placemarks.size} placemarks.")
                placemarkAdapter.submitList(placemarks)
                binding.emptyView.isVisible = placemarks.isEmpty()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.placemarksRecyclerView.adapter = null // Important for RecyclerView cleanup
        _binding = null // Clear ViewBinding reference
    }
}
