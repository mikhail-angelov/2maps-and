package com.bconf.a2maps_and.track

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.bconf.a2maps_and.R

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
}
