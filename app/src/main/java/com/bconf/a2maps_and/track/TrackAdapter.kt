package com.bconf.a2maps_and.track

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bconf.a2maps_and.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TrackAdapter(
    private var tracks: List<File>,
    private val onTrackClicked: (File) -> Unit,
    private val onTrackDelete: (File) -> Unit // Add a callback for deletion
) : RecyclerView.Adapter<TrackAdapter.TrackViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrackViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_track, parent, false)
        return TrackViewHolder(view)
    }

    override fun onBindViewHolder(holder: TrackViewHolder, position: Int) {
        val trackFile = tracks[position]
        holder.bind(trackFile)
    }

    override fun getItemCount(): Int = tracks.size

    fun updateTracks(newTracks: List<File>) {
        this.tracks = newTracks
        notifyDataSetChanged() // A more efficient way is to use DiffUtil
    }

    inner class TrackViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameTextView: TextView = itemView.findViewById(R.id.trackNameTextView)
        private val detailsTextView: TextView = itemView.findViewById(R.id.trackDetailsTextView)
        private val deleteButton: ImageButton = itemView.findViewById(R.id.deleteTrackButton)

        fun bind(file: File) {
            nameTextView.text = file.name
            val lastModified = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(file.lastModified()))
            val sizeKb = file.length() / 1024
            detailsTextView.text = "Date: $lastModified, Size: ${sizeKb}KB"

            // Handle whole item click
            itemView.setOnClickListener {
                onTrackClicked(file)
            }

            // Handle delete button click
            deleteButton.setOnClickListener {
                onTrackDelete(file)
            }
        }
    }
}
