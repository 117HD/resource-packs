import com.google.gson.Gson
import com.google.gson.GsonBuilder
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.kohsuke.github.GitHub
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.TimeZone
import java.util.concurrent.TimeUnit

data class ManifestEntry(
    val hasIcon: Boolean,
    val hasCompactIcon: Boolean,
    val internalName: String,
    val tags: List<String> = emptyList(),
    val commit: String,
    val support: String? = null,
    val author: String? = null,
    val description: String? = null,
    val packType: String,
    val link: String,
    val fileSize: Long,
    val sha256: String,
    val hasSettings: Boolean = false,
    val version: String? = null
)

private data class PackSource(val internalName: String, val owner: String, val repository: String, val commit: String)
private data class ArchiveInfo(val size: Long, val sha256: String)

open class ManifestTask : DefaultTask()
{
    companion object
    {
        private const val MANIFEST_BRANCH = "manifest"
        private const val MAX_ARCHIVE_SIZE = 512L * 1024 * 1024
        private const val MAX_API_RESPONSE_SIZE = 1024 * 1024
        private const val MAX_PROPERTIES_SIZE = 64 * 1024
        private val INTERNAL_NAME = Regex("[a-z0-9_-]+")
        private val COMMIT = Regex("(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})")
        private val GITHUB_REPOSITORY = Regex("https://github\\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)")
    }

    @Internal
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    @TaskAction
    fun generate()
    {
        val packFiles = project.file("packs").listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()
        require(packFiles.isNotEmpty()) { "No official pack descriptors were found" }
        val entries = packFiles.map { generateEntry(readSource(it)) }.sortedBy { it.internalName }
        require(entries.map { it.internalName }.distinct().size == entries.size) { "Pack internal names must be unique" }
        updateRepo(entries)
        println("Published ${entries.size} resource pack entries")
    }

    private fun readSource(file: java.io.File): PackSource
    {
        val properties = Properties().apply { file.inputStream().use(::load) }
        val internalName = properties.getProperty("internalName")?.trim() ?: error("${file.name}: internalName is required")
        require(INTERNAL_NAME.matches(internalName)) { "${file.name}: internalName may contain only lowercase letters, numbers, _ and -" }
        val repositoryUrl = properties.getProperty("repository")?.trim() ?: error("${file.name}: repository is required")
        val match = GITHUB_REPOSITORY.matchEntire(repositoryUrl)
            ?: error("${file.name}: repository must be exactly https://github.com/owner/repository")
        val commit = properties.getProperty("commit")?.trim() ?: error("${file.name}: commit is required")
        require(COMMIT.matches(commit)) { "${file.name}: commit must be a full 40-character SHA-1 or 64-character SHA-256" }
        return PackSource(internalName, match.groupValues[1], match.groupValues[2], commit.toLowerCase(Locale.ROOT))
    }

    private fun generateEntry(source: PackSource): ManifestEntry
    {
        val resolvedCommit = Gson().fromJson(getText(apiUrl(source, "commits/${source.commit}"), MAX_API_RESPONSE_SIZE), Map::class.java)["sha"] as? String
            ?: error("${source.internalName}: unable to resolve commit ${source.commit}")
        require(resolvedCommit.equals(source.commit, ignoreCase = true)) { "${source.internalName}: GitHub resolved a different commit than requested" }
        val properties = Properties().apply { load(StringReader(getText(rawUrl(source, "pack.properties"), MAX_PROPERTIES_SIZE))) }
        val displayName = properties.getProperty("displayName")?.trim()
            ?: error("${source.internalName}: pack.properties must declare displayName")
        val archive = downloadArchive(source)
        return ManifestEntry(
            hasIcon = exists(rawUrl(source, "icon.png")),
            hasCompactIcon = exists(rawUrl(source, "compact-icon.png")),
            internalName = source.internalName,
            tags = properties.getProperty("tags")?.split(',')?.map(String::trim)?.filter(String::isNotEmpty) ?: emptyList(),
            commit = resolvedCommit.toLowerCase(Locale.ROOT),
            support = properties.getProperty("support")?.trim()?.takeIf(String::isNotEmpty),
            author = properties.getProperty("author")?.trim()?.takeIf(String::isNotEmpty),
            description = properties.getProperty("description")?.trim()?.takeIf(String::isNotEmpty),
            packType = properties.getProperty("packType", "RESOURCE").trim(),
            link = "https://github.com/${source.owner}/${source.repository}",
            fileSize = archive.size,
            sha256 = archive.sha256,
            hasSettings = exists(rawUrl(source, "settings.properties")),
            version = properties.getProperty("version")?.trim()?.takeIf(String::isNotEmpty)
        ).also { println("${it.internalName}: $displayName (${archive.size} bytes)") }
    }

