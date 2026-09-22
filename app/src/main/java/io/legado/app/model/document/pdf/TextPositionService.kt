package io.legado.app.model.document.pdf

import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * PDF 字符坐标解析与句子几何构建服务。
 *
 * 坐标系与 PDFBox 一致（原点在 CropBox 左上角，y 向下）。
 */
object TextPositionService {

    private const val SENTENCE_TERMS = "。！？!?；;"

    internal data class Line(
        val text: String,
        val top: Float,
        val bottom: Float,
        val chars: List<CharBox>
    ) {
        val xMin: Float = chars.minOfOrNull { it.x } ?: 0f
        val xMax: Float = chars.maxOfOrNull { it.x + it.w } ?: 0f
    }

    /** 字符 -> 行（PDFBox 阅读顺序） */
    internal fun buildLines(chars: List<CharBox>): List<Line> {
        val raw = mutableListOf<MutableList<CharBox>>()
        for (c in chars) {
            if (raw.isEmpty()) {
                raw.add(mutableListOf(c))
                continue
            }
            val prev = raw.last().last()
            val sameLine = abs(c.y - prev.y) <= 0.55f * (prev.fs + c.fs) / 2f &&
                    (c.x - prev.x) > -c.fs * 0.3f
            if (sameLine) {
                raw.last().add(c)
            } else {
                raw.add(mutableListOf(c))
            }
        }

        return raw.filter { it.isNotEmpty() }.map { l ->
            val top = l.first().y - 0.88f * l.first().fs
            val bottom = l.first().y + 0.12f * l.first().fs
            val sb = StringBuilder()
            for (c in l) {
                sb.append(c.ch)
            }
            Line(sb.toString(), top, bottom, l)
        }
    }

    /**
     * 字符坐标 -> 句子列表（按终止标点切句，句子可跨行）。
     *
     * @param chars 页面提取的字符列表
     * @param pageIndex 页面索引
     * @param paraGapEm 段落判定空行系数
     */
    fun buildSentences(
        chars: List<CharBox>,
        pageIndex: Int = 0,
        paraGapEm: Float = 1.8f
    ): List<SentenceBox> {
        if (chars.isEmpty()) return emptyList()
        val charH = medianFs(chars) * 1.2f
        val charW = medianFs(chars)
        val result = mutableListOf<SentenceBox>()

        val sb = StringBuilder()
        val rects = mutableListOf<RectF>()
        var left = Float.POSITIVE_INFINITY
        var right = Float.NEGATIVE_INFINITY
        var curTop = 0f
        var curBottom = 0f
        var lastChar = ""

        fun closeRect() {
            if (right >= left) {
                rects.add(RectF(left, curTop, right, curBottom))
            }
            left = Float.POSITIVE_INFINITY
            right = Float.NEGATIVE_INFINITY
        }

        fun flush() {
            closeRect()
            if (sb.isNotEmpty() && rects.isNotEmpty()) {
                val text = sb.toString().trim()
                if (text.isNotEmpty()) {
                    result.add(SentenceBox(text = text, rects = ArrayList(rects), pageIndex = pageIndex))
                }
            }
            sb.clear()
            rects.clear()
            lastChar = ""
        }

        val lines = buildLines(chars)
        val blocks = splitLineBlocks(lines, paraGapEm * charH)

        for (block in blocks) {
            val blockLeft = block.minOfOrNull { it.xMin } ?: 0f
            val blockRight = block.maxOfOrNull { it.xMax } ?: 0f

            for (i in block.indices) {
                val line = block[i]
                if (line.chars.isEmpty()) continue

                if (sb.isNotEmpty()) {
                    val prev = block[i - 1]
                    val merge = LineMergeRules.canMergeLines(
                        prevRight = prev.xMax,
                        prevLeft = prev.xMin,
                        blockRight = blockRight,
                        nextLeft = line.xMin,
                        blockLeft = blockLeft,
                        charW = charW,
                        prevLastChar = lastChar,
                        nextFirstChar = line.chars.first().ch,
                        prevLineText = prev.text,
                        nextLineText = line.text
                    )
                    if (!merge) {
                        flush()
                    } else if (LineMergeRules.needsSpaceBetween(lastChar, line.chars.first().ch)) {
                        sb.append(" ")
                    }
                }

                curTop = line.top
                curBottom = line.bottom
                for (c in line.chars) {
                    val ch = c.ch
                    left = min(left, c.x)
                    right = max(right, c.x + c.w)
                    sb.append(ch)
                    lastChar = ch
                    if (SENTENCE_TERMS.contains(ch)) {
                        flush()
                    }
                }
                closeRect()
            }
            flush()
        }
        return result
    }

