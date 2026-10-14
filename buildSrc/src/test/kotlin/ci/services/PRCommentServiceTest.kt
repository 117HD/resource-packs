package ci.services

import ci.Labels
import ci.models.PackFileInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.kohsuke.github.GHCommitPointer
import org.kohsuke.github.GHPullRequest
import org.kohsuke.github.GHPullRequestReviewBuilder
import org.kohsuke.github.GHRepository
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import java.net.URL

class PRCommentServiceTest {

    private lateinit var reviewBuilder: GHPullRequestReviewBuilder
    private lateinit var service: PRCommentService

    @BeforeEach
    fun setUp() {
        reviewBuilder = Mockito.mock(GHPullRequestReviewBuilder::class.java, Mockito.RETURNS_SELF)
        service = PRCommentService()
    }

    private fun pr(headRef: String = "feature-branch", repoUrl: String = "https://github.com/owner/repo"): GHPullRequest {
        val repo = Mockito.mock(GHRepository::class.java)
        Mockito.`when`(repo.htmlUrl).thenReturn(URL(repoUrl))
        val head = Mockito.mock(GHCommitPointer::class.java)
        Mockito.`when`(head.ref).thenReturn(headRef)
        val pullRequest = Mockito.mock(GHPullRequest::class.java)
        Mockito.`when`(pullRequest.repository).thenReturn(repo)
        Mockito.`when`(pullRequest.head).thenReturn(head)
        Mockito.`when`(pullRequest.createReview()).thenReturn(reviewBuilder)
        return pullRequest
    }

    private fun packInfo(commit: String) = PackFileInfo("my_pack", "https://github.com/owner/repo", commit)

    // --- addPackChangesComment ---

    @Test
    fun `added pack renders the added header and a repository link`() {
        val pullRequest = pr()
        service.addPackChangesComment(pullRequest, "packs/my_pack", Labels.ADDED, packInfo("a".repeat(40)), null, 3)

        val captor = org.mockito.ArgumentCaptor.forClass(String::class.java)
        verify(pullRequest).comment(captor.capture())
        val comment = captor.value
        assertTrue(comment.contains("Pack File Added"), comment)
        assertTrue(comment.contains("packs/my_pack"), comment)
        assertTrue(comment.contains("**Files Changed**: 3"), comment)
        assertTrue(comment.contains("[https://github.com/owner/repo](https://github.com/owner/repo/tree/${"a".repeat(40)})"), comment)
    }

    @Test
    fun `changed pack with a prior commit renders a diff link`() {
        val pullRequest = pr()
        service.addPackChangesComment(
            pullRequest, "packs/my_pack", Labels.CHANGED,
            packInfo("b".repeat(40)), packInfo("a".repeat(40)), 5
        )

        val captor = org.mockito.ArgumentCaptor.forClass(String::class.java)
        verify(pullRequest).comment(captor.capture())
        val comment = captor.value
        assertTrue(comment.contains("Pack File Changes"), comment)
        assertTrue(comment.contains("View Diff"), comment)
        assertTrue(comment.contains("compare/${"a".repeat(40)}...${"b".repeat(40)}"), comment)
    }

    @Test
    fun `changed pack with no prior commit falls back to a repository link`() {
        val pullRequest = pr()
        service.addPackChangesComment(pullRequest, "packs/my_pack", Labels.CHANGED, packInfo("b".repeat(40)), null, 1)

        val captor = org.mockito.ArgumentCaptor.forClass(String::class.java)
        verify(pullRequest).comment(captor.capture())
        assertTrue(captor.value.contains("tree/${"b".repeat(40)}"), captor.value)
    }

    @Test
    fun `removed pack renders the removed header`() {
        val pullRequest = pr()
        service.addPackChangesComment(pullRequest, "packs/my_pack", Labels.REMOVED, packInfo("a".repeat(40)), null, 2)

        val captor = org.mockito.ArgumentCaptor.forClass(String::class.java)
        verify(pullRequest).comment(captor.capture())
        assertTrue(captor.value.contains("Pack File Removed"), captor.value)
    }

    @Test
    fun `validation errors are appended and a review is requested`() {
        val pullRequest = pr()
        service.addPackChangesComment(
            pullRequest, "packs/my_pack", Labels.ADDED, packInfo("a".repeat(40)), null, 1,
            validationErrors = listOf("missing licenses.txt", "icon.png wrong size")
        )

        val captor = org.mockito.ArgumentCaptor.forClass(String::class.java)
        verify(pullRequest).comment(captor.capture())
        assertTrue(captor.value.contains("File Validation Errors"), captor.value)
        assertTrue(captor.value.contains("missing licenses.txt"), captor.value)
        assertTrue(captor.value.contains("icon.png wrong size"), captor.value)
        verify(reviewBuilder, times(1)).create()
    }

    @Test
    fun `no validation errors means no error block and no review`() {
        val pullRequest = pr()
        service.addPackChangesComment(pullRequest, "packs/my_pack", Labels.ADDED, packInfo("a".repeat(40)), null, 1)

        val captor = org.mockito.ArgumentCaptor.forClass(String::class.java)
        verify(pullRequest).comment(captor.capture())
        assertTrue(!captor.value.contains("File Validation Errors"), captor.value)
        verify(pullRequest, never()).createReview()
    }

    // --- addErrorComment ---

    @Test
    fun `addErrorComment turns the file path into a markdown link and requests a review`() {
        val pullRequest = pr(headRef = "my-branch")
        service.addErrorComment(pullRequest, "packs/my_pack", "packs/my_pack is invalid: bad commit")

        val captor = org.mockito.ArgumentCaptor.forClass(String::class.java)
        verify(pullRequest).comment(captor.capture())
        assertEquals(
            "[packs/my_pack](https://github.com/owner/repo/blob/my-branch/packs/my_pack) is invalid: bad commit",
            captor.value
        )
        verify(reviewBuilder, times(1)).create()
    }

    @Test
    fun `addErrorComment propagates failures instead of swallowing them`() {
        val pullRequest = Mockito.mock(GHPullRequest::class.java)
        Mockito.`when`(pullRequest.repository).thenThrow(RuntimeException("boom"))

        assertThrows(RuntimeException::class.java) {
            service.addErrorComment(pullRequest, "packs/my_pack", "some message")
        }
    }
}
