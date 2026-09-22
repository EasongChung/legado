package io.legado.app.ui.book.read.page

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout
import com.github.barteksc.pdfviewer.PDFView
import com.github.barteksc.pdfviewer.PdfViewGeometryBridge
import com.github.barteksc.pdfviewer.listener.OnDrawListener
import com.github.barteksc.pdfviewer.listener.OnPageChangeListener
import com.github.barteksc.pdfviewer.listener.OnTapListener
import com.shockwave.pdfium.util.SizeF
import io.legado.app.model.document.pdf.PdfDocumentHelper
import io.legado.app.model.document.pdf.SentenceBox
import io.legado.app.model.document.pdf.TextPositionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * PDF 原文排版与点击高亮朗读视图。
 *
 * 整合 AndroidPdfViewer 与字符几何计算：
 * 1. 矢量渲染 PDF 原文，支持手势缩放和平移；
 * 2. 页面文本预解析并构建句子集合 [SentenceBox]；
 * 3. 利用 OnDrawListener 实时绘制当前朗读句子的半透明高亮矩形（含次轴居中偏移补偿）；
 * 4. 监听单击事件，换算 PDF 页面真实坐标并命中最近句子，向宿主抛出点读回调。
 */
class PdfPageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    val pdfView: PDFView = PDFView(context, null)

    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())
    private var pdfFile: File? = null

    // 缓存各页解析出的句子列表
    private val pageSentencesCache = mutableMapOf<Int, List<SentenceBox>>()
    // 缓存各页原始 PDF 点尺寸
    private val pagePointSizes = mutableMapOf<Int, SizeF>()

    private var currentPage: Int = 0
    private var currentSentenceIndex: Int = -1
    private val highlights = mutableListOf<RectF>()
    private var highlightPage: Int = -1

    // 高亮画笔（暖色半透明）
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4DFFA726") // 半透明橙黄色
        style = Paint.Style.FILL
    }

    var onSentenceClickListener: ((SentenceBox, Int) -> Unit)? = null
    var onPageChangedListener: ((page: Int, total: Int) -> Unit)? = null
    var onCenterClickListener: (() -> Unit)? = null

    init {
        addView(pdfView, LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        PdfDocumentHelper.init(context)
    }

    fun openFile(file: File, initialPage: Int = 0) {
        this.pdfFile = file
        this.currentPage = initialPage
        pageSentencesCache.clear()
        pagePointSizes.clear()
        highlights.clear()

        pdfView.fromFile(file)
            .defaultPage(initialPage)
            .enableSwipe(true)
            .swipeHorizontal(false)
            .enableDoubletap(true)
            .defaultPage(initialPage)
            .onDraw(OnDrawListener { canvas, pageWidth, pageHeight, displayedPage ->
                drawHighlights(canvas, pageWidth, pageHeight, displayedPage)
            })
            .onPageChange(OnPageChangeListener { page, total ->
                currentPage = page
                if (highlightPage != page) {
                    highlights.clear()
                    highlightPage = -1
                }
                loadPageSentences(page)
                onPageChangedListener?.invoke(page, total)
            })
            .onTap(OnTapListener { e ->
                handleTap(e)
            })
            .load()

        loadPageSentences(initialPage)
    }

    private fun loadPageSentences(page: Int) {
        val file = pdfFile ?: return
        if (pageSentencesCache.containsKey(page)) return

        coroutineScope.launch(Dispatchers.IO) {
            val pageData = PdfDocumentHelper.extractPageData(file, page)
            val sentences = TextPositionService.buildSentences(pageData.chars, page)
            withContext(Dispatchers.Main) {
                pageSentencesCache[page] = sentences
                if (pageData.pageSize != null && pageData.pageSize.width > 0f && pageData.pageSize.height > 0f) {
                    pagePointSizes[page] = pageData.pageSize
                }
            }
        }
    }

    /** 绘制当前页朗读高亮 */
    private fun drawHighlights(canvas: Canvas, pageWidth: Float, pageHeight: Float, displayedPage: Int) {
        if (highlightPage != displayedPage || highlights.isEmpty()) return
        if (!PdfViewGeometryBridge.isReady(pdfView)) return

        val size = PdfViewGeometryBridge.getPageSize(pdfView, displayedPage)
        if (size.width <= 0f || size.height <= 0f) return

        val points = pagePointSizes[displayedPage]
        val baseW = if (points != null && points.width > 0f) points.width else size.width
        val baseH = if (points != null && points.height > 0f) points.height else size.height
        val sx = pageWidth / baseW
        val sy = pageHeight / baseH

        val secondary = PdfViewGeometryBridge.getSecondaryPageOffset(pdfView, displayedPage)
        val fixX = if (pdfView.isSwipeVertical) secondary else 0f
        val fixY = if (pdfView.isSwipeVertical) 0f else secondary

        val saved = canvas.save()
        canvas.translate(fixX, fixY)
        for (r in highlights) {
            canvas.drawRoundRect(
                r.left * sx,
                r.top * sy,
                r.right * sx,
                r.bottom * sy,
                4f * sx,
                4f * sy,
                highlightPaint
            )
        }
        canvas.restoreToCount(saved)
    }

    /** 点击处理与坐标换算 */
    private fun handleTap(e: MotionEvent): Boolean {
        if (!PdfViewGeometryBridge.isReady(pdfView)) return false

        // 检测是否点击中心区域呼出菜单
        val w = width.toFloat()
        val h = height.toFloat()
        if (w > 0 && h > 0) {
            val cx1 = w * 0.3f
            val cx2 = w * 0.7f
            val cy1 = h * 0.3f
            val cy2 = h * 0.7f
            if (e.x in cx1..cx2 && e.y in cy1..cy2) {
                onCenterClickListener?.invoke()
                return true
            }
        }

        val mappedX = -pdfView.currentXOffset + e.x
        val mappedY = -pdfView.currentYOffset + e.y
        val vertical = pdfView.isSwipeVertical
        val page = PdfViewGeometryBridge.getPageAtOffset(pdfView, if (vertical) mappedY else mappedX)

        val primary = PdfViewGeometryBridge.getPageOffset(pdfView, page)
        val secondary = PdfViewGeometryBridge.getSecondaryPageOffset(pdfView, page)
        val pageLeft = if (vertical) secondary else primary
        val pageTop = if (vertical) primary else secondary

        val zoom = pdfView.zoom
        if (zoom <= 0f) return false

        val fittedX = (mappedX - pageLeft) / zoom
        val fittedY = (mappedY - pageTop) / zoom

        val fitted = PdfViewGeometryBridge.getPageSize(pdfView, page)
        if (fitted.width <= 0f || fitted.height <= 0f) return false
        if (fittedX < 0f || fittedY < 0f || fittedX > fitted.width || fittedY > fitted.height) {
            return false
        }

        val points = pagePointSizes[page]
        val pageW = points?.width ?: fitted.width
        val pageH = points?.height ?: fitted.height
        val scaleX = if (fitted.width > 0f) pageW / fitted.width else 1f
        val scaleY = if (fitted.height > 0f) pageH / fitted.height else 1f

        val pdfX = fittedX * scaleX
        val pdfY = fittedY * scaleY

        val sentences = pageSentencesCache[page]
        if (!sentences.isNullOrEmpty()) {
            val hit = TextPositionService.hitSentence(sentences, PointF(pdfX, pdfY))
            if (hit != null) {
                highlightSentence(page, hit)
                val index = sentences.indexOf(hit)
                onSentenceClickListener?.invoke(hit, index)
                return true
            }
        }
        return false
    }

    /** 高亮指定句子 */
    fun highlightSentence(page: Int, sentence: SentenceBox) {
        highlightPage = page
        highlights.clear()
        highlights.addAll(sentence.rects)
        pdfView.invalidate()
    }

    /** 按句子索引高亮当前页某句 */
    fun highlightSentenceIndex(index: Int) {
        val sentences = pageSentencesCache[currentPage] ?: return
        if (index in sentences.indices) {
            currentSentenceIndex = index
            highlightSentence(currentPage, sentences[index])
        }
    }

    /** 清空高亮 */
    fun clearHighlights() {
        highlights.clear()
        highlightPage = -1
        currentSentenceIndex = -1
        pdfView.invalidate()
    }

    /** 获取当前页句子 */
    fun getCurrentPageSentences(): List<SentenceBox> {
        return pageSentencesCache[currentPage] ?: emptyList()
    }

    /** 跳转到下一页 */
    fun nextPage() {
        if (currentPage < pdfView.pageCount - 1) {
            pdfView.jumpTo(currentPage + 1, true)
        }
    }

    /** 跳转到上一页 */
    fun prevPage() {
        if (currentPage > 0) {
            pdfView.jumpTo(currentPage - 1, true)
        }
    }

    /** 跳转到指定页（0-indexed） */
    fun jumpTo(page: Int) {
        if (page in 0 until pdfView.pageCount) {
            currentPage = page
            pdfView.jumpTo(page, true)
        }
    }
}

