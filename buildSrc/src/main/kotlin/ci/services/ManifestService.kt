package ci.services

import ci.config.Constants
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request

class ManifestService(private val client: OkHttpClient) {

    fun getExistingInternalNames(repoOwner: String, repoName: String): Set<String> {
        val manifestUrl = Constants.getManifestUrl(repoOwner, repoName)
        val request = Request.Builder().url(manifestUrl).build()
        return client.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "Unable to fetch the existing manifest (HTTP ${response.code})" }

            val jsonArray = JsonParser.parseString(response.body.string()).asJsonArray
                ?: error("The existing manifest is not a JSON array")

            jsonArray.mapNotNull { jsonObj ->
                jsonObj.asJsonObject.get("internalName")?.asString
            }.toSet()
        }
    }
}
