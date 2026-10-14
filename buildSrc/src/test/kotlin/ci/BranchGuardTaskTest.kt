package ci

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BranchGuardTaskTest {

    @Test
    fun `flags a direct packs descriptor as a candidate`() {
        assertTrue(BranchGuardTask.isCandidatePackDescriptorPath("packs/my_pack"))
    }

    @Test
    fun `does not flag files outside packs`() {
        assertFalse(BranchGuardTask.isCandidatePackDescriptorPath("README.md"))
        assertFalse(BranchGuardTask.isCandidatePackDescriptorPath("buildSrc/src/main/kotlin/ManifestTask.kt"))
        assertFalse(BranchGuardTask.isCandidatePackDescriptorPath(".github/workflows/main.yml"))
    }

    @Test
    fun `does not flag nested paths inside packs`() {
        assertFalse(BranchGuardTask.isCandidatePackDescriptorPath("packs/sub/my_pack"))
    }

    @Test
    fun `does not flag unsafe descriptor names`() {
        assertFalse(BranchGuardTask.isCandidatePackDescriptorPath("packs/MyPack"))
        assertFalse(BranchGuardTask.isCandidatePackDescriptorPath("packs/my.pack"))
        assertFalse(BranchGuardTask.isCandidatePackDescriptorPath("packs/"))
    }
}
