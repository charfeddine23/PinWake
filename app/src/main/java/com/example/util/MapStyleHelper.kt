package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.example.R
import com.example.data.local.entity.FavoriteCategory
import com.example.data.local.entity.GeoAlarmEntity
import com.example.data.remote.dto.NominatimPlaceDto
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.BackgroundLayer
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

object MapStyleHelper {

    const val BUILDINGS_3D_LAYER_ID = "pinwake-3d-buildings"
    private const val SOURCE_OPENMAPTILES = "openmaptiles"
    private const val SOURCE_LAYER_BUILDING = "building"

    /**
     * Configures MapLibre for flagship Google Maps-like responsiveness, full-DPI sharpness,
     * zoom depth up to 21.0, smooth fling inertia, tilt, and rotation gestures.
     */
    fun configureHighPrecisionMap(
        context: Context,
        mapView: MapView,
        map: MapLibreMap,
        allowGestures: Boolean = true
    ) {
        map.setMinZoomPreference(2.0)
        map.setMaxZoomPreference(21.0)

        // Ensure view renders crisp full-density visuals without software-layer blurring
        mapView.isHorizontalScrollBarEnabled = false
        mapView.isVerticalScrollBarEnabled = false

        val density = context.resources.displayMetrics.density
        map.uiSettings.apply {
            isCompassEnabled = false
            isLogoEnabled = false
            isAttributionEnabled = false
            if (allowGestures) {
                isScrollGesturesEnabled = true
                isZoomGesturesEnabled = true
                isDoubleTapGesturesEnabled = true
                isQuickZoomGesturesEnabled = true
                isTiltGesturesEnabled = true
                isRotateGesturesEnabled = true
                isScaleVelocityAnimationEnabled = true
                isFlingVelocityAnimationEnabled = true
                isRotateVelocityAnimationEnabled = true
                setFocalPoint(null)
            } else {
                setAllGesturesEnabled(false)
            }
            // Keep pixelRatio awareness in mind via density; hardware acceleration handles native DPR
            if (density > 0f) {
                isDeselectMarkersOnTap = true
            }
        }
    }

    /**
     * Smooth 800ms EaseInOut-style camera jump to search results or user GPS location.
     */
    fun animateCameraSmooth(
        map: MapLibreMap,
        lat: Double,
        lon: Double,
        zoom: Double = 15.8,
        tilt: Double = 0.0,
        durationMs: Int = 800
    ) {
        val position = CameraPosition.Builder()
            .target(LatLng(lat, lon))
            .zoom(zoom.coerceIn(2.0, 21.0))
            .tilt(tilt)
            .build()
        map.animateCamera(
            CameraUpdateFactory.newCameraPosition(position),
            durationMs
        )
    }

    /**
     * Transforms OpenFreeMap Liberty vector layers at runtime into Google Maps Dark or
     * Classic Light visual hierarchy with optional 3D extruded buildings.
     */
    fun applyGoogleNavigationStyling(
        style: Style,
        mode: OpenFreeMapStyle,
        enable3dBuildings: Boolean
    ) {
        if (!style.isFullyLoaded) return
        val isDark = mode.isDark

        if (isDark) {
            applyGoogleDarkPalette(style)
        } else {
            applyGoogleLightPalette(style)
        }

        configure3dBuildingsLayer(style, isDark = isDark, enabled = enable3dBuildings)
    }

