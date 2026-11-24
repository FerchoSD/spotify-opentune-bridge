# Spotify → YouTube Music

Aplicación Android que enlaza una playlist de Spotify con YouTube Music:
lee una playlist de tu cuenta de Spotify y crea esa misma playlist en tu
cuenta de YouTube. Una vez creada, la playlist aparece en YouTube Music
y, desde ahí, la puedes abrir en OpenTune.

## Cómo funciona

1. Inicias sesión con Spotify (OAuth 2.0 oficial, flujo Authorization Code
   con PKCE, sin guardar tu contraseña en ningún lado).
2. Eliges una de tus playlists de Spotify.
3. Inicias sesión con Google (OAuth 2.0 oficial, también con PKCE).
4. La app busca cada canción en YouTube con la API oficial de YouTube
   Data v3, elige el mejor resultado por título, artista y duración,
   crea la playlist en tu cuenta de YouTube y agrega los videos.
5. Abres la playlist en YouTube Music (y en OpenTune, que lee de ahí).

## Solo APIs oficiales

- Spotify Web API con OAuth 2.0 + PKCE. Sin cookies, sin credenciales
  prestadas, sin scraping.
- YouTube Data API v3 con OAuth 2.0 + PKCE. Sin APIs internas ni scraping.

No se guarda ninguna contraseña. Los tokens de acceso se guardan
únicamente en el dispositivo (DataStore) y se pueden revocar en
cualquier momento desde tu cuenta de Spotify y de Google.

## Lo que necesitas antes de compilar

### 1. App de Spotify (gratis, 5 minutos)

1. Entra a https://developer.spotify.com/dashboard con tu cuenta de Spotify.
2. Create app: nombre, por ejemplo, `SpotifyToYouTube`.
3. En la configuración de la app, agrega este Redirect URI:
   `com.ferchosd.spotifyytm://callback`
4. Copia el **Client ID** (es un identificador público, no es un secreto).

### 2. Proyecto de Google Cloud (gratis, 10 minutos)

1. Entra a https://console.cloud.google.com con tu cuenta de Google.
2. Crea un proyecto nuevo y habilita la **YouTube Data API v3**
   (APIs y servicios → Biblioteca).
3. APIs y servicios → Credenciales → Crear credenciales → ID de cliente
   de OAuth → tipo de aplicación **Android**:
   - Nombre del paquete: `com.ferchosd.spotifyytm`
   - Huella digital SHA-1: la de tu keystore de depuración. Para
     sacarla: `keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android`
4. Copia el **Client ID** de ese cliente OAuth.

### 3. Configuración local (nunca se sube a GitHub)

Crea el archivo `local.properties` en la raíz del proyecto con:

```properties
SPOTIFY_CLIENT_ID=tu_client_id_de_spotify
GOOGLE_CLIENT_ID=tu_client_id_de_google
```

Sin estos dos valores la app compila pero no puede iniciar sesión.

## Compilar

Ábrelo en Android Studio (o corre `./gradlew assembleDebug`) e instala
el APK en tu teléfono. No hay nada más que configurar.

## Cuota de YouTube

La YouTube Data API v3 tiene una cuota gratuita de 10 000 unidades al
día por proyecto. Cada búsqueda cuesta 100 unidades y cada video
agregado a la playlist cuesta 50. Una playlist de 50 canciones gasta
unas 7 500 unidades, así que cabe una playlist grande al día. Si la
cuota se agota, espera al día siguiente o pide más cuota en Google Cloud.

## Estado

v0.1 — primera versión funcional: login de Spotify, listado de
playlists, login de Google, conversión con progreso y reporte de
canciones sin coincidencia. No probada aún con una playlist real.

## Licencia

GPL-3.0. No está afiliada a Spotify, Google, YouTube ni OpenTune.
