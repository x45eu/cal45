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

    /**
     * Only DNS names are permitted. Raw IPv4/IPv6 literals, single-label names,
     * localhost, and numeric TLDs are rejected without resolving DNS.
     */
    private fun isDomain(host: String): Boolean {
        if (host.length > 253 || !host.contains('.') || host.endsWith('.')) return false
        if (host.contains(':') || host.startsWith('[') || host.endsWith(']')) return false
        if (host.equals("localhost", ignoreCase = true)) return false
        val labels = host.split('.')
        if (labels.any { label ->
            label.isEmpty() || label.length > 63 ||
                !label.first().isLetterOrDigit() || !label.last().isLetterOrDigit() ||
                !label.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }
        }) return false
        // Requires alphabetic characters in the top-level domain: rejects 192.0.2.1.
        val tld = labels.last()
        return tld.length >= 2 && tld.any { it.isLetter() }
    }

    fun normalizedUrl(input: String): String? {
        val uri = try { URI(input.trim()) } catch (_: Exception) { return null }
        val host = uri.host ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) || !isDomain(host) ||
            uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
            uri.port !in -1..65535 || uri.port == 0) return null
        val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
        if (!path.startsWith("/") || path.contains("//") ||
            path.split('/').any { it == "." || it == ".." }) return null
        return try {
            URI("https", null, host.lowercase(java.util.Locale.ROOT), uri.port, path, null, null)
                .toASCIIString()
        } catch (_: Exception) { null }
    }

    /** Cal45 device-token format, not proof that the server rejected a user password. */
    fun looksLikeAppPassword(value: String): Boolean =
        value.startsWith("cal45_") && value.length >= 32 &&
            value.length <= 256 && value.all { it.isLetterOrDigit() || it == '_' || it == '-' }

    fun check(serverUrl: String, username: String, appPassword: String): Result {
        if (username.isBlank() || username.contains(':') || username.any { it.isISOControl() } ||
            !looksLikeAppPassword(appPassword)) {
            return Result(false, "Benutzername ungültig oder kein Cal45-App-Passwort (cal45_…).")
        }
        val url = normalizedUrl(serverUrl) ?: return Result(false, "Nur HTTPS-Adressen mit Domainnamen sind erlaubt (keine IP-Adressen).")
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
