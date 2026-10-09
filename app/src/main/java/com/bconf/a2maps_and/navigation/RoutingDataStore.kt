package com.bconf.a2maps_and.navigation

import android.content.Context
import btools.mapaccess.PhysicalFile
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class RoadSegment(val name: String, val bytes: Long)

class RoutingDataStore(private val context: Context) {
    internal val directory = File(context.filesDir, "routing/segments4").apply { mkdirs() }

    fun segments(): List<RoadSegment> = directory.listFiles()?.filter { validName(it.name) != null }?.sortedBy { it.name }
        ?.map { RoadSegment(it.name, it.length()) }.orEmpty()

    fun importSegment(name: String, input: InputStream): RoadSegment {
        val safeName = validName(name) ?: throw IllegalArgumentException("Choose a road data file such as E40_N55.rd5.")
        val temporary = File.createTempFile("segment-", ".tmp", directory)
        try {
            temporary.outputStream().use { output -> input.copyTo(output) }
            if (PhysicalFile.checkFileIntegrity(temporary) != null) throw IOException("Road data file is corrupt or incompatible.")
            val destination = File(directory, safeName)
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            return RoadSegment(destination.name, destination.length())
        } finally { temporary.delete() }
    }

    internal fun prepareProfile(): File {
        val profileDir = File(context.filesDir, "routing/profiles2").apply { mkdirs() }
        val version = File(profileDir, "version")
        if (version.takeIf { it.exists() }?.readText() != PROFILE_VERSION ||
            !File(profileDir, "car-vario.brf").exists() || !File(profileDir, "lookups.dat").exists()) {
            for (name in listOf("car-vario.brf", "lookups.dat")) {
                val temporary = File(profileDir, "$name.tmp")
                context.assets.open("routing/$name").use { input -> temporary.outputStream().use { input.copyTo(it) } }
                Files.move(temporary.toPath(), File(profileDir, name).toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            version.writeText(PROFILE_VERSION)
        }
        return File(profileDir, "car-vario.brf")
    }

    companion object {
        private const val PROFILE_VERSION = "brouter-1.7.9-lookups-11.2"
        fun validName(name: String): String? {
            val match = Regex("([EW])(\\d{1,3})_([NS])(\\d{1,2})\\.rd5", RegexOption.IGNORE_CASE).matchEntire(name) ?: return null
            val longitude = match.groupValues[2].toInt(); val latitude = match.groupValues[4].toInt()
            if (longitude > 180 || latitude > 90 || longitude % 5 != 0 || latitude % 5 != 0) return null
            return "${match.groupValues[1].uppercase()}${longitude}_${match.groupValues[3].uppercase()}${latitude}.rd5"
        }
    }
}
