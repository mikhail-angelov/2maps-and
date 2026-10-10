package com.bconf.a2maps_and.home

import android.content.Context
import androidx.core.content.edit
import com.bconf.a2maps_and.navigation.GeoPoint

/** The single Home point; private prefs excluded from backups like other location data. */
class HomeStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var home: GeoPoint?
        get() = if (prefs.contains(KEY_LATITUDE) && prefs.contains(KEY_LONGITUDE))
            GeoPoint(Double.fromBits(prefs.getLong(KEY_LATITUDE, 0)), Double.fromBits(prefs.getLong(KEY_LONGITUDE, 0))).takeIf { it.isValid() }
            else null
        set(value) = prefs.edit {
            if (value == null) { remove(KEY_LATITUDE); remove(KEY_LONGITUDE) }
            else { putLong(KEY_LATITUDE, value.latitude.toRawBits()); putLong(KEY_LONGITUDE, value.longitude.toRawBits()) }
        }

    companion object {
        const val PREFS_NAME = "home_prefs"
        private const val KEY_LATITUDE = "latitude"
        private const val KEY_LONGITUDE = "longitude"
    }
}
