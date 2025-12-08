package ci.services

import ci.config.Constants
import ci.models.PackFileInfo
import ci.Labels
import org.kohsuke.github.GHPullRequest
import org.kohsuke.github.GHPullRequestReviewEvent
import java.io.InputStream

class PRCommentService {

    fun addPackChangesComment(
        pr: GHPullRequest,
        filePath: String,
        status: Labels,
        headPackInfo: PackFileInfo,
        mainPackInfo: PackFileInfo?,
        filesChanged: Int,
        validationErrors: List<String> = emptyList()
    ) {
        runCatching {
            val template = loadCommentTemplate()
            val oldCommit = mainPackInfo?.commit

            val header = when (status) {
                Labels.ADDED -> "Pack File Added"
                Labels.CHANGED -> "Pack File Changes"
                Labels.REMOVED -> "Pack File Removed"
                else -> "Pack File Changes"
            }

            val diffLine = when {
                status == Labels.ADDED -> "- **Repository**: [${headPackInfo.repository}](${headPackInfo.repository}/tree/${headPackInfo.commit})"
                status == Labels.CHANGED && oldCommit != null ->
                    "- **Diff Link**: [View Diff](${headPackInfo.repository}/compare/$oldCommit...${headPackInfo.commit})"
                else -> "- **Repository**: [${headPackInfo.repository}](${headPackInfo.repository}/tree/${headPackInfo.commit})"
            }

            var comment = template
                .replace("{HEADER}", header)
                .replace("{FILE_PATH}", filePath)
                .replace("{STATUS}", status.labelName)
                .replace("{DIFF_LINE}", diffLine)
                .replace("{FILES_CHANGED}", filesChanged.toString())

            if (validationErrors.isNotEmpty()) {
                val errorBlock = validationErrors.joinToString("\n")
                comment += "\n\n## ⚠️ File Validation Errors\n\n```\n$errorBlock\n```"
            }

            pr.comment(comment)

            if (validationErrors.isNotEmpty()) {
                runCatching {
                    pr.createReview()
                        .body(comment)
                        .event(GHPullRequestReviewEvent.REQUEST_CHANGES)
                        .create()
                }
            }
        }.onFailure { e ->
            System.err.println("Failed to add pack changes comment: ${e.message}")
        }
    }

    fun addErrorComment(pr: GHPullRequest, filePath: String, message: String) {
        runCatching {
            val repoUrl = pr.repository.htmlUrl.toString()
            val branch = pr.head.ref
            val fileLink = "[$filePath]($repoUrl/blob/$branch/$filePath)"
            val comment = message.replace(filePath, fileLink)

            pr.comment(comment)

            runCatching {
                pr.createReview()
                    .body(comment)
                    .event(GHPullRequestReviewEvent.REQUEST_CHANGES)
                    .create()
            }
        }.onFailure { e ->
            System.err.println("Failed to add comment to PR: ${e.message}")
            throw e
        }
    }


    private fun loadCommentTemplate(): String {
        val templateStream: InputStream? = javaClass.classLoader.getResourceAsStream(Constants.COMMENT_TEMPLATE_RESOURCE)
            ?: javaClass.getResourceAsStream("/${Constants.COMMENT_TEMPLATE_RESOURCE}")

        return templateStream?.bufferedReader()?.readText()
            ?: throw IllegalStateException("${Constants.COMMENT_TEMPLATE_RESOURCE} not found in resources")
    }
}

