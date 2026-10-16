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

    @Test fun splitKeepsSurrogatePairs() {
        val text = "a".repeat(3) + "😀" + "b"
        val parts = displayLines(text, max = 4)
        assertEquals(text, parts.joinToString(""))
        assertTrue(parts.none { it.isNotEmpty() && Character.isHighSurrogate(it.last()) })
        assertTrue(parts.none { it.isNotEmpty() && Character.isLowSurrogate(it.first()) })
    }
}
