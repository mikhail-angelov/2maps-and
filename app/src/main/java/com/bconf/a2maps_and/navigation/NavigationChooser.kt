package com.bconf.a2maps_and.navigation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

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
        val deepLink = Uri.parse("yandexnavi://build_route_on_map?lat_to=$lat&lon_to=$lng")
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, deepLink))
        } catch (e: ActivityNotFoundException) {
            val fallback = Uri.parse("https://yandex.ru/maps/?rtext=~$lat,$lng&rtt=auto")
            context.startActivity(Intent(Intent.ACTION_VIEW, fallback))
        }
    }

    private fun openOsmAnd(context: Context, lat: Double, lng: Double) {
        val deepLink = Uri.parse("osmand.navigation://navigate?lat=$lat&lon=$lng")
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, deepLink))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "OsmAnd is not installed", Toast.LENGTH_SHORT).show()
        }
    }
}
