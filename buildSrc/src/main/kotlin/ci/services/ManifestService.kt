package ci.services

import ci.config.Constants
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request

class ManifestService(private val client: OkHttpClient) {

    fun getExistingInternalNames(repoOwner: String, repoName: String): Set<String> = runCatching {
        val manifestUrl = Constants.getManifestUrl(repoOwner, repoName)
        val request = Request.Builder().url(manifestUrl).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use emptySet<String>()

            val json = response.body.string() ?: return@use emptySet<String>()
            val jsonArray = JsonParser.parseString(json).asJsonArray ?: return@use emptySet<String>()

            jsonArray.mapNotNull { jsonObj ->
                jsonObj.asJsonObject.get("internalName")?.asString
            }.toSet()
        }
    }.getOrElse {
        System.err.println("Failed to fetch manifest.json: ${it.message}")
        emptySet()
    }
}

