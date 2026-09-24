package tech.egrie.soundtrail.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoPointTest {
    @Test fun `validates coordinates before saving`() {
        assertTrue(GeoPoint(-90.0, 180.0).isValid())
        assertFalse(GeoPoint(91.0, 0.0).isValid())
        assertFalse(GeoPoint(0.0, Double.NaN).isValid())
        assertFalse(GeoPoint(Double.POSITIVE_INFINITY, 0.0).isValid())
    }

    @Test fun `distance is symmetric and zero for the same place`() {
        val london = GeoPoint(51.5072, -0.1276)
        val paris = GeoPoint(48.8566, 2.3522)
        assertEquals(0.0, london.distanceTo(london), 0.001)
        assertEquals(london.distanceTo(paris), paris.distanceTo(london), 0.001)
        assertEquals(344_000.0, london.distanceTo(paris), 6_000.0)
    }
}
