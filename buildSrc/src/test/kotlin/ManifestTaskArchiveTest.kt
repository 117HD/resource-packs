import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ManifestTaskArchiveTest {

    private lateinit var task: ManifestTask

    @BeforeEach
    fun setUp() {
        val project = ProjectBuilder.builder().build()
        task = project.tasks.create("manifest", ManifestTask::class.java)
    }

    private fun zipBytesOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ZipInputStream =
        ZipInputStream(ByteArrayInputStream(zipBytesOf(*entries)))

    private fun bytes(size: Int): ByteArray = ByteArray(size) { 120.toByte() }

    /**
     * ZipInputStream parses local file headers sequentially and ignores the central
     * directory, so concatenating one zip's local-file section with another complete
     * zip produces a stream with two entries (even same-named ones) even though
     * ZipOutputStream itself refuses to ever write such a file directly.
     */
    private fun localFileSectionOf(zipBytes: ByteArray): ByteArray {
        val centralDirectorySignature = byteArrayOf(0x50, 0x4B, 0x01, 0x02)
        for (i in 0..zipBytes.size - 4) {
            if ((0..3).all { zipBytes[i + it] == centralDirectorySignature[it] }) {
                return zipBytes.copyOfRange(0, i)
            }
        }
        error("central directory signature not found")
    }

    private fun zipWithDuplicateEntryName(name: String, first: ByteArray, second: ByteArray): ZipInputStream {
        val firstZip = localFileSectionOf(zipBytesOf(name to first))
        val secondZip = zipBytesOf(name to second)
        return ZipInputStream(ByteArrayInputStream(firstZip + secondZip))
    }

    // --- validateArchive ---

    @Test
    fun `accepts an archive with exactly one pack properties`() {
        val zip = zipOf("pack.properties" to bytes(10), "icon.png" to bytes(10), "licenses.txt" to bytes(5))
        assertDoesNotThrow { task.validateArchive(zip, "pack") }
    }

    @Test
    fun `accepts pack properties nested in a subdirectory`() {
        val zip = zipOf("my_pack/pack.properties" to bytes(10))
        assertDoesNotThrow { task.validateArchive(zip, "pack") }
    }

    @Test
    fun `rejects an archive with no pack properties`() {
        val zip = zipOf("icon.png" to bytes(10))
        val ex = assertThrows(IllegalArgumentException::class.java) { task.validateArchive(zip, "pack") }
        assertEquals("pack: archive must contain exactly one pack.properties", ex.message)
    }

    @Test
    fun `rejects an archive with more than one pack properties`() {
        val zip = zipOf("pack.properties" to bytes(10), "sub/pack.properties" to bytes(10))
        val ex = assertThrows(IllegalArgumentException::class.java) { task.validateArchive(zip, "pack") }
        assertEquals("pack: archive must contain exactly one pack.properties", ex.message)
    }

    @Test
    fun `rejects an unsafe entry path`() {
        val zip = zipOf("pack.properties" to bytes(10), "../escape.txt" to bytes(1))
        val ex = assertThrows(IllegalArgumentException::class.java) { task.validateArchive(zip, "pack") }
        assertEquals("pack: archive contains an unsafe entry path", ex.message)
    }

    @Test
    fun `rejects duplicate entry names`() {
        val zip = zipWithDuplicateEntryName("pack.properties", bytes(10), bytes(5))
        val ex = assertThrows(IllegalArgumentException::class.java) { task.validateArchive(zip, "pack") }
        assertEquals("pack: archive contains duplicate entries", ex.message)
    }

    @Test
    fun `rejects an archive with too many entries`() {
        val zip = zipOf("pack.properties" to bytes(1), "a" to bytes(1), "b" to bytes(1))
        val ex = assertThrows(IllegalArgumentException::class.java) {
            task.validateArchive(zip, "pack", maxEntries = 2)
        }
        assertEquals("pack: archive has too many entries", ex.message)
    }

    @Test
    fun `accepts an archive right at the entry limit`() {
        val zip = zipOf("pack.properties" to bytes(1), "a" to bytes(1))
        assertDoesNotThrow { task.validateArchive(zip, "pack", maxEntries = 2) }
    }

    @Test
    fun `rejects a single entry larger than the per-entry limit`() {
        val zip = zipOf("pack.properties" to bytes(20))
        val ex = assertThrows(IllegalArgumentException::class.java) {
            task.validateArchive(zip, "pack", maxEntrySize = 10)
        }
        assertEquals("pack: archive entry is too large", ex.message)
    }

    @Test
    fun `rejects entries that individually pass but together exceed the uncompressed limit`() {
        val zip = zipOf("pack.properties" to bytes(10), "icon.png" to bytes(10))
        val ex = assertThrows(IllegalArgumentException::class.java) {
            task.validateArchive(zip, "pack", maxEntrySize = 10, maxUncompressedSize = 15)
        }
        assertEquals("pack: archive expands beyond the allowed size", ex.message)
    }

    // --- ValidatingArchiveInputStream ---

    @Test
    fun `ValidatingArchiveInputStream computes size and sha256 over the raw stream`() {
        val data = "hello world".toByteArray()
        val digest = MessageDigest.getInstance("SHA-256")
        val stream = ManifestTask.ValidatingArchiveInputStream(ByteArrayInputStream(data), digest, "pack")

        val read = stream.readBytes()
        assertEquals(data.size.toLong(), stream.size)
        assertEquals(data.toList(), read.toList())
        assertEquals(
            MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) },
            digest.digest().joinToString("") { "%02x".format(it) }
        )
    }

    @Test
    fun `ValidatingArchiveInputStream rejects a stream larger than the configured limit`() {
        val data = bytes(100)
        val digest = MessageDigest.getInstance("SHA-256")
        val stream = ManifestTask.ValidatingArchiveInputStream(ByteArrayInputStream(data), digest, "pack", maxSize = 50)

        assertThrows(IllegalArgumentException::class.java) { stream.readBytes() }
    }

    @Test
    fun `ValidatingArchiveInputStream accepts a stream right at the configured limit`() {
        val data = bytes(50)
        val digest = MessageDigest.getInstance("SHA-256")
        val stream = ManifestTask.ValidatingArchiveInputStream(ByteArrayInputStream(data), digest, "pack", maxSize = 50)

        assertDoesNotThrow { stream.readBytes() }
        assertEquals(50L, stream.size)
    }
}
