package ci.models

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Properties

class PackValidationTest {

    private fun descriptor(
        internalName: String? = "my_pack",
        repository: String? = "https://github.com/owner/repo",
        commit: String? = "a".repeat(40)
    ): Properties = Properties().apply {
        internalName?.let { setProperty("internalName", it) }
        repository?.let { setProperty("repository", it) }
        commit?.let { setProperty("commit", it) }
    }

    // --- readDescriptor: internalName ---

    @Test
    fun `readDescriptor accepts a valid descriptor`() {
        val info = PackValidation.readDescriptor(descriptor(), "packs/my_pack")
        assertEquals("my_pack", info.internalName)
        assertEquals("https://github.com/owner/repo", info.repository)
        assertEquals("a".repeat(40), info.commit)
        assertEquals("owner/repo", info.repoLink)
    }

    @Test
    fun `readDescriptor rejects missing internalName`() {
        val ex = assertThrows(IllegalStateException::class.java) {
            PackValidation.readDescriptor(descriptor(internalName = null), "packs/x")
        }
        assertTrue(ex.message!!.contains("internalName"))
    }

    @Test
    fun `readDescriptor rejects uppercase internalName`() {
        assertThrows(IllegalArgumentException::class.java) {
            PackValidation.readDescriptor(descriptor(internalName = "MyPack"), "packs/x")
        }
    }

    @Test
    fun `readDescriptor rejects internalName with spaces or symbols`() {
        assertThrows(IllegalArgumentException::class.java) {
            PackValidation.readDescriptor(descriptor(internalName = "my pack!"), "packs/x")
        }
    }

    @Test
    fun `readDescriptor accepts internalName with underscores and dashes`() {
        val info = PackValidation.readDescriptor(descriptor(internalName = "my-pack_2"), "packs/x")
        assertEquals("my-pack_2", info.internalName)
    }

    // --- readDescriptor: repository ---

    @Test
    fun `readDescriptor rejects missing repository`() {
        assertThrows(IllegalStateException::class.java) {
            PackValidation.readDescriptor(descriptor(repository = null), "packs/x")
        }
    }

    @Test
    fun `readDescriptor rejects repository with trailing slash`() {
        assertThrows(IllegalArgumentException::class.java) {
            PackValidation.readDescriptor(descriptor(repository = "https://github.com/owner/repo/"), "packs/x")
        }
    }

    @Test
    fun `readDescriptor rejects repository with extra path segments`() {
        assertThrows(IllegalArgumentException::class.java) {
            PackValidation.readDescriptor(descriptor(repository = "https://github.com/owner/repo/tree/main"), "packs/x")
        }
    }

    @Test
    fun `readDescriptor rejects repository on a different host`() {
        assertThrows(IllegalArgumentException::class.java) {
            PackValidation.readDescriptor(descriptor(repository = "https://gitlab.com/owner/repo"), "packs/x")
        }
    }

    @Test
    fun `readDescriptor rejects non-https repository`() {
        assertThrows(IllegalArgumentException::class.java) {
            PackValidation.readDescriptor(descriptor(repository = "http://github.com/owner/repo"), "packs/x")
        }
    }

    // --- readDescriptor: commit ---

    @Test
    fun `readDescriptor rejects missing commit`() {
        assertThrows(IllegalStateException::class.java) {
            PackValidation.readDescriptor(descriptor(commit = null), "packs/x")
        }
    }

    @Test
    fun `readDescriptor rejects short commit hash`() {
        assertThrows(IllegalArgumentException::class.java) {
            PackValidation.readDescriptor(descriptor(commit = "abc123"), "packs/x")
        }
    }

    @Test
    fun `readDescriptor rejects non-hex commit hash`() {
        assertThrows(IllegalArgumentException::class.java) {
            PackValidation.readDescriptor(descriptor(commit = "g".repeat(40)), "packs/x")
        }
    }

    @Test
    fun `readDescriptor accepts a 64-character SHA-256 commit`() {
        val info = PackValidation.readDescriptor(descriptor(commit = "b".repeat(64)), "packs/x")
        assertEquals("b".repeat(64), info.commit)
    }

    @Test
    fun `readDescriptor accepts uppercase hex commit and leaves it as-is`() {
        val commit = "A".repeat(40)
        val info = PackValidation.readDescriptor(descriptor(commit = commit), "packs/x")
        assertEquals(commit, info.commit)
    }

    // --- requiredMetadataErrors ---

    @Test
    fun `requiredMetadataErrors reports all missing fields`() {
        val errors = PackValidation.requiredMetadataErrors(PackProperties(null, null, null))
        assertEquals(3, errors.size)
    }

    @Test
    fun `requiredMetadataErrors reports blank fields as missing`() {
        val errors = PackValidation.requiredMetadataErrors(PackProperties("", "   ", ""))
        assertEquals(3, errors.size)
    }

    @Test
    fun `requiredMetadataErrors passes for complete metadata`() {
        val errors = PackValidation.requiredMetadataErrors(PackProperties("Author", "Description", "Display Name"))
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `requiredMetadataErrors rejects an overlong displayName`() {
        val errors = PackValidation.requiredMetadataErrors(
            PackProperties("Author", "Description", "D".repeat(81))
        )
        assertTrue(errors.any { it.contains("displayName") })
    }

    @Test
    fun `requiredMetadataErrors rejects an overlong author`() {
        val errors = PackValidation.requiredMetadataErrors(
            PackProperties("A".repeat(121), "Description", "Display")
        )
        assertTrue(errors.any { it.contains("author") })
    }

    @Test
    fun `requiredMetadataErrors rejects an overlong description`() {
        val errors = PackValidation.requiredMetadataErrors(
            PackProperties("Author", "D".repeat(513), "Display")
        )
        assertTrue(errors.any { it.contains("description") })
    }

    @Test
    fun `requiredMetadataErrors accepts fields at the exact length limit`() {
        val errors = PackValidation.requiredMetadataErrors(
            PackProperties("A".repeat(120), "D".repeat(512), "N".repeat(80))
        )
        assertTrue(errors.isEmpty())
    }

    // --- allowsSettings ---

    @Test
    fun `allowsSettings is true only for the allow-listed pack`() {
        assertTrue(PackValidation.allowsSettings("vanilla-plus"))
        assertFalse(PackValidation.allowsSettings("legacy_theatre_of_blood"))
        assertFalse(PackValidation.allowsSettings("vanilla_plus"))
    }

    // --- isSafeDescriptorFilename ---

    @Test
    fun `isSafeDescriptorFilename accepts lowercase letters, numbers, underscore and dash`() {
        assertTrue(PackValidation.isSafeDescriptorFilename("legacy-tob_2"))
    }

    @Test
    fun `isSafeDescriptorFilename rejects uppercase`() {
        assertFalse(PackValidation.isSafeDescriptorFilename("README"))
    }

    @Test
    fun `isSafeDescriptorFilename rejects dots`() {
        assertFalse(PackValidation.isSafeDescriptorFilename("README.md"))
    }

    @Test
    fun `isSafeDescriptorFilename rejects slashes`() {
        assertFalse(PackValidation.isSafeDescriptorFilename("packs/foo"))
    }

    @Test
    fun `isSafeDescriptorFilename rejects empty string`() {
        assertFalse(PackValidation.isSafeDescriptorFilename(""))
    }
}
