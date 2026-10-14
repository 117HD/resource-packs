package ci.config

import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Properties

class PRConfigTest {

    private lateinit var project: Project

    @BeforeEach
    fun setUp() {
        project = ProjectBuilder.builder().build()
    }

    private fun config(env: Map<String, String> = emptyMap()) = PRConfig(project) { env[it] }

    private fun setProjectProperty(name: String, value: String) {
        project.extensions.extraProperties.set(name, value)
    }

    // --- getToken ---

    @Test
    fun `getToken prefers the project property over everything else`() {
        setProjectProperty("token", "from-project")
        val result = config(env = mapOf("GITHUB_TOKEN" to "from-env"))
            .getToken(Properties().apply { setProperty("token", "from-props") })
        assertEquals("from-project", result)
    }

    @Test
    fun `getToken falls back to the environment variable`() {
        val result = config(env = mapOf("GITHUB_TOKEN" to "from-env"))
            .getToken(Properties().apply { setProperty("token", "from-props") })
        assertEquals("from-env", result)
    }

    @Test
    fun `getToken falls back to test properties`() {
        val result = config().getToken(Properties().apply { setProperty("token", "from-props") })
        assertEquals("from-props", result)
    }

    @Test
    fun `getToken throws a clear error when nothing is configured`() {
        val ex = assertThrows(IllegalStateException::class.java) { config().getToken(Properties()) }
        assertEquals(
            "GitHub token not found. Set the token Gradle property, GITHUB_TOKEN, or test.properties.",
            ex.message
        )
    }

    // --- getPRNumber ---

    @Test
    fun `getPRNumber prefers the project property`() {
        setProjectProperty("PR_NUMBER", "42")
        val result = config(env = mapOf("PR_NUMBER" to "7")).getPRNumber(Properties())
        assertEquals(42, result)
    }

    @Test
    fun `getPRNumber falls through when the project property is not numeric`() {
        setProjectProperty("PR_NUMBER", "not-a-number")
        val result = config(env = mapOf("PR_NUMBER" to "7")).getPRNumber(Properties())
        assertEquals(7, result)
    }

    @Test
    fun `getPRNumber falls back to the environment variable`() {
        val result = config(env = mapOf("PR_NUMBER" to "99")).getPRNumber(Properties())
        assertEquals(99, result)
    }

    @Test
    fun `getPRNumber falls back to test properties`() {
        val result = config().getPRNumber(Properties().apply { setProperty("PR_NUMBER", "13") })
        assertEquals(13, result)
    }

    @Test
    fun `getPRNumber throws a clear error when nothing is configured`() {
        val ex = assertThrows(IllegalStateException::class.java) { config().getPRNumber(Properties()) }
        assertEquals(
            "PR_NUMBER not found. Set 'PR_NUMBER' property, environment variable, or in test.properties.",
            ex.message
        )
    }

    @Test
    fun `getPRNumber throws when every source is non-numeric`() {
        assertThrows(IllegalStateException::class.java) {
            config().getPRNumber(Properties().apply { setProperty("PR_NUMBER", "nope") })
        }
    }

    // --- getRepoName ---

    @Test
    fun `getRepoName prefers the project property`() {
        setProjectProperty("REPO_NAME", "owner/from-project")
        val result = config(env = mapOf("GITHUB_REPOSITORY" to "owner/from-env")).getRepoName(Properties())
        assertEquals("owner/from-project", result)
    }

    @Test
    fun `getRepoName falls back to the environment variable`() {
        val result = config(env = mapOf("GITHUB_REPOSITORY" to "owner/from-env")).getRepoName(Properties())
        assertEquals("owner/from-env", result)
    }

    @Test
    fun `getRepoName falls back to test properties`() {
        val result = config().getRepoName(Properties().apply { setProperty("REPO_NAME", "owner/from-props") })
        assertEquals("owner/from-props", result)
    }

    @Test
    fun `getRepoName falls back to the default repo when nothing is configured`() {
        val result = config().getRepoName(Properties())
        assertEquals(Constants.DEFAULT_REPO_NAME, result)
    }
}
