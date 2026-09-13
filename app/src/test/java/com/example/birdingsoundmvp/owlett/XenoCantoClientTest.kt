package com.example.birdingsoundmvp.owlett

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async

class XenoCantoClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `search sends v3 key and returns quality sorted attributed recordings`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "numRecordings": "2",
                      "recordings": [
                        {
                          "id": "20", "gen": "Turdus", "sp": "merula", "en": "Common Blackbird",
                          "rec": "Recorder B", "cnt": "Spain", "loc": "Wetland", "date": "2025-04-02",
                          "type": ["call"], "q": "B", "length": "0:12",
                          "lic": "https://creativecommons.org/licenses/by-nc/4.0/",
                          "file": "//cdn.example/20.mp3", "sono": {"med": "//cdn.example/20.png"}
                        },
                        {
                          "id": "10", "gen": "Turdus", "sp": "merula", "en": "Common Blackbird",
                          "rec": "Recorder A", "cnt": "France", "loc": "Forest", "date": "2025-04-01",
                          "type": "song", "q": "A", "length": "0:20",
                          "lic": "https://creativecommons.org/licenses/by/4.0/",
                          "file": "http://cdn.example/10.mp3", "sono": {"small": "//cdn.example/10.png"}
                        }
                      ]
                    }
                    """.trimIndent()
                )
        )
        val client = XenoCantoClient(
            httpClient = okhttp3.OkHttpClient(),
            baseUrl = server.url("/api/3/recordings").toString()
        )

        val response = client.search("xc-secret", "Turdus merula", maxResults = 2)

        assertTrue(response is XenoCantoResponse.Success)
        val recordings = (response as XenoCantoResponse.Success).recordings
        assertEquals(listOf("10", "20"), recordings.map { it.id })
        assertEquals("Recorder A", recordings.first().recordist)
        assertEquals("song", recordings.first().soundType)
        assertEquals("https://cdn.example/10.mp3", recordings.first().audioUrl)
        assertTrue(recordings.first().license.contains("creativecommons"))
        val requestUrl = server.takeRequest().requestUrl!!
        assertEquals("xc-secret", requestUrl.queryParameter("key"))
        assertEquals("10", requestUrl.queryParameter("per_page"))
        assertTrue(requestUrl.queryParameter("query")!!.contains("Turdus merula"))
    }

    @Test
    fun `search fails locally when api key is missing`() = runBlocking {
        val response = XenoCantoClient(baseUrl = server.url("/api/3/recordings").toString())
            .search("", "Turdus merula")

        assertTrue(response is XenoCantoResponse.Failure)
        assertEquals(0, server.requestCount)
    }

    private fun empty() = MockResponse().setBody("""{"numRecordings":"0","recordings":[]}""")
    private fun found() = MockResponse().setBody("""{"numRecordings":"1","recordings":[{"id":"1","gen":"Turdus","sp":"merula","cnt":"China","file":"https://example.org/1.mp3"}]}""")
    private fun client() = XenoCantoClient(baseUrl = server.url("/api/3/recordings").toString())

    @Test fun `system region falls back to country before global`() = runBlocking {
        server.enqueue(empty()); server.enqueue(found())
        val result = client().searchRegional("test-key", "Turdus merula", BirdCallRegion("China", "Beijing")) as XenoCantoResponse.Success
        assertTrue(result.scopeNote.contains("所在国家"))
        val local = server.takeRequest().requestUrl!!.queryParameter("query")!!
        val country = server.takeRequest().requestUrl!!.queryParameter("query")!!
        assertTrue(local.contains("cnt:\"China\" loc:\"Beijing\""))
        assertTrue(country.contains("cnt:\"China\"")); assertTrue(!country.contains("loc:"))
        assertEquals(2, server.requestCount)
    }
    @Test fun `global fallback only follows confirmed zero country records`() = runBlocking {
        server.enqueue(empty()); server.enqueue(found())
        val result = client().searchRegional("test-key", "Turdus merula", BirdCallRegion("China")) as XenoCantoResponse.Success
        assertTrue(result.scopeNote.contains("扩大到全球"))
        server.takeRequest()
        assertTrue(!server.takeRequest().requestUrl!!.queryParameter("query")!!.contains("cnt:"))
    }
    @Test fun `explicit region and failed country requests never silently widen`() = runBlocking {
        server.enqueue(empty())
        val explicit = client().searchRegional("test-key", "Turdus merula", BirdCallRegion("China", "Beijing", "对话指定地区")) as XenoCantoResponse.Success
        assertTrue(explicit.scopeNote.contains("未擅自扩大"))
        server.enqueue(MockResponse().setResponseCode(429))
        assertTrue(client().searchRegional("test-key", "Turdus merula", BirdCallRegion("China")) is XenoCantoResponse.Failure)
        server.enqueue(MockResponse().setBody("<html>temporarily unavailable</html>"))
        assertTrue(client().searchRegional("test-key", "Turdus merula", BirdCallRegion("China")) is XenoCantoResponse.Failure)
        assertEquals(3, server.requestCount)
    }
    @Test fun `records without playable files do not imply country has zero records`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"numRecordings":"3","recordings":[{"id":"1"}]}"""))
        val result = client().searchRegional("test-key", "Turdus merula", BirdCallRegion("China")) as XenoCantoResponse.Success
        assertEquals(3, result.total)
        assertTrue(result.recordings.isEmpty())
        assertEquals(1, server.requestCount)
    }
    @Test fun `cancelling lookup cancels active HTTP request`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
        val task = async { client().search("test-key", "Turdus merula") }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { server.takeRequest(3, java.util.concurrent.TimeUnit.SECONDS) }
        task.cancel()
        kotlinx.coroutines.withTimeout(1000) { task.join() }
        assertTrue(task.isCancelled)
    }
}
