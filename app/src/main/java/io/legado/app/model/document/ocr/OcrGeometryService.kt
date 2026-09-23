package io.legado.app.model.document.ocr

import android.graphics.PointF
import android.graphics.RectF
import io.legado.app.model.document.pdf.SentenceBox
import kotlin.math.max

/**
 * OCR 识别出的单行与文本块结构。
 */
data class OcrLine(
    val text: String,
    val rect: RectF, // 归一化坐标 [0..1]
)

data class OcrBlock(
    val text: String,
    val rect: RectF, // 归一化坐标 [0..1]
    val lines: List<OcrLine>,
)

/**
 * OCR 句子几何服务：把 ML Kit 识别出的**行级**轴向框合并为**句子级**矩形。
 *
 * 移植自 WiseMuse OcrGeometryService 与 line_merge_rules 规范：
 * 1. 行内先按终止标点切段；
 * 2. 跨行合并按排版自动折行判据（行末贴边、无标点、下行无缩进、首字符非符号）；
 * 3. 输出 SentenceBox（归一化坐标），支持多行 rects 组合与点读坐标命中。
 */
object OcrGeometryService {

    private const val LINE_END_BREAKERS = "。！？!?；;，,、：:）)】]》>」』\"”'’…—-～~／/\\|"
    private const val LINE_START_BREAKERS =
        "。！？!?；;，,、：:（(【[《<「『\"“'‘•·◦▪▸●○※§#*+-—…" +
                "①②③④⑤⑥⑦⑧⑨⑩⑪⑫⑬⑭⑮⑯⑰⑱⑲⑳" +
                "⒈⒉⒊⒋⒌⒍⒎⒏⒐⒑" +
                "ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩ"

    private val SENTENCE_TERMS = setOf('。', '！', '？', '!', '?', '…', ';', '；')

    private val LINE_START_ORDINAL = Regex(
        "^(?:\\(?\\d+[.)、．]|[一二三四五六七八九十百千]+[、.．)]|[IVXLC]+[.)、])"
    )

    /**
     * 判断上一行行尾与下一行行首是否属于同一句的自动折行。
     */
    fun canMergeLines(
        prevRight: Float,
        prevLeft: Float,
        blockRight: Float,
        nextLeft: Float,
        blockLeft: Float,
        charW: Float,
        prevLastChar: Char,
        nextFirstChar: Char,
        prevLineText: String = "",
        nextLineText: String = "",
        endSlackChars: Float = 2.0f,
        indentSlackChars: Float = 0.5f,
    ): Boolean {
        val w = if (charW > 0f) charW else 0.02f

        // 1) 行末必须贴近版心右边界（排不下才折行）
        if (blockRight - prevRight > endSlackChars * w) return false

        // 2) 行末不得有任何标点/符号
        if (LINE_END_BREAKERS.contains(prevLastChar)) return false

        // 3) 下一行不得有缩进
        if (nextLeft - blockLeft > indentSlackChars * w) return false

        // 4) 下一行行首不得是标点、项目符号或序号
        if (LINE_START_BREAKERS.contains(nextFirstChar)) return false
        if (nextLineText.isNotEmpty() && LINE_START_ORDINAL.containsMatchIn(nextLineText)) {
            return false
        }

        // 5) 同行无连续 2 空格（多列/表格边界）
        if (prevLineText.contains("  ") || nextLineText.contains("  ")) return false

        return true
    }

