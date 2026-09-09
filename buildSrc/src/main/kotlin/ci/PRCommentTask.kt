package ci

import Constants.BASE_GUTHUB_LINK_RAW
import ci.config.Constants
import ci.config.PRConfig
import ci.models.PackFileInfo
import ci.models.PackValidation
import ci.services.*
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.kohsuke.github.GHPullRequest

@OptIn(ExperimentalStdlibApi::class)
open class PRCommentTask : DefaultTask() {

    @Internal
    lateinit var client: okhttp3.OkHttpClient

    @TaskAction
    fun processPR() {
        val config = PRConfig(project)
        val testProps = config.loadTestProperties()
        val token = config.getToken(testProps)
        val prNumber = config.getPRNumber(testProps)
        val baseRepoName = config.getRepoName(testProps)

        client = okhttp3.OkHttpClient()
        val github = GitHubClientService.connect(token)

        // Try to get PR from the specified repo first
        val repo = try {
            val testRepo = github.getRepository(baseRepoName)
            val testPr = testRepo.getPullRequest(prNumber)
            println("Found PR #$prNumber in repository: $baseRepoName")
            testRepo
        } catch (e: Exception) {
            // If not found, try the workflow repository or default
            val workflowRepo = System.getenv("GITHUB_REPOSITORY") ?: Constants.DEFAULT_REPO_NAME
            println("PR #$prNumber not found in $baseRepoName, trying $workflowRepo...")
            try {
                val testRepo = github.getRepository(workflowRepo)
                val testPr = testRepo.getPullRequest(prNumber)
                println("Found PR #$prNumber in repository: $workflowRepo")
                testRepo
            } catch (e2: Exception) {
                throw IllegalStateException("Failed to find PR #$prNumber in $baseRepoName or $workflowRepo: ${e.message}")
            }
        }
        
        val pr = repo.getPullRequest(prNumber)
        
        // Use the PR's actual repository (in case it's different from where we fetched it)
        val actualRepo = pr.repository
        println("Processing PR #$prNumber from repository: ${actualRepo.fullName}")
        println("PR URL: ${pr.htmlUrl}")
        
        require(pr.state != org.kohsuke.github.GHIssueState.CLOSED) { "PR #$prNumber is closed" }
        println("PR #$prNumber state: ${pr.state}")

        val prFile = getPRFile(pr)
        val status = determineStatus(prFile)
        require(status != Labels.RENAMED) { "Renaming pack descriptors is not supported; submit a removal and addition instead" }
        val packFilePath = validatePackFilePath(prFile.filename)

        val packService = PackService(client)

        val headPackInfo = if (status == Labels.REMOVED) {
            packService.readPackFile(actualRepo, packFilePath, pr.base.ref)
        } else {
            packService.readPackFile(actualRepo, packFilePath, pr.head.sha)
        }
        
        validateCommit(headPackInfo, packFilePath, client)

        val headPackProps = packService.readPackProperties(headPackInfo.repoLink, headPackInfo.commit)

        val manifestService = ManifestService(client)
        val repoOwner = actualRepo.ownerName
        val repoName = actualRepo.name
        val existingInternalNames = manifestService.getExistingInternalNames(repoOwner, repoName)
        val fileValidationService = FileValidationService(client)
        val validationErrors = fileValidationService.validateRequiredFiles(
            headPackInfo, headPackProps, status, existingInternalNames
        )

        require(validationErrors.isEmpty()) { "Pack validation failed: ${validationErrors.joinToString("; ")}" }

        logSummary(prNumber, prFile.filename, status, headPackInfo)
    }


    fun getPRFile(pr: GHPullRequest) = pr.listFiles().toList().let { files ->
        require(files.size == 1) { "PR should only contain 1 file, but found ${files.size} files" }
        files.first()
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun determineStatus(prFile: org.kohsuke.github.GHPullRequestFileDetail): Labels =
        when (prFile.status.lowercase()) {
            "added" -> Labels.ADDED
            "modified", "changed" -> Labels.CHANGED
            "removed" -> Labels.REMOVED
            "renamed" -> Labels.RENAMED
            else -> Labels.NOT_KNOWN
        }

    fun validatePackFilePath(filePath: String): String {
        require(filePath.startsWith("packs/")) { "File must be in packs/ directory: $filePath" }
        val filename = filePath.removePrefix("packs/")
        require(!filename.contains('/') && PackValidation.isSafeDescriptorFilename(filename)) {
            "Pack descriptor must be directly inside packs/ and use only lowercase letters, numbers, _ and -: $filePath"
        }
        return filePath
    }

    fun validateCommit(
        packInfo: PackFileInfo,
        filePath: String,
        client: okhttp3.OkHttpClient
    ) {
        val packPropsUrl = "${BASE_GUTHUB_LINK_RAW}${packInfo.repoLink}/${packInfo.commit}/pack.properties"
        val request = okhttp3.Request.Builder().url(packPropsUrl).build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Unable to locate repository or commit for $filePath: ${packInfo.repository}@${packInfo.commit}")
            }
        }
    }

    private fun logSummary(
        prNumber: Int,
        filename: String,
        status: Labels,
        packInfo: PackFileInfo
    ) {
        println("File in PR #$prNumber:")
        println("  - $filename [${status.labelName}]")
        println("  - Repository: ${packInfo.repository}")
        println("  - Commit: ${packInfo.commit}")
        println("  - Status Label: ${status.labelName}")
    }
}
