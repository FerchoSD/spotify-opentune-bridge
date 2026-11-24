package com.ferchosd.spotifyytm.spotify

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

@Serializable
data class SpotifyPlaylist(
    val id: String,
    val name: String,
    val tracks: TracksRef? = null,
)

@Serializable
data class TracksRef(val total: Int = 0)

@Serializable
data class SpotifyTrack(
    val id: String,
    val name: String,
    val artists: List<Artist> = emptyList(),
    val duration_ms: Long = 0,
    val is_local: Boolean = false,
)

@Serializable
data class Artist(val name: String)

@Serializable
private data class PlaylistPage(
    val items: List<SpotifyPlaylist> = emptyList(),
    val total: Int = 0,
)

@Serializable
private data class TrackPageItem(val track: SpotifyTrack? = null)

@Serializable
private data class TrackPage(
    val items: List<TrackPageItem> = emptyList(),
    val total: Int = 0,
)

/**
 * Cliente mínimo de la Spotify Web API (oficial).
 * Solo lee playlists y canciones; no modifica nada en Spotify.
 */
class SpotifyApi {
    private val http = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getMyPlaylists(accessToken: String): List<SpotifyPlaylist> =
        withContext(Dispatchers.IO) {
            val out = mutableListOf<SpotifyPlaylist>()
            var url: String? = "https://api.spotify.com/v1/me/playlists?limit=50"
            while (url != null) {
                val page = json.decodeFromString<PlaylistPage>(get(url, accessToken))
                out += page.items
                url = if (out.size < page.total && page.items.isNotEmpty()) {
                    "https://api.spotify.com/v1/me/playlists?limit=50&offset=${out.size}"
                } else null
            }
            out
        }

    suspend fun getPlaylistTracks(accessToken: String, playlistId: String): List<SpotifyTrack> =
        withContext(Dispatchers.IO) {
            val out = mutableListOf<SpotifyTrack>()
            var offset = 0
            var total = Int.MAX_VALUE
            val fields = URLEncoder.encode(
                "items(track(id,name,artists(name),duration_ms,is_local)),total",
                "UTF-8"
            )
            while (offset < total) {
                val url = "https://api.spotify.com/v1/playlists/$playlistId/tracks" +
                    "?limit=50&offset=$offset&fields=$fields"
                val page = json.decodeFromString<TrackPage>(get(url, accessToken))
                total = page.total
                out += page.items.mapNotNull { it.track }
                    .filter { !it.is_local && it.id.isNotBlank() }
                offset += page.items.size
                if (page.items.isEmpty()) break
            }
            out
        }

    suspend fun getDisplayName(accessToken: String): String = withContext(Dispatchers.IO) {
        val text = get("https://api.spotify.com/v1/me", accessToken)
        json.parseToJsonElement(text).toString().let {
            // Extracción simple del display_name sin modelo extra.
            Regex("\"display_name\"\\s*:\\s*\"([^\"]*)\"").find(it)?.groupValues?.getOrNull(1)
                ?: "tu cuenta"
        }
    }

    private fun get(url: String, accessToken: String): String {
        val response = http.newCall(
            Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .build()
        ).execute()
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw IllegalStateException("Spotify API falló (${it.code}): $text")
            return text
        }
    }
}
