package tech.egrie.soundtrail.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistMergeTest {
    private val song = PlaylistItem(
        id = "s1", title = "Ribs", artist = "Lorde", url = null, provider = null, createdAt = 1L
    )
    private val local = Playlist(
        id = "p1", name = "Island ferries", createdAt = 10L,
        items = listOf(song.copy(localFile = "7.mp3"))
    )

    @Test fun `a playlist the device has never seen arrives whole`() {
        val brought = Playlist(
            id = "p2", name = "Road trip", createdAt = 20L,
            items = listOf(song, song.copy(id = "s2", title = "Motion Sickness"))
        )
        val merged = mergePlaylists(listOf(local), listOf(brought))
        assertEquals(1, merged.addedPlaylists)
        assertEquals(2, merged.addedItems)
        assertEquals(2, merged.lists.size)
        assertEquals("Road trip", merged.lists.single { it.id == "p2" }.name)
        // The local playlist must come through untouched.
        assertEquals(local, merged.lists.single { it.id == "p1" })
    }

    @Test fun `a known playlist only gains the songs it is missing`() {
        val brought = local.copy(items = local.items + song.copy(id = "s2", title = "Team"))
        val merged = mergePlaylists(listOf(local), listOf(brought))
        assertEquals(0, merged.addedPlaylists)
        assertEquals(1, merged.addedItems)
        val restored = merged.lists.single()
        assertEquals(listOf("s1", "s2"), restored.items.map { it.id })
        // The local copy of s1 (with its audio file) is the one that survives.
        assertEquals("7.mp3", restored.items.first().localFile)
    }

    @Test fun `re-importing the same backup changes nothing`() {
        val backup = local.copy(items = local.items + song.copy(id = "s2"))
        val once = mergePlaylists(listOf(local), listOf(backup))
        val twice = mergePlaylists(once.lists, listOf(backup))
        assertEquals(0, twice.addedPlaylists)
        assertEquals(0, twice.addedItems)
        assertEquals(once.lists, twice.lists)
    }

    @Test fun `local edits are never overwritten by an older backup`() {
        val edited = local.copy(name = "Renamed here", items = emptyList())
        val merged = mergePlaylists(listOf(edited), listOf(local))
        // The rename wins; the backup's song only adds itself back, overwriting nothing.
        assertEquals(0, merged.addedPlaylists)
        assertEquals(1, merged.addedItems)
        assertEquals("Renamed here", merged.lists.single().name)
        assertEquals(listOf("s1"), merged.lists.single().items.map { it.id })
    }
}
