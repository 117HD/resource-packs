package ci.services

import Constants.BASE_GUTHUB_LINK_RAW
import ci.config.Constants
import ci.models.PackFileInfo
import ci.models.PackProperties
import ci.models.PackValidation
import ci.Labels
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class FileValidationService(private val client: OkHttpClient) {

    companion object {
        private const val MAX_ICON_SIZE = 1024 * 1024
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun validateRequiredFiles(
        packInfo: PackFileInfo,
        packProps: PackProperties,
        status: Labels,
        existingInternalNames: Set<String>
    ): List<String> = buildList {
        addAll(PackValidation.requiredMetadataErrors(packProps))

        // The descriptor owns the stable identifier; a display name may change freely.
        if (status == Labels.ADDED && packInfo.internalName in existingInternalNames) {
            add("${packInfo.internalName} is already in use; choose a new internalName")
        }

        val baseUrl = "${BASE_GUTHUB_LINK_RAW}${packInfo.repoLink}/${packInfo.commit}"

        if (!fileExists("$baseUrl/licenses.txt")) {
            add("licenses.txt has not been found this is required")
        }

        if (fileExists("$baseUrl/settings.properties")) {
            if (!PackValidation.allowsSettings(packInfo.internalName))
                add("settings.properties is not allowed for ${packInfo.internalName}")
        }

        if (fileExists("$baseUrl/icon.png")) {
            validateIconSize("$baseUrl/icon.png", "icon.png", Constants.ICON_WIDTH, Constants.ICON_HEIGHT)?.let(::add)
        }

        if (fileExists("$baseUrl/compact-icon.png")) {
            validateIconSize(
                "$baseUrl/compact-icon.png",
                "compact-icon.png",
                Constants.COMPACT_ICON_WIDTH,
                Constants.COMPACT_ICON_HEIGHT
            )?.let(::add)
        }
    }

    private fun fileExists(link: String): Boolean {
        val request = Request.Builder().url(link).build()
        return client.newCall(request).execute().use { it.code == 200 }
    }

    private fun validateIconSize(iconUrl: String, fileName: String, expectedWidth: Int, expectedHeight: Int): String? =
        runCatching {
            val request = Request.Builder().url(iconUrl).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null

                val imageBytes = readLimited(response.body.byteStream(), MAX_ICON_SIZE)
                val image = ImageIO.read(java.io.ByteArrayInputStream(imageBytes))

                if (image == null) {
                    return@use "$fileName is not a valid image file"
                }

                val (width, height) = image.width to image.height

                if (width != expectedWidth || height != expectedHeight) {
                    return@use "$fileName must be ${expectedWidth}x${expectedHeight} pixels (found ${width}x${height})"
                }

                null
            }
        }.getOrElse {
            System.err.println("Failed to validate $fileName size: ${it.message}")
            null
        }

    private fun readLimited(input: java.io.InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        input.use {
            while (true) {
                val read = it.read(buffer)
                if (read < 0) break
                require(output.size() + read <= limit) { "Image exceeds $limit bytes" }
                output.write(buffer, 0, read)
            }
        }
        return output.toByteArray()
    }
}
