package com.bconf.a2maps_and.track

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.bconf.a2maps_and.R
import java.io.File

class TrackFragment : Fragment() {

    private val trackViewModel: TrackViewModel by activityViewModels()
    private lateinit var tracksRecyclerView: RecyclerView
    private lateinit var noTracksTextView: TextView
    private lateinit var trackAdapter: TrackAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_track, container, false)
        tracksRecyclerView = view.findViewById(R.id.tracksRecyclerView)
        noTracksTextView = view.findViewById(R.id.noTracksTextView)

        // Initialize the adapter here once
        setupRecyclerView()

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        trackViewModel.tracks.observe(viewLifecycleOwner) { tracks ->
            if (tracks.isNullOrEmpty()) {
                tracksRecyclerView.visibility = View.GONE
                noTracksTextView.visibility = View.VISIBLE
            } else {
                tracksRecyclerView.visibility = View.VISIBLE
                noTracksTextView.visibility = View.GONE
                // Update the adapter with the new list
                trackAdapter.updateTracks(tracks)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Load or refresh tracks every time the fragment is shown
        trackViewModel.loadTracks()
    }

    private fun setupRecyclerView() {
        trackAdapter = TrackAdapter(
            tracks = emptyList(),
            onTrackClicked = { trackFile ->
                trackViewModel.displayTrackFromFile(trackFile)
                findNavController().popBackStack()
            },
            onTrackDelete = { trackFile ->
                // Show a confirmation dialog before deleting
                showDeleteConfirmationDialog(trackFile)
            },
            onTrackShare = { trackFile ->
                shareTrackFile(trackFile)
            }
        )
        tracksRecyclerView.adapter = trackAdapter
    }

    private fun showDeleteConfirmationDialog(trackFile: java.io.File) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete Track")
            .setMessage("Are you sure you want to delete '${trackFile.name}'?")
            .setPositiveButton("Delete") { _, _ ->
                trackViewModel.deleteTrack(trackFile)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun shareTrackFile(trackFile: File) {
        val context = requireContext()
        val authority = "${context.packageName}.provider"
        try {
            val fileUri = FileProvider.getUriForFile(context, authority, trackFile)

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/gpx+xml" // Specific MIME type for GPX
                putExtra(Intent.EXTRA_STREAM, fileUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(shareIntent, "Share GPX Track"))
        } catch (e: IllegalArgumentException) {
            Toast.makeText(context, "Error: Could not share file.", Toast.LENGTH_LONG).show()
        }
    }
}
