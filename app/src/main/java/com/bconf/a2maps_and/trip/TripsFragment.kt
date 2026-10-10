package com.bconf.a2maps_and.trip

import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.bconf.a2maps_and.R
import com.bconf.a2maps_and.navigation.NavigationViewModel
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.util.Date

/** Trips imported from Mamon QR codes, newest first; tapping one starts offline navigation through its points. */
class TripsFragment : Fragment() {
    private val navigationViewModel: NavigationViewModel by activityViewModels()
    private val store by lazy { TripStore(requireContext()) }
    private val adapter = TripAdapter(::startTrip, ::confirmDelete)
    private lateinit var empty: TextView

    private val scanner = registerForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@registerForActivityResult
        val trip = TripCode.parse(text)
        if (trip == null) {
            Toast.makeText(requireContext(), R.string.trips_invalid_code, Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        store.add(trip)
        Toast.makeText(requireContext(), getString(R.string.trips_added, trip.name, trip.points.size), Toast.LENGTH_SHORT).show()
        refresh()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_trips, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<RecyclerView>(R.id.tripsRecyclerView).adapter = adapter
        empty = view.findViewById(R.id.tripsEmpty)
        view.findViewById<FloatingActionButton>(R.id.tripsAdd).setOnClickListener {
            scanner.launch(ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt(getString(R.string.trips_scan_prompt))
                .setBeepEnabled(false)
                .setOrientationLocked(false))
        }
        refresh()
    }

    private fun refresh() {
        val trips = store.all()
        adapter.submit(trips)
        empty.isVisible = trips.isEmpty()
    }

    private fun startTrip(trip: Trip) {
        if (navigationViewModel.startTrip(trip.id)) findNavController().popBackStack()
    }

    private fun confirmDelete(trip: Trip) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.trips_delete_title)
            .setMessage(getString(R.string.trips_delete_message, trip.name))
            .setPositiveButton(R.string.trips_delete) { _, _ -> store.delete(trip.id); refresh() }
            .setNegativeButton(R.string.trips_cancel, null)
            .show()
    }
}

private class TripAdapter(
    private val onOpen: (Trip) -> Unit,
    private val onDelete: (Trip) -> Unit,
) : RecyclerView.Adapter<TripAdapter.Holder>() {
    private var trips: List<Trip> = emptyList()

    fun submit(trips: List<Trip>) {
        this.trips = trips
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_trip, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(trips[position])

    override fun getItemCount() = trips.size

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val name: TextView = view.findViewById(R.id.tripName)
        private val details: TextView = view.findViewById(R.id.tripDetails)
        private val delete: View = view.findViewById(R.id.tripDelete)

        fun bind(trip: Trip) {
            val context = itemView.context
            name.text = trip.name
            details.text = context.getString(R.string.trips_details,
                DateFormat.getMediumDateFormat(context).format(Date(trip.createdAt)), trip.visitedCount, trip.points.size)
            itemView.setOnClickListener { onOpen(trip) }
            delete.setOnClickListener { onDelete(trip) }
        }
    }
}
