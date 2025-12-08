package ci.services

import ci.Labels
import org.kohsuke.github.GHRepository

object LabelService {
    @OptIn(ExperimentalStdlibApi::class)
    fun buildLabelList(size: Labels, status: Labels, authorChanged: Boolean): List<Labels> =
        buildList {
            add(size)
            add(status)
            // Only add author-changed label for changed packs, not new ones
            if (authorChanged && status != Labels.ADDED) {
                add(Labels.AUTHOR_CHANGED)
            }
        }

    fun ensureLabelsExist(repo: GHRepository, labels: List<Labels>) {
        labels.forEach { label ->
            runCatching {
                val existingLabel = repo.getLabel(label.labelName)
                if (existingLabel.color != label.color) {
                    existingLabel.delete()
                    repo.createLabel(label.labelName, label.color)
                }
            }.onFailure {
                runCatching {
                    repo.createLabel(label.labelName, label.color)
                }.onFailure { createError ->
                    val errorMessage = createError.message ?: ""
                    if (!errorMessage.contains("already_exists", ignoreCase = true)) {
                        System.err.println("Failed to create label ${label.labelName}: $errorMessage")
                    }
                }
            }
        }
    }
}

