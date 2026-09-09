package ci.models

import java.util.Properties

object PackValidation {
    private val settingsAllowedPacks = setOf("vanilla-plus")
    private val internalNamePattern = Regex("[a-z0-9_-]+")
    private val commitPattern = Regex("(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})")
    private val githubRepositoryPattern = Regex("https://github\\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")

    fun readDescriptor(properties: Properties, source: String): PackFileInfo {
        val internalName = properties.getProperty("internalName")?.trim() ?: error("internalName property not found in $source")
        require(internalNamePattern.matches(internalName)) { "internalName in $source may contain only lowercase letters, numbers, _ and -" }
        val repository = properties.getProperty("repository")?.trim() ?: error("repository property not found in $source")
        require(githubRepositoryPattern.matches(repository)) { "repository in $source must be exactly https://github.com/owner/repository" }
        val commit = properties.getProperty("commit")?.trim() ?: error("commit property not found in $source")
        require(commitPattern.matches(commit)) { "commit in $source must be a full 40-character SHA-1 or 64-character SHA-256" }
        return PackFileInfo(internalName, repository, commit)
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun requiredMetadataErrors(properties: PackProperties): List<String> = buildList {
        if (properties.displayName.isNullOrBlank()) add("displayName is required in pack.properties")
        if (properties.author.isNullOrBlank()) add("author is required in pack.properties")
        if (properties.description.isNullOrBlank()) add("description is required in pack.properties")
    }

    fun allowsSettings(internalName: String): Boolean = internalName in settingsAllowedPacks

    fun isSafeDescriptorFilename(filename: String): Boolean = internalNamePattern.matches(filename)
}
