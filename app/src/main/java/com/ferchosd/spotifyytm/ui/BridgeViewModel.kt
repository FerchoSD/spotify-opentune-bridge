package com.ferchosd.spotifyytm.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ferchosd.spotifyytm.BuildConfig
import com.ferchosd.spotifyytm.auth.PkceAuth
import com.ferchosd.spotifyytm.auth.Tokens
import com.ferchosd.spotifyytm.match.TrackMatcher
import com.ferchosd.spotifyytm.spotify.SpotifyApi
import com.ferchosd.spotifyytm.spotify.SpotifyPlaylist
import com.ferchosd.spotifyytm.spotify.SpotifyTrack
import com.ferchosd.spotifyytm.youtube.YouTubeApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val Context.dataStore by preferencesDataStore(name = "auth_tokens")

enum class Provider { SPOTIFY, GOOGLE }

data class BridgeState(
    val spotifyConnected: Boolean = false,
    val spotifyName: String = "",
    val googleConnected: Boolean = false,
    val playlists: List<SpotifyPlaylist> = emptyList(),
    val selectedPlaylistId: String? = null,
    val tracks: List<SpotifyTrack> = emptyList(),
    val converting: Boolean = false,
    val progressDone: Int = 0,
    val progressTotal: Int = 0,
    val createdPlaylistId: String? = null,
    val createdPlaylistTitle: String = "",
    val unmatched: List<String> = emptyList(),
    val error: String? = null,
)

class BridgeViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(BridgeState())
    val state = _state.asStateFlow()

    private val spotifyApi = SpotifyApi()
    private val youTubeApi = YouTubeApi()

    private var pendingProvider: Provider? = null
    private var pendingVerifier: String? = null
    private var pendingState: String? = null

    companion object {
        const val REDIRECT_URI = "com.ferchosd.spotifyytm://callback"

        private const val SPOTIFY_AUTH = "https://accounts.spotify.com/authorize"
        private const val SPOTIFY_TOKEN = "https://accounts.spotify.com/api/token"
        private val SPOTIFY_SCOPES = listOf("playlist-read-private", "playlist-read-collaborative")

        private const val GOOGLE_AUTH = "https://accounts.google.com/o/oauth2/v2/auth"
        private const val GOOGLE_TOKEN = "https://oauth2.googleapis.com/token"
        private val GOOGLE_SCOPES = listOf("https://www.googleapis.com/auth/youtube")

        private val SPOTIFY_ACCESS = stringPreferencesKey("spotify_access")
        private val SPOTIFY_REFRESH = stringPreferencesKey("spotify_refresh")
        private val SPOTIFY_EXPIRES = longPreferencesKey("spotify_expires")
        private val GOOGLE_ACCESS = stringPreferencesKey("google_access")
        private val GOOGLE_REFRESH = stringPreferencesKey("google_refresh")
        private val GOOGLE_EXPIRES = longPreferencesKey("google_expires")
    }

    init {
        viewModelScope.launch {
            val spotify = readTokens(Provider.SPOTIFY)
            if (spotify != null) {
                _state.value = _state.value.copy(spotifyConnected = true)
                loadPlaylists()
            }
            if (readTokens(Provider.GOOGLE) != null) {
                _state.value = _state.value.copy(googleConnected = true)
            }
        }
    }

    fun startLogin(context: Context, provider: Provider) {
        val clientId = when (provider) {
            Provider.SPOTIFY -> BuildConfig.SPOTIFY_CLIENT_ID
            Provider.GOOGLE -> BuildConfig.GOOGLE_CLIENT_ID
        }
        if (clientId.isBlank()) {
            _state.value = _state.value.copy(
                error = "Falta el Client ID de ${provider.name} en local.properties. Revisa el README."
            )
            return
        }
        val (authEndpoint, tokenEndpoint, scopes) = when (provider) {
            Provider.SPOTIFY -> Triple(SPOTIFY_AUTH, SPOTIFY_TOKEN, SPOTIFY_SCOPES)
            Provider.GOOGLE -> Triple(GOOGLE_AUTH, GOOGLE_TOKEN, GOOGLE_SCOPES)
        }
        val extra = if (provider == Provider.GOOGLE) {
            mapOf("access_type" to "offline", "prompt" to "consent")
        } else emptyMap()
        val request = PkceAuth.buildAuthRequest(
            authEndpoint = authEndpoint,
            clientId = clientId,
            redirectUri = REDIRECT_URI,
            scopes = scopes,
            extraParams = extra,
        )
        pendingProvider = provider
        pendingVerifier = request.codeVerifier
        pendingState = request.state
        val intent = CustomTabsIntent.Builder().build().intent.apply {
            data = Uri.parse(request.authUrl)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun handleDeepLink(uri: Uri?) {
        val provider = pendingProvider ?: return
        val verifier = pendingVerifier ?: return
        val expectedState = pendingState
        val code = uri?.getQueryParameter("code")
        val state = uri?.getQueryParameter("state")
        val error = uri?.getQueryParameter("error")
        if (error != null) {
            _state.value = _state.value.copy(error = "Inicio de sesión cancelado o fallido: $error")
            clearPending()
            return
        }
        if (code.isNullOrBlank() || state != expectedState) {
            _state.value = _state.value.copy(error = "Respuesta de inicio de sesión inválida.")
            clearPending()
            return
        }
        clearPending()
        viewModelScope.launch {
            try {
                val (tokenEndpoint, clientId) = when (provider) {
                    Provider.SPOTIFY -> SPOTIFY_TOKEN to BuildConfig.SPOTIFY_CLIENT_ID
                    Provider.GOOGLE -> GOOGLE_TOKEN to BuildConfig.GOOGLE_CLIENT_ID
                }
                val tokens = PkceAuth.exchangeCode(
                    tokenEndpoint = tokenEndpoint,
                    clientId = clientId,
                    redirectUri = REDIRECT_URI,
                    code = code,
                    codeVerifier = verifier,
                )
                saveTokens(provider, tokens)
                if (provider == Provider.SPOTIFY) {
                    _state.value = _state.value.copy(spotifyConnected = true)
                    loadPlaylists()
                } else {
                    _state.value = _state.value.copy(googleConnected = true)
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = "No se pudo completar el inicio de sesión: ${e.message}")
            }
        }
    }

    fun selectPlaylist(playlistId: String) {
        _state.value = _state.value.copy(
            selectedPlaylistId = playlistId,
            createdPlaylistId = null,
            unmatched = emptyList(),
            error = null,
        )
        viewModelScope.launch {
            try {
                val token = validAccessToken(Provider.SPOTIFY)
                    ?: throw IllegalStateException("Conecta Spotify primero.")
                val tracks = spotifyApi.getPlaylistTracks(token, playlistId)
                _state.value = _state.value.copy(tracks = tracks)
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = "No se pudieron cargar las canciones: ${e.message}")
            }
        }
    }

    fun convert() {
        val playlistId = _state.value.selectedPlaylistId ?: return
        val playlist = _state.value.playlists.firstOrNull { it.id == playlistId } ?: return
        val tracks = _state.value.tracks
        if (tracks.isEmpty()) {
            _state.value = _state.value.copy(error = "La playlist no tiene canciones o aún no cargan.")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(
                converting = true,
                progressDone = 0,
                progressTotal = tracks.size,
                createdPlaylistId = null,
                unmatched = emptyList(),
                error = null,
            )
            try {
                val googleToken = validAccessToken(Provider.GOOGLE)
                    ?: throw IllegalStateException("Conecta Google primero.")
                val title = "${playlist.name} (de Spotify)"
                val newPlaylistId = youTubeApi.createPlaylist(
                    accessToken = googleToken,
                    title = title,
                    description = "Playlist convertida desde Spotify con Spotify a YouTube Music.",
                )
                val unmatched = mutableListOf<String>()
                var done = 0
                for (track in tracks) {
                    try {
                        val query = "${track.name} ${track.artists.joinToString(" ") { it.name }}"
                        val candidates = youTubeApi.search(googleToken, query)
                        val best = TrackMatcher.pickBest(track, candidates)
                        if (best != null) {
                            youTubeApi.addVideo(googleToken, newPlaylistId, best.id)
                        } else {
                            unmatched += "${track.name} - ${track.artists.joinToString(", ") { it.name }}"
                        }
                    } catch (e: Exception) {
                        unmatched += "${track.name} - ${track.artists.joinToString(", ") { it.name }}"
                    }
                    done++
                    _state.value = _state.value.copy(progressDone = done)
                }
                _state.value = _state.value.copy(
                    converting = false,
                    createdPlaylistId = newPlaylistId,
                    createdPlaylistTitle = title,
                    unmatched = unmatched,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    converting = false,
                    error = "La conversión falló: ${e.message}",
                )
            }
        }
    }

    fun loadPlaylists() {
        viewModelScope.launch {
            try {
                val token = validAccessToken(Provider.SPOTIFY) ?: return@launch
                val playlists = spotifyApi.getPlaylists(token)
                val name = runCatching { spotifyApi.getDisplayName(token) }.getOrDefault("")
                _state.value = _state.value.copy(playlists = playlists, spotifyName = name)
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = "No se pudieron cargar tus playlists: ${e.message}")
            }
        }
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    private fun clearPending() {
        pendingProvider = null
        pendingVerifier = null
        pendingState = null
    }

    private suspend fun validAccessToken(provider: Provider): String? {
        val tokens = readTokens(provider) ?: return null
        if (tokens.expiresAtMs > System.currentTimeMillis() + 60_000) return tokens.accessToken
        val refreshToken = tokens.refreshToken ?: return null
        val (tokenEndpoint, clientId) = when (provider) {
            Provider.SPOTIFY -> SPOTIFY_TOKEN to BuildConfig.SPOTIFY_CLIENT_ID
            Provider.GOOGLE -> GOOGLE_TOKEN to BuildConfig.GOOGLE_CLIENT_ID
        }
        return try {
            val refreshed = PkceAuth.refresh(tokenEndpoint, clientId, refreshToken)
            saveTokens(provider, refreshed)
            refreshed.accessToken
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun readTokens(provider: Provider): Tokens? {
        val (accessKey, refreshKey, expiresKey) = when (provider) {
            Provider.SPOTIFY -> Triple(SPOTIFY_ACCESS, SPOTIFY_REFRESH, SPOTIFY_EXPIRES)
            Provider.GOOGLE -> Triple(GOOGLE_ACCESS, GOOGLE_REFRESH, GOOGLE_EXPIRES)
        }
        val prefs = getApplication<Application>().dataStore.data.first()
        val access = prefs[accessKey] ?: return null
        return Tokens(
            accessToken = access,
            refreshToken = prefs[refreshKey],
            expiresAtMs = prefs[expiresKey] ?: 0L,
        )
    }

    private suspend fun saveTokens(provider: Provider, tokens: Tokens) {
        val (accessKey, refreshKey, expiresKey) = when (provider) {
            Provider.SPOTIFY -> Triple(SPOTIFY_ACCESS, SPOTIFY_REFRESH, SPOTIFY_EXPIRES)
            Provider.GOOGLE -> Triple(GOOGLE_ACCESS, GOOGLE_REFRESH, GOOGLE_EXPIRES)
        }
        getApplication<Application>().dataStore.edit { prefs ->
            prefs[accessKey] = tokens.accessToken
            if (tokens.refreshToken != null) prefs[refreshKey] = tokens.refreshToken
            prefs[expiresKey] = tokens.expiresAtMs
        }
    }
}
