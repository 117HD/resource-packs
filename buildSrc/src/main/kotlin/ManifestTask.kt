import com.google.gson.Gson
import com.google.gson.GsonBuilder
import ci.models.PackProperties
import ci.models.PackValidation
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.kohsuke.github.GitHub
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

data class ManifestEntry(
    val hasIcon: Boolean,
    val hasCompactIcon: Boolean,
    val displayName: String,
    val internalName: String,
    val tags: List<String> = emptyList(),
    val commit: String,
    val support: String? = null,
    val author: String? = null,
    val description: String? = null,
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
    @Internal
    var publish = true

    companion object
    {
        private const val MANIFEST_BRANCH = "manifest"
        private const val MAX_ARCHIVE_SIZE = 512L * 1024 * 1024
        private const val MAX_ARCHIVE_ENTRIES = 10_000
        private const val MAX_ARCHIVE_ENTRY_SIZE = 128L * 1024 * 1024
        private const val MAX_ARCHIVE_UNCOMPRESSED_SIZE = 1024L * 1024 * 1024
        private const val MAX_API_RESPONSE_SIZE = 1024 * 1024
        private const val MAX_PROPERTIES_SIZE = 64 * 1024
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
        val packFiles = project.file("packs").listFiles()
            ?.filter { Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) }
            ?.sortedBy { it.name }
            ?: emptyList()
        require(packFiles.isNotEmpty()) { "No official pack descriptors were found" }
        val entries = packFiles.map { generateEntry(readSource(it)) }.sortedBy { it.internalName }
        require(entries.map { it.internalName }.distinct().size == entries.size) { "Pack internal names must be unique" }
        require(entries.map { it.sha256 }.distinct().size == entries.size) { "Official packs must not share an archive SHA-256" }
        if (publish)
            updateRepo(entries)
        println("${if (publish) "Published" else "Validated"} ${entries.size} resource pack entries")
    }

    private fun readSource(file: java.io.File): PackSource
    {
        val properties = Properties().apply { file.inputStream().use(::load) }
        require(PackValidation.isSafeDescriptorFilename(file.name)) { "${file.name}: descriptor filenames may contain only lowercase letters, numbers, _ and -" }
        val descriptor = PackValidation.readDescriptor(properties, file.name)
        val repositoryParts = descriptor.repoLink.split('/')
        return PackSource(descriptor.internalName, repositoryParts[0], repositoryParts[1], descriptor.commit.toLowerCase(Locale.ROOT))
    }

    private fun generateEntry(source: PackSource): ManifestEntry
    {
        val resolvedCommit = Gson().fromJson(getText(apiUrl(source, "commits/${source.commit}"), MAX_API_RESPONSE_SIZE), Map::class.java)["sha"] as? String
            ?: error("${source.internalName}: unable to resolve commit ${source.commit}")
        require(resolvedCommit.equals(source.commit, ignoreCase = true)) { "${source.internalName}: GitHub resolved a different commit than requested" }
        val properties = Properties().apply { load(StringReader(getText(rawUrl(source, "pack.properties"), MAX_PROPERTIES_SIZE))) }
        val packProperties = PackProperties(properties.getProperty("author")?.trim(), properties.getProperty("description")?.trim(), properties.getProperty("displayName")?.trim())
        require(PackValidation.requiredMetadataErrors(packProperties).isEmpty()) { "${source.internalName}: pack.properties is missing required metadata" }
        val displayName = packProperties.displayName!!
        val archive = downloadArchive(source)
        val hasSettings = exists(rawUrl(source, "settings.properties"))
        require(!hasSettings || PackValidation.allowsSettings(source.internalName)) {
            "${source.internalName}: settings.properties is not allowed for this pack"
        }
        return ManifestEntry(
            hasIcon = exists(rawUrl(source, "icon.png")),
            hasCompactIcon = exists(rawUrl(source, "compact-icon.png")),
            displayName = displayName,
            internalName = source.internalName,
            tags = parseTags(properties.getProperty("tags"), source.internalName),
            commit = resolvedCommit.toLowerCase(Locale.ROOT),
            support = properties.getProperty("support")?.trim()?.takeIf(String::isNotEmpty),
            author = packProperties.author,
            description = packProperties.description,
            link = "https://github.com/${source.owner}/${source.repository}",
            fileSize = archive.size,
            sha256 = archive.sha256,
            hasSettings = hasSettings,
            version = properties.getProperty("version")?.trim()?.takeIf(String::isNotEmpty)
        ).also { println("${it.internalName}: $displayName (${archive.size} bytes)") }
    }

    private fun downloadArchive(source: PackSource): ArchiveInfo
    {
        val request = Request.Builder().url(githubUrl(source, "archive/${source.commit}.zip")).get().build()
        return client.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "${source.internalName}: archive download failed (HTTP ${response.code})" }
            val digest = MessageDigest.getInstance("SHA-256")
            val input = ValidatingArchiveInputStream(response.body.byteStream(), digest, source.internalName)
            // Shield `input` from ZipInputStream.close(), which would otherwise close the
            // underlying stream before we can drain the remaining bytes below.
            val shielded = object : FilterInputStream(input) {
                override fun close() {}
            }
            ZipInputStream(shielded).use { zip -> validateArchive(zip, source.internalName) }
            // ZipInputStream stops reading once it has parsed the local file entries, leaving the
            // central directory / EOCD record (and any archive comment) unread. Drain the rest so
            // size/sha256 reflect the full byte stream the real client downloads.
            val drain = ByteArray(DEFAULT_BUFFER_SIZE)
            while (input.read(drain) >= 0) { /* drain remaining bytes */ }
            input.close()
            ArchiveInfo(input.size, digest.digest().joinToString("") { "%02x".format(it) })
        }
    }

    private fun validateArchive(zip: ZipInputStream, internalName: String) {
        val names = mutableSetOf<String>()
        var entries = 0
        var uncompressedSize = 0L
        var packProperties = 0
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val entry = zip.nextEntry ?: break
            require(++entries <= MAX_ARCHIVE_ENTRIES) { "$internalName: archive has too many entries" }
            require(isSafeArchivePath(entry.name)) { "$internalName: archive contains an unsafe entry path" }
            require(names.add(entry.name)) { "$internalName: archive contains duplicate entries" }
            if (!entry.isDirectory && (entry.name == "pack.properties" || entry.name.endsWith("/pack.properties")))
                packProperties++
            var entrySize = 0L
            while (true) {
                val read = zip.read(buffer)
                if (read < 0) break
                entrySize += read
                uncompressedSize += read
                require(entrySize <= MAX_ARCHIVE_ENTRY_SIZE) { "$internalName: archive entry is too large" }
                require(uncompressedSize <= MAX_ARCHIVE_UNCOMPRESSED_SIZE) { "$internalName: archive expands beyond the allowed size" }
            }
            zip.closeEntry()
        }
        require(packProperties == 1) { "$internalName: archive must contain exactly one pack.properties" }
    }

    private fun isSafeArchivePath(path: String): Boolean {
        val normalized = path.removeSuffix("/")
        return normalized.isNotEmpty()
            && !normalized.startsWith('/')
            && !normalized.contains('\\')
            && normalized.split('/').none { it.isEmpty() || it == "." || it == ".." }
    }

    private fun parseTags(rawTags: String?, internalName: String): List<String> {
        val tags = rawTags?.split(',')?.map(String::trim)?.filter(String::isNotEmpty) ?: emptyList()
        require(tags.size <= 12) { "$internalName: pack has too many tags" }
        require(tags.all { it.length <= 40 }) { "$internalName: pack tags must be at most 40 characters" }
        require(tags.distinct().size == tags.size) { "$internalName: pack tags must be unique" }
        return tags
    }

    private class ValidatingArchiveInputStream(input: java.io.InputStream, private val digest: MessageDigest, private val internalName: String) : FilterInputStream(input) {
        var size = 0L
            private set

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) record(byteArrayOf(value.toByte()))
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = super.read(buffer, offset, length)
            if (read > 0) record(buffer, offset, read)
            return read
        }

        private fun record(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size) {
            size += length
            require(size <= MAX_ARCHIVE_SIZE) { "$internalName: archive exceeds ${MAX_ARCHIVE_SIZE / 1024 / 1024} MiB" }
            digest.update(buffer, offset, length)
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
