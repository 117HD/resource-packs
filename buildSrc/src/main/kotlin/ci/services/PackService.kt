package ci.services

import Constants.BASE_GUTHUB_LINK_RAW
import ci.models.PackFileInfo
import ci.models.PackProperties
import okhttp3.OkHttpClient
import okhttp3.Request
import org.kohsuke.github.GHRepository
import java.util.Properties

class PackService(private val client: OkHttpClient) {

    fun readPackFile(repo: GHRepository, filePath: String, ref: String): PackFileInfo {
        val content = repo.getFileContent(filePath, ref)
        val props = Properties().apply {
            load(content.read().bufferedReader())
        }

        val repository = props.getProperty("repository")?.trim()
            ?: throw IllegalStateException("repository property not found in $filePath")
        val commit = props.getProperty("commit")?.trim()
            ?: throw IllegalStateException("commit property not found in $filePath")

        return PackFileInfo(repository, commit)
    }

    fun readPackProperties(repoLink: String, commit: String): PackProperties {
        val url = "${BASE_GUTHUB_LINK_RAW}${repoLink}/$commit/pack.properties"
        val request = Request.Builder().url(url).build()

        return client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val props = Properties().apply {
                    load(response.body.string().reader())
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
}

