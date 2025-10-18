package com.bconf.a2maps_and.placemark

import android.app.Activity
import android.content.Intent
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
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.bconf.a2maps_and.R
import com.bconf.a2maps_and.databinding.FragmentPlacemarksBinding
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.launch

class PlacemarksFragment : Fragment() {

    private var _binding: FragmentPlacemarksBinding? = null
    private val binding get() = _binding!!

    private val placemarkViewModel: PlacemarksViewModel by activityViewModels()
    private lateinit var filePickerLauncher: ActivityResultLauncher<Intent>
    private var importType: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        filePickerLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
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

        val adapter = PlacemarksPagerAdapter(this)
        binding.viewPager.adapter = adapter

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "Placemarks"
                1 -> "Gas"
                else -> null
            }
        }.attach()


        val menuHost: MenuHost = requireActivity()
        menuHost.addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.placemarks_fragment_menu, menu)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return when (menuItem.itemId) {
                    R.id.action_import_placemarks -> {
                        importType = "placemarks"
                        openFilePicker()
                        true
                    }
                    R.id.action_import_gas_stations -> {
                        importType = "gas_stations"
                        openFilePicker()
                        true
                    }
                    else -> false
                }
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (importType == "gas_stations") "*/*" else "application/json"
        }
        try {
            filePickerLauncher.launch(intent)
        } catch (e: Exception) {
            Log.e("PlacemarksFragment", "Error launching file picker", e)
            Toast.makeText(
                requireContext(),
                "Error opening file picker: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
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
            Log.d("PlacemarksFragment", "Successfully opened URI. Ready to process JSON.")
            Toast.makeText(
                requireContext(),
                "JSON file selected: ${uri.lastPathSegment}",
                Toast.LENGTH_LONG
            ).show()

            viewLifecycleOwner.lifecycleScope.launch {
                val success = when (importType) {
                    "placemarks" -> placemarkViewModel.importPlacemarksFromUri(uri)
                    "gas_stations" -> placemarkViewModel.importGasStationsFromUri(uri)
                    else -> false
                }

                if (success) {
                    Toast.makeText(requireContext(), "Import successful!", Toast.LENGTH_SHORT)
                        .show()
                } else {
                    Toast.makeText(
                        requireContext(),
                        "Failed to import.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

        } catch (e: Exception) {
            Log.e("PlacemarksFragment", "Error handling selected JSON file", e)
            Toast.makeText(
                requireContext(),
                "Error processing file: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
