package com.bconf.a2maps_and.maps

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.bconf.a2maps_and.databinding.FragmentMapsBinding
import java.io.File
import java.io.FileOutputStream

class MapsFragment : Fragment() {

    private lateinit var binding: FragmentMapsBinding
    private lateinit var mapsViewModel: MapsViewModel
    private lateinit var mapsAdapter: MapsAdapter

    private val importMap = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.also { uri ->
                val fileName = getFileName(uri)
                val inputStream = requireContext().contentResolver.openInputStream(uri)
                val mapsDir = File(requireContext().filesDir, "maps")
                if (!mapsDir.exists()) {
                    mapsDir.mkdirs()
                }
                val file = File(mapsDir, fileName)
                val outputStream = FileOutputStream(file)
                inputStream?.copyTo(outputStream)
                mapsViewModel.loadMaps(mapsDir)
            }
        }
    }

    private fun getFileName(uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = requireContext().contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    result = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                }
            } finally {
                cursor?.close()
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != -1) {
                if (cut != null) {
                    result = result?.substring(cut + 1)
                }
            }
        }
        return result ?: "map.mbtiles"
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

        mapsViewModel = ViewModelProvider(this).get(MapsViewModel::class.java)

        mapsAdapter = MapsAdapter(emptyList())
        binding.mapsRecyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = mapsAdapter
        }

        mapsViewModel.maps.observe(viewLifecycleOwner) { maps ->
            mapsAdapter.updateMaps(maps)
        }

        binding.importMapFab.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            importMap.launch(intent)
        }

        val mapsDir = File(requireContext().filesDir, "maps")
        if (!mapsDir.exists()) {
            mapsDir.mkdirs()
        }
        mapsViewModel.loadMaps(mapsDir)
    }
}