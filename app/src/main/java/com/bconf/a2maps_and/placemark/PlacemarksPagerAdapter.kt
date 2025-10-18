package com.bconf.a2maps_and.placemark

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter

class PlacemarksPagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {

    override fun getItemCount(): Int = 2

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> PlacemarkListFragment()
            1 -> GasStationListFragment()
            else -> throw IllegalStateException("Invalid position $position")
        }
    }
}