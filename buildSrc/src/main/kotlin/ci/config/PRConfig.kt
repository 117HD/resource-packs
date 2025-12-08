package ci.config

import org.gradle.api.Project
import java.io.File
import java.util.Properties

class PRConfig(private val project: Project) {
    fun loadTestProperties(): Properties = Properties().apply {
        File(TEST_PROPERTIES_FILE).takeIf(File::exists)?.inputStream()?.use(::load)
    }

    fun getToken(testProps: Properties): String =
        project.findProperty("token")?.toString()
            ?: testProps.getProperty("token")
            ?: throw IllegalStateException("GitHub token not found. Set 'token' property in gradle.properties or test.properties.")

    fun getPRNumber(testProps: Properties): Int =
        project.findProperty("PR_NUMBER")?.toString()?.toIntOrNull()
            ?: System.getenv("PR_NUMBER")?.toIntOrNull()
            ?: testProps.getProperty("PR_NUMBER")?.toIntOrNull()
            ?: throw IllegalStateException("PR_NUMBER not found. Set 'PR_NUMBER' property, environment variable, or in test.properties.")

    fun getRepoName(testProps: Properties): String =
        project.findProperty("REPO_NAME")?.toString()
            ?: System.getenv("GITHUB_REPOSITORY")
            ?: testProps.getProperty("REPO_NAME")
            ?: Constants.DEFAULT_REPO_NAME

    companion object {
        private const val TEST_PROPERTIES_FILE = "test.properties"
    }
}

