package com.bconf.a2maps_and.placemark

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bconf.a2maps_and.R

class PlacemarksAdapter(private var placemarks: List<Placemark>) : RecyclerView.Adapter<PlacemarksAdapter.PlacemarkViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlacemarkViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.list_item_placemark, parent, false)
        return PlacemarkViewHolder(view)
    }

    override fun onBindViewHolder(holder: PlacemarkViewHolder, position: Int) {
        val placemark = placemarks[position]
        holder.bind(placemark)
    }

    override fun getItemCount(): Int = placemarks.size

    fun updatePlacemarks(newPlacemarks: List<Placemark>) {
        placemarks = newPlacemarks
        notifyDataSetChanged() // Consider using DiffUtil for better performance
    }

    class PlacemarkViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameTextView: TextView = itemView.findViewById(R.id.placemark_name)

        fun bind(placemark: Placemark) {
            nameTextView.text = placemark.name
            // You can bind other Placemark properties here if you add more views to list_item_placemark.xml
        }
    }
}
