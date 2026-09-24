package tech.egrie.soundtrail.data

import tech.egrie.soundtrail.integrations.MusicProvider
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** Coordinates never leave this device; only the provider link is opened externally. */
data class GeoPoint(val latitude: Double, val longitude: Double) {
    fun isValid(): Boolean = latitude.isFinite() && longitude.isFinite() &&
        latitude in -90.0..90.0 && longitude in -180.0..180.0

    fun distanceTo(other: GeoPoint): Double {
        val lat1 = Math.toRadians(latitude)
        val lat2 = Math.toRadians(other.latitude)
        val deltaLat = Math.toRadians(other.latitude - latitude)
        val deltaLon = Math.toRadians(other.longitude - longitude)
        val a = sin(deltaLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(deltaLon / 2).pow(2)
        return 6_371_000.0 * 2 * asin(kotlin.math.sqrt(a.coerceIn(0.0, 1.0)))
    }
}

data class MusicPin(
    val id: String,
    val title: String,
    val artist: String,
    val place: String,
    val note: String,
    val url: String,
    val provider: MusicProvider,
    val point: GeoPoint,
    val createdAt: Long
)
