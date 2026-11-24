package com.ferchosd.spotifyytm

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.ferchosd.spotifyytm.ui.BridgeState
import com.ferchosd.spotifyytm.ui.BridgeViewModel
import com.ferchosd.spotifyytm.ui.Provider

class MainActivity : ComponentActivity() {

    private val viewModel: BridgeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.handleDeepLink(intent?.data)
        setContent {
            MaterialTheme {
                App(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        viewModel.handleDeepLink(intent.data)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(viewModel: BridgeViewModel) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    Scaffold(
        topBar = { TopAppBar(title = { Text("Spotify a YouTube Music") }) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (BuildConfig.SPOTIFY_CLIENT_ID.isBlank() || BuildConfig.GOOGLE_CLIENT_ID.isBlank()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "Faltan los Client ID",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                "Crea el archivo local.properties con SPOTIFY_CLIENT_ID y " +
                                    "GOOGLE_CLIENT_ID. Los pasos están en el README del repositorio.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }

            item {
                StepCard(
                    title = "1. Spotify",
                    body = if (state.spotifyConnected) {
                        "Conectado como ${state.spotifyName.ifBlank { "tu cuenta" }}."
                    } else {
                        "Conecta tu cuenta de Spotify para ver tus playlists."
                    },
                    buttonText = if (state.spotifyConnected) "Volver a conectar" else "Conectar Spotify",
                    onButton = { viewModel.startLogin(context, Provider.SPOTIFY) },
                )
            }

            if (state.spotifyConnected && state.playlists.isNotEmpty()) {
                item {
                    Text("2. Elige una playlist", style = MaterialTheme.typography.titleMedium)
                }
                items(state.playlists) { playlist ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.selectPlaylist(playlist.id) }
                            .padding(12.dp),
                    ) {
                        Text(
                            text = (if (playlist.id == state.selectedPlaylistId) "✓ " else "") +
                                playlist.name +
                                " (${playlist.tracks?.total ?: "?"} canciones)",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                if (state.selectedPlaylistId != null) {
                    item {
                        Text(
                            "${state.tracks.size} canciones cargadas.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            item {
                StepCard(
                    title = "3. Google / YouTube",
                    body = if (state.googleConnected) {
                        "Conectado. La playlist se creará en tu cuenta de YouTube."
                    } else {
                        "Conecta tu cuenta de Google para crear la playlist en YouTube."
                    },
                    buttonText = if (state.googleConnected) "Volver a conectar" else "Conectar Google",
                    onButton = { viewModel.startLogin(context, Provider.GOOGLE) },
                )
            }

            item {
                Button(
                    onClick = { viewModel.convert() },
                    enabled = state.selectedPlaylistId != null &&
                        state.googleConnected && !state.converting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Convertir a YouTube Music")
                }
            }

            if (state.converting) {
                item {
                    Column {
                        LinearProgressIndicator(
                            progress = {
                                if (state.progressTotal > 0) {
                                    state.progressDone.toFloat() / state.progressTotal
                                } else 0f
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("${state.progressDone} / ${state.progressTotal} canciones")
                    }
                }
            }

            if (state.createdPlaylistId != null) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("¡Listo!", style = MaterialTheme.typography.titleMedium)
                            Text("Playlist creada: ${state.createdPlaylistTitle}")
                            TextButton(onClick = {
                                uriHandler.openUri(
                                    "https://music.youtube.com/playlist?list=${state.createdPlaylistId}"
                                )
                            }) {
                                Text("Abrir en YouTube Music")
                            }
                            if (state.unmatched.isNotEmpty()) {
                                Text(
                                    "Sin coincidencia (${state.unmatched.size}):",
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                state.unmatched.take(20).forEach { name ->
                                    Text("• $name", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }

            state.error?.let { error ->
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(error, style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { viewModel.dismissError() }) {
                                Text("Cerrar")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StepCard(title: String, body: String, buttonText: String, onButton: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onButton) { Text(buttonText) }
        }
    }
}
