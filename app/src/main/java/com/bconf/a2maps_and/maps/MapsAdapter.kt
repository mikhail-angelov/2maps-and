package com.bconf.a2maps_and.maps

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.navigation.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.bconf.a2maps_and.R
import com.bconf.a2maps_and.databinding.ItemMapBinding
import java.io.File

class MapsAdapter(private var maps: List<File>, private val context: Context) : RecyclerView.Adapter<MapsAdapter.MapViewHolder>() {

    private var selectedPosition = RecyclerView.NO_POSITION

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MapViewHolder {
        val binding = ItemMapBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return MapViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MapViewHolder, position: Int) {
        val position = holder.getAdapterPosition()
        val map = maps[position]
        holder.binding.mapName.text = map.name

        val sharedPreferences = holder.itemView.context.getSharedPreferences("maps_prefs", Context.MODE_PRIVATE)
        val selectedMapPath = sharedPreferences.getString("selected_map", null)

        if (selectedMapPath != null && selectedMapPath == map.absolutePath) {
            selectedPosition = position
        }

        if (position == selectedPosition) {
            holder.itemView.setBackgroundColor(ContextCompat.getColor(context, R.color.selected_item_color))
        } else {
            holder.itemView.setBackgroundColor(Color.TRANSPARENT)
        }

        holder.itemView.setOnClickListener {
            if (selectedPosition != holder.adapterPosition) {
                notifyItemChanged(selectedPosition)
                selectedPosition = holder.adapterPosition
                notifyItemChanged(selectedPosition)
            }

            with(sharedPreferences.edit()) {
                putString("selected_map", map.absolutePath)
                apply()
            }
            holder.itemView.findNavController().navigate(R.id.action_mapsFragment_to_mapFragment)
        }
    }

    override fun getItemCount(): Int = maps.size

    fun updateMaps(newMaps: List<File>) {
        maps = newMaps
        notifyDataSetChanged()
    }

    class MapViewHolder(val binding: ItemMapBinding) : RecyclerView.ViewHolder(binding.root)
}
