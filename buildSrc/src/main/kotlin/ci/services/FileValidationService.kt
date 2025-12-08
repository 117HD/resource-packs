package ci.services

import Constants.BASE_GUTHUB_LINK_RAW
import ci.config.Constants
import ci.models.PackFileInfo
import ci.models.PackProperties
import ci.Labels
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.imageio.ImageIO

class FileValidationService(private val client: OkHttpClient) {

    @OptIn(ExperimentalStdlibApi::class)
    fun validateRequiredFiles(
        packInfo: PackFileInfo,
        packProps: PackProperties,
        status: Labels,
        existingInternalNames: Set<String>
    ): List<String> = buildList {
        if (packProps.displayName == null) {
            add("displayName is required in pack.properties")
        }

        if (packProps.description == null) {
            add("description is required in pack.properties")
        }

        // Check for unique internalName only for new packs
        if (status == Labels.ADDED && packProps.internalName != null) {
            if (packProps.internalName in existingInternalNames) {
                add("${packProps.internalName} is already in use please use a new name")
            }
        }

        val baseUrl = "${BASE_GUTHUB_LINK_RAW}${packInfo.repoLink}/${packInfo.commit}"

        if (!fileExists("$baseUrl/licenses.txt")) {
            add("licenses.txt has not been found this is required")
        }

        if (fileExists("$baseUrl/settings.properties")) {
            val is117Team = packProps.author?.contains(Constants.REQUIRED_117_TEAM, ignoreCase = true) == true
            if (!is117Team) {
                add("settings.properties is only allowed for 117 HD team packs")
            }
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

                val imageBytes = response.body?.bytes() ?: return@use null
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
}

