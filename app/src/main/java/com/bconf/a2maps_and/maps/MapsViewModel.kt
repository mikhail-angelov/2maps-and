package com.bconf.a2maps_and.maps

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
// No longer needed since we are not drawing a Paint rect
// import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.database.getStringOrNull
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import kotlin.math.pow

class MapsViewModel(application: Application) : AndroidViewModel(application) {

    private val _maps = MutableLiveData<List<MapItem>>()
    val maps: LiveData<List<MapItem>> = _maps

    fun loadMaps(mapsDir: File) {
        viewModelScope.launch(Dispatchers.IO) {
            val mapFiles = mapsDir.listFiles { _, name -> name.endsWith(".mbtiles") }?.toList() ?: emptyList()
            // --- FIX: Use .map instead of .mapNotNull to include all mbtiles files ---
            val mapItems = mapFiles.map { mbtilesFile ->
                // This will attempt to generate a preview if it doesn't exist.
                // For vector tiles, it will be correctly skipped.
                ensurePreviewExists(mbtilesFile, mapsDir)
                val previewFile = File(mapsDir, "${mbtilesFile.nameWithoutExtension}.png")

                // Always create a MapItem. The previewFile might not exist,
                // and the UI layer (Adapter) will handle that case.
                MapItem(
                    file = mbtilesFile,
                    name = mbtilesFile.nameWithoutExtension,
                    previewImage = previewFile
                )
            }
            _maps.postValue(mapItems)
        }
    }

