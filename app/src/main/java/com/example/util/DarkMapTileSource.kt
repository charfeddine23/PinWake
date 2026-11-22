package com.example.util

import android.content.Context
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.MapTileIndex

enum class FreeMapStyle {
    DARK,
    VOYAGER,
    STREETS,
    SATELLITE
}

object DarkMapTileSource {

    private const val PREFS_NAME = "pin_wake_prefs"
    private const val KEY_MAP_STYLE = "free_map_style"

    val ESRI_WORLD_STREETS: OnlineTileSourceBase = object : OnlineTileSourceBase(
        "EsriWorldStreets",
        0,
        19,
        256,
        "",
        arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/"),
        "Tiles © Esri"
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String {
            val zoom = MapTileIndex.getZoom(pMapTileIndex)
            val x = MapTileIndex.getX(pMapTileIndex)
            val y = MapTileIndex.getY(pMapTileIndex)
            return "${baseUrl}$zoom/$y/$x"
        }
    }

    val ESRI_SATELLITE: OnlineTileSourceBase = object : OnlineTileSourceBase(
        "EsriWorldImagery",
        0,
        18,
        256,
        "",
        arrayOf("https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"),
        "Tiles © Esri"
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String {
            val zoom = MapTileIndex.getZoom(pMapTileIndex)
            val x = MapTileIndex.getX(pMapTileIndex)
            val y = MapTileIndex.getY(pMapTileIndex)
            return "${baseUrl}$zoom/$y/$x"
        }
    }

    private val DARK_OSM_COLOR_FILTER: ColorFilter by lazy {
        val invertMatrix = ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        // Hue-rotate 180 deg so oceans stay deep blue and parks stay dark green after inversion
        val hueRotate180 = ColorMatrix(
            floatArrayOf(
                -0.574f, 1.430f, 0.144f, 0f, 0f,
                0.426f, 0.430f, 0.144f, 0f, 0f,
                0.426f, 1.430f, -0.856f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        invertMatrix.postConcat(hueRotate180)
        ColorMatrixColorFilter(invertMatrix)
    }

    fun tileSourceFor(style: FreeMapStyle): ITileSource {
        return when (style) {
            FreeMapStyle.VOYAGER -> ESRI_WORLD_STREETS
            FreeMapStyle.DARK -> TileSourceFactory.MAPNIK
            FreeMapStyle.STREETS -> TileSourceFactory.MAPNIK
            FreeMapStyle.SATELLITE -> ESRI_SATELLITE
        }
    }

    fun colorFilterFor(style: FreeMapStyle): ColorFilter? {
        return if (style == FreeMapStyle.DARK) DARK_OSM_COLOR_FILTER else null
    }

    fun getSavedStyle(context: Context): FreeMapStyle {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_MAP_STYLE, FreeMapStyle.VOYAGER.name)
        return runCatching { FreeMapStyle.valueOf(raw ?: FreeMapStyle.VOYAGER.name) }
            .getOrDefault(FreeMapStyle.VOYAGER)
    }

    fun saveStyle(context: Context, style: FreeMapStyle) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MAP_STYLE, style.name)
            .apply()
    }
}
