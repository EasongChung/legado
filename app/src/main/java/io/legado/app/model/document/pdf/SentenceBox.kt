package io.legado.app.model.document.pdf

import android.graphics.RectF

/**
 * 句子的几何与文本。
 *
 * 一个句子可能跨越多行，因此包含多个行级矩形 [rects]。
 * 所有矩形坐标均为 PDF 页面坐标（原点在 CropBox 左上角，y 向下）。
 */
data class SentenceBox(
    val text: String,
    val rects: List<RectF>,
    val pageIndex: Int = 0,
) {
    /** 环绕整句的外接矩形 */
    val union: RectF by lazy {
        if (rects.isEmpty()) RectF()
        else {
            val r = RectF(rects.first())
            for (i in 1 until rects.size) {
                r.union(rects[i])
            }
            r
        }
    }
}

/**
 * 段落的几何与文本（句子定位失败时的兜底单位）。
 */
data class ParagraphBox(
    val text: String,
    val rects: List<RectF>,
    val pageIndex: Int = 0,
) {
    val union: RectF by lazy {
        if (rects.isEmpty()) RectF()
        else {
            val r = RectF(rects.first())
            for (i in 1 until rects.size) {
                r.union(rects[i])
            }
            r
        }
    }
}
