package com.bconf.a2maps_and.placemark

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.bconf.a2maps_and.R
import com.google.gson.annotations.SerializedName
import org.maplibre.android.geometry.LatLng
import java.util.UUID

data class Placemark(
    @SerializedName("id") val id: String = UUID.randomUUID().toString(),
    @SerializedName("name") val name: String,
    @SerializedName("lat") val latitude: Double,
    @SerializedName("lng") val longitude: Double,
    @SerializedName("rate") val rate: Int?,
    @SerializedName("description") val description: String?,
    @SerializedName("timestamp") val timestamp: Long
) {
    @delegate:Transient
    val coordinates: LatLng by lazy { LatLng(latitude, longitude) }
}
