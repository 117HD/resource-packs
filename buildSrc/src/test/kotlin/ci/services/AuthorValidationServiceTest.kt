package ci.services

import ci.Labels
import ci.models.PackFileInfo
import ci.models.PackProperties
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.kohsuke.github.GHPullRequest
import org.kohsuke.github.GHUser
import org.mockito.Mockito

class AuthorValidationServiceTest {

    private lateinit var commentService: PRCommentService
    private lateinit var service: AuthorValidationService

    @BeforeEach
    fun setUp() {
        commentService = Mockito.mock(PRCommentService::class.java)
        service = AuthorValidationService(commentService)
    }

    private fun pr(login: String): GHPullRequest {
        val user = Mockito.mock(GHUser::class.java)
        Mockito.`when`(user.login).thenReturn(login)
        val pullRequest = Mockito.mock(GHPullRequest::class.java)
        Mockito.`when`(pullRequest.user).thenReturn(user)
        return pullRequest
    }

    private fun packInfo() = PackFileInfo("my_pack", "https://github.com/owner/repo", "a".repeat(40))

    // --- ADDED (not CHANGED): delegates straight to author-match check ---

    @Test
    fun `added pack with no author in properties is rejected without throwing`() {
        val result = service.validateAuthor(
            pr("someone"), "packs/my_pack", packInfo(), null,
            Labels.ADDED, PackProperties(null, "d", "n"), null
        )
        assertFalse(result)
    }

    @Test
    fun `added pack is accepted when the PR author matches pack author`() {
        val result = service.validateAuthor(
            pr("alice"), "packs/my_pack", packInfo(), null,
            Labels.ADDED, PackProperties("alice", "d", "n"), null
        )
        assertTrue(result)
    }

    @Test
    fun `added pack accepts any author from a comma separated list`() {
        val result = service.validateAuthor(
            pr("bob"), "packs/my_pack", packInfo(), null,
            Labels.ADDED, PackProperties("alice, bob", "d", "n"), null
        )
        assertTrue(result)
    }

    @Test
    fun `added pack allows a 117 HD team pack to be submitted by an allow-listed user`() {
        val result = service.validateAuthor(
            pr("Mark7625"), "packs/my_pack", packInfo(), null,
            Labels.ADDED, PackProperties("117 HD Team", "d", "n"), null
        )
        assertTrue(result)
    }

    @Test
    fun `added pack rejects a non-matching author and throws`() {
        assertThrows(IllegalStateException::class.java) {
            service.validateAuthor(
                pr("mallory"), "packs/my_pack", packInfo(), null,
                Labels.ADDED, PackProperties("alice", "d", "n"), null
            )
        }
    }

    // --- CHANGED with no prior pack info: same author-match path ---

    @Test
    fun `changed pack with no main pack info falls back to author match`() {
        assertThrows(IllegalStateException::class.java) {
            service.validateAuthor(
                pr("mallory"), "packs/my_pack", packInfo(), null,
                Labels.CHANGED, PackProperties("alice", "d", "n"), null
            )
        }
    }

    // --- CHANGED with prior pack info and an unchanged author: author-match path ---

    @Test
    fun `changed pack with unchanged author falls back to author match`() {
        val result = service.validateAuthor(
            pr("alice"), "packs/my_pack", packInfo(), packInfo(),
            Labels.CHANGED, PackProperties("alice", "d", "n"), PackProperties("alice", "d", "n")
        )
        assertTrue(result)
    }

    @Test
    fun `changed pack where the new author is blanked out is treated as unchanged and rejected`() {
        // authorChanged requires the new author to be non-null, so clearing it falls
        // back to the author-match path, which rejects when there's no author to match.
        val result = service.validateAuthor(
            pr("anyone"), "packs/my_pack", packInfo(), packInfo(),
            Labels.CHANGED, PackProperties(null, "d", "n"), PackProperties("alice", "d", "n")
        )
        assertFalse(result)
    }

    // --- CHANGED with a genuine author change ---

    @Test
    fun `author change is accepted when the PR author is one of the new authors`() {
        val result = service.validateAuthor(
            pr("carol"), "packs/my_pack", packInfo(), packInfo(),
            Labels.CHANGED, PackProperties("bob, carol", "d", "n"), PackProperties("alice", "d", "n")
        )
        assertTrue(result)
    }

    @Test
    fun `author change allows a 117 HD team pack to be re-authored by an allow-listed user`() {
        val result = service.validateAuthor(
            pr("ahooder"), "packs/my_pack", packInfo(), packInfo(),
            Labels.CHANGED, PackProperties("117 HD Team", "d", "n"), PackProperties("alice", "d", "n")
        )
        assertTrue(result)
    }

    @Test
    fun `author change is rejected when the PR author is not among the new authors`() {
        assertThrows(IllegalStateException::class.java) {
            service.validateAuthor(
                pr("mallory"), "packs/my_pack", packInfo(), packInfo(),
                Labels.CHANGED, PackProperties("bob, carol", "d", "n"), PackProperties("alice", "d", "n")
            )
        }
    }
}
