package app.megaglass.android.feature.viewer

import org.junit.Assert.*
import org.junit.Test

class ViewerLayoutTest {
    @Test fun pdfPageRatioStaysMeasurable() {
        assertEquals(0.5f, pdfPageRatio(300, 600), 0f)
        // 3 x 14400 pt: about 5 million px tall at 1000 px wide without the limit.
        assertEquals(0.05f, pdfPageRatio(3, 14_400), 0f)
        assertEquals(20f, pdfPageRatio(14_400, 3), 0f)
        assertEquals(1f, pdfPageRatio(0, 0), 0f)
        // Constraints allow 262,142 px height for widths below 8191 px.
        assertTrue(8190 / pdfPageRatio(1, Int.MAX_VALUE) < 262_142)
    }

    @Test fun longLinesSplitForTheReadOnlyView() {
        assertEquals(listOf("a", "", "b"), displayLines("a\n\nb"))
        val line = "x".repeat(10_000)
        val parts = displayLines(line, max = 4096)
        assertEquals(listOf(4096, 4096, 1808), parts.map { it.length })
        assertEquals(line, parts.joinToString(""))
    }

    @Test fun lineBreaksAreTheSameAsStringLines() {
        for (text in listOf("", "a", "a\n", "a\r\nb\rc\n", "\n\n", "a\r", "x".repeat(9) + "\n" + "y".repeat(3))) {
            assertEquals(text.lines().flatMap { it.chunked(4) .ifEmpty { listOf("") } }, displayLines(text, max = 4))
        }
    }

    @Test fun manyShortLinesKeepOnlyOffsets() {
        val text = "a\n".repeat(1_000_000)
        val starts = displayLineStarts(text)
        assertEquals(1_000_001, starts.size)
        assertEquals("a", displayLine(text, starts[999_999]))
        assertEquals("", displayLine(text, starts[1_000_000]))
    }

    @Test fun splitKeepsSurrogatePairs() {
        val text = "a".repeat(3) + "😀" + "b"
        val parts = displayLines(text, max = 4)
        assertEquals(text, parts.joinToString(""))
        assertTrue(parts.none { it.isNotEmpty() && Character.isHighSurrogate(it.last()) })
        assertTrue(parts.none { it.isNotEmpty() && Character.isLowSurrogate(it.first()) })
    }
}
