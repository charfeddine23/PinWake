package com.example.util

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex

object DarkMapTileSource {
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
}
