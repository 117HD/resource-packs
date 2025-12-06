import Constants.BASE_GUTHUB_LINK
import club.minnced.discord.webhook.WebhookClient
import club.minnced.discord.webhook.WebhookClientBuilder
import club.minnced.discord.webhook.send.WebhookEmbed
import club.minnced.discord.webhook.send.WebhookEmbedBuilder
import club.minnced.discord.webhook.send.WebhookMessageBuilder
import com.beust.klaxon.Json
import com.beust.klaxon.Klaxon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.internal.lowercase
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.kohsuke.github.GitHub
import java.awt.Color
import java.io.File
import java.io.StringReader
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoField
import java.time.temporal.TemporalAccessor
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
    val packType: String,
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
    val validPack = emptyMap<File,Pair<String,String>>().toMutableMap()
    @Internal
    val failedPacks = emptyMap<String,MutableList<String>>().toMutableMap()
    @Internal
    val finalManifest = emptyMap<String,ManifestEntry>().toMutableMap()

    @TaskAction
    fun generate() {

        val time = measureTimeMillis {
            var repoLink = ""
            File("./packs/").listFiles()?.forEach {
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
                    validPack[it] = Pair(repoLink,response.body.string())

                    println("${it.name} -  ${BASE_GUTHUB_LINK}${repoLink}/commits/${commit}")
                } else {
                    addError(
                        it.nameWithoutExtension,
                        "Unable to Find repo"
                    )
                    System.err.println("Unable to update: ${it.nameWithoutExtension} Code: ${response.code}")
                }
            }
        }
        runBlocking {
            withContext(coroutineContext) {
                validPack.forEach {
                    async(Dispatchers.IO) {
                        val data = getData(it.value.first,it.value.second)
                        if(data.second != null && data.first.isNotEmpty()) {
                            finalManifest.put(data.second!!.internalName,data.second!!)
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

        updateRepo()

        if(project.hasProperty("discord")) {
            sendWebhook()
        }

        println("Manifest Updated in $time ms")
    }

    private fun sendWebhook() {
        val builder = WebhookClientBuilder("https://discord.com/api/webhooks/${project.property("discord")}") // or id, token

        builder.setThreadFactory { job ->
            val thread = Thread(job)
            thread.name = "hook"
            thread.isDaemon = true
            thread
        }
        builder.setWait(true)
        val client: WebhookClient = builder.build()

        var content = ""

        content += "**Resource Packs Have Been Updated**"
        content += "\\n\\n Total Packs: ${finalManifest.size}"
        content += "\\n Failed: ${failedPacks.size}"

        if(failedPacks.isNotEmpty()) {
            content += "\\n\\n**Failed**"
            failedPacks.keys.forEach {
                content += "\\n- $it"
            }
        }

        val embed: WebhookEmbed = WebhookEmbedBuilder()
            .setColor(Color.GREEN.rgb)
            .setAuthor(
                WebhookEmbed.EmbedAuthor(
                    "Resource Packs Bot",
                    "https://i.imgur.com/olzjgJH.png",
                    "https://github.com/117HD/resource-packs/"
                )
            )
            .setDescription(content.replace("\\n", "\n"))
            .setTimestamp(Instant.now())
        .build()

        val messageBuilder = WebhookMessageBuilder()
        messageBuilder.setUsername("117HD - Resource Packs")
        messageBuilder.addEmbeds(embed)
        messageBuilder.setAvatarUrl("https://i.imgur.com/olzjgJH.png")

        client.send(messageBuilder.build())


    }

    private fun addError(name : String, error : String) {
        val list = failedPacks[name]?: emptyList<String>().toMutableList()
        list.add(error)
        failedPacks[name] = list
    }

    private fun getData(repo : String, content : String): Pair<String,ManifestEntry?> {
        val github = Klaxon().parse<Github>(content) ?: return Pair(
            "pack.properties is missing please add it",null
        )

        val request = Request.Builder().url(
            "${Constants.BASE_GUTHUB_LINK_RAW}${repo}/${github.sha}/pack.properties"
        ).build()

        val response = client.newCall(request).execute()
        if (response.code == 200) {

            val foundIcon = foundFile("${Constants.BASE_GUTHUB_LINK_RAW}${repo}/${github.sha}/icon.png")

            if(!foundFile("${Constants.BASE_GUTHUB_LINK_RAW}${repo}/${github.sha}/licenses.txt")) {
                return Pair("licenses.txt has not been found this is required",null)
            }

            val properties = Properties()

            properties.load(StringReader(response.body.string()))
            if(properties.getProperty("displayName") == null) {
                return Pair("displayName is required in pack.properties",null)
            }
            val internalName = properties.getProperty("displayName").lowercase().replace(
                " ", "_"
            )

            if(finalManifest.containsKey(internalName)) {
                return Pair("$internalName is already in use please use a new name",null)
            }

            if(properties.getProperty("description") == null) {
                return Pair("description is required in pack.properties",null)
            }

            val author = when (properties.contains("author")) {
                true -> properties.getProperty("author").toString()
                false -> github.commit.author.name
            }

            val version = properties.getProperty("version")
            val support = properties.getProperty("support")
            val description = properties.getProperty("description")
            val packType = properties.getProperty("packType","RESOURCE")
            val tags = properties.getProperty("tags").split(",")

            return Pair(" ",ManifestEntry(
                hasIcon = foundIcon, internalName = internalName,
                tags = tags, commit = github.sha,
                support = support, author = author,
                packType = packType,
                description = description, link = "https://github.com/${repo}", version = version
            ))
        }
        return Pair("",null)


    }

    private fun foundFile(link : String) : Boolean {
        val requestIcon = Request.Builder().url(link).build()
        return client.newCall(requestIcon).execute().code == 200
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

    fun updateRepo() {
        val gh = GitHub.connectUsingOAuth(project.property("token").toString())
        val repo = gh.getOrganization("117HD").getRepository("resource-packs")

        val format = SimpleDateFormat("dd MMM yyyy HH:mm:ss z")
        format.timeZone = TimeZone.getTimeZone("Europe/London")

        repo.getFileContent("manifest.json","manifest").update(
            finalManifest.values.jsonToString(true),
            "Update manifest.json ${format.format(GregorianCalendar.getInstance().time)}",
            "manifest"
        )
    }


}