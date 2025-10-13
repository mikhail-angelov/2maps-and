package com.bconf.a2maps_and.placemark

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RatingBar
import android.widget.ScrollView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.bconf.a2maps_and.R
import com.bconf.a2maps_and.databinding.BottomSheetPlacemarkBinding
import com.bconf.a2maps_and.ui.viewmodel.NavigationViewModel
import com.bconf.a2maps_and.utils.PlacemarkUtils
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
                navigationViewModel.lastKnownGpsLocation.collectLatest { location ->
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

                        if (displayItem == null) {
                            Log.e(
                                "PlacemarkModal",
                                "Placemark with ID '$placemarkId' not found in displayItems."
                            )
                            // Optional: show an error message or dismiss the modal
                            Toast.makeText(
                                requireContext(),
                                "Placemark not found",
                                Toast.LENGTH_SHORT
                            ).show()
                            dismiss()
                            return@collectLatest
                        }

                        bindPlacemarkDetails(displayItem,location)
                    }
                }
            }
        }
    }

    private fun bindPlacemarkDetails(item: PlacemarkDisplayItem, location: android.location.Location?) {
        val placemark = item.placemark
        Log.d(
            "PlacemarkModal",
            "Binding details for ${placemark.name}: ${item.distanceString}/${item.distanceInMeters}"
        )
        binding.placemarkName.text = placemark.name
        binding.placemarkRating.rating = placemark.rate?.toFloat() ?: 0f // Set the rating stars
        val distanceResult = PlacemarkUtils.calculateDistance(location, placemark)
        binding.placemarkDistance.text = distanceResult.distanceString

        if (placemark.description.isNullOrBlank()) {
            binding.placemarkDescription.isVisible = false
        } else {
            binding.placemarkDescription.text = placemark.description
            binding.placemarkDescription.isVisible = true
        }

        binding.navigateButton.setOnClickListener {
            Log.d("PlacemarkModal", "Navigate button clicked for ${placemark.name}")
            // Navigate to the map screen if not already there
            if (findNavController().currentDestination?.id != R.id.mapFragment) {
                findNavController().navigate(R.id.mapFragment)
            }
            navigationViewModel.requestNavigationTo(LatLng(placemark.latitude, placemark.longitude))
            dismiss()
        }
        binding.shareButton.setOnClickListener {
            val lat = placemark.latitude
            val lng = placemark.longitude
            val placemarkName = placemark.name

            // Create a geo URI for universal handling by map apps
            val geoUri = "geo:$lat,$lng?q=$lat,$lng($placemarkName)"
            // Create the text to share
            val shareText = "Check out this location: $placemarkName\nCoordinates: $lat, $lng\n\nOpen in maps: $geoUri"

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, shareText)
                putExtra(Intent.EXTRA_SUBJECT, "Location: $placemarkName")
            }
            // Use a chooser to let the user pick an app
            startActivity(Intent.createChooser(shareIntent, "Share Location"))
        }
        // Center on map
        binding.centerButton.setOnClickListener {
            val action = PlacemarksFragmentDirections.actionPlacemarksFragmentToMapFragment(
                placemarkLat = placemark.latitude.toFloat(),
                placemarkLng = placemark.longitude.toFloat(),
                placemarkId = placemark.id
            )
            findNavController().navigate(action)
            dismiss()
        }

        // Edit
        binding.editButton.setOnClickListener {
            showEditDialog(item.placemark)
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

    private fun showEditDialog(placemark: Placemark) {
        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle("Edit Placemark")

        // --- Create the custom layout for the dialog ---
        val context = requireContext()
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }

        // Input for Name
        val nameInput = EditText(context).apply {
            hint = "Name"
            setText(placemark.name)
        }
        layout.addView(nameInput)

        // Input for Description
        val descriptionInput = EditText(context).apply {
            hint = "Description"
            setText(placemark.description)
        }
        layout.addView(descriptionInput)

        // Input for Rate (using a RatingBar)
        val rateInput = RatingBar(context).apply {
            numStars = 5
            stepSize = 1.0f
            rating = placemark.rate?.toFloat() ?: 0f
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = (8 * resources.displayMetrics.density).toInt()
            layoutParams = lp
        }
        layout.addView(rateInput)

        // Put the layout in a ScrollView in case the keyboard covers the fields
        val scrollView = ScrollView(context)
        scrollView.addView(layout)
        builder.setView(scrollView)

        // --- Dialog Buttons ---
        builder.setPositiveButton("Save") { _, _ ->
            val updatedName = nameInput.text.toString().trim()
            val updatedDescription = descriptionInput.text.toString().trim()
            val updatedRate = rateInput.rating.toInt()

            if (updatedName.isNotEmpty()) {
                val updatedPlacemark = placemark.copy(
                    name = updatedName,
                    description = updatedDescription,
                    rate = updatedRate
                )
                placemarkViewModel.updatePlacemark(updatedPlacemark)
                dismiss() // Dismiss the modal after saving
            } else {
                Toast.makeText(context, "Placemark name cannot be empty", Toast.LENGTH_SHORT).show()
            }
        }
        builder.setNegativeButton("Cancel", null)

        builder.create().show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
