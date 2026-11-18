package eu.x45.cal45.sync

import android.util.Base64
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Minimal DAV authentication probe; no changes to remote collections.
 * Strict TLS validation is delegated to the Android platform trust store.
 */
object DavProbe {
    data class Result(val successful: Boolean, val message: String)

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
        var connection: HttpsURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpsURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 7000
            connection.readTimeout = 7000
            connection.requestMethod = "PROPFIND"
            connection.setRequestProperty("Depth", "0")
            connection.setRequestProperty("Accept", "application/xml")
            val token = Base64.encodeToString(
                "$username:$appPassword".toByteArray(Charsets.UTF_8), Base64.NO_WRAP
            )
            connection.setRequestProperty("Authorization", "Basic $token")
            when (connection.responseCode) {
                207 -> Result(true, "DAV-Verbindung und Zugangsdaten erfolgreich geprüft.")
                401, 403 -> Result(false, "Zugang abgelehnt. Benutzername oder App-Passwort prüfen.")
                301, 302, 303, 307, 308 -> Result(false, "Server leitet um. Bitte die endgültige HTTPS-DAV-Adresse verwenden.")
                else -> Result(false, "Keine DAV-Bestätigung (HTTP ${connection.responseCode}).")
            }
        } catch (_: Exception) {
            Result(false, "DAV-Verbindung fehlgeschlagen. HTTPS, Zertifikat und Serveradresse prüfen.")
        } finally {
            connection?.disconnect()
        }
    }
}
