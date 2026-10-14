package ci

import ci.config.PRConfig
import ci.models.PackValidation
import ci.services.GitHubClientService
import ci.services.PackService
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction
import org.kohsuke.github.GHIssueState

/**
 * Guards a non-"main" branch (e.g. "ci") against pack descriptor submissions that were
 * opened against the wrong branch. Pack descriptors only belong on main; everything else
 * here is assumed to be a normal code change and is left alone.
 */
open class BranchGuardTask : DefaultTask() {

    companion object {
        /**
         * True if [filename] has the shape of a pack descriptor (packs/<safe-name>, no
         * nesting). Doesn't confirm the contents actually parse as a descriptor.
         */
        fun isCandidatePackDescriptorPath(filename: String): Boolean {
            if (!filename.startsWith("packs/")) return false
            val descriptorName = filename.removePrefix("packs/")
            return !descriptorName.contains('/') && PackValidation.isSafeDescriptorFilename(descriptorName)
        }
    }

    @TaskAction
    fun guard() {
        val config = PRConfig(project)
        val testProps = config.loadTestProperties()
        val token = config.getToken(testProps)
        val prNumber = config.getPRNumber(testProps)
        val repoName = config.getRepoName(testProps)

        val github = GitHubClientService.connect(token)
        val repo = github.getRepository(repoName)
        val pr = repo.getPullRequest(prNumber)

        if (pr.state == GHIssueState.CLOSED) {
            println("PR #$prNumber is already closed; nothing to guard")
            return
        }

        val files = pr.listFiles().toList()
        if (files.size != 1) {
            println("PR #$prNumber touches ${files.size} files; treating as a regular code change")
            return
        }

        val file = files.first()
        if (!isCandidatePackDescriptorPath(file.filename)) {
            println("PR #$prNumber changes ${file.filename}; treating as a regular code change")
            return
        }

        val client = okhttp3.OkHttpClient()
        val looksLikePackDescriptor = runCatching {
            PackService(client).readPackFile(pr.repository, file.filename, pr.head.sha)
        }.isSuccess

        if (!looksLikePackDescriptor) {
            println("PR #$prNumber changes ${file.filename}, but it isn't a pack descriptor; treating as a regular code change")
            return
        }

        val message = "This looks like a resource pack submission (`${file.filename}`), but pack " +
            "descriptors belong on the `main` branch, not `${pr.base.ref}`.\n\n" +
            "Please close this PR and open it against `main` instead. Closing this one automatically."
        runCatching {
            pr.comment(message)
            pr.close()
        }.onFailure { println("Failed to auto-close PR #$prNumber: ${it.message}") }
    }
}
