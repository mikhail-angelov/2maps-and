package com.bconf.a2maps_and.placemark

import android.location.Location
import android.util.Log

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