    private fun downloadArchive(source: PackSource): ArchiveInfo
    {
        val request = Request.Builder().url(githubUrl(source, "archive/${source.commit}.zip")).get().build()
        return client.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "${source.internalName}: archive download failed (HTTP ${response.code})" }
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            response.body.byteStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true)
                {
                    val read = input.read(buffer)
                    if (read < 0) break
                    size += read
                    require(size <= MAX_ARCHIVE_SIZE) { "${source.internalName}: archive exceeds ${MAX_ARCHIVE_SIZE / 1024 / 1024} MiB" }
                    digest.update(buffer, 0, read)
                }
            }
            ArchiveInfo(size, digest.digest().joinToString("") { "%02x".format(it) })
        }
    }

    private fun exists(url: String): Boolean
    {
        val request = Request.Builder().url(url).head().build()
        return client.newCall(request).execute().use { it.isSuccessful }
    }

    private fun getText(url: String, limit: Int): String
    {
        val request = Request.Builder().url(url).get().build()
        return client.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "Request failed for $url (HTTP ${response.code})" }
            readLimited(response.body.byteStream(), limit)
        }
    }

    private fun readLimited(input: java.io.InputStream, limit: Int): String
    {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        input.use {
            while (true)
            {
                val read = it.read(buffer)
                if (read < 0)
                    break
                require(output.size() + read <= limit) { "Response exceeds $limit bytes" }
                output.write(buffer, 0, read)
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun githubUrl(source: PackSource, suffix: String): String = "https://github.com".toHttpUrl().newBuilder()
        .addPathSegment(source.owner).addPathSegment(source.repository).addPathSegments(suffix).build().toString()

    private fun rawUrl(source: PackSource, path: String): String = "https://raw.githubusercontent.com".toHttpUrl().newBuilder()
        .addPathSegment(source.owner).addPathSegment(source.repository).addPathSegment(source.commit).addPathSegments(path).build().toString()

    private fun apiUrl(source: PackSource, path: String): String = "https://api.github.com/repos".toHttpUrl().newBuilder()
        .addPathSegment(source.owner).addPathSegment(source.repository).addPathSegments(path).build().toString()

    private fun updateRepo(entries: List<ManifestEntry>)
    {
        val token = project.findProperty("token")?.toString() ?: System.getenv("GITHUB_TOKEN")
            ?: error("GitHub token not found. Set the token Gradle property or GITHUB_TOKEN.")
        val repoName = project.findProperty("REPO_NAME")?.toString() ?: System.getenv("GITHUB_REPOSITORY") ?: "117HD/resource-packs"
        val repo = GitHub.connectUsingOAuth(token).getRepository(repoName)
        val content = entries.jsonToString(true)
        val format = SimpleDateFormat("dd MMM yyyy HH:mm:ss z").apply { timeZone = TimeZone.getTimeZone("Europe/London") }
        val message = "Update manifest.json ${format.format(Date())}"
        val existing = runCatching { repo.getFileContent("manifest.json", MANIFEST_BRANCH) }.getOrNull()
        if (existing == null)
            repo.createContent().path("manifest.json").content(content).branch(MANIFEST_BRANCH).message(message).commit()
        else
            existing.update(content, message, MANIFEST_BRANCH)
    }

    private fun Any.jsonToString(prettyPrint: Boolean): String =
        (if (prettyPrint) GsonBuilder().setPrettyPrinting().create() else Gson()).toJson(this)
}
