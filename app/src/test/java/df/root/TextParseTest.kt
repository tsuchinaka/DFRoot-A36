package df.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextParseTest {

    @Test
    fun taStateParsesSecondParcelWord() {
        val parcel = "Result: Parcel(\n  00000000 00000002 '........'"
        assertEquals(2, TextParse.taState(parcel))
    }

    @Test
    fun taStateRejectsMissingInput() {
        assertEquals(-1, TextParse.taState(null))
        assertEquals(-1, TextParse.taState(""))
        assertEquals(-1, TextParse.taState("no parcel here"))
    }

    @Test
    fun enrolledCoversActiveLockedCompleted() {
        assertTrue(TextParse.enrolled(2))
        assertTrue(TextParse.enrolled(3))
        assertTrue(TextParse.enrolled(4))
        assertFalse(TextParse.enrolled(0))
        assertFalse(TextParse.enrolled(1))
        assertFalse(TextParse.enrolled(5))
        assertFalse(TextParse.enrolled(-1))
    }
}
