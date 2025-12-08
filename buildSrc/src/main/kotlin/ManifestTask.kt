import Constants.BASE_GUTHUB_LINK
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.internal.lowercase
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.kohsuke.github.GitHub
import java.io.File
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.*
import kotlin.system.measureTimeMillis


data class ManifestEntry(
    val hasIcon: Boolean,
    val hasCompactIcon: Boolean,
    val internalName: String,
    val tags: List<String>? = null,
    val commit: String,
    val support: String? = null,
    val author: String? = null,
    val description: String? = null,
    val packType: String,
    val link: String,
    val fileSize: Long? = null,
    val hasSettings: Boolean = false,
    val version: String? = null
)

data class Github(
    val sha : String = "",
    val files : Array<Files> = emptyArray(),
    val commit : Commit
)

data class Files(
    val sha : String = "",
    val filename : String,
    val raw_url : String
)

data class Commit(
    val author: Author
)

data class Author(
    val name : String
)

open class ManifestTask : DefaultTask() {

    @Internal
    val client : OkHttpClient = OkHttpClient()
    @Internal
    val validPack = emptyMap<File,Pair<String,String>>().toMutableMap()
    @Internal
    val failedPacks = emptyMap<String,MutableList<String>>().toMutableMap()
    @Internal
    val finalManifest = emptyMap<String,ManifestEntry>().toMutableMap()

