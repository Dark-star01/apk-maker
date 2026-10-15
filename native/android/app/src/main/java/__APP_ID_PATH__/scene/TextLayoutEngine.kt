package __APP_ID__.scene

/** One word placed inside a [TextBlock]. [x] is the visual LEFT edge, relative to the block's left edge. */
internal class WordBox(val text: String, val index: Int, val charStart: Int, val x: Float, val line: Int, val w: Float)

/** Result of laying out a text: words in LOGICAL order, each with its visual position. Pure data. */
internal class TextBlock(val words: List<WordBox>, val lineCount: Int, val width: Float, val rtl: Boolean) {
    val totalChars: Int get() = words.sumOf { it.text.length }
    companion object { val EMPTY = TextBlock(emptyList(), 0, 0f, false) }
}

/**
 * Word-level text layout with RTL support, independent of Android (the caller passes a measuring function).
 *
 * Why word by word: per-word and per-character reveal needs each word's position, and each word is a separate bidi run
 * that the platform shapes correctly on its own (Arabic joins inside a word, not across spaces). Direction is taken from
 * the first strong character; in RTL the first logical word sits at the right end of its line.
 */
internal object TextLayoutEngine {

    fun isRtl(text: String): Boolean {
        for (ch in text) {
            when (Character.getDirectionality(ch)) {
                Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return true
                Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return false
                else -> {}
            }
        }
        return false
    }

    /**
     * @param maxWidth wrap limit in pixels (a single longer word keeps its own line and may overflow; the renderer clamps)
     * @param measure width in pixels of a string in the final font and size
     * @param spaceW width of one space in pixels
     */
    fun layout(text: String, maxWidth: Float, align: TextAlign, spaceW: Float, measure: (String) -> Float): TextBlock {
        val clean = text.replace("\r", "")
        if (clean.isBlank()) return TextBlock.EMPTY
        val rtl = isRtl(clean)
        // 1) wrap into lines of (word, width) keeping logical order
        class W(val text: String, val index: Int, val charStart: Int, val w: Float)
        val lines = ArrayList<ArrayList<W>>()
        var wordIndex = 0
        var charCount = 0
        for (para in clean.split('\n')) {
            var cur = ArrayList<W>(); var curW = 0f
            lines.add(cur)
            val words = para.split(' ', '\t').filter { it.isNotEmpty() }
            if (words.isEmpty()) continue
            for (word in words) {
                val ww = measure(word)
                val add = if (cur.isEmpty()) ww else spaceW + ww
                if (cur.isNotEmpty() && curW + add > maxWidth) { cur = ArrayList(); lines.add(cur); curW = 0f }
                curW += if (cur.isEmpty()) ww else spaceW + ww
                cur.add(W(word, wordIndex++, charCount, ww))
                charCount += word.length
            }
        }
        while (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.size - 1)
        if (lines.isEmpty()) return TextBlock.EMPTY
        fun lineWidth(l: List<W>) = l.sumOf { it.w.toDouble() }.toFloat() + spaceW * Math.max(0, l.size - 1)
        val blockW = lines.maxOf { lineWidth(it) }
        // 2) visual positions
        val out = ArrayList<WordBox>()
        for ((li, l) in lines.withIndex()) {
            val lw = lineWidth(l)
            val start = when (align) { TextAlign.LEFT -> 0f; TextAlign.CENTER -> (blockW - lw) / 2f; TextAlign.RIGHT -> blockW - lw }
            var cursor = 0f // distance consumed from the line's reading start
            for (w in l) {
                val x = if (rtl) start + lw - cursor - w.w else start + cursor
                out.add(WordBox(w.text, w.index, w.charStart, x, li, w.w))
                cursor += w.w + spaceW
            }
        }
        return TextBlock(out, lines.size, blockW, rtl)
    }
}