    private fun applyGoogleDarkPalette(style: Style) {
        // Suppress light relief raster in dark mode so #0F172A / #17263C stays rich & clean
        style.getLayerAs<RasterLayer>("natural_earth")?.setProperties(
            PropertyFactory.visibility(Property.NONE)
        )

        style.getLayerAs<BackgroundLayer>("background")?.setProperties(
            PropertyFactory.backgroundColor("#0F172A")
        )

        // Urban land & secondary landuses
        setFillColor(style, "landuse_residential", "#17263C", opacity = 0.72f)
        setFillColor(style, "landcover_sand", "#1B2940")
        setFillColor(style, "aeroway_fill", "#19263D")
        setFillColor(style, "landuse_hospital", "#1F263B")
        setFillColor(style, "landuse_school", "#1A273D")
        setFillColor(style, "landuse_cemetery", "#172E2B")
        setFillColor(style, "landuse_track", "#1C2940")

        // Water bodies (#0E1626 with crisp waterway accents)
        setFillColor(style, "water", "#0E1626")
        setFillColor(style, "landcover_ice", "#142138")
        setLineColor(style, "waterway_river", "#162B47")
        setLineColor(style, "waterway_other", "#152842")
        setLineColor(style, "waterway_tunnel", "#122238")

        // Parks & Green areas (#18332E)
        setFillColor(style, "park", "#18332E")
        setFillColor(style, "landcover_wood", "#17302B")
        setFillColor(style, "landcover_grass", "#1A3832")
        setFillColor(style, "landuse_pitch", "#1C3D36")
        setLineColor(style, "park_outline", "#22473F")

        // Airport runways
        setLineColor(style, "aeroway_runway", "#2C394E")
        setLineColor(style, "aeroway_taxiway", "#263246")

        // Highways & Main Arteries (#38414E fill with warm #D97706 / #64748B casings)
        val highwayFills = listOf(
            "road_motorway", "bridge_motorway", "tunnel_motorway",
            "road_trunk_primary", "bridge_trunk_primary", "tunnel_trunk_primary",
            "road_motorway_link", "bridge_motorway_link", "tunnel_motorway_link"
        )
        highwayFills.forEach { id ->
            val opacity = if (id.startsWith("tunnel_")) 0.70f else 1.0f
            setLineColor(style, id, "#38414E", opacity = opacity)
        }

        val majorHighwayCasings = listOf(
            "road_motorway_casing", "bridge_motorway_casing", "tunnel_motorway_casing",
            "road_trunk_primary_casing", "bridge_trunk_primary_casing", "tunnel_trunk_primary_casing"
        )
        majorHighwayCasings.forEach { id ->
            setLineColor(style, id, "#D97706", opacity = 0.85f)
        }

        val linkCasings = listOf(
            "road_motorway_link_casing", "bridge_motorway_link_casing", "tunnel_motorway_link_casing",
            "road_link_casing", "bridge_link_casing", "tunnel_link_casing"
        )
        linkCasings.forEach { id ->
            setLineColor(style, id, "#64748B", opacity = 0.80f)
        }

        // Secondary & Local Roads (#2C3646 with #192233 casings)
        val secondaryFills = listOf(
            "road_secondary_tertiary", "bridge_secondary_tertiary", "tunnel_secondary_tertiary",
            "road_minor", "bridge_street", "tunnel_minor",
            "road_link", "bridge_link", "tunnel_link",
            "road_service_track", "bridge_service_track", "tunnel_service_track"
        )
        secondaryFills.forEach { id ->
            val opacity = if (id.startsWith("tunnel_")) 0.65f else 1.0f
            setLineColor(style, id, "#2C3646", opacity = opacity)
        }

        val secondaryCasings = listOf(
            "road_secondary_tertiary_casing", "bridge_secondary_tertiary_casing", "tunnel_secondary_tertiary_casing",
            "road_minor_casing", "bridge_street_casing", "tunnel_street_casing",
            "road_service_track_casing", "bridge_service_track_casing", "tunnel_service_track_casing",
            "bridge_path_pedestrian_casing"
        )
        secondaryCasings.forEach { id ->
            setLineColor(style, id, "#182131", opacity = 0.9f)
        }

        val pedestrianPaths = listOf(
            "road_path_pedestrian", "bridge_path_pedestrian", "tunnel_path_pedestrian"
        )
        pedestrianPaths.forEach { id ->
            setLineColor(style, id, "#222C3D", opacity = 0.85f)
        }

        val railLayers = listOf(
            "road_major_rail", "bridge_major_rail", "tunnel_major_rail",
            "road_transit_rail", "bridge_transit_rail", "tunnel_transit_rail"
        )
        railLayers.forEach { id ->
            setLineColor(style, id, "#334155", opacity = 0.75f)
        }

        // Administrative boundaries
        listOf("boundary_2", "boundary_3", "boundary_disputed").forEach { id ->
            setLineColor(style, id, "#475569", opacity = 0.65f)
        }

        // 2D building footprint subtle fill
        setFillColor(style, "building", "#1D283E", opacity = 0.85f)

        // High-contrast road, place & POI labels (#F1F5F9 text, 1.0 opacity, #090D16 1.5f dark halo)
        style.layers.forEach { layer ->
            if (layer is SymbolLayer && !layer.id.startsWith("pinwake-")) {
                val textColor = when {
                    layer.id.startsWith("water") -> "#7DD3FC"
                    else -> "#F1F5F9"
                }
                layer.setProperties(
                    PropertyFactory.textColor(textColor),
                    PropertyFactory.textOpacity(1.0f),
                    PropertyFactory.textHaloColor("#090D16"),
                    PropertyFactory.textHaloWidth(1.5f),
                    PropertyFactory.textHaloBlur(0.4f)
                )
            }
        }
    }

