package com.bconf.a2maps_and

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainMenuFragment : Fragment() {

    private lateinit var mainMenuRecyclerView: RecyclerView
    private lateinit var mainMenuAdapter: MainMenuAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        val view = inflater.inflate(R.layout.fragment_main_menu, container, false)

        mainMenuRecyclerView = view.findViewById(R.id.mainMenuRecyclerView)
        mainMenuRecyclerView.layoutManager = LinearLayoutManager(context)

        val menuItems = listOf(
            MainMenuItem("Placemarks", actionId = R.id.action_mainMenuFragment_to_placemarksFragment),
            MainMenuItem("Import Placemarks") { showImportPlacemarksToast() }
        )

        mainMenuAdapter = MainMenuAdapter(menuItems) { menuItem ->
            menuItem.actionId?.let {
                findNavController().navigate(it)
            } ?: menuItem.action?.invoke()
        }
        mainMenuRecyclerView.adapter = mainMenuAdapter

        return view
    }

    private fun showImportPlacemarksToast() {
        // TODO: Implement file selection dialog
        Toast.makeText(context, "Import Placemarks clicked", Toast.LENGTH_SHORT).show()
    }
}