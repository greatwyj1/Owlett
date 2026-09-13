package com.example.birdingsoundmvp.planning

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

class EbirdHttpTransportTest {
    @Test fun timeoutsAreRetriedTwiceAndRemainTyped() = runBlocking {
        MockWebServer().use { server ->
            repeat(3) { server.enqueue(MockResponse().setBody("[]").setHeadersDelay(1, TimeUnit.SECONDS)) }
            val failures = java.util.concurrent.CopyOnWriteArrayList<String>()
            val client = OkHttpClient.Builder().readTimeout(80, TimeUnit.MILLISECONDS).retryOnConnectionFailure(false)
                .eventListener(object : okhttp3.EventListener() {
                    override fun callFailed(call: okhttp3.Call, ioe: java.io.IOException) { failures += ioe.toString() }
                }).build()
            val error = runCatching {
                // Pin IPv4 so OkHttp's failed-route cache cannot switch to an unbound localhost IPv6 address.
                EbirdHttpTransport(client).get(Request.Builder().url(server.url("/timeout").newBuilder().host("127.0.0.1").build()).build())
            }.exceptionOrNull() as EbirdRequestException
            assertTrue("${error.failure.message}: $failures", error.failure.message.contains("超时"))
            assertTrue(error.failure.retryable)
            assertEquals(3, server.requestCount)
        }
    }
    @Test fun retriesRateLimitAndServerFailureWithoutNestedRetries() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "0"))
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
            server.enqueue(MockResponse().setBody("[]"))
            val result = EbirdHttpTransport(OkHttpClient()).get(Request.Builder().url(server.url("/regions")).build())
            assertEquals("[]", result.body)
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun successfulDirectoriesAreCachedAndStaleCacheIsExplicit() = runBlocking {
        MockWebServer().use { server ->
            var now = 1_000L
            val transport = EbirdHttpTransport(OkHttpClient(), maxRetries = 0, now = { now })
            val request = Request.Builder().url(server.url("/countries")).build()
            server.enqueue(MockResponse().setBody("[]"))
            val first = transport.get(request, "countries")
            assertFalse(transport.get(request, "countries").stale)
            assertEquals(1, server.requestCount)
            now += EbirdHttpTransport.CACHE_MS + 1
            server.enqueue(MockResponse().setResponseCode(503))
            val stale = transport.get(request, "countries")
            assertTrue(stale.stale)
            assertEquals(first.fetchedAtMs, stale.fetchedAtMs)
            assertEquals(2, server.requestCount)
            // Authentication errors must never be hidden by a stale directory.
            server.enqueue(MockResponse().setResponseCode(401))
            val error = runCatching { transport.get(request, "countries") }.exceptionOrNull()
            assertEquals(401, (error as EbirdRequestException).failure.statusCode)
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun authenticationFailsImmediately() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403))
            val error = runCatching {
                EbirdHttpTransport(OkHttpClient()).get(Request.Builder().url(server.url("/observations")).build())
            }.exceptionOrNull() as EbirdRequestException
            assertFalse(error.failure.retryable)
            assertEquals(403, error.failure.statusCode)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun cancellingStopsAnOutstandingHttpCall() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val client = OkHttpClient()
            val job = launch(Dispatchers.IO) {
                EbirdHttpTransport(client).get(Request.Builder().url(server.url("/wait")).build())
            }
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            job.cancelAndJoin()
            withTimeout(3_000) { while (client.dispatcher.runningCallsCount() != 0) delay(10) }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun zeroHotspotMatchesAreNotConnectionErrors() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("[{\"locId\":\"L1\",\"locName\":\"Other park\"}]"))
            val client = EbirdRecentObservationsClient(baseUrl = server.url("/v2").toString())
            val result = client.searchHotspots("key", "CN-11", "missing")
            assertTrue(result is EbirdHotspotSearchResponse.Success)
            assertTrue((result as EbirdHotspotSearchResponse.Success).hotspots.isEmpty())
        }
    }

    @Test fun chineseEnglishAliasesAndVerifiedCodesMatchSameRegion() {
        val beijing = EbirdRegionOption("CN-11", "北京")
        listOf("北京", "北京市", "Beijing", "cn-11").forEach { assertTrue(it, EbirdRegionNames.matches(beijing, it)) }
        assertTrue(EbirdRegionNames.matches(EbirdRegionOption("CN", "中国"), "China"))
        assertTrue(EbirdRegionNames.matches(EbirdRegionOption("CN-45", "广西"), "广西壮族自治区"))
        assertFalse(EbirdRegionNames.matches(beijing, "CN-99"))
    }
}
