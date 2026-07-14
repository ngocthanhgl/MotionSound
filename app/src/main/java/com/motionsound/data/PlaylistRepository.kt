package com.motionsound.data

import android.content.Context
import android.util.Log
import com.motionsound.model.Playlist
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object PlaylistRepository {

    private const val FILE_NAME = "playlists.json"
    private const val TAG = "PlaylistRepository"

    @Synchronized
    fun load(context: Context): List<Playlist> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return emptyList()
        return try {
            val json = file.readText()
            parsePlaylists(json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load playlists", e)
            emptyList()
        }
    }

    @Synchronized
    fun save(context: Context, playlists: List<Playlist>) {
        try {
            val json = toJson(playlists)
            val target = File(context.filesDir, FILE_NAME)
            val tmp = File(context.filesDir, "${FILE_NAME}.tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(target)) {
                Log.w(TAG, "Atomic rename failed, falling back to direct write")
                target.writeText(json)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save playlists", e)
        }
    }

    private fun parsePlaylists(json: String): List<Playlist> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            val songIdsArr = obj.getJSONArray("songIds")
            Playlist(
                id = obj.getString("id"),
                name = obj.getString("name"),
                songIds = (0 until songIdsArr.length()).map { j -> songIdsArr.getLong(j) },
                createdAt = obj.getLong("createdAt")
            )
        }
    }

    private fun toJson(playlists: List<Playlist>): String {
        val arr = JSONArray()
        for (p in playlists) {
            val songIdsArr = JSONArray()
            for (id in p.songIds) songIdsArr.put(id)
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("songIds", songIdsArr)
                put("createdAt", p.createdAt)
            })
        }
        return arr.toString(2)
    }
}
