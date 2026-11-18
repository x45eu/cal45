package eu.x45.cal45.sync

import android.util.Base64
import android.util.Xml
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Cal45 CardDAV read-only discovery and retrieval.
 * Never follows redirects, never connects to another hostname and never
 * sends PUT/DELETE. Imported data is deliberately limited.
 */
internal class CardDavReader(
    private val server: String,
    private val username: String,
    private val password: String
) {
    data class RemoteCard(val source: String, val text: String, val etag: String?)
    data class Scan(val newCards: List<RemoteCard>, val changedCards: List<RemoteCard>, val protectedChanges: Int)
    data class DavResource(val href: String, val addressBook: Boolean, val etag: String?)
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    private val base: URI = URI(DavProbe.normalizedUrl(server)
        ?: throw IOException("HTTPS-Domain fehlt"))
    private val ownerHome: URI
    private val auth: String

    init {
        if (!username.matches(Regex("[A-Za-z0-9_.@-]{1,128}")) || username.contains("..") ||
            !DavProbe.looksLikeAppPassword(password)) throw IOException("Ungültige DAV-Zugangsdaten")
        val escaped = URLEncoder.encode(username, "UTF-8").replace("+", "%20")
        ownerHome = URI(base.toASCIIString().trimEnd('/') + "/$escaped/")
        auth = "Basic " + Base64.encodeToString(
            "$username:$password".toByteArray(Charsets.UTF_8), Base64.NO_WRAP
        )
    }

    private fun withinOwner(url: URI): Boolean =
        url.scheme.equals("https", true) &&
            url.host.equals(ownerHome.host, true) &&
            url.port == ownerHome.port &&
            url.rawUserInfo == null && url.rawQuery == null && url.rawFragment == null &&
            url.normalize() == url &&
            !Regex("(?i)%(?:2e|2f|5c|00)").containsMatchIn(url.rawPath) &&
            !url.path.contains('\\') &&
            url.rawPath.length <= 1024 &&
            url.rawPath.startsWith(ownerHome.rawPath) &&
            DavProbe.normalizedUrl(url.toASCIIString()) != null

    private fun childUrl(href: String): URI {
        val resolved = ownerHome.resolve(href)
        if (!withinOwner(resolved)) throw IOException("Fremde DAV-Ressource verweigert")
        return resolved
    }

    private fun request(url: URI, method: String, body: String? = null, maxBytes: Int, ifMatch: String? = null): String {
        if (!withinOwner(url)) throw IOException("DAV-Ziel liegt außerhalb des Kontos")
        val builder = Request.Builder().url(url.toASCIIString())
            .header("Authorization", auth)
            .header("Accept", "application/xml, text/vcard")
        if (method == "GET" && ifMatch != null) builder.header("If-Match", ifMatch)
        val request = if (method == "PROPFIND") {
            builder.method(method, (body ?: "").toRequestBody(
                "application/xml; charset=utf-8".toMediaType()
            )).header("Depth", "1").build()
        } else builder.get().build()
        client.newCall(request).execute().use { response ->
            val expected = if (method == "PROPFIND") 207 else 200
            if (response.code != expected) throw IOException("DAV HTTP ${response.code}")
            val stream = response.body?.byteStream() ?: throw IOException("DAV ohne Antwort")
            val output = ByteArrayOutputStream()
            stream.use {
                val buffer = ByteArray(8192)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw IOException("Sync abgebrochen")
                    val n = it.read(buffer)
                    if (n < 0) break
                    if (output.size() + n > maxBytes) throw IOException("DAV-Antwort zu groß")
                    output.write(buffer, 0, n)
                }
            }
            return output.toString("UTF-8")
        }
    }

    private fun propfind(url: URI): List<DavResource> {
        val xml = request(url, "PROPFIND", """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:">
              <d:prop><d:resourcetype/><d:getetag/></d:prop>
            </d:propfind>
        """.trimIndent(), 2 * 1024 * 1024)
        return parseMultistatus(xml)
    }

    fun scanCards(knownSources: Map<String, String?>, maximum: Int = 200): Scan {
        require(maximum in 1..200)
        val books = propfind(ownerHome)
            .filter { it.addressBook }
            .map { childUrl(it.href) }
            .distinct()
            .take(10)
        val cards = ArrayList<RemoteCard>()
        val visited = HashSet<String>()
        var protectedChanges = 0
        val changedCards = ArrayList<RemoteCard>()
        for (book in books) {
            if (!withinOwner(book)) continue
            val resources = propfind(book)
            for (resource in resources) {
                if (resource.addressBook) continue
                val remote = childUrl(resource.href)
                if (!remote.rawPath.startsWith(book.rawPath)) continue
                if (!remote.rawPath.endsWith(".vcf", ignoreCase = true)) continue
                val source = remote.toASCIIString()
                if (!visited.add(source)) continue
                if (source in knownSources) {
                    val saved = knownSources[source]
                    if (saved != null && resource.etag != null && saved != resource.etag) {
                        val strong = ContactChangePolicy.safeETag(resource.etag)
                        if (ContactChangePolicy.safeETag(saved) != null && strong != null) {
                            if (cards.size + changedCards.size >= maximum)
                                throw IOException("CardDAV-Sicherheitslimit für Änderungen")
                            changedCards.add(RemoteCard(
                                source, request(remote, "GET", maxBytes = 256 * 1024, ifMatch = strong),
                                strong
                            ))
                        } else protectedChanges++
                    }
                    continue
                }
                if (cards.size + changedCards.size >= maximum) throw IOException("CardDAV-Sicherheitslimit: zu viele Kontakte")
                cards.add(RemoteCard(source, request(remote, "GET", maxBytes = 256 * 1024), ContactChangePolicy.safeETag(resource.etag)))
            }
        }
        return Scan(cards, changedCards, protectedChanges)
    }

    companion object {
        fun parseMultistatus(xml: String): List<DavResource> {
            if (xml.contains("<!DOCTYPE", true) || xml.contains("<!ENTITY", true) ||
                xml.length > 2 * 1024 * 1024) throw IOException("Unsichere DAV-XML-Antwort")
            val parser = Xml.newPullParser()
            parser.setInput(xml.reader())
            val resources = ArrayList<DavResource>()
            var inResponse = false
            var responseHref: String? = null
            var addressbook = false
            var etag: String? = null
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when {
                        parser.namespace == "DAV:" && parser.name == "response" -> {
                            inResponse = true
                            responseHref = null
                            addressbook = false
                            etag = null
                        }
                        inResponse && parser.namespace == "DAV:" && parser.name == "href" ->
                            responseHref = parser.nextText()
                        inResponse && parser.namespace == "urn:ietf:params:xml:ns:carddav" &&
                            parser.name == "addressbook" -> addressbook = true
                        inResponse && parser.namespace == "DAV:" && parser.name == "getetag" ->
                            etag = parser.nextText()
                    }
                } else if (event == XmlPullParser.END_TAG &&
                    parser.namespace == "DAV:" && parser.name == "response") {
                    responseHref?.let { resources.add(DavResource(it, addressbook, etag)) }
                    if (resources.size > 1000) throw IOException("Zu viele DAV-Ressourcen")
                    inResponse = false
                }
                event = parser.next()
            }
            return resources
        }
    }
}
