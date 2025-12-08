package ci.services

import ci.config.Constants
import ci.models.PackFileInfo
import ci.models.PackProperties
import ci.Labels
import org.kohsuke.github.GHPullRequest

class AuthorValidationService(
    private val commentService: PRCommentService
) {

    fun validateAuthor(
        pr: GHPullRequest,
        packFilePath: String,
        headPackInfo: PackFileInfo,
        mainPackInfo: PackFileInfo?,
        status: Labels,
        headPackProps: PackProperties,
        mainPackProps: PackProperties?
    ): Boolean {
        val prAuthor = pr.user.login

        if (status != Labels.CHANGED || mainPackInfo == null) {
            return validateAuthorMatch(pr, packFilePath, prAuthor, headPackProps)
        }

        val authorChanged = mainPackProps?.author != null &&
            headPackProps.author != null &&
            mainPackProps.author != headPackProps.author

        if (!authorChanged) {
            return validateAuthorMatch(pr, packFilePath, prAuthor, headPackProps)
        }

        val authorForValidation = headPackProps.author ?: return true
        val is117Team = authorForValidation.contains(Constants.REQUIRED_117_TEAM, ignoreCase = true)

        if (Constants.ALLOWED_USERS.contains(prAuthor) && is117Team) {
            return true
        }

        val authors = authorForValidation.split(",").map(String::trim)
        if (prAuthor !in authors) {
            commentService.addErrorComment(
                pr, packFilePath,
                "Author changed from '${mainPackProps?.author}' to '$authorForValidation', but PR author '$prAuthor' does not match the new author(s). Please ensure the PR is created by one of the listed authors: ${authors.joinToString(", ")}."
            )
        }

        return true
    }

    private fun validateAuthorMatch(
        pr: GHPullRequest,
        packFilePath: String,
        prAuthor: String,
        packProps: PackProperties
    ): Boolean {
        val author = packProps.author ?: return false

        val authors = author.split(",").map(String::trim)
        val is117Team = author.contains(Constants.REQUIRED_117_TEAM, ignoreCase = true)

        if (Constants.ALLOWED_USERS.contains(prAuthor) && is117Team) {
            return true
        }

        if (prAuthor !in authors) {
            commentService.addErrorComment(
                pr, packFilePath,
                "PR author '$prAuthor' does not match author(s) in pack.properties: ${authors.joinToString(", ")}. The PR must be created by one of the listed authors."
            )
            throw IllegalStateException("Author validation failed: PR author does not match")
        }

        return true
    }
}

