package com.bconf.a2maps_and.utils

import android.location.Location
import android.util.Log
import com.bconf.a2maps_and.placemark.Placemark

/**
 * A data class to hold the result of a distance calculation.
 * This makes the return type from the utility function clear and easy to use.
 *
 * @param distanceInMeters The calculated distance in meters, or null if the calculation failed.
 * @param distanceString A user-friendly formatted string (e.g., "150 m", "2 km"), or "N/A" on failure.
 */
data class DistanceResult(
    val distanceInMeters: Float?,
    val distanceString: String
)

/**
 * Utility object for common functions related to Placemarks.
 */
object PlacemarkUtils {

    /**
     * Calculates the distance between a user's location and a placemark.
     *
     * @param userLocation The user's current Location. Can be null, in which case the result is "N/A".
     * @param placemark The target Placemark containing latitude and longitude.
     * @return A [DistanceResult] containing the formatted string and the raw distance in meters.
     */
    fun calculateDistance(userLocation: Location?, placemark: Placemark): DistanceResult {
        if (userLocation == null) {
            return DistanceResult(null, "N/A")
        }

        return try {
            val results = FloatArray(1)
            Location.distanceBetween(
                userLocation.latitude, userLocation.longitude,
                placemark.latitude, placemark.longitude,
                results
            )
            val distanceInMeters = results[0]
            val distanceString = formatDistance(distanceInMeters)

            DistanceResult(distanceInMeters, distanceString)

        } catch (e: IllegalArgumentException) {
            Log.e("PlacemarkUtils", "Error calculating distance for ${placemark.name}", e)
            DistanceResult(null, "Error")
        }
    }

    fun formatDistanceForDisplay(distanceMeters: Double): String {
        return if (distanceMeters < 1.0 && distanceMeters > 0) { // for < 1m show cm or "now"
            "Now" // Or String.format("%.0f cm", distanceMeters * 100) but "Now" is common
        } else if (distanceMeters < 10.0) { // Distances like 9.5m
            String.format(
                "%.0f m",
                distanceMeters
            ) // Show without decimal for values like 7m, 8m, 9m
        } else if (distanceMeters < 50.0 && distanceMeters >= 10.0) { // For 10m to 49m, round to nearest 5 or 10
            String.format("%.0f m", (Math.round(distanceMeters / 5.0) * 5.0))
        } else if (distanceMeters < 1000.0) { // From 50m up to 999m
            // Round to nearest 10m for cleaner display (e.g., 50m, 60m, not 53m)
            String.format("%.0f m", (Math.round(distanceMeters / 10.0) * 10.0))
        } else { // Kilometers
            val km = distanceMeters / 1000.0
            if (km < 10.0) { // e.g. 1.2 km, 9.8 km
                String.format("%.1f km", km)
            } else { // e.g. 10 km, 125 km
                String.format("%.0f km", km)
            }
        }
    }

    /**
     * Formats a distance in meters into a human-readable string (e.g., "m" or "km").
     */
    private fun formatDistance(distanceInMeters: Float): String {
        return if (distanceInMeters < 1000) {
            "${distanceInMeters.toInt()} m"
        } else {
            "${(distanceInMeters / 1000).toInt()} km"
        }
    }
}
