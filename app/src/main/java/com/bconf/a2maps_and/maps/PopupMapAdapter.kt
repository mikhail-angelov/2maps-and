package com.bconf.a2maps_and.maps

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bconf.a2maps_and.R

class PopupMapAdapter(
    private val items: List<MapItem>,
    private val onItemClick: (MapItem) -> Unit
) : RecyclerView.Adapter<PopupMapAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.popup_list_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val mapItem = items[position]

        if (mapItem.previewImage.exists()) {
            val bitmap = BitmapFactory.decodeFile(mapItem.previewImage.absolutePath)
            holder.imageView.setImageBitmap(bitmap)
            holder.textView.visibility = View.GONE
        } else {
            holder.imageView.visibility = View.GONE
            holder.textView.text = mapItem.name
            holder.textView.visibility = View.VISIBLE
        }

        holder.itemView.setOnClickListener {
            onItemClick(mapItem)
        }
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val imageView: ImageView = itemView.findViewById(R.id.popup_item_image)
        val textView: TextView = itemView.findViewById(R.id.popup_item_name)
    }
}
