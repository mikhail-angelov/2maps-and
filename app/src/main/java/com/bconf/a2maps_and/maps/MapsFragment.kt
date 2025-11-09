package com.bconf.a2maps_and.maps

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.bconf.a2maps_and.databinding.FragmentMapsBinding
import java.io.File

class MapsFragment : Fragment() {

    private lateinit var binding: FragmentMapsBinding
    private lateinit var mapsViewModel: MapsViewModel
    private lateinit var mapsAdapter: MapsAdapter

    private val importMapLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.also { uri ->
                mapsViewModel.importMap(uri)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentMapsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Use AndroidViewModelFactory to pass Application to ViewModel
        val factory = ViewModelProvider.AndroidViewModelFactory.getInstance(requireActivity().application)
        mapsViewModel = ViewModelProvider(this, factory).get(MapsViewModel::class.java)

        // Initialize adapter with an empty list of MapItem
        mapsAdapter = MapsAdapter(emptyList())
        binding.mapsRecyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = mapsAdapter
        }

        // Observe the LiveData<List<MapItem>>
        mapsViewModel.maps.observe(viewLifecycleOwner) { mapItems ->
            mapsAdapter.updateMaps(mapItems)
        }

        binding.importMapFab.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*" // Or be more specific e.g. "application/octet-stream" for .mbtiles
            }
            importMapLauncher.launch(intent)
        }

        val mapsDir = File(requireContext().filesDir, "maps")
        if (!mapsDir.exists()) {
            mapsDir.mkdirs()
        }
        mapsViewModel.loadMaps(mapsDir)
    }
}
