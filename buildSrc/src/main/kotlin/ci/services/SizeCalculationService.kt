package ci.services

import Constants.BASE_GUTHUB_LINK
import ci.Labels
import ci.models.PackFileInfo
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request

class SizeCalculationService(private val client: OkHttpClient) {

    fun calculateSize(status: Labels, headPackInfo: PackFileInfo, mainPackInfo: PackFileInfo?): Labels =
        when (status) {
            Labels.ADDED -> getFileCountFromTree(headPackInfo.repoLink, headPackInfo.commit)
            Labels.CHANGED -> mainPackInfo?.let {
                getFileCountFromDiff(headPackInfo.repoLink, it.commit, headPackInfo.commit)
            } ?: getFileCountFromDiff(headPackInfo.repoLink, headPackInfo.commit)
            else -> Labels.SIZE_L
        }

    fun calculateFilesChanged(status: Labels, headPackInfo: PackFileInfo, mainPackInfo: PackFileInfo?): Int =
        when (status) {
            Labels.ADDED -> getFileCountFromTreeForComment(headPackInfo.repoLink, headPackInfo.commit)
            Labels.CHANGED -> mainPackInfo?.let {
                getFileCountFromDiffForComment(headPackInfo.repoLink, it.commit, headPackInfo.commit)
            } ?: getFileCountFromDiffForComment(headPackInfo.repoLink, headPackInfo.commit)
            else -> 0
        }

    private fun getFileCountFromTree(repoLink: String, commit: String): Labels {
        val url = "${BASE_GUTHUB_LINK}${repoLink}/git/trees/$commit?recursive=1"
        val request = Request.Builder().url(url).build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use Labels.SIZE_L

            val json = response.body.string()
            val tree = JsonParser.parseString(json).asJsonObject
            val treeItems = tree.getAsJsonArray("tree") ?: return@use Labels.SIZE_L

            val fileCount = treeItems.count { it.asJsonObject.get("type").asString == "blob" }
            mapFileCountToLabel(fileCount)
        }
    }

    private fun getFileCountFromDiff(repoLink: String, oldCommit: String, newCommit: String): Labels {
        val url = "${BASE_GUTHUB_LINK}${repoLink}/compare/$oldCommit...$newCommit"
        val request = Request.Builder().url(url).build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use Labels.SIZE_L

            val json = response.body.string()
            val compareObj = JsonParser.parseString(json).asJsonObject
            val files = compareObj.getAsJsonArray("files") ?: return@use Labels.SIZE_L

            mapFileCountToLabel(files.size())
        }
    }

    private fun getFileCountFromDiff(repoLink: String, commit: String): Labels {
        val url = "${BASE_GUTHUB_LINK}${repoLink}/commits/$commit"
        val request = Request.Builder().url(url).build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use Labels.SIZE_L

            val json = response.body.string()
            val commitObj = JsonParser.parseString(json).asJsonObject
            val files = commitObj.getAsJsonArray("files") ?: return@use Labels.SIZE_L

            mapFileCountToLabel(files.size())
        }
    }

    private fun getFileCountFromTreeForComment(repoLink: String, commit: String): Int {
        val url = "${BASE_GUTHUB_LINK}${repoLink}/git/trees/$commit?recursive=1"
        val request = Request.Builder().url(url).build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use 0

            val json = response.body.string()
            val tree = JsonParser.parseString(json).asJsonObject
            val treeItems = tree.getAsJsonArray("tree") ?: return@use 0

            treeItems.count { it.asJsonObject.get("type").asString == "blob" }
        }
    }

    private fun getFileCountFromDiffForComment(repoLink: String, commit: String): Int {
        val url = "${BASE_GUTHUB_LINK}${repoLink}/commits/$commit"
        val request = Request.Builder().url(url).build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use 0

            val json = response.body.string() ?: return@use 0
            val commitObj = JsonParser.parseString(json).asJsonObject
            val files = commitObj.getAsJsonArray("files") ?: return@use 0

            files.size()
        }
    }

    private fun getFileCountFromDiffForComment(repoLink: String, oldCommit: String, newCommit: String): Int {
        val url = "${BASE_GUTHUB_LINK}${repoLink}/compare/$oldCommit...$newCommit"
        val request = Request.Builder().url(url).build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use 0

            val json = response.body.string() ?: return@use 0
            val compareObj = JsonParser.parseString(json).asJsonObject
            val files = compareObj.getAsJsonArray("files") ?: return@use 0

            files.size()
        }
    }

    private fun mapFileCountToLabel(fileCount: Int): Labels = when {
        fileCount < 10 -> Labels.SIZE_XS
        fileCount < 50 -> Labels.SIZE_S
        fileCount < 200 -> Labels.SIZE_M
        fileCount < 500 -> Labels.SIZE_L
        else -> Labels.SIZE_XL
    }
}

