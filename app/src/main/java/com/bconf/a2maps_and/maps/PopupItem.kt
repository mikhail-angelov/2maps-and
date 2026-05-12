package com.bconf.a2maps_and.maps

sealed class PopupItem {
    object Menu : PopupItem()
    data class Map(val mapItem: MapItem) : PopupItem()

    val displayName: String
        get() = when (this) {
            is Menu -> "Menu"
            is Map -> mapItem.name
        }
}
