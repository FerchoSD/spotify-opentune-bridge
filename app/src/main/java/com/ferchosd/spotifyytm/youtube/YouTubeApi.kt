package com.ferchosd.spotifyytm.youtube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

@Serializable
private data class SearchResponse(val items: List<SearchItem> = emptyList())

@Serializable
private data class SearchItem(
    val id: SearchId = SearchId(),
    val snippet: Snippet = Snippet(),
)

@Serializable
private data class SearchId(val videoId: String = "")

@Serializable
private data class Snippet(val title: String = "", val channelTitle: String = "")

@Serializable
private data class VideosResponse(val items: List<VideoItem> = emptyList())

@Serializable
private data class VideoItem(
    val id: String = "",
    val contentDetails: ContentDetails = ContentDetails(),
)

@Serializable
private data class ContentDetails(val duration: String = "")

data class YtVideo(
    val id: String,
    val title: String,
    val channel: String,
    val durationSec: Long,
)

/**
 * Cliente mínimo de la YouTube Data API v3 (oficial).
 * Busca videos, crea playlists y agrega videos a la playlist.
 */
class YouTubeApi {
    private val http = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun search(accessToken: String, query: String, maxResults: Int = 5): List<YtVideo> =
        withContext(Dispatchers.IO) {
            val q = URLEncoder.encode(query, "UTF-8")
            val url = "https://www.googleapis.com/youtube/v3/search" +
                "?part=snippet&type=video&maxResults=$maxResults&q=$q"
            val response = json.decodeFromString<SearchResponse>(get(url, accessToken))
            val ids = response.items.mapNotNull { it.id.videoId.takeIf(String::isNotBlank) }
            if (ids.isEmpty()) return@withContext emptyList()
            val durations = getDurations(accessToken, ids)
            response.items.mapNotNull { item ->
                val id = item.id.videoId.takeIf(String::isNotBlank) ?: return@mapNotNull null
                YtVideo(
                    id = id,
                    title = item.snippet.title,
                    channel = item.snippet.channelTitle,
                    durationSec = durations[id] ?: -1,
                )
            }
        }

    suspend fun createPlaylist(accessToken: String, title: String, description: String): String =
        withContext(Dispatchers.IO) {
            val bodyJson = buildJsonObject {
                putJsonObject("snippet") {
                    put("title", title)
                    put("description", description)
                }
                putJsonObject("status") {
                    put("privacyStatus", "private")
                }
            }.toString()
            val text = post(
                "https://www.googleapis.com/youtube/v3/playlists?part=snippet,status",
                accessToken,
                bodyJson,
            )
            val id = json.parseToJsonElement(text)
                .let { it.toString() }
                .let { Regex("\"id\"\\s*:\\s*\"([^\"]*)\"").find(it)?.groupValues?.getOrNull(1) }
                ?: throw IllegalStateException("No se pudo crear la playlist: $text")
            id
        }

    suspend fun addVideo(accessToken: String, playlistId: String, videoId: String) =
        withContext(Dispatchers.IO) {
            val bodyJson = buildJsonObject {
                putJsonObject("snippet") {
                    put("playlistId", playlistId)
                    putJsonObject("resourceId") {
                        put("kind", "youtube#video")
                        put("videoId", videoId)
                    }
                }
            }.toString()
            post(
                "https://www.googleapis.com/youtube/v3/playlistItems?part=snippet",
                accessToken,
                bodyJson,
            )
        }

    private fun getDurations(accessToken: String, videoIds: List<String>): Map<String, Long> {
        val url = "https://www.googleapis.com/youtube/v3/videos" +
            "?part=contentDetails&id=${videoIds.joinToString(",")}"
        val response = json.decodeFromString<VideosResponse>(get(url, accessToken))
        return response.items.associate { it.id to parseIso8601Duration(it.contentDetails.duration) }
    }

    /** PT3M21S, PT1H2M3S, PT45S -> segundos. */
    internal fun parseIso8601Duration(value: String): Long {
        val match = Regex("PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?").matchEntire(value)
            ?: return -1
        val h = match.groupValues[1].toLongOrNull() ?: 0
        val m = match.groupValues[2].toLongOrNull() ?: 0
        val s = match.groupValues[3].toLongOrNull() ?: 0
        return h * 3600 + m * 60 + s
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
            if (!it.isSuccessful) throw IllegalStateException("YouTube API falló (${it.code}): $text")
            return text
        }
    }

    private fun post(url: String, accessToken: String, bodyJson: String): String {
        val response = http.newCall(
            Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .post(bodyJson.toRequestBody(jsonMediaType))
                .build()
        ).execute()
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw IllegalStateException("YouTube API falló (${it.code}): $text")
            return text
        }
    }
}
