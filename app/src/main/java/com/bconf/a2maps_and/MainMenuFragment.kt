package com.bconf.a2maps_and

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bconf.a2maps_and.auth.AuthRepository
import kotlinx.coroutines.launch

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

        val menuItems = mutableListOf(
            MainMenuItem("Placemarks", actionId = R.id.action_mainMenuFragment_to_placemarksFragment),
            MainMenuItem("Tracks", actionId = R.id.action_mainMenuFragment_to_tracksFragment),
            MainMenuItem("Maps", actionId = R.id.action_mainMenuFragment_to_mapsFragment),
            MainMenuItem("Import Placemarks") { showImportPlacemarksToast() }
        )

        if (AuthRepository.isLoggedIn()) {
            menuItems.add(MainMenuItem("Sync Placemarks") { syncPlacemarks() })
            menuItems.add(MainMenuItem("2maps.xyz (${AuthRepository.getUserEmail()})") {
                AuthRepository.clearAuth()
                Toast.makeText(context, "Signed out", Toast.LENGTH_SHORT).show()
                // Recreate menu by navigating to itself
            })
        } else {
            menuItems.add(MainMenuItem("2maps.xyz - Sign In") {
                findNavController().navigate(R.id.authFragment)
            })
        }

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

    private fun syncPlacemarks() {
        lifecycleScope.launch {
            try {
                val token = AuthRepository.getToken() ?: return@launch
                val currentPlacemarks = com.bconf.a2maps_and.placemark.PlacemarkService.placemarks.value

                val serverMarks = currentPlacemarks.map { pm ->
                    com.bconf.a2maps_and.auth.ServerPlacemark(
                        id = pm.id,
                        name = pm.name,
                        lat = pm.latitude,
                        lng = pm.longitude,
                        rate = pm.rate,
                        description = pm.description,
                        timestamp = pm.timestamp
                    )
                }

                Toast.makeText(context, "Syncing ${serverMarks.size} placemarks...", Toast.LENGTH_SHORT).show()

                val response = AuthRepository.api.syncMarks(
                    token = "Bearer $token",
                    marks = serverMarks
                )

                if (response.isSuccessful) {
                    val syncedMarks = response.body()
                    Toast.makeText(
                        context,
                        "Sync complete: ${syncedMarks?.size ?: 0} marks on server",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        context,
                        "Sync failed: ${response.code()}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Sync error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}