    fun importMap(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>().applicationContext
            val fileName = getFileName(uri)
            val inputStream = context.contentResolver.openInputStream(uri)
            val mapsDir = File(context.filesDir, "maps")
            if (!mapsDir.exists()) {
                mapsDir.mkdirs()
            }
            val file = File(mapsDir, fileName)
            val outputStream = FileOutputStream(file)
            inputStream?.use { input ->
                outputStream.use { output ->
                    input.copyTo(output)
                }
            }
            // After importing, ensure its preview is generated
            if (file.extension == "mbtiles") {
                ensurePreviewExists(file, mapsDir)
            }
            // Reload all maps to update the UI
            loadMaps(mapsDir)
        }
    }

    private fun ensurePreviewExists(mbtilesFile: File, mapsDir: File) {
        val previewFile = File(mapsDir, "${mbtilesFile.nameWithoutExtension}.png")
        if (previewFile.exists()) {
            Log.d("MapsViewModel", "Preview already exists for ${mbtilesFile.name}")
            return
        }

        // The generatePreview function will handle skipping vector tiles internally.
        Log.d("MapsViewModel", "Attempting to generate preview for ${mbtilesFile.name}")
        try {
            generatePreview(mbtilesFile, previewFile)
        } catch (e: Exception) {
            Log.e("MapsViewModel", "Failed to generate preview for ${mbtilesFile.name}", e)
        }
    }

    private fun generatePreview(mbtilesFile: File, outputFile: File, width: Int = 512, height: Int = 512) {
        val db = SQLiteDatabase.openDatabase(mbtilesFile.path, null, SQLiteDatabase.OPEN_READONLY)

        // 1. Get metadata and check if the format is raster.
        // The format can be 'jpg', 'png', etc. We will now attempt to decode any format.
        val format = getMetadataValue(db, "format")
        if (format == "pbf") { // Explicitly skip vector tiles
            Log.w("MapsViewModel", "Skipping preview generation for vector tileset (pbf).")
            db.close()
            return
        }

        // 2. Get max zoom level
        val maxZoom = getMetadataValue(db, "maxzoom")?.toIntOrNull() ?: db.rawQuery("SELECT MAX(zoom_level) FROM tiles", null).use {
            if (it.moveToFirst()) it.getInt(0) else 14 // Fallback zoom
        }

        // 3. Get center tile
        val boundsStr = getMetadataValue(db, "bounds")
        val (centerX, centerY) = if (boundsStr != null) {
            val bounds = boundsStr.split(',').map { it.toDouble() }
            val centerLon = (bounds[0] + bounds[2]) / 2
            val centerLat = (bounds[1] + bounds[3]) / 2
            // FIX: Convert lat/lon to tile coordinates, then convert Y to MBTiles row for the query
            val (tileX, tileY) = lonLatToTile(centerLon, centerLat, maxZoom)
            Pair(tileX, mbtilesRowToY(tileY, maxZoom)) // We need the MBTiles row for the DB
        } else {
            // Fallback: find the center of all available tiles at max zoom
            db.rawQuery("SELECT AVG(tile_column), AVG(tile_row) FROM tiles WHERE zoom_level = ?", arrayOf(maxZoom.toString())).use {
                if (it.moveToFirst()) Pair(it.getDouble(0).toInt(), it.getDouble(1).toInt()) else Pair(0, 0)
            }
        }

        // 4. Create base image and draw tiles
        val tileSize = 256
        val tilesPerSide = width / tileSize
        val baseImage = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(baseImage)
        canvas.drawColor(Color.LTGRAY) // Background color for empty areas

        // FIX: The starting Y coordinate needs to be based on the standard TMS coordinate system
        // The centerY from the DB is already an MBTiles row.
        val startX = centerX - tilesPerSide / 2
        val startY = mbtilesRowToY(centerY, maxZoom) - tilesPerSide / 2

        for (xOffset in 0 until tilesPerSide) {
            for (yOffset in 0 until tilesPerSide) {
                val tileX = startX + xOffset
                val tileY = startY + yOffset
                // FIX: Convert the standard Y coordinate back to an MBTiles row to query the database
                val mbtilesRow = mbtilesRowToY(tileY, maxZoom)

                val tileData = getTileData(db, maxZoom, tileX, mbtilesRow)
                if (tileData != null) {
                    // FIX: Use BitmapFactory to decode the raw byte array. It automatically handles PNG, JPG, etc.
                    val tileBitmap = BitmapFactory.decodeByteArray(tileData, 0, tileData.size)
                    if (tileBitmap != null) {
                        val posX = xOffset * tileSize
                        val posY = yOffset * tileSize
                        canvas.drawBitmap(tileBitmap, null, Rect(posX, posY, posX + tileSize, posY + tileSize), null)
                        tileBitmap.recycle()
                    }
                }
            }
        }

        db.close()

        // 5. Save the final image as a PNG file
        FileOutputStream(outputFile).use { out ->
            baseImage.compress(Bitmap.CompressFormat.PNG, 90, out)
        }
        baseImage.recycle()
        Log.d("MapsViewModel", "Successfully generated preview: ${outputFile.name}")
    }

    private fun getTileData(db: SQLiteDatabase, zoom: Int, column: Int, row: Int): ByteArray? {
        return db.rawQuery("SELECT tile_data FROM tiles WHERE zoom_level = ? AND tile_column = ? AND tile_row = ?", arrayOf(zoom.toString(), column.toString(), row.toString())).use {
            if (it.moveToFirst()) it.getBlob(0) else null
        }
    }

    private fun getMetadataValue(db: SQLiteDatabase, name: String): String? {
        return db.rawQuery("SELECT value FROM metadata WHERE name = ?", arrayOf(name)).use {
            if (it.moveToFirst()) it.getStringOrNull(0) else null
        }
    }

    // Convert standard TMS tile Y to MBTiles row Y
    private fun mbtilesRowToY(y: Int, zoom: Int): Int = (2.0.pow(zoom.toDouble())).toInt() - 1 - y

    // Convert longitude and latitude to standard TMS tile coordinates (X, Y)
    private fun lonLatToTile(lon: Double, lat: Double, zoom: Int): Pair<Int, Int> {
        val x = ((lon + 180) / 360 * 2.0.pow(zoom)).toInt()
        val y = ((1 - Math.log(Math.tan(Math.toRadians(lat)) + 1 / Math.cos(Math.toRadians(lat))) / Math.PI) / 2 * 2.0.pow(zoom)).toInt()
        return Pair(x, y)
    }

    private fun getFileName(uri: Uri): String {
        val context = getApplication<Application>().applicationContext
        var result: String? = null
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    result = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != null && cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result ?: "map.mbtiles"
    }
}
