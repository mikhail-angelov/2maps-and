package com.bconf.a2maps_and.placemark

import android.app.Activity
import android.content.Intent
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bconf.a2maps_and.R
import com.bconf.a2maps_and.databinding.FragmentPlacemarksBinding
import com.bconf.a2maps_and.ui.viewmodel.NavigationViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng

class PlacemarksFragment : Fragment() {

    private var _binding: FragmentPlacemarksBinding? = null
    private val binding get() = _binding!!

    private lateinit var placemarkViewModel: PlacemarksViewModel
    private lateinit var navigationViewModel: NavigationViewModel
    private lateinit var placemarkAdapter: PlacemarkAdapter
    private lateinit var filePickerLauncher: ActivityResultLauncher<Intent>

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        placemarkViewModel = ViewModelProvider(this).get(PlacemarksViewModel::class.java)
        navigationViewModel =
            ViewModelProvider(requireActivity()).get(NavigationViewModel::class.java) // Activity-scoped

        filePickerLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    // Handle the selected file URI
                    Log.d("PlacemarksFragment", "Selected file URI: $uri")
                    handleSelectedJsonFile(uri)
                }
            }
        }

        _binding = FragmentPlacemarksBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        observeViewModel()
        // Setup the Menu
        val menuHost: MenuHost =
            requireActivity() // Or requireView() if you want it scoped to the fragment's view lifecycle
        menuHost.addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.placemarks_fragment_menu, menu)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return when (menuItem.itemId) {
                    R.id.action_import_json -> {
                        openFilePicker()
                        true // Indicate the item selection was handled
                    }

                    else -> false // Let other components handle the item
                }
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED) // Observe while fragment is resumed

    }

    private fun setupRecyclerView() {
        placemarkAdapter = PlacemarkAdapter(
            onItemClicked = { item, position ->
                val modal = PlacemarkModal.newInstance(item.placemark.id)
                modal.show(parentFragmentManager, "PlacemarkViewModal")
            }
        )

        binding.placemarksRecyclerView.apply {
            adapter = placemarkAdapter
            layoutManager = LinearLayoutManager(context)
        }

    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            // Assuming lastKnownGpsLocation is the correct flow for general location updates not strictly tied to active navigation
            navigationViewModel.lastKnownGpsLocation.collectLatest { location ->
                placemarkViewModel.updateCurrentLocation(location) // Pass location to PlacemarksViewModel
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            placemarkViewModel.displayItems.collectLatest { displayItems ->
                placemarkAdapter.submitList(displayItems)
                binding.emptyView.isVisible = displayItems.isEmpty()
            }
        }
    }


    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json" // Only show JSON files
            // Optionally, you can specify `Intent.EXTRA_MIME_TYPES` for multiple types
        }
        try {
            filePickerLauncher.launch(intent)
        } catch (e: Exception) {
            Log.e("PlacemarksFragment", "Error launching file picker", e)
            Toast.makeText(requireContext(), "Error opening file picker: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleSelectedJsonFile(uri: Uri) {
        try {
            val inputStream = requireContext().contentResolver.openInputStream(uri)
            if (inputStream == null) {
                Log.e("PlacemarksFragment", "Failed to open input stream for URI: $uri")
                Toast.makeText(requireContext(), "Failed to read file", Toast.LENGTH_SHORT).show()
                return
            }
            // Now you have the InputStream, you can read the JSON content
            // For example, pass it to your ViewModel to process
//             val jsonString = inputStream.bufferedReader().use { it.readText() }
//             placemarkViewModel.importPlacemarksFromJson(jsonString)

            // For now, just log it and show a toast
            Log.d("PlacemarksFragment", "Successfully opened URI. Ready to process JSON.")
            Toast.makeText(requireContext(), "JSON file selected: ${uri.lastPathSegment}", Toast.LENGTH_LONG).show()

            // TODO: Implement the actual JSON parsing and import logic in your ViewModel
//             Example:
             viewLifecycleOwner.lifecycleScope.launch {
                 val success = placemarkViewModel.importPlacemarksFromUri(uri)
                 if (success) {
                     Toast.makeText(requireContext(), "Placemarks imported!", Toast.LENGTH_SHORT).show()
                 } else {
                     Toast.makeText(requireContext(), "Failed to import placemarks.", Toast.LENGTH_SHORT).show()
                 }
             }

        } catch (e: Exception) {
            Log.e("PlacemarksFragment", "Error handling selected JSON file", e)
            Toast.makeText(requireContext(), "Error processing file: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.placemarksRecyclerView.adapter = null // Important to clear adapter
        _binding = null
    }
}
