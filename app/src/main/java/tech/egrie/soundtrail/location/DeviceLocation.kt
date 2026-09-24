package tech.egrie.soundtrail.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import tech.egrie.soundtrail.data.GeoPoint
import kotlin.coroutines.resume

class LocationUnavailable(message: String) : Exception(message)

/** One foreground location fix, using Android's LocationManager (no Play Services dependency). */
class DeviceLocation(private val context: Context) {
    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    suspend fun locate(): GeoPoint = withContext(Dispatchers.Main) {
        if (!hasPermission()) throw LocationUnavailable("Allow location, or enter coordinates manually.")
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        // Network location is faster indoors and may be supplied by microG; GPS is a fallback.
        val providers = buildList {
            if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
            if (fine && manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
        }
        if (providers.isEmpty()) {
            throw LocationUnavailable("Turn on device location, or enter coordinates manually.")
        }
        try {
            val last = providers.mapNotNull(manager::getLastKnownLocation).maxByOrNull(Location::getTime)
            if (last != null && System.currentTimeMillis() - last.time in 0L..120_000L) {
                return@withContext last.toPoint()
            }
            withTimeoutOrNull(15_000L) {
                suspendCancellableCoroutine<GeoPoint> { continuation ->
                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            runCatching { manager.removeUpdates(this) }
                            if (continuation.isActive) continuation.resume(location.toPoint())
                        }
                        override fun onProviderDisabled(provider: String) = Unit
                        override fun onProviderEnabled(provider: String) = Unit
                        @Deprecated("Legacy LocationListener callback")
                        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                    }
                    continuation.invokeOnCancellation { runCatching { manager.removeUpdates(listener) } }
                    try {
                        providers.forEach { provider ->
                            manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                        }
                    } catch (e: Exception) {
                        runCatching { manager.removeUpdates(listener) }
                        throw e
                    }
                }
            } ?: throw LocationUnavailable("Couldn't get a location fix. Try outside or enter coordinates.")
        } catch (_: SecurityException) {
            throw LocationUnavailable("Location permission changed. Try again or enter coordinates.")
        }
    }

    private fun Location.toPoint() = GeoPoint(latitude, longitude)
}
