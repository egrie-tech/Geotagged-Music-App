package tech.egrie.soundtrail.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import tech.egrie.soundtrail.integrations.MusicLinkParser

/** Private on-device collection; deliberately no account sync or background location tracking. */
class PinStore(context: Context) {
    private val prefs = context.getSharedPreferences("soundtrail_pins", Context.MODE_PRIVATE)

    fun all(): List<MusicPin> {
        val array = runCatching { JSONArray(prefs.getString("pins", "[]")) }.getOrNull()
            ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            runCatching {
                val item = array.getJSONObject(index)
                val link = MusicLinkParser.parse(item.getString("url")) ?: return@runCatching null
                val point = GeoPoint(item.getDouble("lat"), item.getDouble("lon"))
                if (!point.isValid()) return@runCatching null
                MusicPin(
                    id = item.getString("id"),
                    title = item.getString("title"),
                    artist = item.optString("artist"),
                    place = item.optString("place", "Somewhere special"),
                    note = item.optString("note"),
                    url = link.url,
                    provider = link.provider,
                    point = point,
                    createdAt = item.getLong("createdAt")
                )
            }.getOrNull()
        }.sortedByDescending { it.createdAt }
    }

    fun add(pin: MusicPin) = save(all() + pin)

    fun remove(id: String) = save(all().filterNot { it.id == id })

    private fun save(pins: List<MusicPin>) {
        val array = JSONArray()
        pins.forEach { pin ->
            array.put(JSONObject().apply {
                put("id", pin.id)
                put("title", pin.title)
                put("artist", pin.artist)
                put("place", pin.place)
                put("note", pin.note)
                put("url", pin.url)
                put("lat", pin.point.latitude)
                put("lon", pin.point.longitude)
                put("createdAt", pin.createdAt)
            })
        }
        prefs.edit().putString("pins", array.toString()).apply()
    }
}
