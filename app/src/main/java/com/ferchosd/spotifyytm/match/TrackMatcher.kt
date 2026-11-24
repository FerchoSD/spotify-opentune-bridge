package com.ferchosd.spotifyytm.match

import com.ferchosd.spotifyytm.spotify.SpotifyTrack
import com.ferchosd.spotifyytm.youtube.YtVideo

/**
 * Elige el mejor resultado de YouTube para una canción de Spotify
 * por título, artista y duración. Umbral mínimo 0.35: por debajo
 * se reporta como sin coincidencia en vez de agregar algo incorrecto.
 */
object TrackMatcher {

    private val NOISE_WORDS = setOf(
        "remaster", "remastered", "remasterizado", "remasterizada",
        "official", "video", "lyric", "lyrics", "audio", "hd",
        "letra", "officialvideo", "officialaudio",
    )

    fun pickBest(track: SpotifyTrack, candidates: List<YtVideo>): YtVideo? {
        if (candidates.isEmpty()) return null
        val trackTitle = normalize(track.name)
        val trackArtist = normalize(track.artists.joinToString(" ") { it.name })
        val scored = candidates.map { candidate ->
            candidate to score(
                trackTitle = trackTitle,
                trackArtist = trackArtist,
                trackDurationMs = track.duration_ms,
                candidate = candidate,
            )
        }
        val (best, bestScore) = scored.maxByOrNull { it.second } ?: return null
        return if (bestScore >= 0.35) best else null
    }

    internal fun score(
        trackTitle: String,
        trackArtist: String,
        trackDurationMs: Long,
        candidate: YtVideo,
    ): Double {
        val titleSim = similarity(trackTitle, normalize(candidate.title))
        val artistSim = similarity(trackArtist, normalize(candidate.channel)) * 0.6 +
            tokenOverlap(trackArtist, normalize(candidate.title)) * 0.4
        val durationScore = if (trackDurationMs > 0 && candidate.durationSec > 0) {
            val diffSec = kotlin.math.abs(trackDurationMs / 1000 - candidate.durationSec)
            maxOf(0.0, 1.0 - diffSec / 60.0)
        } else {
            0.5
        }
        return 0.5 * titleSim + 0.3 * artistSim.coerceIn(0.0, 1.0) + 0.2 * durationScore
    }

    internal fun normalize(raw: String): String {
        var text = raw.lowercase()
        text = text.replace(Regex("\\([^)]*\\)"), " ")
        text = text.replace(Regex("\\[[^]]*]"), " ")
        text = text.replace(Regex("[^a-z0-9áéíóúñü ]"), " ")
        val tokens = text.split(Regex("\\s+"))
            .filter { it.isNotBlank() && it !in NOISE_WORDS }
        return tokens.joinToString(" ")
    }

    internal fun tokenOverlap(a: String, b: String): Double {
        val setA = a.split(" ").filter(String::isNotBlank).toSet()
        val setB = b.split(" ").filter(String::isNotBlank).toSet()
        if (setA.isEmpty() || setB.isEmpty()) return 0.0
        return setA.intersect(setB).size.toDouble() / maxOf(setA.size, setB.size)
    }

    internal fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        val distance = levenshtein(a, b)
        val base = 1.0 - distance.toDouble() / maxOf(a.length, b.length)
        return maxOf(base, tokenOverlap(a, b))
    }

    private fun levenshtein(a: String, b: String): Int {
        val m = a.length
        val n = b.length
        if (m == 0) return n
        if (n == 0) return m
        var prev = IntArray(n + 1) { it }
        var curr = IntArray(n + 1)
        for (i in 1..m) {
            curr[0] = i
            for (j in 1..n) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[n]
    }
}
