package eu.x45.cal45.sync

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConditionalDavUploadTest {
    private val server = "https://dav.example.org/"
    private val source = "https://dav.example.org/alice/contacts/one.vcf"
    private val credential = "cal45_" + "a".repeat(43)
    private val fingerprint = "f".repeat(64)
    private fun fake(status: Int, tag: String? = "\"new\"",
                     inspect: (okhttp3.Request) -> Unit = {}): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            inspect(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(status).message("test")
                .apply { if (tag != null) header("ETag", tag) }
                .body("".toResponseBody(null)).build()
        }).build()

    @Test fun onlyConditionalSameAccountPutIsAccepted() {
        var requests = 0
        val client = fake(204) { req ->
            requests++
            assertEquals("PUT", req.method)
            assertEquals("\"old\"", req.header("If-Match"))
            assertEquals("https", req.url.scheme)
            assertEquals("/alice/contacts/one.vcf", req.url.encodedPath)
            assertTrue(req.header("Authorization")!!.startsWith("Basic "))
        }
        val writer = ConditionalDavUpload(server,"alice",credential,client)
        assertEquals(ConditionalDavUpload.Result.Uploaded("\"new\""),
            writer.replaceExisting(source,ConditionalDavUpload.Media.VCARD,
                "\"old\"", fingerprint,fingerprint,"BEGIN:VCARD\nFN:Ada\nEND:VCARD"))
        assertEquals(1, requests)
    }
    @Test fun remoteConflictNeverRetriesWithoutPrecondition() {
        var requests = 0
        val client=fake(412){ requests++ }
        val writer=ConditionalDavUpload(server,"alice",credential,client)
        assertEquals(ConditionalDavUpload.Result.Conflict,
            writer.replaceExisting(source,ConditionalDavUpload.Media.VCARD,
                "\"old\"",fingerprint,fingerprint,"BEGIN:VCARD\nFN:Ada\nEND:VCARD"))
        assertEquals(1,requests)
    }
    @Test fun failClosedForUnknownVersionSnapshotAndWrongDestination() {
        var requests=0
        val writer=ConditionalDavUpload(server,"alice",credential,fake(204){ requests++ })
        val payload="BEGIN:VCARD\nFN:Ada\nEND:VCARD"
        assertEquals(ConditionalDavUpload.Result.Unverified,
            writer.replaceExisting(source,ConditionalDavUpload.Media.VCARD,
                "W/\"old\"",fingerprint,fingerprint,payload))
        assertThrows(IllegalArgumentException::class.java) {
            writer.replaceExisting(source,ConditionalDavUpload.Media.VCARD,
                "\"old\"",fingerprint,"e".repeat(64),payload)
        }
        for(bad in listOf(
            "https://evil.example/alice/contacts/one.vcf",
            "http://dav.example.org/alice/contacts/one.vcf",
            "https://dav.example.org/bob/contacts/one.vcf",
            "https://dav.example.org/alice/contacts/%2e%2e/one.vcf",
            "https://dav.example.org/alice/contacts/one.vcf?x=1",
            "https://dav.example.org/alice/contacts/one.ics"
        )) assertThrows(IllegalArgumentException::class.java) {
            writer.replaceExisting(bad,ConditionalDavUpload.Media.VCARD,
                "\"old\"",fingerprint,fingerprint,payload)
        }
        assertEquals(0,requests)
    }
    @Test fun missingResponseTagIsUnverifiedAndCredentialsStayPrivate() {
        val writer=ConditionalDavUpload(server,"alice",credential,fake(204,null))
        assertEquals(ConditionalDavUpload.Result.Unverified,
            writer.replaceExisting(source,ConditionalDavUpload.Media.VCARD,
                "\"old\"",fingerprint,fingerprint,"BEGIN:VCARD\nFN:Ada\nEND:VCARD"))
    }
    private fun preflightFake(
        status: Int, tag: String?, body: String,
        inspect: (okhttp3.Request) -> Unit = {}
    ): OkHttpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        inspect(chain.request())
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .code(status).message("test")
            .apply { if (tag != null) header("ETag", tag) }
            .body(body.toResponseBody(null)).build()
    }).build()

    @Test fun readOnlySnapshotRequiresMatchingETagAndPreservesUnknownFields() {
        val original = "BEGIN:VCARD\nFN:Ada\nX-PRIVATE:keep-unknown\nEND:VCARD"
        var requests = 0
        val writer = ConditionalDavUpload(server, "alice", credential,
            preflightFake(200, "\"old\"", original) { request ->
                requests++
                assertEquals("GET", request.method)
                assertEquals("\"old\"", request.header("If-Match"))
            })
        assertEquals(ConditionalDavUpload.Snapshot.Verified("\"old\"", original),
            writer.fetchVerifiedBase(source, ConditionalDavUpload.Media.VCARD, "\"old\""))
        assertEquals(1, requests)
    }

    @Test fun snapshotFailsClosedWhenServerChangedOrVersionMissing() {
        val original = "BEGIN:VCARD\nFN:Ada\nEND:VCARD"
        val changed = ConditionalDavUpload(server,"alice",credential,
            preflightFake(412,"\"new\"",original))
        assertEquals(ConditionalDavUpload.Snapshot.Conflict,
            changed.fetchVerifiedBase(source,ConditionalDavUpload.Media.VCARD,"\"old\""))
        val missing = ConditionalDavUpload(server,"alice",credential,
            preflightFake(200,null,original))
        assertEquals(ConditionalDavUpload.Snapshot.Unverified,
            missing.fetchVerifiedBase(source,ConditionalDavUpload.Media.VCARD,"\"old\""))
        val mismatched = ConditionalDavUpload(server,"alice",credential,
            preflightFake(200,"\"new\"",original))
        assertEquals(ConditionalDavUpload.Snapshot.Unverified,
            mismatched.fetchVerifiedBase(source,ConditionalDavUpload.Media.VCARD,"\"old\""))
        var requests = 0
        val invalid = ConditionalDavUpload(server,"alice",credential,
            preflightFake(200,"\"old\"",original) { requests++ })
        assertEquals(ConditionalDavUpload.Snapshot.Unverified,
            invalid.fetchVerifiedBase(source,ConditionalDavUpload.Media.VCARD,"W/\"old\""))
        assertEquals(0, requests)
    }

}
