package com.ferchosd.spotifyytm.auth

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * OAuth 2.0 Authorization Code con PKCE (RFC 7636).
 * Sirve igual para Spotify y para Google: ninguno de los dos pide
 * client_secret en apps instaladas cuando se usa PKCE.
 */
data class Tokens(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtMs: Long,
)

data class AuthRequest(
    val authUrl: String,
    val codeVerifier: String,
    val state: String,
)

object PkceAuth {

    private val http = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    fun buildAuthRequest(
        authEndpoint: String,
        clientId: String,
        redirectUri: String,
        scopes: List<String>,
        extraParams: Map<String, String> = emptyMap(),
    ): AuthRequest {
        val verifier = generateVerifier()
        val challenge = challenge(verifier)
        val state = generateVerifier().take(32)
        val params = buildString {
            append("client_id=").append(clientId)
            append("&response_type=code")
            append("&redirect_uri=").append(redirectUri)
            append("&scope=").append(scopes.joinToString("%20"))
            append("&code_challenge=").append(challenge)
            append("&code_challenge_method=S256")
            append("&state=").append(state)
            extraParams.forEach { (k, v) -> append("&").append(k).append("=").append(v) }
        }
        return AuthRequest(
            authUrl = "$authEndpoint?$params",
            codeVerifier = verifier,
            state = state,
        )
    }

    suspend fun exchangeCode(
        tokenEndpoint: String,
        clientId: String,
        redirectUri: String,
        code: String,
        codeVerifier: String,
    ): Tokens = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", redirectUri)
            .add("client_id", clientId)
            .add("code_verifier", codeVerifier)
            .build()
        val response = http.newCall(
            Request.Builder().url(tokenEndpoint).post(body).build()
        ).execute()
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw IllegalStateException("Token exchange falló (${it.code}): $text")
            parseTokens(text)
        }
    }

    suspend fun refresh(
        tokenEndpoint: String,
        clientId: String,
        refreshToken: String,
    ): Tokens = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", clientId)
            .build()
        val response = http.newCall(
            Request.Builder().url(tokenEndpoint).post(body).build()
        ).execute()
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw IllegalStateException("Refresh falló (${it.code}): $text")
            parseTokens(text, fallbackRefreshToken = refreshToken)
        }
    }

    private fun parseTokens(text: String, fallbackRefreshToken: String? = null): Tokens {
        val obj = json.parseToJsonElement(text).jsonObject
        val access = obj["access_token"]?.jsonPrimitive?.content
            ?: throw IllegalStateException("Respuesta sin access_token: $text")
        val refresh = obj["refresh_token"]?.jsonPrimitive?.content ?: fallbackRefreshToken
        val expiresIn = obj["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600L
        return Tokens(
            accessToken = access,
            refreshToken = refresh,
            expiresAtMs = System.currentTimeMillis() + expiresIn * 1000L,
        )
    }

    private fun generateVerifier(): String {
        val bytes = ByteArray(64)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            .take(128)
    }

    private fun challenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}
