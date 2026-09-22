package io.legado.app.model.document.pdf

import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlin.math.abs

/**
 * 单个字符的坐标与字体度量。
 *
 * 坐标系为 PDFBox「方向校正后的显示空间」: 原点在页面 CropBox 左上角,
 * x 向右为正、y 向下为正, 单位是 PDF 点(1/72 英寸)。
 */
data class CharBox(
    val ch: String,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val fs: Float,
    val asc: Float,
    val desc: Float,
) {
    /** 字形上沿: 基线上方 0.88 em */
    val topEm: Float get() = y - ASCENT_EM * fs

    /** 字形下沿: 基线下方 0.12 em */
    val bottomEm: Float get() = y + DESCENT_EM * fs

    companion object {
        const val ASCENT_EM = 0.88f
        const val DESCENT_EM = 0.12f
    }
}

/**
 * 收集字符级坐标的 [PDFTextStripper] 子类。
 *
 * sortByPosition = true 保证回调顺序即阅读顺序(供合成句子框使用)。
 */
class CharBoxStripper : PDFTextStripper() {

    val boxes = mutableListOf<CharBox>()

    init {
        sortByPosition = true
    }

    override fun writeString(text: String, textPositions: List<TextPosition>) {
        for (p in textPositions) {
            val unicode = p.unicode
            if (unicode.isNullOrEmpty()) continue

            val fs = abs(p.textMatrix.scalingFactorY)
            val descriptor = p.font.fontDescriptor
            var ascRatio = (descriptor?.ascent ?: 0f) / 1000f
            var descRatio = (descriptor?.descent ?: 0f) / 1000f

            if (ascRatio <= 0f || ascRatio > 1.5f) ascRatio = 0.88f
            if (descRatio >= 0f || descRatio < -0.6f) descRatio = -0.12f

            boxes.add(
                CharBox(
                    ch = unicode,
                    x = p.xDirAdj,
                    y = p.yDirAdj,
                    w = p.widthDirAdj,
                    h = p.heightDir,
                    fs = fs,
                    asc = ascRatio * fs,
                    desc = descRatio * fs,
                )
            )
        }
        super.writeString(text, textPositions)
    }
}
