package ci.services

import ci.Labels
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class LabelServiceTest {

    @Test
    fun `includes size and status labels`() {
        val labels = LabelService.buildLabelList(Labels.SIZE_M, Labels.CHANGED, authorChanged = false)
        assertEquals(listOf(Labels.SIZE_M, Labels.CHANGED), labels)
    }

    @Test
    fun `adds author-changed label when the author changed on a non-added pack`() {
        val labels = LabelService.buildLabelList(Labels.SIZE_S, Labels.CHANGED, authorChanged = true)
        assertEquals(listOf(Labels.SIZE_S, Labels.CHANGED, Labels.AUTHOR_CHANGED), labels)
    }

    @Test
    fun `never adds author-changed label for a newly added pack`() {
        val labels = LabelService.buildLabelList(Labels.SIZE_XS, Labels.ADDED, authorChanged = true)
        assertFalse(labels.contains(Labels.AUTHOR_CHANGED))
    }

    @Test
    fun `does not add author-changed label when the author did not change`() {
        val labels = LabelService.buildLabelList(Labels.SIZE_L, Labels.CHANGED, authorChanged = false)
        assertFalse(labels.contains(Labels.AUTHOR_CHANGED))
    }
}
