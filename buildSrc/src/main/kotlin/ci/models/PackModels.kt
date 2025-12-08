package ci.models

@OptIn(ExperimentalStdlibApi::class)
data class PackFileInfo(
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
) {
    @OptIn(ExperimentalStdlibApi::class)
    val internalName: String? = displayName?.lowercase()?.replace(" ", "_")
}

