package ci.services

import Constants.BASE_GUTHUB_LINK_RAW
import ci.models.PackFileInfo
import ci.models.PackProperties
import ci.models.PackValidation
import okhttp3.OkHttpClient
import okhttp3.Request
import org.kohsuke.github.GHRepository
import java.io.ByteArrayOutputStream
import java.util.Properties

class PackService(private val client: OkHttpClient) {

    companion object {
        private const val MAX_PROPERTIES_SIZE = 64 * 1024
    }

    fun readPackFile(repo: GHRepository, filePath: String, ref: String): PackFileInfo {
        val content = repo.getFileContent(filePath, ref)
        val props = Properties().apply {
            load(content.read().bufferedReader())
        }

        return PackValidation.readDescriptor(props, filePath)
    }

    fun readPackProperties(repoLink: String, commit: String): PackProperties {
        val url = "${BASE_GUTHUB_LINK_RAW}${repoLink}/$commit/pack.properties"
        val request = Request.Builder().url(url).build()

        return client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val props = Properties().apply {
                    load(readLimited(response.body.byteStream(), MAX_PROPERTIES_SIZE).reader())
                }
                PackProperties(
                    author = props.getProperty("author")?.trim(),
                    description = props.getProperty("description")?.trim(),
                    displayName = props.getProperty("displayName")?.trim()
                )
            } else {
                PackProperties(null, null, null)
            }
        }
    }

    private fun readLimited(input: java.io.InputStream, limit: Int): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        input.use {
            while (true) {
                val read = it.read(buffer)
                if (read < 0) break
                require(output.size() + read <= limit) { "pack.properties exceeds $limit bytes" }
                output.write(buffer, 0, read)
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }
}
