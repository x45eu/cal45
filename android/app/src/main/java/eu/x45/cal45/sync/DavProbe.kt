package eu.x45.cal45.sync

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Read-only DAV authentication probe.
 * Uses platform TLS validation, rejects redirects, and never changes remote data.
 */
object DavProbe {
    data class Result(val successful: Boolean, val message: String)

    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    fun normalizedUrl(input: String): String? {
        val uri = try { URI(input.trim()) } catch (_: Exception) { return null }
        if (!uri.scheme.equals("https", true) || uri.host.isNullOrBlank() ||
            uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
            uri.port !in -1..65535 || uri.port == 0) return null
        val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
        if (!path.startsWith("/") || path.contains("//") || path.contains("/../") ||
            path.contains("/./")) return null
        return try {
            URI("https", null, uri.host, uri.port, path, null, null).toASCIIString()
        } catch (_: Exception) { null }
    }

    fun check(serverUrl: String, username: String, appPassword: String): Result {
        if (username.isBlank() || username.contains(':') || username.any { it.isISOControl() } ||
            appPassword.isBlank()) {
            return Result(false, "Benutzername oder App-Passwort ungültig.")
        }
        val url = normalizedUrl(serverUrl) ?: return Result(false, "Nur gültige HTTPS-Adressen sind erlaubt.")
        return try {
            val token = Base64.encodeToString(
                "$username:$appPassword".toByteArray(Charsets.UTF_8), Base64.NO_WRAP
            )
            val request = Request.Builder()
                .url(url)
                .method("PROPFIND", byteArrayOf().toRequestBody(null))
                .header("Depth", "0")
                .header("Accept", "application/xml")
                .header("Authorization", "Basic $token")
                .build()
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    207 -> Result(true, "DAV-Verbindung und Zugangsdaten erfolgreich geprüft.")
                    401, 403 -> Result(false, "Zugang abgelehnt. Benutzername oder App-Passwort prüfen.")
                    301, 302, 303, 307, 308 -> Result(false, "Server leitet um. Bitte die endgültige HTTPS-DAV-Adresse verwenden.")
                    else -> Result(false, "Keine DAV-Bestätigung (HTTP ${response.code}).")
                }
            }
        } catch (_: Exception) {
            Result(false, "DAV-Verbindung fehlgeschlagen. HTTPS, Zertifikat und Serveradresse prüfen.")
        }
    }
}
