package eu.x45.cal45.sync

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Opt-in transport building block for future bidirectional DAV synchronization.
 *
 * IMPORTANT: neither the Contacts nor the Calendar SyncAdapter calls this.
 * Only an already-existing, versioned resource can be replaced; there is no
 * blind PUT, resource creation, DELETE, redirect or fallback to weak ETags.
 */
internal class ConditionalDavUpload(
    server: String,
    username: String,
    private val appPassword: String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()
) {
    enum class Media(val suffix: String, val contentType: String) {
        VCARD(".vcf", "text/vcard; charset=utf-8"),
        ICALENDAR(".ics", "text/calendar; charset=utf-8")
    }
    sealed class Result {
        data class Uploaded(val newETag: String) : Result()
        object Conflict : Result()
        object Unauthorized : Result()
        object Unverified : Result()
    }
    sealed class Snapshot {
        data class Verified(val etag: String, val originalText: String) : Snapshot()
        object Conflict : Snapshot()
        object Unauthorized : Snapshot()
        object Unverified : Snapshot()
    }
    /**
     * Separate, read-only snapshot stage before any future write workflow.
     * The original document is returned in RAM only, including fields the
     * current Android parsers cannot represent. A later serializer MUST keep
     * them losslessly or refuse the upload.
     */
    fun fetchVerifiedBase(source: String, media: Media, previousETag: String?): Snapshot {
        val target = checkedSource(source, media)
        val etag = CalDavReader.safeStrongETag(previousETag)
            ?: return Snapshot.Unverified
        val request = Request.Builder()
            .url(target.toASCIIString())
            .header("Authorization", authorization)
            .header("If-Match", etag)
            .header("Accept", media.contentType)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            when (response.code) {
                409, 412 -> return Snapshot.Conflict
                401, 403 -> return Snapshot.Unauthorized
                200 -> Unit
                else -> throw IOException("DAV preflight rejected (HTTP ${response.code})")
            }
            if (CalDavReader.safeStrongETag(response.header("ETag")) != etag)
                return Snapshot.Unverified
            val body = response.body ?: return Snapshot.Unverified
            val out = ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    if (Thread.currentThread().isInterrupted)
                        throw IOException("DAV preflight interrupted")
                    val size = input.read(buffer)
                    if (size < 0) break
                    if (out.size() + size > 256 * 1024)
                        throw IOException("DAV preflight document too large")
                    out.write(buffer, 0, size)
                }
            }
            val text = out.toString("UTF-8")
            val envelope = if (media == Media.VCARD) "VCARD" else "VCALENDAR"
            if (!text.contains("BEGIN:$envelope") || !text.contains("END:$envelope"))
                return Snapshot.Unverified
            return Snapshot.Verified(etag, text)
        }
    }

    private val root = URI(DavProbe.normalizedUrl(server)
        ?: throw IllegalArgumentException("HTTPS-Domain erforderlich"))
    private val owner: URI
    private val authorization: String

    init {
        require(username.matches(Regex("[A-Za-z0-9_.@-]{1,128}")) &&
            !username.contains("..")) { "Ungültiger DAV-Benutzername" }
        require(DavProbe.looksLikeAppPassword(appPassword)) { "Nur Cal45-Gerätepasswörter" }
        val encoded = URLEncoder.encode(username, "UTF-8").replace("+", "%20")
        owner = URI(root.toASCIIString().trimEnd('/') + "/$encoded/")
        authorization = okhttp3.Credentials.basic(username, appPassword, Charsets.UTF_8)
    }

    private fun checkedSource(source: String, media: Media): URI {
        require(source.length <= 1100) { "Pfad zu lang" }
        val uri = try { URI(source) } catch (_: Exception) {
            throw IllegalArgumentException("Ungültige Ressource")
        }
        require(uri.isAbsolute && uri.scheme.equals("https", true) &&
            uri.host.equals(owner.host, true) && uri.port == owner.port &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.normalize() == uri &&
            uri.rawPath != null && uri.rawPath.length <= 1024 &&
            uri.rawPath.startsWith(owner.rawPath) &&
            uri.rawPath.endsWith(media.suffix, ignoreCase = true) &&
            !Regex("(?i)%(?:2e|2f|5c|00)").containsMatchIn(uri.rawPath) &&
            !uri.path.contains('\\') &&
            uri.toASCIIString() == source &&
            DavProbe.normalizedUrl(source) != null
        ) { "DAV-Ressource liegt außerhalb des eigenen Kontos" }
        return uri
    }

    /**
     * Local snapshot must be verified by the caller immediately before PUT.
     * No write occurs if there is no strong server ETag or no matching local
     * snapshot; the caller must not retry Conflict with an unconditional PUT.
     *
     * The returned ETag MUST be saved alongside the new local snapshot before
     * another upload is attempted. Missing/weak ETag -> Unverified (fail closed).
     */
    fun replaceExisting(
        source: String,
        media: Media,
        previousETag: String?,
        expectedLocalFingerprint: String?,
        actualLocalFingerprint: String,
        payload: String
    ): Result {
        val target = checkedSource(source, media)
        val etag = CalDavReader.safeStrongETag(previousETag)
            ?: return Result.Unverified
        require(expectedLocalFingerprint != null &&
            expectedLocalFingerprint.matches(Regex("[a-f0-9]{64}")) &&
            expectedLocalFingerprint == actualLocalFingerprint
        ) { "Lokaler Änderungsstand nicht sicher bestätigt" }
        require(payload.isNotBlank() && payload.toByteArray(Charsets.UTF_8).size <= 256 * 1024) {
            "Ungültiger oder zu großer DAV-Upload"
        }
        val envelope = if (media == Media.VCARD) "VCARD" else "VCALENDAR"
        require(payload.contains("BEGIN:$envelope") && payload.contains("END:$envelope")) {
            "Ungültiges DAV-Datenformat"
        }
        val request = Request.Builder()
            .url(target.toASCIIString())
            .header("Authorization", authorization)
            .header("If-Match", etag)
            .header("Accept", media.contentType)
            .put(payload.toRequestBody(media.contentType.toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            return when (response.code) {
                200, 201, 204 -> {
                    val newTag = CalDavReader.safeStrongETag(response.header("ETag"))
                    if (newTag != null) Result.Uploaded(newTag) else Result.Unverified
                }
                409, 412 -> Result.Conflict
                401, 403 -> Result.Unauthorized
                else -> throw IOException("DAV write rejected (HTTP ${response.code})")
            }
        }
    }
}