    private fun applyGoogleLightPalette(style: Style) {
        style.getLayerAs<RasterLayer>("natural_earth")?.setProperties(
            PropertyFactory.visibility(Property.VISIBLE),
            PropertyFactory.rasterOpacity(0.18f)
        )

        style.getLayerAs<BackgroundLayer>("background")?.setProperties(
            PropertyFactory.backgroundColor("#F8F9FA")
        )

        setFillColor(style, "landuse_residential", "#F1F3F4", opacity = 0.8f)
        setFillColor(style, "landcover_sand", "#F5EFE0")
        setFillColor(style, "aeroway_fill", "#E8EAED")
        setFillColor(style, "landuse_hospital", "#FCE8E6")
        setFillColor(style, "landuse_school", "#FEF7E0")
        setFillColor(style, "landuse_cemetery", "#E6F4EA")
        setFillColor(style, "landuse_track", "#ECEFF1")

        // Classic Google Maps water (#AADAFF)
        setFillColor(style, "water", "#AADAFF")
        setFillColor(style, "landcover_ice", "#E3F2FD")
        setLineColor(style, "waterway_river", "#8CC8F8")
        setLineColor(style, "waterway_other", "#9DD2FB")
        setLineColor(style, "waterway_tunnel", "#B8E0FC")

        // Classic soft green parks (#CEEAD6)
        setFillColor(style, "park", "#CEEAD6")
        setFillColor(style, "landcover_wood", "#C5E5CF")
        setFillColor(style, "landcover_grass", "#D4EFDC")
        setFillColor(style, "landuse_pitch", "#BEE3C8")
        setLineColor(style, "park_outline", "#A8DAB5")

        setLineColor(style, "aeroway_runway", "#D1D5DB")
        setLineColor(style, "aeroway_taxiway", "#E5E7EB")

        // Warm yellow/amber highways (#FCD34D / #F59E0B)
        listOf(
            "road_motorway", "bridge_motorway", "tunnel_motorway",
            "road_trunk_primary", "bridge_trunk_primary", "tunnel_trunk_primary",
            "road_motorway_link", "bridge_motorway_link", "tunnel_motorway_link"
        ).forEach { id ->
            val opacity = if (id.startsWith("tunnel_")) 0.72f else 1.0f
            setLineColor(style, id, "#FCD34D", opacity = opacity)
        }

        listOf(
            "road_motorway_casing", "bridge_motorway_casing", "tunnel_motorway_casing",
            "road_trunk_primary_casing", "bridge_trunk_primary_casing", "tunnel_trunk_primary_casing",
            "road_motorway_link_casing", "bridge_motorway_link_casing", "tunnel_motorway_link_casing"
        ).forEach { id ->
            setLineColor(style, id, "#F59E0B", opacity = 0.90f)
        }

        // Crisp white local & secondary roads with light-gray borders
        listOf(
            "road_secondary_tertiary", "bridge_secondary_tertiary", "tunnel_secondary_tertiary",
            "road_minor", "bridge_street", "tunnel_minor",
            "road_link", "bridge_link", "tunnel_link",
            "road_service_track", "bridge_service_track", "tunnel_service_track"
        ).forEach { id ->
            setLineColor(style, id, "#FFFFFF", opacity = 1.0f)
        }

        listOf(
            "road_secondary_tertiary_casing", "bridge_secondary_tertiary_casing", "tunnel_secondary_tertiary_casing",
            "road_minor_casing", "bridge_street_casing", "tunnel_street_casing",
            "road_link_casing", "bridge_link_casing", "tunnel_link_casing",
            "road_service_track_casing", "bridge_service_track_casing", "tunnel_service_track_casing",
            "bridge_path_pedestrian_casing"
        ).forEach { id ->
            setLineColor(style, id, "#DADCE0", opacity = 1.0f)
        }

        listOf("road_path_pedestrian", "bridge_path_pedestrian", "tunnel_path_pedestrian").forEach { id ->
            setLineColor(style, id, "#EFF1F3", opacity = 0.95f)
        }

        listOf("boundary_2", "boundary_3", "boundary_disputed").forEach { id ->
            setLineColor(style, id, "#9AA0A6", opacity = 0.70f)
        }

        setFillColor(style, "building", "#E8EAED", opacity = 0.95f)

        style.layers.forEach { layer ->
            if (layer is SymbolLayer && !layer.id.startsWith("pinwake-")) {
                val textColor = when {
                    layer.id.startsWith("water") -> "#1967D2"
                    layer.id == "highway-name-major" -> "#78350F"
                    else -> "#3C4043"
                }
                layer.setProperties(
                    PropertyFactory.textColor(textColor),
                    PropertyFactory.textHaloColor("#FFFFFF"),
                    PropertyFactory.textHaloWidth(1.6f),
                    PropertyFactory.textHaloBlur(0.4f)
                )
            }
        }
    }

