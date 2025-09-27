package com.bconf.a2maps_and

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class MainMenuItem(val title: String, val actionId: Int? = null, val action: (() -> Unit)? = null)

class MainMenuAdapter(
    private val menuItems: List<MainMenuItem>,
    private val onItemClick: (MainMenuItem) -> Unit
) : RecyclerView.Adapter<MainMenuAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.list_item_main_menu, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val menuItem = menuItems[position]
        holder.menuItemTextView.text = menuItem.title
        holder.itemView.setOnClickListener {
            onItemClick(menuItem)
        }
    }

    override fun getItemCount(): Int = menuItems.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val menuItemTextView: TextView = view.findViewById(R.id.menuItemTextView)
    }
}