package com.example.musicplayer

import android.content.ContentUris
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.MediaStore.Audio.Media
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/** Loads ALL audio on the phone; `music` marks real songs (used by the "Music only" filter). */
fun loadSongs(ctx: Context): List<Song> {
    val base = Media.EXTERNAL_CONTENT_URI
    val artBase = Uri.parse("content://media/external/audio/albumart")
    val hasGenre = Build.VERSION.SDK_INT >= 30   // genre column exists on Android 11+
    val cols = buildList {
        addAll(listOf(Media._ID, Media.TITLE, Media.ARTIST, Media.ALBUM_ID, Media.IS_MUSIC, Media.DURATION))
        if (hasGenre) add(Media.GENRE)
    }.toTypedArray()
    val out = mutableListOf<Song>()
    ctx.contentResolver.query(base, cols, null, null, "${Media.TITLE} COLLATE NOCASE ASC")?.use { c ->
        val idC = c.getColumnIndexOrThrow(Media._ID)
        val tC = c.getColumnIndexOrThrow(Media.TITLE)
        val aC = c.getColumnIndexOrThrow(Media.ARTIST)
        val alC = c.getColumnIndexOrThrow(Media.ALBUM_ID)
        val mC = c.getColumnIndexOrThrow(Media.IS_MUSIC)
        val dC = c.getColumnIndexOrThrow(Media.DURATION)
        val gC = if (hasGenre) c.getColumnIndex(Media.GENRE) else -1
        while (c.moveToNext()) {
            val artist = c.getString(aC)
            val g = if (gC >= 0) c.getString(gC) else null
            out += Song(
                id = c.getLong(idC),
                title = c.getString(tC) ?: "Unknown title",
                artist = if (artist.isNullOrBlank() || artist == "<unknown>") "Unknown artist" else artist,
                uri = ContentUris.withAppendedId(base, c.getLong(idC)),
                art = ContentUris.withAppendedId(artBase, c.getLong(alC)),
                music = c.getInt(mC) != 0 && c.getLong(dC) >= 30000,
                genre = if (g.isNullOrBlank() || g == "<unknown>") "Unknown genre" else g
            )
        }
    }
    return out
}

fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

/** Favourites + playlists, saved on the phone and observable by Compose. */
class UserData(private val prefs: SharedPreferences) {
    var favs by mutableStateOf<Set<Long>>(
        prefs.getStringSet("favs", emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet()
    )
        private set

    var lists by mutableStateOf<Map<String, List<Long>>>(
        try {
            val o = JSONObject(prefs.getString("lists", "{}")!!)
            o.keys().asSequence().associateWith { n ->
                val a = o.getJSONArray(n)
                (0 until a.length()).map { a.getLong(it) }
            }
        } catch (e: Exception) { emptyMap() }
    )
        private set

    private fun save() {
        val o = JSONObject()
        lists.forEach { (n, ids) -> o.put(n, JSONArray(ids)) }
        prefs.edit()
            .putStringSet("favs", favs.map { it.toString() }.toSet())
            .putString("lists", o.toString())
            .apply()
    }

    fun toggleFav(id: Long) { favs = if (id in favs) favs - id else favs + id; save() }
    fun createList(name: String) { if (name !in lists) { lists = lists + (name to emptyList()); save() } }
    fun deleteList(name: String) { lists = lists - name; save() }
    fun addToList(name: String, id: Long) {
        val cur = lists[name] ?: return
        if (id !in cur) { lists = lists + (name to (cur + id)); save() }
    }
    fun removeFromList(name: String, id: Long) {
        val cur = lists[name] ?: return
        lists = lists + (name to (cur - id)); save()
    }
}
