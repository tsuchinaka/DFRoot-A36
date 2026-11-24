package df.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LogFilesTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun trimKeepsOnlyTheTail() {
        val file = temp.newFile("run.log")
        file.writeText("0123456789")

        LogFiles.trim(file, 8L, 4L)

        assertEquals("6789", file.readText())
    }

    @Test
    fun trimLeavesSmallFilesAlone() {
        val file = temp.newFile("run.log")
        file.writeText("abc")

        LogFiles.trim(file, 8L, 4L)

        assertEquals("abc", file.readText())
    }

    @Test
    fun appendRotatesAfterWriting() {
        val file = temp.newFile("run.log")
        file.writeText("0123456789")

        LogFiles.append(file, "abcd".toByteArray(), 12L, 6L)

        val text = file.readText()
        assertEquals(6, text.length)
        assertTrue(text.endsWith("89abcd"))
    }

    @Test
    fun readTailReturnsSuffix() {
        val file = temp.newFile("boot.log")
        file.writeText("0123456789")

        assertEquals("789", LogFiles.readTail(file, 3))
        assertEquals("0123456789", LogFiles.readTail(file, 100))
        assertEquals("", LogFiles.readTail(temp.root, 3))
    }
}