    private fun configure3dBuildingsLayer(style: Style, isDark: Boolean, enabled: Boolean) {
        // Hide stock Liberty 3D building layer to prevent duplicate z-fighting extrusions
        style.getLayer("building-3d")?.setProperties(
            PropertyFactory.visibility(Property.NONE)
        )

        val extrusionColor = if (isDark) "#1E293B" else "#D5DAE1"
        val existing = style.getLayerAs<FillExtrusionLayer>(BUILDINGS_3D_LAYER_ID)
        if (existing != null) {
            existing.minZoom = 15f
            existing.setProperties(
                PropertyFactory.visibility(if (enabled) Property.VISIBLE else Property.NONE),
                PropertyFactory.fillExtrusionColor(extrusionColor),
                PropertyFactory.fillExtrusionOpacity(0.75f),
                PropertyFactory.fillExtrusionVerticalGradient(true)
            )
            return
        }

        if (style.getSource(SOURCE_OPENMAPTILES) == null) return

        val extrusionLayer = FillExtrusionLayer(BUILDINGS_3D_LAYER_ID, SOURCE_OPENMAPTILES).apply {
            sourceLayer = SOURCE_LAYER_BUILDING
            minZoom = 15f
            setProperties(
                PropertyFactory.visibility(if (enabled) Property.VISIBLE else Property.NONE),
                PropertyFactory.fillExtrusionColor(extrusionColor),
                PropertyFactory.fillExtrusionOpacity(0.75f),
                PropertyFactory.fillExtrusionHeight(
                    Expression.coalesce(
                        Expression.get("render_height"),
                        Expression.literal(10.0)
                    )
                ),
                PropertyFactory.fillExtrusionBase(
                    Expression.coalesce(
                        Expression.get("render_min_height"),
                        Expression.literal(0.0)
                    )
                ),
                PropertyFactory.fillExtrusionVerticalGradient(true)
            )
        }

        // Insert right below street labels so road names & POIs stay sharp on top of 3D geometry
        when {
            style.getLayer("highway-name-path") != null ->
                style.addLayerBelow(extrusionLayer, "highway-name-path")
            style.getLayer("boundary_3") != null ->
                style.addLayerBelow(extrusionLayer, "boundary_3")
            else ->
                style.addLayer(extrusionLayer)
        }
    }

    /**
     * Installs or updates a Google-style translucent cyan geofence circle (#2206B6D4 fill, #06B6D4 line).
     */
    fun ensureGeofenceCircleLayers(
        style: Style,
        sourceId: String,
        fillLayerId: String,
        lineLayerId: String,
        features: FeatureCollection
    ) {
        val existingSource = style.getSourceAs<GeoJsonSource>(sourceId)
        if (existingSource == null) {
            style.addSource(GeoJsonSource(sourceId, features))
        } else {
            existingSource.setGeoJson(features)
        }

        if (style.getLayer(fillLayerId) == null) {
            style.addLayer(
                FillLayer(fillLayerId, sourceId).apply {
                    setProperties(
                        PropertyFactory.fillColor("#06B6D4"),
                        PropertyFactory.fillOpacity(0.14f) // Equivalent to #2206B6D4
                    )
                }
            )
        }
        if (style.getLayer(lineLayerId) == null) {
            style.addLayer(
                LineLayer(lineLayerId, sourceId).apply {
                    setProperties(
                        PropertyFactory.lineColor("#06B6D4"),
                        PropertyFactory.lineWidth(2.4f),
                        PropertyFactory.lineOpacity(0.95f)
                    )
                }
            )
        }
    }

