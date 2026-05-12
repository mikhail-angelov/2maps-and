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
    private val items: List<PopupItem>,
    private val onItemClick: (PopupItem) -> Unit
) : RecyclerView.Adapter<PopupMapAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.popup_list_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val previewFile = (item as? PopupItem.Map)?.mapItem?.previewImage

        if (previewFile?.exists() == true) {
            val bitmap = BitmapFactory.decodeFile(previewFile.absolutePath)
            holder.imageView.setImageBitmap(bitmap)
            holder.textView.visibility = View.GONE
        } else {
            holder.imageView.visibility = View.GONE
            holder.textView.text = item.displayName
            holder.textView.visibility = View.VISIBLE
        }

        holder.itemView.setOnClickListener { onItemClick(item) }
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val imageView: ImageView = itemView.findViewById(R.id.popup_item_image)
        val textView: TextView = itemView.findViewById(R.id.popup_item_name)
    }
}
