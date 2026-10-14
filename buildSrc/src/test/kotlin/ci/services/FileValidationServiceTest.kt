package ci.services

import ci.Labels
import ci.models.PackFileInfo
import ci.models.PackProperties
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class FileValidationServiceTest {

    private lateinit var server: MockWebServer
    private val service = FileValidationService(OkHttpClient())

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun baseUrl(): String = server.url("/pack").toString().removeSuffix("/")

    private fun png(width: Int, height: Int): Buffer {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return Buffer().write(out.toByteArray())
    }

    private fun serve(responses: Map<String, MockResponse>) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                responses[request.path]?.clone() ?: MockResponse().setResponseCode(404)
        }
    }

    private fun packInfo(internalName: String = "my_pack") =
        PackFileInfo(internalName, "https://github.com/owner/repo", "a".repeat(40))

    private fun completeProps() = PackProperties(author = "Author", description = "Description", displayName = "Display Name")

    private fun validate(
        packInfo: PackFileInfo = packInfo(),
        props: PackProperties = completeProps(),
        status: Labels = Labels.CHANGED,
        existingNames: Set<String> = emptySet()
    ) = service.validateRequiredFiles(packInfo, props, status, existingNames, baseUrl())

    @Test
    fun `passes when all required files are present and valid`() {
        serve(
            mapOf(
                "/pack/licenses.txt" to MockResponse().setResponseCode(200).setBody("MIT"),
                "/pack/icon.png" to MockResponse().setResponseCode(200).setBody(png(221, 145)),
                "/pack/compact-icon.png" to MockResponse().setResponseCode(200).setBody(png(222, 45))
            )
        )
        val errors = validate()
        assertTrue(errors.isEmpty()) { errors.toString() }
    }

    @Test
    fun `reports missing licenses txt`() {
        serve(emptyMap())
        val errors = validate()
        assertTrue(errors.any { it.contains("licenses.txt") }) { errors.toString() }
    }

    @Test
    fun `reports missing metadata alongside missing files`() {
        serve(emptyMap())
        val errors = validate(props = PackProperties(null, null, null))
        assertTrue(errors.any { it.contains("displayName") })
        assertTrue(errors.any { it.contains("author") })
        assertTrue(errors.any { it.contains("description") })
        assertTrue(errors.any { it.contains("licenses.txt") })
    }

    @Test
    fun `reports settings properties not allowed for most packs`() {
        serve(
            mapOf(
                "/pack/licenses.txt" to MockResponse().setResponseCode(200).setBody("MIT"),
                "/pack/settings.properties" to MockResponse().setResponseCode(200).setBody("key=value")
            )
        )
        val errors = validate(packInfo = packInfo(internalName = "some_pack"))
        assertTrue(errors.any { it.contains("settings.properties is not allowed") }) { errors.toString() }
    }

    @Test
    fun `allows settings properties for the allow-listed pack`() {
        serve(
            mapOf(
                "/pack/licenses.txt" to MockResponse().setResponseCode(200).setBody("MIT"),
                "/pack/settings.properties" to MockResponse().setResponseCode(200).setBody("key=value")
            )
        )
        val errors = validate(packInfo = packInfo(internalName = "vanilla-plus"))
        assertTrue(errors.none { it.contains("settings.properties") }) { errors.toString() }
    }

    @Test
    fun `reports an icon with the wrong dimensions`() {
        serve(
            mapOf(
                "/pack/licenses.txt" to MockResponse().setResponseCode(200).setBody("MIT"),
                "/pack/icon.png" to MockResponse().setResponseCode(200).setBody(png(100, 100))
            )
        )
        val errors = validate()
        assertTrue(errors.any { it.contains("icon.png must be 221x145") }) { errors.toString() }
    }

    @Test
    fun `reports a compact icon with the wrong dimensions`() {
        serve(
            mapOf(
                "/pack/licenses.txt" to MockResponse().setResponseCode(200).setBody("MIT"),
                "/pack/compact-icon.png" to MockResponse().setResponseCode(200).setBody(png(10, 10))
            )
        )
        val errors = validate()
        assertTrue(errors.any { it.contains("compact-icon.png must be 222x45") }) { errors.toString() }
    }

    @Test
    fun `reports an icon that is not a valid image`() {
        serve(
            mapOf(
                "/pack/licenses.txt" to MockResponse().setResponseCode(200).setBody("MIT"),
                "/pack/icon.png" to MockResponse().setResponseCode(200).setBody("not a png")
            )
        )
        val errors = validate()
        assertTrue(errors.any { it.contains("icon.png is not a valid image file") }) { errors.toString() }
    }

    @Test
    fun `does not flag a correctly sized icon`() {
        serve(
            mapOf(
                "/pack/licenses.txt" to MockResponse().setResponseCode(200).setBody("MIT"),
                "/pack/icon.png" to MockResponse().setResponseCode(200).setBody(png(221, 145))
            )
        )
        val errors = validate()
        assertTrue(errors.none { it.contains("icon.png") }) { errors.toString() }
    }

    @Test
    fun `reports a duplicate internalName only when the pack is newly added`() {
        serve(mapOf("/pack/licenses.txt" to MockResponse().setResponseCode(200).setBody("MIT")))

        val addedErrors = validate(
            packInfo = packInfo(internalName = "existing_pack"),
            status = Labels.ADDED,
            existingNames = setOf("existing_pack")
        )
        assertTrue(addedErrors.any { it.contains("already in use") }) { addedErrors.toString() }

        val changedErrors = validate(
            packInfo = packInfo(internalName = "existing_pack"),
            status = Labels.CHANGED,
            existingNames = setOf("existing_pack")
        )
        assertTrue(changedErrors.none { it.contains("already in use") }) { changedErrors.toString() }
    }

    @Test
    fun `allows a new internalName that is not already in use`() {
        serve(mapOf("/pack/licenses.txt" to MockResponse().setResponseCode(200).setBody("MIT")))
        val errors = validate(
            packInfo = packInfo(internalName = "brand_new_pack"),
            status = Labels.ADDED,
            existingNames = setOf("some_other_pack")
        )
        assertTrue(errors.none { it.contains("already in use") }) { errors.toString() }
    }
}
