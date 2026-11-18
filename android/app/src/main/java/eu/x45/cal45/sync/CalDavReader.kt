package eu.x45.cal45.sync

import android.util.Base64
import android.util.Xml
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Read-only CalDAV discovery under the authenticated Cal45 account.
 * No redirects, cross-origin fetch, writes, or unrestricted DAV traversal.
 */
internal class CalDavReader(server: String, username: String, password: String) {
    data class RemoteEvent(val calendarUrl: String, val source: String, val text: String, val etag: String?)
    data class Resource(val href: String, val calendar: Boolean, val etag: String?)
    private val root = URI(DavProbe.normalizedUrl(server)
        ?: throw IOException("Nur HTTPS-Domains sind erlaubt"))
    private val owner: URI
    private val authorization: String
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    init {
        if (!username.matches(Regex("[A-Za-z0-9_.@-]{1,128}")) ||
            username.contains("..") || !DavProbe.looksLikeAppPassword(password)) {
            throw IOException("Ungültige Cal45-Anmeldedaten")
        }
        val encoded = URLEncoder.encode(username, "UTF-8").replace("+", "%20")
        owner = URI(root.toASCIIString().trimEnd('/') + "/$encoded/")
        authorization = "Basic " + Base64.encodeToString(
            "$username:$password".toByteArray(Charsets.UTF_8), Base64.NO_WRAP
        )
    }

    private fun allowed(uri: URI): Boolean =
        uri.scheme.equals("https", true) &&
            uri.host.equals(owner.host, true) &&
            uri.port == owner.port &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.normalize() == uri && uri.rawPath != null &&
            !Regex("(?i)%(?:2e|2f|5c|00)").containsMatchIn(uri.rawPath) &&
            !uri.path.contains('\\') && uri.rawPath.length <= 1024 &&
            uri.rawPath.startsWith(owner.rawPath) &&
            DavProbe.normalizedUrl(uri.toASCIIString()) != null

    private fun child(href: String): URI {
        val resolved = owner.resolve(href)
        if (!allowed(resolved)) throw IOException("Ungültiger CalDAV-Pfad")
        return resolved
    }

    private fun read(url: URI, method: String, maximum: Int, ifMatch: String? = null): String {
        if (!allowed(url)) throw IOException("Ungültige Kalenderadresse")
        val builder = Request.Builder().url(url.toASCIIString())
            .header("Authorization", authorization)
            .header("Accept", if (method == "GET") "text/calendar" else "application/xml")
        if (method == "GET" && ifMatch != null) builder.header("If-Match", ifMatch)
        val request = if (method == "PROPFIND") {
            builder.method(method, """
                <?xml version="1.0" encoding="utf-8"?>
                <d:propfind xmlns:d="DAV:">
                  <d:prop><d:resourcetype/><d:getetag/></d:prop>
                </d:propfind>
            """.trimIndent().toRequestBody("application/xml; charset=utf-8".toMediaType()))
                .header("Depth", "1").build()
        } else builder.get().build()
        client.newCall(request).execute().use { response ->
            val expected = if (method == "PROPFIND") 207 else 200
            if (response.code != expected) throw IOException("CalDAV HTTP ${response.code}")
            val input = response.body?.byteStream() ?: throw IOException("Kalenderantwort fehlt")
            val out = ByteArrayOutputStream()
            input.use {
                val chunk = ByteArray(8192)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw IOException("Abgebrochen")
                    val n = it.read(chunk)
                    if (n < 0) break
                    if (out.size() + n > maximum) throw IOException("Kalenderantwort zu groß")
                    out.write(chunk, 0, n)
                }
            }
            return out.toString("UTF-8")
        }
    }

    fun fetchChangedEvents(existing: Map<String, String?>, maximum: Int = 200): List<RemoteEvent> {
        require(maximum in 1..200)
        val calendars = parseMultistatus(read(owner, "PROPFIND", 2 * 1024 * 1024))
            .filter { it.calendar }.map { child(it.href) }.distinct()
        if (calendars.size > 10) throw IOException("Zu viele Kalender")
        val events = ArrayList<RemoteEvent>()
        val visited = HashSet<String>()
        for (calendar in calendars) {
            if (!calendar.rawPath.endsWith("/")) continue
            for (resource in parseMultistatus(read(calendar, "PROPFIND", 2 * 1024 * 1024))) {
                val uri = child(resource.href)
                if (!uri.rawPath.startsWith(calendar.rawPath) ||
                    resource.calendar || !uri.rawPath.endsWith(".ics", ignoreCase = true)) continue
                val source = uri.toASCIIString()
                if (!visited.add(source)) continue
                val etag = safeStrongETag(resource.etag)
                // Legacy imports without a saved ETag must never be overwritten.
                if (source in existing && (etag == null || existing[source] == null ||
                    existing[source] == etag)) continue
                if (events.size >= maximum) throw IOException("Mehr als $maximum neue Termine")
                events.add(RemoteEvent(
                    calendar.toASCIIString(), source,
                    read(uri, "GET", 256 * 1024, etag), etag
                ))
            }
        }
        return events
    }

    companion object {
        fun parseMultistatus(xml: String): List<Resource> {
            if (xml.length > 2 * 1024 * 1024 ||
                xml.contains("<!DOCTYPE", true) || xml.contains("<!ENTITY", true)) {
                throw IOException("Unsichere CalDAV-XML-Antwort")
            }
            val parser = Xml.newPullParser()
            parser.setInput(xml.reader())
            val resources = ArrayList<Resource>()
            var inResponse = false
            var href: String? = null
            var isCalendar = false
            var etag: String? = null
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when {
                        parser.namespace == "DAV:" && parser.name == "response" -> {
                            inResponse = true
                            href = null
                            isCalendar = false
                            etag = null
                        }
                        inResponse && parser.namespace == "DAV:" && parser.name == "href" ->
                            href = parser.nextText()
                        inResponse && parser.namespace == "DAV:" && parser.name == "getetag" ->
                            etag = parser.nextText()
                        inResponse && parser.namespace == "urn:ietf:params:xml:ns:caldav" &&
                            parser.name == "calendar" -> isCalendar = true
                    }
                } else if (event == XmlPullParser.END_TAG &&
                    parser.namespace == "DAV:" && parser.name == "response") {
                    href?.let { resources.add(Resource(it, isCalendar, etag)) }
                    if (resources.size > 1000) throw IOException("Zu viele CalDAV-Ressourcen")
                    inResponse = false
                }
                event = parser.next()
            }
            return resources
        }
    }
}
