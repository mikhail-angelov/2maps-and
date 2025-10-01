package com.bconf.a2maps_and.placemark

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bconf.a2maps_and.R

class PlacemarkAdapter(private val onItemClicked: (Placemark) -> Unit) :
    ListAdapter<PlacemarkDisplayItem, PlacemarkAdapter.PlacemarkViewHolder>(PlacemarkDisplayItemDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlacemarkViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.list_item_placemark, parent, false) // Using new layout
        return PlacemarkViewHolder(view)
    }

    override fun onBindViewHolder(holder: PlacemarkViewHolder, position: Int) {
        val displayItem = getItem(position)
        holder.bind(displayItem)
        holder.itemView.setOnClickListener {
            onItemClicked(displayItem.placemark) // Pass the original Placemark for click handling
        }
    }

    class PlacemarkViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameTextView: TextView = itemView.findViewById(R.id.placemark_name)
        private val distanceTextView: TextView = itemView.findViewById(R.id.placemark_distance)

        fun bind(displayItem: PlacemarkDisplayItem) {
            nameTextView.text = displayItem.placemark.name
            distanceTextView.text = displayItem.distanceString
        }
    }

    class PlacemarkDisplayItemDiffCallback : DiffUtil.ItemCallback<PlacemarkDisplayItem>() {
        override fun areItemsTheSame(oldItem: PlacemarkDisplayItem, newItem: PlacemarkDisplayItem): Boolean {
            return oldItem.placemark.id == newItem.placemark.id // Compare by original placemark ID
        }

        override fun areContentsTheSame(oldItem: PlacemarkDisplayItem, newItem: PlacemarkDisplayItem): Boolean {
            return oldItem == newItem
        }
    }
}