    private fun splitLineBlocks(lines: List<Line>, gap: Float): List<List<Line>> {
        val blocks = mutableListOf<MutableList<Line>>()
        for (line in lines) {
            if (blocks.isEmpty() || line.top - blocks.last().last().bottom > gap) {
                blocks.add(mutableListOf(line))
            } else {
                blocks.last().add(line)
            }
        }
        return blocks
    }

    /** 字符坐标 -> 段落列表（兜底） */
    fun buildParagraphs(
        chars: List<CharBox>,
        pageIndex: Int = 0,
        paraGapEm: Float = 1.8f
    ): List<ParagraphBox> {
        if (chars.isEmpty()) return emptyList()
        val charH = medianFs(chars) * 1.2f
        val paragraphs = mutableListOf<ParagraphBox>()
        val curRects = mutableListOf<RectF>()
        val buf = StringBuilder()

        fun flush() {
            if (curRects.isEmpty()) return
            paragraphs.add(
                ParagraphBox(
                    text = buf.toString().trim(),
                    rects = ArrayList(curRects),
                    pageIndex = pageIndex
                )
            )
            curRects.clear()
            buf.clear()
        }

        for (line in buildLines(chars)) {
            if (curRects.isNotEmpty() && line.top - curRects.last().bottom > paraGapEm * charH) {
                flush()
            }
            curRects.add(RectF(line.xMin, line.top, line.xMax, line.bottom))
            buf.append(line.text).append("\n")
        }
        flush()
        return paragraphs
    }

    /**
     * 点所在的句子；未包含则吸附最近句（容忍点击偏移）。
     *
     * @param point 点击点（PDF 页面坐标）
     * @param snapEm 吸附倍数（以单行真实高度计）
     */
    fun hitSentence(
        sentences: List<SentenceBox>,
        point: PointF,
        snapEm: Float = 4.0f
    ): SentenceBox? {
        if (sentences.isEmpty()) return null
        val lineHeights = sentences.flatMap { it.rects.map { r -> r.height() } }
            .filter { it > 0 }
            .sorted()
        val est = if (lineHeights.isNotEmpty()) lineHeights[lineHeights.size / 2] else 12.0f
        val snapThreshold = snapEm * max(est, 1.0f)

        var best: SentenceBox? = null
        var bestD = Float.POSITIVE_INFINITY

        for (s in sentences) {
            for (r in s.rects) {
                if (r.contains(point.x, point.y)) {
                    return s
                }
                val d = distToRect(point, r)
                if (d < bestD) {
                    bestD = d
                    best = s
                }
            }
        }
        return if (bestD <= snapThreshold) best else null
    }

    /** 点到矩形的最短距离（矩形内为 0） */
    fun distToRect(p: PointF, r: RectF): Float {
        if (r.contains(p.x, p.y)) return 0f
        val dx = if (p.x < r.left) r.left - p.x else if (p.x > r.right) p.x - r.right else 0f
        val dy = if (p.y < r.top) r.top - p.y else if (p.y > r.bottom) p.y - r.bottom else 0f
        return sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }

    private fun medianFs(chars: List<CharBox>): Float {
        val fsList = chars.map { it.fs }.sorted()
        return if (fsList.isEmpty()) 12f else fsList[fsList.size / 2]
    }
}