    fun createGeodesicCircleFeature(
        lat: Double,
        lon: Double,
        radiusMeters: Int,
        steps: Int = 72
    ): Feature {
        val earthRadiusMeters = 6371008.8
        val latRad = Math.toRadians(lat)
        val lonRad = Math.toRadians(lon)
        val angularDistance = radiusMeters.toDouble() / earthRadiusMeters

        val ring = ArrayList<Point>(steps + 1)
        for (i in 0..steps) {
            val bearing = 2.0 * Math.PI * (i % steps) / steps
            val pointLat = asin(
                sin(latRad) * cos(angularDistance) +
                    cos(latRad) * sin(angularDistance) * cos(bearing)
            )
            val pointLon = lonRad + atan2(
                sin(bearing) * sin(angularDistance) * cos(latRad),
                cos(angularDistance) - sin(latRad) * sin(pointLat)
            )
            ring.add(Point.fromLngLat(Math.toDegrees(pointLon), Math.toDegrees(pointLat)))
        }
        return Feature.fromGeometry(Polygon.fromLngLats(listOf(ring)))
    }

    /**
     * Renders permanent pin symbols on the map for all pinned destinations (Home, Work, Station,
     * Custom, and standard Wake Pins) with category-specific icons and colors.
     */
    fun ensureSavedPlaceMarkersLayer(
        context: Context,
        style: Style,
        sourceId: String,
        haloLayerId: String,
        symbolLayerId: String,
        favorites: List<GeoAlarmEntity>,
        isDark: Boolean
    ) {
        if (!style.isFullyLoaded) return

        registerFavoriteMarkerImages(context, style)

        val features = favorites.map { place ->
            val iconId = when (place.favoriteCategory) {
                FavoriteCategory.HOME -> MARKER_IMAGE_HOME
                FavoriteCategory.WORK -> MARKER_IMAGE_WORK
                FavoriteCategory.STATION -> MARKER_IMAGE_STATION
                FavoriteCategory.CUSTOM -> MARKER_IMAGE_CUSTOM
                FavoriteCategory.NONE -> MARKER_IMAGE_ALARM
            }
            val label = buildSavedPlaceLabel(context, place)
            Feature.fromGeometry(Point.fromLngLat(place.longitude, place.latitude)).apply {
                addStringProperty("icon", iconId)
                addStringProperty("label", label)
                addStringProperty("category", place.favoriteCategory.name)
                addStringProperty("activeState", if (place.isActive) "1" else "0")
                addNumberProperty("alarmId", place.id)
            }
        }
        val collection = FeatureCollection.fromFeatures(features)

        val existingSource = style.getSourceAs<GeoJsonSource>(sourceId)
        if (existingSource == null) {
            style.addSource(GeoJsonSource(sourceId, collection))
        } else {
            existingSource.setGeoJson(collection)
        }

        val categoryHaloColorExpr = Expression.match(
            Expression.get("category"),
            Expression.color(Color.parseColor("#00D4FF")),
            Expression.stop("HOME", Expression.color(Color.parseColor("#10B981"))),
            Expression.stop("WORK", Expression.color(Color.parseColor("#F59E0B"))),
            Expression.stop("STATION", Expression.color(Color.parseColor("#06B6D4"))),
            Expression.stop("CUSTOM", Expression.color(Color.parseColor("#38BDF8")))
        )
        val haloRadiusExpr = Expression.match(
            Expression.get("activeState"),
            Expression.literal(9f),
            Expression.stop("1", Expression.literal(13f))
        )
        val haloOpacityExpr = Expression.match(
            Expression.get("activeState"),
            Expression.literal(0.16f),
            Expression.stop("1", Expression.literal(0.26f))
        )

        if (style.getLayer(haloLayerId) == null) {
            style.addLayer(
                CircleLayer(haloLayerId, sourceId).apply {
                    setProperties(
                        PropertyFactory.circleRadius(haloRadiusExpr),
                        PropertyFactory.circleColor(categoryHaloColorExpr),
                        PropertyFactory.circleOpacity(haloOpacityExpr),
                        PropertyFactory.circleStrokeWidth(1.6f),
                        PropertyFactory.circleStrokeColor(categoryHaloColorExpr),
                        PropertyFactory.circleStrokeOpacity(0.78f)
                    )
                }
            )
        }

        // 1. Icon-only SymbolLayer: zero text properties so pin icons ALWAYS render even offline or if font glyphs fail
        val existingIconLayer = style.getLayerAs<SymbolLayer>(symbolLayerId)
        if (existingIconLayer == null) {
            style.addLayer(
                SymbolLayer(symbolLayerId, sourceId).apply {
                    setProperties(
                        PropertyFactory.iconImage(Expression.get("icon")),
                        PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                        PropertyFactory.iconOffset(arrayOf(0f, 2f)),
                        PropertyFactory.iconAllowOverlap(true),
                        PropertyFactory.iconIgnorePlacement(true)
                    )
                }
            )
        } else {
            existingIconLayer.setProperties(
                PropertyFactory.iconImage(Expression.get("icon")),
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                PropertyFactory.iconOffset(arrayOf(0f, 2f)),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true)
            )
        }

