package com.bconf.a2maps_and.placemark

import android.app.AlertDialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.findNavController
import androidx.navigation.fragment.findNavController
import com.bconf.a2maps_and.R
import com.bconf.a2maps_and.databinding.BottomSheetPlacemarkBinding
import com.bconf.a2maps_and.ui.viewmodel.NavigationViewModel
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng

class PlacemarkModal : BottomSheetDialogFragment() {

    private var _binding: BottomSheetPlacemarkBinding? = null
    private val binding get() = _binding!!

    // Use activityViewModels to share the ViewModel with the hosting fragments
    private val placemarkViewModel: PlacemarksViewModel by activityViewModels()
    private val navigationViewModel: NavigationViewModel by activityViewModels()

    private var placemarkId: String? = null

    companion object {
        private const val ARG_PLACEMARK_ID = "placemark_id"

        fun newInstance(placemarkId: String): PlacemarkModal {
            return PlacemarkModal().apply {
                arguments = bundleOf(ARG_PLACEMARK_ID to placemarkId)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        placemarkId = arguments?.getString(ARG_PLACEMARK_ID)
        Log.d("PlacemarkModal", "Received placemark ID: $placemarkId")
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetPlacemarkBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Use a coroutine to observe the StateFlow in a lifecycle-aware manner.
        // This ensures the UI updates if the data changes while the modal is visible.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                placemarkViewModel.displayItems.collectLatest { displayItems ->
                    if (displayItems.isEmpty()) {
                        Log.w(
                            "PlacemarkModal",
                            "displayItems flow is empty, cannot find placemark."
                        )
                        return@collectLatest
                    }

                    Log.d(
                        "PlacemarkModal",
                        "Observing ${displayItems.size} items. Searching for ID: $placemarkId"
                    )
                    val displayItem = displayItems.find { it.placemark.id == placemarkId }

                    if (displayItem != null) {
                        bindPlacemarkDetails(displayItem)
                    } else {
                        Log.e(
                            "PlacemarkModal",
                            "Placemark with ID '$placemarkId' not found in displayItems."
                        )
                        // Optional: show an error message or dismiss the modal
                        // dismiss()
                    }
                }
            }
        }
    }

    private fun bindPlacemarkDetails(item: PlacemarkDisplayItem) {
        val placemark = item.placemark
        binding.placemarkName.text = placemark.name
        binding.placemarkDistance.text = item.distanceString

        if (placemark.description.isNullOrBlank()) {
            binding.placemarkDescription.isVisible = false
        } else {
            binding.placemarkDescription.text = placemark.description
            binding.placemarkDescription.isVisible = true
        }

        binding.navigateButton.setOnClickListener {
            navigationViewModel.requestNavigationTo(LatLng(placemark.latitude, placemark.longitude))
            dismiss() // Close the bottom sheet after starting navigation
        }
        binding.navigateButton.setOnClickListener {
            Log.d("PlacemarkModal", "Navigate button clicked for ${placemark.name}: ${placemark.latitude}/${placemark.longitude}")
            dismiss() // Close the modal
            // Navigate to the map screen if not already there
            if (findNavController().currentDestination?.id != R.id.mapFragment) {
                findNavController().navigate(R.id.mapFragment)
            }
            navigationViewModel.requestNavigationTo(LatLng(placemark.latitude, placemark.longitude))

        }

        // Center on map
        binding.centerButton.setOnClickListener {
            val action = PlacemarksFragmentDirections.actionPlacemarksFragmentToMapFragment(
                placemarkLat = placemark.latitude.toFloat(),
                placemarkLng = placemark.longitude.toFloat(),
                placemarkId = placemark.id
            )
            // Use the activity's NavController to navigate from the modal
//            requireActivity().findNavController(R.id.mapFragment).navigate(action)
//            val action = PlacemarksFragmentDirections.actionPlacemarksFragmentToMapFragment(
//                placemarkLat = placemark.latitude.toFloat(),
//                placemarkLng = placemark.longitude.toFloat(),
//                placemarkId = placemark.id
//            )
            findNavController().navigate(action)
            dismiss()
        }

        // Edit
        binding.editButton.setOnClickListener {
            // val action = PlacemarksFragmentDirections.actionPlacemarksFragmentToEditPlacemarkFragment(placemark.id)
            // requireActivity().findNavController(R.id.nav_host_fragment_content_main).navigate(action)
            Toast.makeText(requireContext(), "Edit not implemented yet!", Toast.LENGTH_SHORT).show()
            dismiss()
        }

        // Delete
        binding.deleteButton.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Delete Placemark")
                .setMessage("Are you sure you want to delete '${placemark.name}'?")
                .setPositiveButton("Delete") { _, _ ->
                    placemarkViewModel.deletePlacemark(placemark.id)
                    dismiss() // Close the modal after confirming deletion
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
