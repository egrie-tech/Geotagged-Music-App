package tech.egrie.soundtrail.integrations

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import tech.egrie.soundtrail.data.GeoPoint
import java.util.Locale

/** App handoffs only. microG is never used as a music API or given this app's credentials. */
class DeviceApps(private val context: Context) {
    private fun installed(name: String): Boolean = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(name, 0)
        true
    } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
        false
    }

    val spotifyInstalled: Boolean get() = installed("com.spotify.music")
    val youtubeMusicPackage: String? get() = listOf(
        "com.google.android.apps.youtube.music", "app.revanced.android.youtube.music"
    ).firstOrNull(::installed)

    /** A standard com.google.android.gms installation may be Google Play Services OR microG. */
    val servicesLabel: String get() = when {
        installed("app.revanced.android.gms") -> "microG-compatible services detected"
        installed("com.mgoogle.android.gms") -> "microG-compatible services detected"
        installed("com.google.android.gms") -> "Google services available (may be microG)"
        else -> "No Google services detected"
    }

    fun play(link: MusicLink): Boolean {
        val verified = MusicLinkParser.parse(link.url) ?: return false
        val preferredPackage = when (verified.provider) {
            MusicProvider.SPOTIFY -> "com.spotify.music".takeIf(::installed)
            MusicProvider.YOUTUBE_MUSIC -> youtubeMusicPackage
        }
        if (preferredPackage != null && view(verified.url, preferredPackage)) return true
        return view(verified.url)
    }

    fun openYouTubeMusic(): Boolean = launch(youtubeMusicPackage) || view("https://music.youtube.com/")
    fun openSpotify(): Boolean = launch("com.spotify.music".takeIf(::installed)) || view("https://open.spotify.com/")
    fun openWeb(url: String): Boolean = view(url)

    fun showOnMap(point: GeoPoint): Boolean {
        val lat = String.format(Locale.US, "%.6f", point.latitude)
        val lon = String.format(Locale.US, "%.6f", point.longitude)
        val geo = "geo:$lat,$lon?q=$lat,$lon"
        if (view(geo)) return true
        return view("https://www.openstreetmap.org/?mlat=$lat&mlon=$lon#map=15/$lat/$lon")
    }

    private fun launch(packageName: String?): Boolean {
        if (packageName == null) return false
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        return start(intent)
    }

    private fun view(url: String, packageName: String? = null): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (packageName != null) setPackage(packageName)
        }
        return start(intent)
    }

    private fun start(intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
