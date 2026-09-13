package com.example.birdingsoundmvp.planning

import java.time.LocalDate
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.runBlocking

class EbirdRecentObservationsClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: EbirdRecentObservationsClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = EbirdRecentObservationsClient(baseUrl = server.url("/v2").toString(), maxRetries = 0)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun historicObservationParsesFullFieldsAndBuildsExpectedPath() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                [{
                  "speciesCode":"grtits1",
                  "sciName":"Parus major",
                  "comName":"Great Tit",
                  "locId":"L123",
                  "locName":"City Park",
                  "obsDt":"2025-03-08 07:30",
                  "howMany":3,
                  "lat":31.2,
                  "lng":121.5,
                  "obsValid":true,
                  "obsReviewed":false,
                  "subId":"S99"
                }]
                """.trimIndent()
            )
        )

        val response = client.fetchHistoricObservations("token", "L123", LocalDate.of(2025, 3, 8))
        val observation = (response as EbirdRecentObservationsResponse.Success).observations.single()
        val request = server.takeRequest()

        assertEquals("/v2/data/obs/L123/historic/2025/3/8?detail=full&includeProvisional=true&cat=species&maxResults=10000", request.path)
        assertEquals("token", request.getHeader("X-eBirdApiToken"))
        assertEquals("Parus major", observation.scientificName)
        assertEquals(3, observation.count)
        assertEquals(31.2, observation.latitude ?: 0.0, 0.0001)
        assertFalse(observation.observationReviewed)
        assertEquals("S99", observation.submissionId)
    }

    @Test
    fun notableEndpointAndRegionListAreParsed() = runBlocking {
        server.enqueue(MockResponse().setBody("[]"))
        server.enqueue(MockResponse().setBody("[{\"code\":\"CN-31\",\"name\":\"Shanghai\"}]"))
        server.enqueue(MockResponse().setBody("[{\"code\":\"CN\",\"name\":\"China\"},{\"code\":\"US\",\"name\":\"United States\"}]"))

        val notable = client.fetchRecentNotableObservations("token", "L123", 30)
        val regions = client.listSubnationalRegions("token", "CN")
        val countries = client.listCountries("token")

        assertTrue(notable is EbirdRecentObservationsResponse.Success)
        assertEquals("/v2/data/obs/L123/recent/notable?back=30&detail=full&includeProvisional=true&maxResults=10000", server.takeRequest().path)
        assertEquals("上海", (regions as EbirdRegionsResponse.Success).regions.single().name)
        assertEquals("/v2/ref/region/list/subnational1/CN?fmt=json", server.takeRequest().path)
        assertEquals(listOf("CN", "US"), (countries as EbirdRegionsResponse.Success).regions.map { it.code })
        assertEquals("/v2/ref/region/list/country/world?fmt=json", server.takeRequest().path)
    }

    @Test
    fun regionRefreshBypassesOnlyTheRequestedDirectoryCache() = runBlocking {
        server.enqueue(MockResponse().setBody("[]"))
        assertTrue((client.listSubnationalRegions("token", "CN") as EbirdRegionsResponse.Success).regions.isEmpty())
        client.listSubnationalRegions("token", "CN")
        assertEquals(1, server.requestCount)
        server.enqueue(MockResponse().setBody("""[{"code":"CN-11","name":"Beijing"}]"""))
        val refreshed = client.listSubnationalRegions("token", "CN", forceRefresh = true) as EbirdRegionsResponse.Success
        assertEquals("北京", refreshed.regions.single().name)
        client.listSubnationalRegions("token", "CN")
        assertEquals(2, server.requestCount)
    }

    @Test
    fun tooManyRequestsReturnsStructuredRetryInformation() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "2")
                .setBody("rate limited")
        )

        val response = client.fetchHistoricObservations("token", "L123", LocalDate.of(2025, 3, 8))
            as EbirdRecentObservationsResponse.Failure

        assertEquals(429, response.statusCode)
        assertEquals(2_000L, response.retryAfterMs)
        assertTrue(response.retryable)
    }
}
