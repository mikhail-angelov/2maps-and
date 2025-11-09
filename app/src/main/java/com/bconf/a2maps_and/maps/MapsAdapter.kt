package com.bconf.a2maps_and.maps

import android.content.Context
import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.navigation.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.bconf.a2maps_and.R
import com.bconf.a2maps_and.databinding.ItemMapBinding // Assumes ViewBinding is enabled and you have this file

class MapsAdapter(
    private var mapItems: List<MapItem>
) : RecyclerView.Adapter<MapsAdapter.MapViewHolder>() {

    class MapViewHolder(val binding: ItemMapBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MapViewHolder {
        val binding = ItemMapBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return MapViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MapViewHolder, position: Int) {
        val mapItem = mapItems[position]
        holder.binding.mapNameText.text = mapItem.name

        val sharedPreferences = holder.itemView.context.getSharedPreferences("maps_prefs", Context.MODE_PRIVATE)
        val selectedMapPath = sharedPreferences.getString("selected_map", null)

        // Check if the preview image file exists and is a file
        if (mapItem.previewImage.exists() && mapItem.previewImage.isFile) {
            // Decode the file into a Bitmap
            val bitmap = BitmapFactory.decodeFile(mapItem.previewImage.absolutePath)
            if (bitmap != null) {
                // Set the Bitmap to the ImageView
                holder.binding.mapPreviewImage.setImageBitmap(bitmap)
            } else {
                // If decoding fails, fall back to the default icon
                holder.binding.mapPreviewImage.setImageResource(R.drawable.ic_map)
            }
        } else {
            // If the file does not exist, set the default icon
            holder.binding.mapPreviewImage.setImageResource(R.drawable.ic_map)
        }
        holder.itemView.setOnClickListener {
            if (position != holder.adapterPosition) {
                notifyItemChanged(position)
            }

            with(sharedPreferences.edit()) {
                putString("selected_map", mapItem.file.absolutePath)
                apply()
            }
            holder.itemView.findNavController().navigate(R.id.action_mapsFragment_to_mapFragment)
        }
    }

    override fun getItemCount(): Int = mapItems.size

    fun updateMaps(newMapItems: List<MapItem>) {
        this.mapItems = newMapItems
        notifyDataSetChanged() // For simplicity. Consider using DiffUtil for better performance.
    }
}
