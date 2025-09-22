package com.bconf.a2maps_and.placemark

import com.google.gson.annotations.SerializedName
import org.maplibre.android.geometry.LatLng
import java.util.UUID

data class Placemark(
    @SerializedName("id") val id: String = UUID.randomUUID().toString(),
    @SerializedName("name") val name: String,
    @SerializedName("latitude") val latitude: Double,
    @SerializedName("longitude") val longitude: Double,
    @SerializedName("rate") val rate: Int,
    @SerializedName("description") val description: String
) {
    @delegate:Transient
    val coordinates: LatLng by lazy { LatLng(latitude, longitude) }
}
