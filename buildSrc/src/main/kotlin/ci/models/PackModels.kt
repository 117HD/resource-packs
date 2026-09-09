package ci.models

data class PackFileInfo(
    val internalName: String,
    val repository: String,
    val commit: String
) {
    val repoLink: String = repository
        .substringAfter("https://github.com/")
        .removeSuffix("/")
}

data class PackProperties(
    val author: String?,
    val description: String?,
    val displayName: String?
)
