package com.bconf.a2maps_and.service

import android.content.Context
import android.location.Location
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GpxLogger(private val context: Context) {

    private var gpxFileWriter: FileWriter? = null

    fun startGpxLogging() {
        if (gpxFileWriter != null) {
            Log.w("GpxLogger", "GPX logging is already active. Finalizing previous log.")
            stopGpxLogging()
        }

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "track_$timeStamp.gpx"
        val gpxFile = File(context.getExternalFilesDir(null), fileName)

        try {
            gpxFileWriter = FileWriter(gpxFile)
            // Write GPX header
            gpxFileWriter?.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            gpxFileWriter?.append("<gpx version=\"1.1\" creator=\"2Maps-And - https://github.com/bconf/2maps-and\" xmlns=\"http://www.topografix.com/GPX/1/1\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\">\n")
            gpxFileWriter?.append("<metadata>\n")
            gpxFileWriter?.append("  <name>$fileName</name>\n")
            gpxFileWriter?.append("</metadata>\n")
            gpxFileWriter?.append("<trk>\n")
            gpxFileWriter?.append("  <name>Navigation Track</name>\n")
            gpxFileWriter?.append("  <trkseg>\n")
            Log.i("GpxLogger", "Started GPX logging to ${gpxFile.absolutePath}")
        } catch (e: IOException) {
            Log.e("GpxLogger", "Error initializing GPX file", e)
            gpxFileWriter = null
        }
    }

    fun appendGpxTrackPoint(location: Location) {
        gpxFileWriter?.let { writer ->
            try {
                val time = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault()).format(Date(location.time))
                writer.append("    <trkpt lat=\"${location.latitude}\" lon=\"${location.longitude}\">\n")
                writer.append("      <ele>${location.altitude}</ele>\n")
                writer.append("      <time>$time</time>\n")
                if (location.hasAccuracy()) {
                    writer.appendLine("      <hdop>${location.accuracy}</hdop>")
                }
                if (location.hasBearing()) {
                    writer.append("      <course>${location.bearing}</course>\n")
                }
                if (location.hasSpeed()) {
                    writer.append("      <speed>${location.speed}</speed>\n")
                }
                writer.append("    </trkpt>\n")
            } catch (e: IOException) {
                Log.e("GpxLogger", "Error writing GPX track point", e)
            }
        }
    }

    fun stopGpxLogging() {
        gpxFileWriter?.let { writer ->
            try {
                writer.append("  </trkseg>\n")
                writer.append("</trk>\n")
                writer.append("</gpx>\n")
                writer.flush()
                writer.close()
                Log.i("GpxLogger", "Finalized GPX log.")
            } catch (e: IOException) {
                Log.e("GpxLogger", "Error finalizing GPX file", e)
            } finally {
                gpxFileWriter = null
            }
        }
    }
}
