package tech.egrie.soundtrail.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import tech.egrie.soundtrail.integrations.MusicLinkParser
import tech.egrie.soundtrail.integrations.MusicProvider

/** One song inside a playlist. A song may be link-less until a supported link is added. */
data class PlaylistItem(
    val id: String,
    val title: String,
    val artist: String,
    val url: String?,
    val provider: MusicProvider?,
    val createdAt: Long
)

data class Playlist(
    val id: String,
    val name: String,
    val items: List<PlaylistItem>,
    val createdAt: Long
)

/** Private on-device song lists for automation triggers; deliberately no account sync. */
class PlaylistStore(context: Context) {
    private val prefs = context.getSharedPreferences("soundtrail_playlists", Context.MODE_PRIVATE)

    fun all(): List<Playlist> {
        val array = runCatching { JSONArray(prefs.getString("playlists", "[]")) }.getOrNull()
            ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            runCatching {
                val entry = array.getJSONObject(index)
                val items = entry.optJSONArray("items") ?: JSONArray()
                Playlist(
                    id = entry.getString("id"),
                    name = entry.getString("name"),
                    items = (0 until items.length()).mapNotNull { itemIndex ->
                        runCatching {
                            val item = items.getJSONObject(itemIndex)
                            val url = item.optString("url").takeIf { it.isNotBlank() && it != "null" }
                            PlaylistItem(
                                id = item.getString("id"),
                                title = item.getString("title"),
                                artist = item.optString("artist"),
                                url = url,
                                provider = url?.let { MusicLinkParser.parse(it)?.provider },
                                createdAt = item.getLong("createdAt")
                            )
                        }.getOrNull()
                    },
                    createdAt = entry.getLong("createdAt")
                )
            }.getOrNull()
        }.sortedBy { it.createdAt }
    }

    fun find(id: String): Playlist? = all().firstOrNull { it.id == id }

    fun save(playlist: Playlist) {
        val lists = all().filterNot { it.id == playlist.id } + playlist
        write(lists.sortedBy { it.createdAt })
    }

    fun remove(id: String) = write(all().filterNot { it.id == id })

    fun addItem(playlistId: String, item: PlaylistItem): Boolean {
        val playlist = find(playlistId) ?: return false
        save(playlist.copy(items = playlist.items + item))
        return true
    }

    fun removeItem(playlistId: String, itemId: String): Boolean {
        val playlist = find(playlistId) ?: return false
        save(playlist.copy(items = playlist.items.filterNot { it.id == itemId }))
        return true
    }

    private fun write(lists: List<Playlist>) {
        val array = JSONArray()
        lists.forEach { playlist ->
            val items = JSONArray()
            playlist.items.forEach { item ->
                items.put(JSONObject().apply {
                    put("id", item.id)
                    put("title", item.title)
                    put("artist", item.artist)
                    item.url?.let { put("url", it) }
                    item.provider?.let { put("provider", it.name) }
                    put("createdAt", item.createdAt)
                })
            }
            array.put(JSONObject().apply {
                put("id", playlist.id)
                put("name", playlist.name)
                put("items", items)
                put("createdAt", playlist.createdAt)
            })
        }
        prefs.edit().putString("playlists", array.toString()).apply()
    }
}
