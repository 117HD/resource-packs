package ci

import Constants.BASE_GUTHUB_LINK_RAW
import ci.config.Constants
import ci.config.PRConfig
import ci.models.PackFileInfo
import ci.services.*
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.kohsuke.github.GHIssueState
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

        client = GitHubClientService.createAuthenticatedClient(token)
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
        
        // Force PR to be open if it's closed (workflow only runs on open PRs anyway)
        if (pr.state == GHIssueState.CLOSED) {
            println("PR #$prNumber is closed, attempting to reopen...")
            try {
                pr.reopen()
                println("PR #$prNumber reopened successfully")
            } catch (e: Exception) {
                println("Warning: Could not reopen PR #$prNumber: ${e.message}. Continuing anyway...")
            }
        }
        
        println("PR #$prNumber state: ${pr.state}")

        val prFile = getPRFile(pr)
        val status = determineStatus(prFile)
        val packFilePath = validatePackFilePath(prFile.filename)

        val packService = PackService(client)

        val headPackInfo = packService.readPackFile(actualRepo, packFilePath, pr.head.sha)
        
        val commentService = PRCommentService()
        validateCommit(headPackInfo, packFilePath, pr, client, commentService)

        val mainPackInfo = if (status == Labels.CHANGED) {
            packService.readPackFile(actualRepo, packFilePath, pr.base.ref)
        } else null

        val headPackProps = packService.readPackProperties(headPackInfo.repoLink, headPackInfo.commit)
        val mainPackProps = mainPackInfo?.let {
            packService.readPackProperties(it.repoLink, it.commit)
        }

        val authorValidationService = AuthorValidationService(commentService)
        val authorChanged = authorValidationService.validateAuthor(
            pr, packFilePath, headPackInfo, mainPackInfo, status,
            headPackProps, mainPackProps
        )

        val sizeCalculationService = SizeCalculationService(client)
        val size = sizeCalculationService.calculateSize(status, headPackInfo, mainPackInfo)

        val labelsToAdd = LabelService.buildLabelList(size, status, authorChanged)
        LabelService.ensureLabelsExist(actualRepo, labelsToAdd)
        pr.setLabels(*labelsToAdd.map(Labels::labelName).toTypedArray())

        val filesChanged = sizeCalculationService.calculateFilesChanged(status, headPackInfo, mainPackInfo)

        val manifestService = ManifestService(client)
        val repoOwner = actualRepo.ownerName
        val repoName = actualRepo.name
        val existingInternalNames = manifestService.getExistingInternalNames(repoOwner, repoName)
        val fileValidationService = FileValidationService(client)
        val validationErrors = fileValidationService.validateRequiredFiles(
            headPackInfo, headPackProps, status, existingInternalNames
        )

        commentService.addPackChangesComment(
            pr, packFilePath, status, headPackInfo, mainPackInfo, filesChanged, validationErrors
        )

        logSummary(prNumber, prFile.filename, status, headPackInfo, size, authorChanged)
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
        return filePath
    }

    fun validateCommit(
        packInfo: PackFileInfo,
        filePath: String,
        pr: GHPullRequest,
        client: okhttp3.OkHttpClient,
        commentService: ci.services.PRCommentService
    ) {
        val packPropsUrl = "${BASE_GUTHUB_LINK_RAW}${packInfo.repoLink}/${packInfo.commit}/pack.properties"
        val request = okhttp3.Request.Builder().url(packPropsUrl).build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                commentService.addErrorComment(
                    pr, filePath,
                    "Unable to locate repository or commit. Repository: ${packInfo.repository}, Commit: ${packInfo.commit}. Please verify the commit exists and is accessible."
                )
                throw IllegalStateException("Commit validation failed")
            }
        }
    }

    private fun logSummary(
        prNumber: Int,
        filename: String,
        status: Labels,
        packInfo: PackFileInfo,
        size: Labels,
        authorChanged: Boolean
    ) {
        println("File in PR #$prNumber:")
        println("  - $filename [${status.labelName}]")
        println("  - Repository: ${packInfo.repository}")
        println("  - Commit: ${packInfo.commit}")
        println("  - Size Label: ${size.labelName}")
        println("  - Status Label: ${status.labelName}")
        if (authorChanged) {
            println("  - Author Changed: true")
        }
    }
}
