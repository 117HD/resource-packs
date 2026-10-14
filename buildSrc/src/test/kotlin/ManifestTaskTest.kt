import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ManifestTaskTest {

    private lateinit var task: ManifestTask

    @BeforeEach
    fun setUp() {
        val project = ProjectBuilder.builder().build()
        task = project.tasks.create("manifest", ManifestTask::class.java)
    }

    // --- isSafeArchivePath ---

    @Test
    fun `isSafeArchivePath accepts ordinary file and directory entries`() {
        assertTrue(task.isSafeArchivePath("pack.properties"))
        assertTrue(task.isSafeArchivePath("icon.png"))
        assertTrue(task.isSafeArchivePath("sub/dir/file.txt"))
        assertTrue(task.isSafeArchivePath("sub/dir/"))
    }

    @Test
    fun `isSafeArchivePath rejects absolute paths`() {
        assertFalse(task.isSafeArchivePath("/etc/passwd"))
    }

    @Test
    fun `isSafeArchivePath rejects backslashes`() {
        assertFalse(task.isSafeArchivePath("sub\\file.txt"))
    }

    @Test
    fun `isSafeArchivePath rejects parent directory traversal`() {
        assertFalse(task.isSafeArchivePath("../escape.txt"))
        assertFalse(task.isSafeArchivePath("sub/../../escape.txt"))
    }

    @Test
    fun `isSafeArchivePath rejects current directory segments`() {
        assertFalse(task.isSafeArchivePath("./file.txt"))
    }

    @Test
    fun `isSafeArchivePath rejects empty segments and empty paths`() {
        assertFalse(task.isSafeArchivePath("sub//file.txt"))
        assertFalse(task.isSafeArchivePath(""))
        assertFalse(task.isSafeArchivePath("/"))
    }

    // --- parseTags ---

    @Test
    fun `parseTags returns an empty list for null or blank input`() {
        assertTrue(task.parseTags(null, "pack").isEmpty())
        assertTrue(task.parseTags("", "pack").isEmpty())
        assertTrue(task.parseTags("   ", "pack").isEmpty())
    }

    @Test
    fun `parseTags trims and splits on comma`() {
        assertEquals(listOf("pvm", "clan", "hd"), task.parseTags(" pvm, clan ,hd", "pack"))
    }

    @Test
    fun `parseTags rejects more than 12 tags`() {
        val tags = (1..13).joinToString(",") { "tag$it" }
        assertThrows(IllegalArgumentException::class.java) { task.parseTags(tags, "pack") }
    }

    @Test
    fun `parseTags accepts exactly 12 tags`() {
        val tags = (1..12).joinToString(",") { "tag$it" }
        assertEquals(12, task.parseTags(tags, "pack").size)
    }

    @Test
    fun `parseTags rejects a tag longer than 40 characters`() {
        assertThrows(IllegalArgumentException::class.java) {
            task.parseTags("a".repeat(41), "pack")
        }
    }

    @Test
    fun `parseTags accepts a tag at exactly 40 characters`() {
        val tag = "a".repeat(40)
        assertEquals(listOf(tag), task.parseTags(tag, "pack"))
    }

    @Test
    fun `parseTags rejects duplicate tags`() {
        assertThrows(IllegalArgumentException::class.java) {
            task.parseTags("pvm,pvm", "pack")
        }
    }

    // --- readSource ---

    private fun descriptorFile(dir: File, name: String, contents: String): File =
        File(dir, name).apply { writeText(contents) }

    @Test
    fun `readSource parses a valid descriptor`(@TempDir dir: File) {
        val file = descriptorFile(
            dir, "my_pack",
            "internalName=my_pack\nrepository=https://github.com/owner/repo\ncommit=${"A".repeat(40)}\n"
        )
        val source = task.readSource(file)
        assertEquals("my_pack", source.internalName)
        assertEquals("owner", source.owner)
        assertEquals("repo", source.repository)
        assertEquals("a".repeat(40), source.commit)
    }

    @Test
    fun `readSource rejects an unsafe descriptor filename`(@TempDir dir: File) {
        val file = descriptorFile(
            dir, "MyPack",
            "internalName=my_pack\nrepository=https://github.com/owner/repo\ncommit=${"a".repeat(40)}\n"
        )
        assertThrows(IllegalArgumentException::class.java) { task.readSource(file) }
    }

    @Test
    fun `readSource rejects a descriptor missing required fields`(@TempDir dir: File) {
        val file = descriptorFile(dir, "my_pack", "internalName=my_pack\n")
        assertThrows(IllegalStateException::class.java) { task.readSource(file) }
    }

    @Test
    fun `readSource rejects an invalid commit hash`(@TempDir dir: File) {
        val file = descriptorFile(
            dir, "my_pack",
            "internalName=my_pack\nrepository=https://github.com/owner/repo\ncommit=not-a-hash\n"
        )
        assertThrows(IllegalArgumentException::class.java) { task.readSource(file) }
    }

    // --- requireUniqueEntries ---

    private fun entry(internalName: String, sha256: String) = ManifestEntry(
        hasIcon = false,
        hasCompactIcon = false,
        displayName = "Display",
        internalName = internalName,
        commit = "a".repeat(40),
        link = "https://github.com/owner/repo",
        fileSize = 1,
        sha256 = sha256
    )

    @Test
    fun `requireUniqueEntries passes for distinct names and hashes`() {
        assertDoesNotThrow {
            task.requireUniqueEntries(
                listOf(entry("pack_one", "a".repeat(64)), entry("pack_two", "b".repeat(64)))
            )
        }
    }

    @Test
    fun `requireUniqueEntries rejects a duplicate internalName`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            task.requireUniqueEntries(
                listOf(entry("same_name", "a".repeat(64)), entry("same_name", "b".repeat(64)))
            )
        }
        assertEquals("Pack internal names must be unique", ex.message)
    }

    @Test
    fun `requireUniqueEntries rejects packs sharing an archive sha256`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            task.requireUniqueEntries(
                listOf(entry("pack_one", "a".repeat(64)), entry("pack_two", "a".repeat(64)))
            )
        }
        assertEquals("Official packs must not share an archive SHA-256", ex.message)
    }

    @Test
    fun `requireUniqueEntries passes for a single entry`() {
        assertDoesNotThrow { task.requireUniqueEntries(listOf(entry("only_pack", "a".repeat(64)))) }
    }

    @Test
    fun `requireUniqueEntries passes for an empty list`() {
        assertDoesNotThrow { task.requireUniqueEntries(emptyList()) }
    }
}
