package ci

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.kohsuke.github.GHPullRequestFileDetail
import org.mockito.Mockito

class PRCommentTaskTest {

    private lateinit var task: PRCommentTask

    @BeforeEach
    fun setUp() {
        val project = ProjectBuilder.builder().build()
        task = project.tasks.create("processPr", PRCommentTask::class.java)
    }

    private fun fileDetail(filename: String, status: String): GHPullRequestFileDetail {
        val detail = Mockito.mock(GHPullRequestFileDetail::class.java)
        Mockito.`when`(detail.filename).thenReturn(filename)
        Mockito.`when`(detail.status).thenReturn(status)
        return detail
    }

    @Suppress("UNCHECKED_CAST")
    private fun mockFileListing(pr: org.kohsuke.github.GHPullRequest, files: List<GHPullRequestFileDetail>) {
        val connector = Mockito.mock(org.kohsuke.github.PagedIterable::class.java) as org.kohsuke.github.PagedIterable<GHPullRequestFileDetail>
        Mockito.`when`(pr.listFiles()).thenReturn(connector)
        Mockito.`when`(connector.toList()).thenReturn(files)
    }

    // --- validatePackFilePath ---

    @Test
    fun `validatePackFilePath accepts a direct packs file`() {
        assertEquals("packs/my_pack", task.validatePackFilePath("packs/my_pack"))
    }

    @Test
    fun `validatePackFilePath rejects files outside packs directory`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            task.validatePackFilePath("README.md")
        }
        assertEquals("File must be in packs/ directory: README.md", ex.message)
    }

    @Test
    fun `validatePackFilePath rejects nested paths inside packs`() {
        assertThrows(IllegalArgumentException::class.java) {
            task.validatePackFilePath("packs/sub/my_pack")
        }
    }

    @Test
    fun `validatePackFilePath rejects uppercase descriptor names`() {
        assertThrows(IllegalArgumentException::class.java) {
            task.validatePackFilePath("packs/MyPack")
        }
    }

    @Test
    fun `validatePackFilePath rejects descriptor names with a dot`() {
        assertThrows(IllegalArgumentException::class.java) {
            task.validatePackFilePath("packs/my.pack")
        }
    }

    @Test
    fun `validatePackFilePath rejects changes to buildSrc or workflow files`() {
        assertThrows(IllegalArgumentException::class.java) {
            task.validatePackFilePath("buildSrc/src/main/kotlin/ManifestTask.kt")
        }
        assertThrows(IllegalArgumentException::class.java) {
            task.validatePackFilePath(".github/workflows/main.yml")
        }
    }

    // --- determineStatus ---

    @Test
    fun `determineStatus maps added`() {
        assertEquals(Labels.ADDED, task.determineStatus(fileDetail("packs/x", "added")))
    }

    @Test
    fun `determineStatus maps modified to changed`() {
        assertEquals(Labels.CHANGED, task.determineStatus(fileDetail("packs/x", "modified")))
    }

    @Test
    fun `determineStatus maps changed`() {
        assertEquals(Labels.CHANGED, task.determineStatus(fileDetail("packs/x", "changed")))
    }

    @Test
    fun `determineStatus maps removed`() {
        assertEquals(Labels.REMOVED, task.determineStatus(fileDetail("packs/x", "removed")))
    }

    @Test
    fun `determineStatus maps renamed`() {
        assertEquals(Labels.RENAMED, task.determineStatus(fileDetail("packs/x", "renamed")))
    }

    @Test
    fun `determineStatus maps unrecognized status to not-known`() {
        assertEquals(Labels.NOT_KNOWN, task.determineStatus(fileDetail("packs/x", "copied")))
    }

    @Test
    fun `determineStatus is case-insensitive`() {
        assertEquals(Labels.ADDED, task.determineStatus(fileDetail("packs/x", "ADDED")))
    }

    // --- getPRFile ---

    @Test
    fun `getPRFile rejects a PR touching more than one file`() {
        val pr = Mockito.mock(org.kohsuke.github.GHPullRequest::class.java)
        val files = listOf(fileDetail("packs/a", "added"), fileDetail("packs/b", "added"))
        mockFileListing(pr, files)
        val ex = assertThrows(IllegalArgumentException::class.java) { task.getPRFile(pr) }
        assertEquals("PR should only contain 1 file, but found 2 files", ex.message)
    }

    @Test
    fun `getPRFile accepts a PR touching exactly one file`() {
        val pr = Mockito.mock(org.kohsuke.github.GHPullRequest::class.java)
        val only = fileDetail("packs/a", "added")
        mockFileListing(pr, listOf(only))
        assertEquals(only, task.getPRFile(pr))
    }
}
