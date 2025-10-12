package com.bconf.a2maps_and.placemark

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bconf.a2maps_and.R


class PlacemarkAdapter(
    private val onItemClicked: (item: PlacemarkDisplayItem, position: Int) -> Unit // Modified listener for main item click
) :
    ListAdapter<PlacemarkDisplayItem, PlacemarkAdapter.PlacemarkViewHolder>(
        PlacemarkDisplayItemDiffCallback()
    ) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlacemarkViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.list_item_placemark, parent, false)
        return PlacemarkViewHolder(view) // Pass listener to ViewHolder
    }

    override fun onBindViewHolder(holder: PlacemarkViewHolder, position: Int) {
        val displayItem = getItem(position)
        holder.bind(displayItem, position)
        // Handle click on the main item (foreground view)
        holder.foregroundView.setOnClickListener { // Use foregroundView reference
            onItemClicked(displayItem, position) // Call modified listener
        }
    }

    class PlacemarkViewHolder(
        itemView: View,
    ) : RecyclerView.ViewHolder(itemView) {
        // Foreground views
        val foregroundView: View =
            itemView.findViewById(R.id.foreground_view) // Reference to foreground
        private val nameTextView: TextView = itemView.findViewById(R.id.placemark_name)
        private val distanceTextView: TextView = itemView.findViewById(R.id.placemark_distance)

        fun bind(displayItem: PlacemarkDisplayItem, position: Int) {
            nameTextView.text = displayItem.placemark.name
            distanceTextView.text = displayItem.distanceString
            foregroundView.translationX = 0f // Reset translation for view recycling
        }
    }

    class PlacemarkDisplayItemDiffCallback : DiffUtil.ItemCallback<PlacemarkDisplayItem>() {
        override fun areItemsTheSame(
            oldItem: PlacemarkDisplayItem,
            newItem: PlacemarkDisplayItem
        ): Boolean {
            return oldItem.placemark.id == newItem.placemark.id
        }

        override fun areContentsTheSame(
            oldItem: PlacemarkDisplayItem,
            newItem: PlacemarkDisplayItem
        ): Boolean {
            return oldItem == newItem
        }
    }
}
