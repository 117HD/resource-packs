package ci.config

object Constants {
    const val DEFAULT_REPO_NAME = "117HD/resource-pack-hub"
    const val COMMENT_TEMPLATE_RESOURCE = "comment-template.md"
    const val REQUIRED_117_TEAM = "117 HD"
    val ALLOWED_USERS = setOf("ahooder", "Mark7625")
    fun getManifestUrl(repoOwner: String, repoName: String): String {
        return "https://raw.githubusercontent.com/$repoOwner/$repoName/refs/heads/manifest/manifest.json"
    }
    
    const val ICON_WIDTH = 221
    const val ICON_HEIGHT = 145
    const val COMPACT_ICON_WIDTH = 222
    const val COMPACT_ICON_HEIGHT = 45
}