    @TaskAction
    fun generate() {
        val specificPackFile = project.findProperty("packFile")?.toString()
        val packFiles = if (specificPackFile != null) {
            val file = File("./packs/$specificPackFile")
            if (file.exists()) listOf(file) else {
                System.err.println("Pack file not found: $specificPackFile")
                emptyList()
            }
        } else {
            File("./packs/").listFiles()?.toList() ?: emptyList()
        }

        if (packFiles.isEmpty()) {
            println("No pack files to process")
            return
        }

        val time = measureTimeMillis {
            var repoLink = ""
            packFiles.forEach {
                val packFile = Properties()
                packFile.load(it.inputStream())
                repoLink = packFile.getProperty("repository").substringAfter("https://github.com/")
                val commit = packFile.getProperty("commit")

                val request = Request.Builder().url(
                    "${BASE_GUTHUB_LINK}${repoLink}/commits/${commit}"
                ).build()

                val call = client.newCall(request)
                val response = call.execute()
                if (response.code == 200) {
                    validPack[it] = Pair(repoLink, response.body.string())
                    println("${it.name} - ${BASE_GUTHUB_LINK}${repoLink}/commits/${commit}")
                } else {
                    addError(
                        it.nameWithoutExtension,
                        "Unable to Find repo"
                    )
                    System.err.println("Unable to update: ${it.nameWithoutExtension} Code: ${response.code}")
                }
            }
        }

        // Use coroutines only when processing multiple packs
        if (validPack.size > 1) {
            runBlocking {
                withContext(coroutineContext) {
                    validPack.forEach {
                        async(Dispatchers.IO) {
                            val data = getData(it.value.first, it.value.second)
                            if (data.second != null && data.first.isNotEmpty()) {
                                finalManifest.put(data.second!!.internalName, data.second!!)
                            } else {
                                addError(
                                    it.key.nameWithoutExtension,
                                    data.first
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // Process single pack synchronously
            validPack.forEach {
                val data = getData(it.value.first, it.value.second)
                if (data.second != null && data.first.isNotEmpty()) {
                    finalManifest.put(data.second!!.internalName, data.second!!)
                } else {
                    addError(
                        it.key.nameWithoutExtension,
                        data.first
                    )
                }
            }
        }

        updateRepo()

        println("Manifest Updated in $time ms")
    }


    private fun addError(name : String, error : String) {
        val list = failedPacks[name]?: emptyList<String>().toMutableList()
        list.add(error)
        failedPacks[name] = list
    }

    private fun getData(repo : String, content : String): Pair<String,ManifestEntry?> {
        val gson = Gson()
        val github = try {
            gson.fromJson(content, Github::class.java)
        } catch (e: Exception) {
            return Pair("pack.properties is missing please add it", null)
        }
        if (github == null) {
            return Pair("pack.properties is missing please add it", null)
        }

        val request = Request.Builder().url(
            "${Constants.BASE_GUTHUB_LINK_RAW}${repo}/${github.sha}/pack.properties"
        ).build()

        val response = client.newCall(request).execute()
        if (response.code == 200) {

            val foundIcon = foundFile("${Constants.BASE_GUTHUB_LINK_RAW}${repo}/${github.sha}/icon.png")
            val foundSettings = foundFile("${Constants.BASE_GUTHUB_LINK_RAW}${repo}/${github.sha}/settings.properties")
            val foundCompactIcon = foundFile("${Constants.BASE_GUTHUB_LINK_RAW}${repo}/${github.sha}/compact-icon.png")

            val properties = Properties()

            properties.load(StringReader(response.body.string()))

            val internalName = properties.getProperty("displayName").lowercase().replace(" ", "_")

            val author = properties.getProperty("author").toString()

            val version = properties.getProperty("version")
            val support = properties.getProperty("support")
            val description = properties.getProperty("description")
            val packType = properties.getProperty("packType","RESOURCE")
            val tags = properties.getProperty("tags").split(",")

            return Pair(
                " ", ManifestEntry(
                    hasIcon = foundIcon,
                    internalName = internalName,
                    tags = tags,
                    commit = github.sha,
                    support = support,
                    author = author,
                    packType = packType,
                    fileSize = downloadZipAndGetSize(repo,github.sha),
                    description = description,
                    link = "https://github.com/${repo}",
                    hasCompactIcon = foundCompactIcon,
                    hasSettings = foundSettings,
                    version = version
                )
            )
        }
        return Pair("",null)


    }

    private fun downloadZipAndGetSize(repo: String, sha : String): Long? {
        val zipUrl = "https://github.com".toHttpUrlOrNull()!!
            .newBuilder()
            .apply {
                val parts = repo.split("/")
                addPathSegment(parts[0])
                addPathSegment(parts[1])
            }
            .addPathSegment("archive")
            .addPathSegment("${sha}.zip")
            .build()
            .toString()



        val request = Request.Builder().url(zipUrl).get().build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null

            val body = response.body

            val temp = File.createTempFile("pack-", ".zip")

            body.byteStream().use { input ->
                temp.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            val size = temp.length()
            temp.delete()

            return size
        }
    }

    private fun foundFile(link: String): Boolean {
        val request = Request.Builder().url(link).build()
        client.newCall(request).execute().use { response ->
            return response.code == 200
        }
    }

    fun Any.jsonToString(prettyPrint: Boolean): String {
        val gson = if (prettyPrint) {
            GsonBuilder().setPrettyPrinting().create()
        } else {
            Gson()
        }
        return gson.toJson(this)
    }

    fun updateRepo() {
        val token = project.property("token").toString()
        val repoName = project.findProperty("REPO_NAME")?.toString()
            ?: System.getenv("REPO_NAME")
            ?: System.getenv("GITHUB_REPOSITORY")
            ?: "117HD/resource-pack-hub"

        val gh = GitHub.connectUsingOAuth(token)

        val repo = try {
            gh.getRepository(repoName)
        } catch (e: Exception) {
            throw IllegalStateException("Unable to load repository $repoName: ${e.message}", e)
        }

        val format = SimpleDateFormat("dd MMM yyyy HH:mm:ss z")
        format.timeZone = TimeZone.getTimeZone("Europe/London")

        // Load existing manifest to merge with new/updated packs
        val existingManifest = try {
            val manifestContent = repo.getFileContent("manifest.json", "manifest")
            val gson = Gson()
            val type = object : TypeToken<List<ManifestEntry>>() {}.type
            val jsonString = manifestContent.read().bufferedReader().use { it.readText() }
            val existingEntries: List<ManifestEntry>? = gson.fromJson(jsonString, type)
            existingEntries?.associateBy { it.internalName }?.toMutableMap() ?: mutableMapOf()
        } catch (e: Exception) {
            System.err.println("Failed to load existing manifest: ${e.message}")
            mutableMapOf()
        }

        // Merge new/updated packs into existing manifest
        existingManifest.putAll(finalManifest)

        repo.getFileContent("manifest.json", "manifest").update(
            existingManifest.values.jsonToString(true),
            "Update manifest.json ${format.format(GregorianCalendar.getInstance().time)}",
            "manifest"
        )
    }


}