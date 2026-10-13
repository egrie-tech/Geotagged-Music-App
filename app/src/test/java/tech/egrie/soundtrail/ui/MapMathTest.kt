package tech.egrie.soundtrail.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.egrie.soundtrail.data.GeoPoint
import kotlin.math.abs

class MapMathTest {
    @Test fun `places the origin and corners where a tile grid expects them`() {
        val (x0, y0) = MapMath.worldPixel(0.0, 0.0, 0.0)
        assertEquals(128.0, x0, 0.001)
        assertEquals(128.0, y0, 0.001)

        val (xEast, yNorth) = MapMath.worldPixel(MapMath.MAX_LATITUDE, 180.0, 0.0)
        assertEquals(256.0, xEast, 0.001)
        assertEquals(0.0, yNorth, 0.01)

        assertEquals(256.0 * 1024.0, MapMath.worldSize(10.0), 0.001)
    }

    @Test fun `pixel and geo conversions round-trip`() {
        listOf(
            GeoPoint(43.6532, -79.3832), GeoPoint(-33.8688, 151.2093),
            GeoPoint(64.1466, -21.9426), GeoPoint(0.0, 179.999)
        ).forEach { point ->
            listOf(2.0, 7.0, 13.5, 18.0).forEach { zoom ->
                val (x, y) = MapMath.worldPixel(point.latitude, point.longitude, zoom)
                val back = MapMath.pointAt(x, y, zoom)
                assertEquals("lat @ $zoom", point.latitude, back.latitude, 1e-6)
                assertEquals("lon @ $zoom", point.longitude, back.longitude, 1e-6)
            }
        }
    }

    @Test fun `clamps latitudes beyond the mercator square`() {
        assertEquals(MapMath.MAX_LATITUDE, MapMath.clampLatitude(90.0), 0.0)
        assertEquals(-MapMath.MAX_LATITUDE, MapMath.clampLatitude(-90.0), 0.0)
        assertEquals(45.0, MapMath.clampLatitude(45.0), 0.0)
        // The clamped pole must land on the world's edge, not NaN.
        val y = MapMath.worldPixel(90.0, 0.0, 4.0).second
        assertTrue(abs(y) < 1e-6)
    }

    @Test fun `world height fractions run north to south`() {
        assertEquals(0.5, MapMath.mercatorY(0.0), 1e-9)
        assertTrue(MapMath.mercatorY(60.0) < 0.5)
        assertTrue(MapMath.mercatorY(-60.0) > 0.5)
        assertEquals(0.0, MapMath.mercatorY(MapMath.MAX_LATITUDE), 1e-6)
    }

    @Test fun `wraps longitudes and shortest pixel deltas around the world`() {
        assertEquals(-170.0, MapMath.wrapLongitude(190.0), 1e-9)
        assertEquals(170.0, MapMath.wrapLongitude(-190.0), 1e-9)
        // The seam is half-open: 180 lands on -180, the same meridian.
        assertEquals(-180.0, MapMath.wrapLongitude(180.0), 1e-9)
        assertEquals(-10.0, MapMath.wrapDelta(350.0, 360.0), 1e-9)
        assertEquals(-10.0, MapMath.wrapDelta(-10.0, 360.0), 1e-9)
        assertEquals(5.0, MapMath.wrapDelta(5.0, 360.0), 1e-9)
    }

    @Test fun `fitting tight clusters zooms closer than fitting far apart pins`() {
        val near = MapMath.fitTo(listOf(GeoPoint(43.65, -79.38), GeoPoint(43.66, -79.37)), 800.0, 600.0)
        val far = MapMath.fitTo(listOf(GeoPoint(43.65, -79.38), GeoPoint(51.507, -0.1276)), 800.0, 600.0)
        assertTrue("cluster zoom ${near.second} should exceed intercity zoom ${far.second}",
            near.second > far.second)
        assertTrue(near.second in MapMath.MIN_ZOOM..MapMath.MAX_FIT_ZOOM)
        assertTrue(far.second in MapMath.MIN_ZOOM..MapMath.MAX_FIT_ZOOM)
        // The fitted center should sit inside the cluster's frame, not at 0,0.
        assertTrue(abs(near.first.latitude) > 40.0)
    }

    @Test fun `single pin and empty maps get sensible defaults`() {
        val one = GeoPoint(43.65, -79.38) to 14.0
        val fitted = MapMath.fitTo(listOf(GeoPoint(43.65, -79.38)), 800.0, 600.0)
        assertEquals(one.second, fitted.second, 0.0)
        assertEquals(one.first.latitude, fitted.first.latitude, 1e-9)
        assertEquals(MapMath.MIN_ZOOM, MapMath.fitTo(emptyList(), 800.0, 600.0).second, 0.0)
        assertEquals(MapMath.MIN_ZOOM, MapMath.fitTo(listOf(GeoPoint(43.65, -79.38)), 0.0, 0.0).second, 0.0)
    }

    @Test fun `lists the tiles covering the viewport`() {
        val range = MapMath.visibleTiles(GeoPoint(0.0, 0.0), 3.0, 256.0, 256.0)
        assertEquals(3, range.zoom)
        assertEquals(3..4, range.xs)
        assertEquals(3..4, range.ys)

        // A fractional zoom still selects the floor's tiles and the view stays finite.
        val fractional = MapMath.visibleTiles(GeoPoint(0.0, 0.0), 3.7, 400.0, 300.0)
        assertEquals(3, fractional.zoom)
        assertTrue(fractional.xs.first <= fractional.xs.last)
        assertTrue(fractional.ys.first >= 0)

        // Above the north pole edge the y range clamps instead of going negative.
        val top = MapMath.visibleTiles(GeoPoint(85.0, 0.0), 4.0, 300.0, 300.0)
        assertTrue(top.ys.first >= 0)
    }

    @Test fun `screen offsets center the viewport and wrap at the dateline`() {
        val center = GeoPoint(0.0, 0.0)
        val (sx, sy) = MapMath.screenOffset(center, center, 4.0, 400.0, 300.0)
        assertEquals(200.0, sx, 1e-6)
        assertEquals(150.0, sy, 1e-6)

        // A pin just across the antimeridian appears at the near edge, not a world away.
        val near = MapMath.screenOffset(GeoPoint(0.0, 179.5), GeoPoint(0.0, -179.5), 4.0, 400.0, 300.0)
        assertTrue(near.first in 0.0..400.0)
    }
}
