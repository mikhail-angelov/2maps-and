package com.bconf.a2maps_and.placemark

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bconf.a2maps_and.R // Ensure this R is your project's R

// Assuming your Placemark data class is defined in this package or imported
// data class Placemark(val id: Long, val name: String, val description: String?, val latitude: Double, val longitude: Double, /* ... */)

class PlacemarkAdapter(private val onItemClicked: (Placemark) -> Unit) :
    ListAdapter<Placemark, PlacemarkAdapter.PlacemarkViewHolder>(PlacemarkDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlacemarkViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_placemark, parent, false)
        return PlacemarkViewHolder(view)
    }

    override fun onBindViewHolder(holder: PlacemarkViewHolder, position: Int) {
        val placemark = getItem(position)
        holder.bind(placemark)
        holder.itemView.setOnClickListener {
            onItemClicked(placemark)
        }
    }

    class PlacemarkViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameTextView: TextView = itemView.findViewById(R.id.placemarkNameTextView)
        private val descriptionTextView: TextView = itemView.findViewById(R.id.placemarkDescriptionTextView)
        private val coordinatesTextView: TextView = itemView.findViewById(R.id.placemarkCoordinatesTextView)

        fun bind(placemark: Placemark) {
            nameTextView.text = placemark.name
            descriptionTextView.text = placemark.description ?: "No description"
            val coordinates = "Lat: ${String.format("%.4f", placemark.latitude)}, Lng: ${String.format("%.4f", placemark.longitude)}"
            coordinatesTextView.text = coordinates
        }
    }

    class PlacemarkDiffCallback : DiffUtil.ItemCallback<Placemark>() {
        override fun areItemsTheSame(oldItem: Placemark, newItem: Placemark): Boolean {
            return oldItem.id == newItem.id // Assuming 'id' is a unique identifier
        }

        override fun areContentsTheSame(oldItem: Placemark, newItem: Placemark): Boolean {
            return oldItem == newItem
        }
    }
}
