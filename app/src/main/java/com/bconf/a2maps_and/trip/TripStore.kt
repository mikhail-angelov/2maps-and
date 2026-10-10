package com.bconf.a2maps_and.trip

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/** Trips imported from QR codes, kept in internal `trips.json`, newest first. */
class TripStore(context: Context) {
    private val file = File(context.filesDir, "trips.json")

    fun all(): List<Trip> = try {
        if (!file.exists()) emptyList()
        else file.reader().use { Gson().fromJson<List<Trip>>(it, object : TypeToken<List<Trip>>() {}.type) }
            .orEmpty().sortedByDescending { it.createdAt }
    } catch (e: Exception) {
        Log.e("TripStore", "Cannot read trips", e)
        emptyList()
    }

    fun get(id: String) = all().firstOrNull { it.id == id }

    fun add(trip: Trip) = save(listOf(trip) + all())

    fun delete(id: String) = save(all().filterNot { it.id == id })

    fun markVisited(id: String, index: Int) = update(id) { trip ->
        trip.copy(points = trip.points.mapIndexed { i, p -> if (i == index) p.copy(visited = true) else p })
    }

    fun resetVisits(id: String) = update(id) { trip -> trip.copy(points = trip.points.map { it.copy(visited = false) }) }

    private fun update(id: String, change: (Trip) -> Trip) = save(all().map { if (it.id == id) change(it) else it })

    private fun save(trips: List<Trip>) {
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writer().use { Gson().toJson(trips, it) }
        if (!temp.renameTo(file)) Log.e("TripStore", "Cannot save trips")
    }
}