        // 2. Text-only SymbolLayer using OpenFreeMap Liberty's actual font stack
        val labelLayerId = "$symbolLayerId-labels"
        val textColor = if (isDark) "#F8FAFC" else "#0F172A"
        val haloColor = if (isDark) "#0F172A" else "#FFFFFF"
        val styleFontStack = resolveStyleFontStack(style)
        val existingLabelLayer = style.getLayerAs<SymbolLayer>(labelLayerId)
        if (existingLabelLayer == null) {
            style.addLayer(
                SymbolLayer(labelLayerId, sourceId).apply {
                    setProperties(
                        PropertyFactory.textField(Expression.get("label")),
                        PropertyFactory.textFont(styleFontStack),
                        PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
                        PropertyFactory.textOffset(arrayOf(0f, 0.35f)),
                        PropertyFactory.textSize(12f),
                        PropertyFactory.textColor(textColor),
                        PropertyFactory.textHaloColor(haloColor),
                        PropertyFactory.textHaloWidth(1.8f),
                        PropertyFactory.textHaloBlur(0.3f),
                        PropertyFactory.textAllowOverlap(true),
                        PropertyFactory.textIgnorePlacement(true),
                        PropertyFactory.textOptional(true)
                    )
                }
            )
        } else {
            existingLabelLayer.setProperties(
                PropertyFactory.textFont(styleFontStack),
                PropertyFactory.textColor(textColor),
                PropertyFactory.textHaloColor(haloColor)
            )
        }
    }

    private fun resolveStyleFontStack(style: Style): Array<String> {
        for (layer in style.layers) {
            if (layer is SymbolLayer && !layer.id.startsWith("pinwake-")) {
                val fonts = layer.textFont.value
                if (fonts != null && fonts.isNotEmpty()) {
                    return fonts
                }
            }
        }
        return arrayOf("Noto Sans Bold", "Noto Sans Regular")
    }

    private fun buildSavedPlaceLabel(context: Context, place: GeoAlarmEntity): String {
        val categoryLabel = when (place.favoriteCategory) {
            FavoriteCategory.HOME -> context.getString(R.string.fav_home)
            FavoriteCategory.WORK -> context.getString(R.string.fav_work)
            FavoriteCategory.STATION -> context.getString(R.string.fav_station)
            FavoriteCategory.CUSTOM -> context.getString(R.string.fav_custom)
            FavoriteCategory.NONE -> ""
        }
        val cleanTitle = NominatimPlaceDto.stripUnsupportedGlyphs(place.title)
            .ifBlank { place.title.trim() }
        return when {
            categoryLabel.isBlank() -> cleanTitle
            cleanTitle.isBlank() || cleanTitle.equals(categoryLabel, ignoreCase = true) -> categoryLabel
            else -> "$categoryLabel · $cleanTitle"
        }
    }

    private fun registerFavoriteMarkerImages(context: Context, style: Style) {
        val specs = listOf(
            Triple(MARKER_IMAGE_HOME, R.drawable.ic_home, Color.parseColor("#10B981")),
            Triple(MARKER_IMAGE_WORK, R.drawable.ic_work, Color.parseColor("#F59E0B")),
            Triple(MARKER_IMAGE_STATION, R.drawable.ic_train, Color.parseColor("#06B6D4")),
            Triple(MARKER_IMAGE_CUSTOM, R.drawable.ic_star, Color.parseColor("#38BDF8")),
            Triple(MARKER_IMAGE_ALARM, R.drawable.ic_alarm_bell, Color.parseColor("#00D4FF"))
        )
        specs.forEach { (id, drawableRes, pinColor) ->
            if (style.getImage(id) == null) {
                style.addImage(id, buildMarkerBitmap(context, drawableRes, pinColor))
            }
        }
    }

    private fun buildMarkerBitmap(
        context: Context,
        drawableRes: Int,
        pinColor: Int
    ): Bitmap {
        val density = context.resources.displayMetrics.density.coerceAtLeast(1.5f)
        val widthPx = (44f * density).toInt()
        val heightPx = (54f * density).toInt()
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val cx = widthPx / 2f
        val headRadius = 16.5f * density
        val headCy = 4f * density + headRadius
        val tipY = heightPx - 3f * density

        // Subtle ground shadow ellipse directly beneath pin tip
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(120, 0, 0, 0)
            style = Paint.Style.FILL
        }
        canvas.drawOval(
            RectF(cx - 8f * density, tipY - 2.4f * density, cx + 8f * density, tipY + 2.4f * density),
            shadowPaint
        )

        // Smooth Google Maps-style teardrop contour pointing to (cx, tipY)
        val leftX = cx - headRadius
        val rightX = cx + headRadius
        val teardropPath = Path().apply {
            moveTo(cx, tipY)
            cubicTo(
                cx - headRadius * 0.25f, tipY - 7.5f * density,
                leftX, headCy + headRadius * 0.72f,
                leftX, headCy
            )
            arcTo(
                RectF(leftX, headCy - headRadius, rightX, headCy + headRadius),
                180f,
                180f,
                false
            )
            cubicTo(
                rightX, headCy + headRadius * 0.72f,
                cx + headRadius * 0.25f, tipY - 7.5f * density,
                cx, tipY
            )
            close()
        }

        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = pinColor
            style = Paint.Style.FILL
        }
        canvas.drawPath(teardropPath, bodyPaint)

        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F0FFFFFF")
            style = Paint.Style.STROKE
            strokeWidth = 1.8f * density
        }
        canvas.drawPath(teardropPath, strokePaint)

        // Crisp dark inner emblem circle holding the category symbol
        val innerRadius = 10.2f * density
        val innerFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F172A")
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, headCy, innerRadius, innerFillPaint)

        val drawable = ContextCompat.getDrawable(context, drawableRes)
        if (drawable != null) {
            val wrapped = DrawableCompat.wrap(drawable).mutate()
            DrawableCompat.setTint(wrapped, Color.WHITE)
            val iconHalf = (6.2f * density).toInt()
            wrapped.setBounds(
                cx.toInt() - iconHalf,
                headCy.toInt() - iconHalf,
                cx.toInt() + iconHalf,
                headCy.toInt() + iconHalf
            )
            wrapped.draw(canvas)
        }
        return bitmap
    }

    private fun setFillColor(style: Style, layerId: String, hex: String, opacity: Float? = null) {
        val layer = style.getLayerAs<FillLayer>(layerId) ?: return
        if (opacity != null) {
            layer.setProperties(
                PropertyFactory.fillColor(hex),
                PropertyFactory.fillOpacity(opacity)
            )
        } else {
            layer.setProperties(PropertyFactory.fillColor(hex))
        }
    }

    private fun setLineColor(style: Style, layerId: String, hex: String, opacity: Float? = null) {
        val layer = style.getLayerAs<LineLayer>(layerId) ?: return
        if (opacity != null) {
            layer.setProperties(
                PropertyFactory.lineColor(hex),
                PropertyFactory.lineOpacity(opacity)
            )
        } else {
            layer.setProperties(PropertyFactory.lineColor(hex))
        }
    }

    private const val MARKER_IMAGE_HOME = "pinwake-marker-home"
    private const val MARKER_IMAGE_WORK = "pinwake-marker-work"
    private const val MARKER_IMAGE_STATION = "pinwake-marker-station"
    private const val MARKER_IMAGE_CUSTOM = "pinwake-marker-custom"
    private const val MARKER_IMAGE_ALARM = "pinwake-marker-alarm"
}
