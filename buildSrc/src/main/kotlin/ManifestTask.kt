import Constants.BASE_GUTHUB_LINK
import com.beust.klaxon.Json
import com.beust.klaxon.Klaxon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.internal.lowercase
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.kohsuke.github.GitHub
import java.awt.Color
import java.io.File
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import kotlin.system.measureTimeMillis


data class ManifestEntry(
    val hasIcon: Boolean,
    val internalName: String,
    @Json(serializeNull = false) val tags: List<String>? = null,
    val commit: String,
    @Json(serializeNull = false) val support: String? = null,
    @Json(serializeNull = false) val author: String? = null,
    @Json(serializeNull = false) val description: String? = null,
    val link: String,
    @Json(serializeNull = false) val version: String? = null
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
    val toUpdate = emptyMap<File,String>().toMutableMap()
    @Internal
    val failed = emptyList<String>().toMutableList()

    @TaskAction
    fun generate() {

        val time = measureTimeMillis {
            if(File("./packs/").exists()) {
                File("./packs/").listFiles()?.forEach {
                    val packFile = Properties()
                    packFile.load(it.inputStream())
                    val repo = packFile.getProperty("repository").substringAfter("https://github.com/")
                    val commit = packFile.getProperty("commit")

                    val request: Request = Request.Builder().
                        url("${BASE_GUTHUB_LINK}${repo}/commits/${commit}")
                    .build()

                    println("${BASE_GUTHUB_LINK}${repo}/commits/${commit}")
                    val call: Call = client.newCall(request)
                    val response: Response = call.execute()
                    if (response.code == 200) {
                        toUpdate[it] = response.body.string()
                    } else {
                        failed.add(it.nameWithoutExtension)
                        System.err.println("Unable to update: ${it.nameWithoutExtension}")
                    }
                }

                val finalManifest : MutableList<ManifestEntry> = emptyList<ManifestEntry>().toMutableList()
                runBlocking {
                    withContext(coroutineContext) {
                        toUpdate.forEach {
                            async(Dispatchers.IO) {
                                val data = getData(it.value)
                                if(data != null) {
                                    finalManifest.add(data)
                                } else {
                                    failed.add(it.key.nameWithoutExtension)
                                    System.err.println("Error getting data for: ${it.key.name}")
                                }
                            }
                        }
                    }

                    val gh = GitHub.connectUsingOAuth(project.property("token").toString())
                    val repo = gh.getOrganization("117HD").getRepository("resource-packs")

                    val format = SimpleDateFormat("dd MMM yyyy HH:mm:ss z")
                    format.timeZone = TimeZone.getTimeZone("Europe/London")

                    repo.getFileContent("manifest.json","manifest").update(
                        finalManifest.jsonToString(true),
                        "Update manifest.json ${format.format(GregorianCalendar.getInstance().time)}",
                        "manifest"
                    )

                }
            } else {
                println("No Packs Found")
            }
        }

        if(project.hasProperty("discord")) {
            processWebhook()
        }

        println("Manifest Updated in $time ms")
    }
    private fun getData(content : String): ManifestEntry? {
        val github = Klaxon().parse<Github>(content) ?: return null

        val hasIcon = github.files.any { it.filename == "icon.png" }
        val propsFileLink = github.files.find { it.filename == "pack.properties" }?.raw_url ?: return null

        val properties = Properties()
        properties.load(URL(propsFileLink).openStream())

        val author = when(properties.contains("author")) {
            true -> properties.getProperty("author").toString()
            false -> github.commit.author.name
        }

        val version = properties.getProperty("version")
        val internalName = properties.getProperty("displayName").lowercase().replace(" ","_")
        val support = properties.getProperty("support")
        val description = properties.getProperty("description")
        val tags = properties.getProperty("tags").split(",")

        val entry = ManifestEntry(
            hasIcon = hasIcon, internalName = internalName,
            tags = tags, commit = github.sha,
            support = support, author = author,
            description = description, link = propsFileLink.substringBefore("/raw"), version = version
        )

        return entry
    }

    private fun processWebhook() {

        var content = ""

        content += "**Resource Packs Have Been Updated**"
        content += "\\n\\n Total Packs: ${toUpdate.size}"
        content += "\\n Failed: ${failed.size}"

        if(failed.isNotEmpty()) {
            content += "\\n\\n**Failed**"
            failed.forEach {
                content += "\\n- $it"
            }
        }

        val webhook = DiscordWebhook("https://discord.com/api/webhooks/${project.property("discord")}")
        webhook.setAvatarUrl("https://i.imgur.com/olzjgJH.png")
        webhook.addEmbed(
            DiscordWebhook.EmbedObject()
                .setTitle("117HD - Resource Packs")
                .setDescription(content)
                .setColor(Color.GREEN))
        webhook.execute()
    }

    fun Any.jsonToString(prettyPrint: Boolean): String{
        var thisJsonString = Klaxon().toJsonString(this)
        var result = thisJsonString
        if(prettyPrint) {
            if(thisJsonString.startsWith("[")){
                result = Klaxon().parseJsonArray(thisJsonString.reader()).toJsonString(true)
            } else {
                result = Klaxon().parseJsonObject(thisJsonString.reader()).toJsonString(true)
            }
        }
        return result
    }

}