package com.bconf.a2maps_and.placemark

import android.animation.ObjectAnimator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bconf.a2maps_and.R

// Interface for handling clicks on the revealed action buttons
interface OnPlacemarkActionClickListener {
    fun onCenterClick(placemark: Placemark, position: Int)
    fun onNavigateClick(placemark: Placemark, position: Int)
    fun onEditClick(placemark: Placemark, position: Int)
    fun onDeleteClick(placemark: Placemark, position: Int)
}

class PlacemarkAdapter(
    private val actionClickListener: OnPlacemarkActionClickListener, // Listener for swipe actions
    private val onItemClicked: (item: PlacemarkDisplayItem, position: Int) -> Unit // Modified listener for main item click
) :
    ListAdapter<PlacemarkDisplayItem, PlacemarkAdapter.PlacemarkViewHolder>(PlacemarkDisplayItemDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlacemarkViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.list_item_placemark, parent, false)
        return PlacemarkViewHolder(view, actionClickListener) // Pass listener to ViewHolder
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
        private val actionClickListener: OnPlacemarkActionClickListener
    ) : RecyclerView.ViewHolder(itemView) {
        // Foreground views
        val foregroundView: View = itemView.findViewById(R.id.foreground_view) // Reference to foreground
        val actionsBackground: View = itemView.findViewById(R.id.actions_background) // Reference to background
        private val nameTextView: TextView = itemView.findViewById(R.id.placemark_name)
        private val distanceTextView: TextView = itemView.findViewById(R.id.placemark_distance)

        // Background action buttons
        private val centerButton: ImageButton = itemView.findViewById(R.id.center_button)
        private val navigateButton: ImageButton = itemView.findViewById(R.id.navigate_button)
        private val editButton: ImageButton = itemView.findViewById(R.id.edit_button)
        private val deleteButton: ImageButton = itemView.findViewById(R.id.delete_button)

        fun bind(displayItem: PlacemarkDisplayItem, position: Int) {
            nameTextView.text = displayItem.placemark.name
            distanceTextView.text = displayItem.distanceString
            foregroundView.translationX = 0f // Reset translation for view recycling

            // Set click listeners for action buttons
            centerButton.setOnClickListener {
                actionClickListener.onCenterClick(displayItem.placemark, position)
            }
            navigateButton.setOnClickListener {
                actionClickListener.onNavigateClick(displayItem.placemark, position)
            }
            editButton.setOnClickListener {
                actionClickListener.onEditClick(displayItem.placemark, position)
            }
            deleteButton.setOnClickListener {
                actionClickListener.onDeleteClick(displayItem.placemark, position)
            }
        }

        fun openActions(animate: Boolean) {
            val targetTranslationX = -actionsBackground.width.toFloat()
            if (animate) {
                ObjectAnimator.ofFloat(foregroundView, "translationX", targetTranslationX).apply {
                    duration = 300 // ms
                    start()
                }
            } else {
                foregroundView.translationX = targetTranslationX
            }
        }

        fun closeActions(animate: Boolean) {
            if (animate) {
                ObjectAnimator.ofFloat(foregroundView, "translationX", 0f).apply {
                    duration = 300 // ms
                    start()
                }
            } else {
                foregroundView.translationX = 0f
            }
        }
    }

    class PlacemarkDisplayItemDiffCallback : DiffUtil.ItemCallback<PlacemarkDisplayItem>() {
        override fun areItemsTheSame(oldItem: PlacemarkDisplayItem, newItem: PlacemarkDisplayItem): Boolean {
            return oldItem.placemark.id == newItem.placemark.id
        }

        override fun areContentsTheSame(oldItem: PlacemarkDisplayItem, newItem: PlacemarkDisplayItem): Boolean {
            return oldItem == newItem
        }
    }
}
