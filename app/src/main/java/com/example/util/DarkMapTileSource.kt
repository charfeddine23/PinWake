package com.example.util

import android.content.Context
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.MapTileIndex

enum class FreeMapStyle {
    DARK,
    VOYAGER,
    STREETS,
    SATELLITE;

    fun isLightBackground(): Boolean = this == VOYAGER || this == STREETS
}

object DarkMapTileSource {

    private const val PREFS_NAME = "pin_wake_prefs"
    private const val KEY_MAP_STYLE = "free_map_style"

    val CARTO_DARK_MATTER: OnlineTileSourceBase = object : OnlineTileSourceBase(
        "CartoDarkMatter",
        0,
        19,
        256,
        ".png",
        arrayOf(
            "https://a.basemaps.cartocdn.com/dark_all/",
            "https://b.basemaps.cartocdn.com/dark_all/",
            "https://c.basemaps.cartocdn.com/dark_all/",
            "https://d.basemaps.cartocdn.com/dark_all/"
        ),
        "© OpenStreetMap contributors © CARTO"
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String {
            val zoom = MapTileIndex.getZoom(pMapTileIndex)
            val x = MapTileIndex.getX(pMapTileIndex)
            val y = MapTileIndex.getY(pMapTileIndex)
            return "${baseUrl}$zoom/$x/$y$mImageFilenameEnding"
        }
    }

    val CARTO_VOYAGER: OnlineTileSourceBase = object : OnlineTileSourceBase(
        "CartoVoyager",
        0,
        19,
        256,
        ".png",
        arrayOf(
            "https://a.basemaps.cartocdn.com/rastertiles/voyager/",
            "https://b.basemaps.cartocdn.com/rastertiles/voyager/",
            "https://c.basemaps.cartocdn.com/rastertiles/voyager/",
            "https://d.basemaps.cartocdn.com/rastertiles/voyager/"
        ),
        "© OpenStreetMap contributors © CARTO"
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String {
            val zoom = MapTileIndex.getZoom(pMapTileIndex)
            val x = MapTileIndex.getX(pMapTileIndex)
            val y = MapTileIndex.getY(pMapTileIndex)
            return "${baseUrl}$zoom/$x/$y$mImageFilenameEnding"
        }
    }

    val ESRI_SATELLITE: OnlineTileSourceBase = object : OnlineTileSourceBase(
        "EsriWorldImagery",
        0,
        18,
        256,
        "",
        arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"),
        "Tiles © Esri"
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String {
            val zoom = MapTileIndex.getZoom(pMapTileIndex)
            val x = MapTileIndex.getX(pMapTileIndex)
            val y = MapTileIndex.getY(pMapTileIndex)
            return "${baseUrl}$zoom/$y/$x"
        }
    }

    fun tileSourceFor(style: FreeMapStyle): ITileSource {
        return when (style) {
            FreeMapStyle.DARK -> CARTO_DARK_MATTER
            FreeMapStyle.VOYAGER -> CARTO_VOYAGER
            FreeMapStyle.STREETS -> TileSourceFactory.MAPNIK
            FreeMapStyle.SATELLITE -> ESRI_SATELLITE
        }
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
