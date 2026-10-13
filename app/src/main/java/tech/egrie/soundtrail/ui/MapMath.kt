package tech.egrie.soundtrail.ui

import tech.egrie.soundtrail.data.GeoPoint
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Pure Web Mercator math for the in-app pin map: world pixels, slippy-map tiles, and
 * screen placement. No Android types, so every formula here is unit-tested on the JVM.
 *
 * Zoom may be fractional; tile selection always floors it, and the drawing code scales
 * integer-zoom tiles by the leftover fraction so pinch-zoom is smooth without a
 * renderer for every zoom level.
 */
internal object MapMath {
    const val MIN_ZOOM = 2.0
    const val MAX_ZOOM = 18.0
    /** One zoom step above this already shows a whole city block; fitting stops short of MAX. */
    const val MAX_FIT_ZOOM = 15.0
    const val TILE_SIZE = 256

    /** Web Mercator's square world ends here; real poles would stretch it to infinity. */
    const val MAX_LATITUDE = 85.05112878

    fun clampLatitude(latitude: Double): Double = latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)

    fun clampZoom(zoom: Double): Double = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)

    /** Width/height in pixels of the whole world at [zoom] (fractional zooms allowed). */
    fun worldSize(zoom: Double): Double = TILE_SIZE * 2.0.pow(zoom)

    /** World pixel coordinates (x eastward, y southward) of a point at [zoom]. */
    fun worldPixel(latitude: Double, longitude: Double, zoom: Double): Pair<Double, Double> {
        val size = worldSize(zoom)
        val lat = Math.toRadians(clampLatitude(latitude))
        val x = (longitude + 180.0) / 360.0 * size
        val y = (0.5 - ln((1 + sin(lat)) / (1 - sin(lat))) / (4.0 * PI)) * size
        return x to y
    }

    /** Inverse of [worldPixel]. Longitude is wrapped into [-180, 180). */
    fun pointAt(worldX: Double, worldY: Double, zoom: Double): GeoPoint {
        val size = worldSize(zoom)
        val longitude = wrapLongitude(worldX / size * 360.0 - 180.0)
        val n = PI - 2.0 * PI * worldY / size
        return GeoPoint(Math.toDegrees(atan(sinh(n))), longitude)
    }

    /** Wraps a longitude into [-180, 180). */
    fun wrapLongitude(longitude: Double): Double {
        var lon = longitude
        while (lon >= 180.0) lon -= 360.0
        while (lon < -180.0) lon += 360.0
        return lon
    }

    /**
     * Signed shortest difference between two positions on a world of [period] pixels —
     * what keeps panning across the antimeridian from flying the map apart.
     */
    fun wrapDelta(delta: Double, period: Double): Double {
        if (period <= 0.0) return delta
        var d = delta % period
        if (d > period / 2.0) d -= period
        if (d < -period / 2.0) d += period
        return d
    }

    /** Latitude as a 0..1 fraction of the world's height (0 = north edge). */
    fun mercatorY(latitude: Double): Double {
        val lat = Math.toRadians(clampLatitude(latitude))
        return (1.0 - ln(tan(lat) + 1.0 / cos(lat)) / PI) / 2.0
    }

    /**
     * Center and zoom that frame every given point in a [width] x [height] view.
     * Returns (center, zoom). An empty list frames the whole world.
     */
    fun fitTo(points: List<GeoPoint>, width: Double, height: Double): Pair<GeoPoint, Double> {
        if (width <= 0.0 || height <= 0.0) return GeoPoint(0.0, 0.0) to MIN_ZOOM
        if (points.isEmpty()) return GeoPoint(0.0, 0.0) to MIN_ZOOM
        if (points.size == 1) return points.single() to 14.0

        val padding = 56.0
        val usableWidth = (width - 2 * padding).coerceAtLeast(64.0)
        val usableHeight = (height - 2 * padding).coerceAtLeast(64.0)
        val latMin = points.minOf { it.latitude }
        val latMax = points.maxOf { it.latitude }
        val lonMin = points.minOf { it.longitude }
        val lonMax = points.maxOf { it.longitude }

        val lonSpan = (lonMax - lonMin) / 360.0
        val ySpan = mercatorY(latMin) - mercatorY(latMax)
        val zoomLon = if (lonSpan > 1e-9) log2(usableWidth / (lonSpan * TILE_SIZE)) else MAX_FIT_ZOOM
        val zoomLat = if (ySpan > 1e-9) log2(usableHeight / (ySpan * TILE_SIZE)) else MAX_FIT_ZOOM
        val zoom = min(zoomLon, zoomLat).coerceIn(MIN_ZOOM, MAX_FIT_ZOOM)

        // Center on the frame's midpoint in world pixels, not on the raw lat/lon average,
        // so high latitudes are not visually off-center.
        val topLeftX = worldPixel(latMax, lonMin, zoom).first
        val bottomRightX = worldPixel(latMin, lonMax, zoom).first
        val topLeftY = worldPixel(latMax, lonMin, zoom).second
        val bottomRightY = worldPixel(latMin, lonMax, zoom).second
        return pointAt((topLeftX + bottomRightX) / 2.0, (topLeftY + bottomRightY) / 2.0, zoom) to zoom
    }

    /** The tiles that cover a viewport centered at [center] with (fractional) [zoom]. */
    fun visibleTiles(
        center: GeoPoint, zoom: Double, width: Double, height: Double
    ): TileRange {
        if (width <= 0.0 || height <= 0.0) return TileRange(floor(zoom).toInt(), IntRange.EMPTY, IntRange.EMPTY)
        val tileZoom = floor(zoom).toInt().coerceIn(0, 24)
        val factor = 2.0.pow(zoom - tileZoom)
        val centerPx = worldPixel(center.latitude, center.longitude, zoom)
        val zpxLeft = (centerPx.first - width / 2.0) / factor / TILE_SIZE
        val zpxRight = (centerPx.first + width / 2.0) / factor / TILE_SIZE
        val zpxTop = (centerPx.second - height / 2.0) / factor / TILE_SIZE
        val zpxBottom = (centerPx.second + height / 2.0) / factor / TILE_SIZE
        val rows = (1 shl tileZoom) - 1
        val x0 = floor(zpxLeft).toInt()
        val x1 = floor(zpxRight).toInt()
        val y0 = floor(zpxTop).toInt().coerceIn(0, rows)
        val y1 = floor(zpxBottom).toInt().coerceIn(0, rows)
        return TileRange(tileZoom, x0..x1, y0..y1)
    }

    /**
     * Screen offset of a geo point inside a viewport centered at [center].
     * [worldX] wraps to the nearest copy so the dateline behaves like a circle.
     */
    fun screenOffset(
        point: GeoPoint, center: GeoPoint, zoom: Double, width: Double, height: Double
    ): Pair<Double, Double> {
        val size = worldSize(zoom)
        val px = worldPixel(point.latitude, point.longitude, zoom)
        val c = worldPixel(center.latitude, center.longitude, zoom)
        return wrapDelta(px.first - c.first, size) + width / 2.0 to
            px.second - c.second + height / 2.0
    }
}

/** Tile indexes for one zoom level; x may run past the world (tiles wrap), y never does. */
internal data class TileRange(val zoom: Int, val xs: IntRange, val ys: IntRange) {
    val isEmpty: Boolean get() = xs.isEmpty() || ys.isEmpty()
}
