package com.bconf.a2maps_and.navigation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import java.util.Locale

object NavigationChooser {

    fun show(context: Context, lat: Double, lng: Double) {
        AlertDialog.Builder(context)
            .setTitle("Navigate via")
            .setItems(arrayOf("Yandex Navigator", "OsmAnd")) { _, which ->
                when (which) {
                    0 -> openYandexNavigator(context, lat, lng)
                    1 -> openOsmAnd(context, lat, lng)
                }
            }
            .show()
    }

    private fun openYandexNavigator(context: Context, lat: Double, lng: Double) {
        val latStr = String.format(Locale.US, "%.6f", lat)
        val lonStr = String.format(Locale.US, "%.6f", lng)
        val deepLink = "yandexnavi://build_route_on_map?lat_to=$latStr&lon_to=$lonStr".toUri()
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, deepLink))
        } catch (_: ActivityNotFoundException) {
            val fallback = "https://yandex.ru/maps/?rtext=~$latStr,$lonStr&rtt=auto".toUri()
            context.startActivity(Intent(Intent.ACTION_VIEW, fallback))
        }
    }

    private fun openOsmAnd(context: Context, lat: Double, lng: Double) {
        val latStr = String.format(Locale.US, "%.6f", lat)
        val lonStr = String.format(Locale.US, "%.6f", lng)
        val url = "https://osmand.net/go?lat=$latStr&lon=$lonStr&z=16".toUri()
        context.startActivity(Intent(Intent.ACTION_VIEW, url))
    }
}
