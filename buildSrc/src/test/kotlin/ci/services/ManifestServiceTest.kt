package ci.services

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ManifestServiceTest {

    private lateinit var server: MockWebServer
    private val service = ManifestService(OkHttpClient())

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `returns the set of internalName values from the manifest`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """[{"internalName":"pack_one"},{"internalName":"pack_two"}]"""
            )
        )
        val names = service.getExistingInternalNames("owner", "repo", server.url("/manifest.json").toString())
        assertEquals(setOf("pack_one", "pack_two"), names)
    }

    @Test
    fun `skips entries with no internalName field`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """[{"internalName":"pack_one"},{"displayName":"no internal name here"}]"""
            )
        )
        val names = service.getExistingInternalNames("owner", "repo", server.url("/manifest.json").toString())
        assertEquals(setOf("pack_one"), names)
    }

    @Test
    fun `returns an empty set for an empty manifest`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
        val names = service.getExistingInternalNames("owner", "repo", server.url("/manifest.json").toString())
        assertEquals(emptySet<String>(), names)
    }

    @Test
    fun `throws when the manifest cannot be fetched`() {
        server.enqueue(MockResponse().setResponseCode(404))
        assertThrows(IllegalArgumentException::class.java) {
            service.getExistingInternalNames("owner", "repo", server.url("/manifest.json").toString())
        }
    }

    @Test
    fun `throws when the response is not a JSON array`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"not":"an array"}"""))
        assertThrows(IllegalStateException::class.java) {
            service.getExistingInternalNames("owner", "repo", server.url("/manifest.json").toString())
        }
    }
}