    /**
     * 将 OCR 文本块解析聚合为句子集合（归一化坐标）。
     */
    fun buildSentences(
        blocks: List<OcrBlock>,
        pageIndex: Int = 0,
    ): List<SentenceBox> {
        val result = mutableListOf<SentenceBox>()

        for (block in blocks) {
            val blockLeft = block.rect.left
            val blockRight = block.rect.right
            val charH = medianLineHeight(block.lines)
            val charW = max(charH, 0.015f)

            val sb = StringBuilder()
            val rects = mutableListOf<RectF>()

            fun flush() {
                val s = sb.toString().trim()
                if (s.isNotEmpty() && rects.isNotEmpty()) {
                    result.add(SentenceBox(s, ArrayList(rects), pageIndex))
                }
                sb.clear()
                rects.clear()
            }

            var prevLineFullText = ""

            for (line in block.lines) {
                val text = line.text.trim()
                if (text.isEmpty()) {
                    flush()
                    prevLineFullText = ""
                    continue
                }

                val lineRect = line.rect
                val segments = splitLineByTerms(text)
                if (segments.isEmpty()) continue

                val lineHasDoubleSpaces = line.text.contains("  ")

                for (i in segments.indices) {
                    val seg = segments[i].trim()
                    if (seg.isEmpty()) continue
                    val segFirst = seg.first()
                    val segLast = seg.last()

                    if (sb.isNotEmpty() && rects.isNotEmpty()) {
                        val forceFlush = lineHasDoubleSpaces && i > 0
                        val merge = if (forceFlush) false else {
                            val lastRect = rects.last()
                            canMergeLines(
                                prevRight = lastRect.right,
                                prevLeft = lastRect.left,
                                blockRight = blockRight,
                                nextLeft = lineRect.left,
                                blockLeft = blockLeft,
                                charW = charW,
                                prevLastChar = sb.last(),
                                nextFirstChar = segFirst,
                                prevLineText = prevLineFullText,
                                nextLineText = text
                            )
                        }

                        if (!merge) {
                            flush()
                        } else {
                            if (needsSpaceBetween(sb.last(), segFirst)) {
                                sb.append(' ')
                            }
                        }
                    }

                    sb.append(seg)
                    rects.add(lineRect)

                    if (SENTENCE_TERMS.contains(segLast)) {
                        flush()
                    }
                }
                prevLineFullText = text
            }
            flush()
        }

        return result
    }

    /**
     * 按标点将单行切分为子段落。
     */
    private fun splitLineByTerms(text: String): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        for (ch in text) {
            sb.append(ch)
            if (SENTENCE_TERMS.contains(ch)) {
                result.add(sb.toString())
                sb.clear()
            }
        }
        if (sb.isNotEmpty()) {
            result.add(sb.toString())
        }
        return result
    }

    private fun needsSpaceBetween(a: Char, b: Char): Boolean {
        val isEngA = (a in 'a'..'z') || (a in 'A'..'Z') || (a in '0'..'9')
        val isEngB = (b in 'a'..'z') || (b in 'A'..'Z') || (b in '0'..'9')
        return isEngA && isEngB
    }

    private fun medianLineHeight(lines: List<OcrLine>): Float {
        if (lines.isEmpty()) return 0.03f
        val heights = lines.map { it.rect.height() }.sorted()
        return heights[heights.size / 2]
    }

    /**
     * 判定点击点（归一化坐标 [0..1]）命中哪个句子。
     */
    fun hitSentence(sentences: List<SentenceBox>, point: PointF): SentenceBox? {
        val px = point.x
        val py = point.y

        // 1. 精确矩形包含判断
        for (s in sentences) {
            for (r in s.rects) {
                if (px in r.left..r.right && py in r.top..r.bottom) {
                    return s
                }
            }
        }

        // 2. 扩大 4% 容差判断邻近点
        val slop = 0.04f
        var bestSentence: SentenceBox? = null
        var minDistance = Float.MAX_VALUE

        for (s in sentences) {
            for (r in s.rects) {
                val expanded = RectF(
                    r.left - slop,
                    r.top - slop,
                    r.right + slop,
                    r.bottom + slop
                )
                if (px in expanded.left..expanded.right && py in expanded.top..expanded.bottom) {
                    val cx = (r.left + r.right) / 2f
                    val cy = (r.top + r.bottom) / 2f
                    val d = (px - cx) * (px - cx) + (py - cy) * (py - cy)
                    if (d < minDistance) {
                        minDistance = d
                        bestSentence = s
                    }
                }
            }
        }
        return bestSentence
    }
}
