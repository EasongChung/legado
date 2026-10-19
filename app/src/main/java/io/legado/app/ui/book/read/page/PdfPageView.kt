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
import com.github.barteksc.pdfviewer.listener.OnLoadCompleteListener
import com.github.barteksc.pdfviewer.listener.OnPageChangeListener
import com.github.barteksc.pdfviewer.listener.OnTapListener
import com.github.barteksc.pdfviewer.util.FitPolicy
import com.shockwave.pdfium.util.SizeF
import io.legado.app.constant.AppLog
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.model.document.pdf.PdfDocumentHelper
import io.legado.app.model.document.pdf.SentenceBox
import io.legado.app.model.document.pdf.TextPositionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

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
    var currentSentenceIndex: Int = -1
        private set
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

    private var isFileLoaded = false
    private var isFileLoading = false
    private var loadedFilePath: String? = null

    fun openFile(file: File, initialPage: Int = 0) {
        if (!file.exists()) return

        // 状态防抖：同一个文件如果正在加载或已加载完成，仅跳转目标页，绝不重复触发底层 Native load()
        if (loadedFilePath == file.absolutePath && (isFileLoaded || isFileLoading)) {
            if (currentPage != initialPage) {
                jumpTo(initialPage)
            }
            return
        }

        this.loadedFilePath = file.absolutePath
        this.isFileLoading = true
        this.isFileLoaded = false
        this.pdfFile = file
        this.currentPage = initialPage
        pageSentencesCache.clear()
        pagePointSizes.clear()
        highlights.clear()

        val isDark = AppConfig.isNightTheme
        val bgColor = if (isDark) Color.parseColor("#121212") else {
            ReadBookConfig.bgMeanColor.takeIf { it != 0 } ?: Color.parseColor("#F5F2E9")
        }
        setBackgroundColor(bgColor)
        pdfView.setBackgroundColor(bgColor)

        pdfView.fromFile(file)
            .defaultPage(initialPage)
            .enableSwipe(true)
            .swipeHorizontal(true)
            .pageSnap(true)
            .pageFling(true)
            .autoSpacing(true)
            .pageFitPolicy(FitPolicy.WIDTH)
            .fitEachPage(true)
            .enableDoubletap(true)
            .nightMode(isDark)
            .onLoad(OnLoadCompleteListener { _ ->
                isFileLoading = false
                isFileLoaded = true
            })
            .onError { t ->
                isFileLoading = false
                isFileLoaded = false
                AppLog.put("PdfView load error: ${t.localizedMessage}", t)
            }
            .onPageError { page, t ->
                AppLog.put("PdfView load page $page error: ${t.localizedMessage}", t)
            }
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
            try {
                val pageData = PdfDocumentHelper.extractPageData(file, page)
                var sentences = TextPositionService.buildSentences(pageData.chars, page)
                // 视觉拓扑排序 (top 升序, left 升序)，确保第 0 句必定是页面首行
                val sortedSentences = sortSentencesVisually(sentences)

                withContext(Dispatchers.Main) {
                    pageSentencesCache[page] = sortedSentences
                    if (pageData.pageSize != null && pageData.pageSize.width > 0f && pageData.pageSize.height > 0f) {
                        pagePointSizes[page] = pageData.pageSize
                    }
                }
            } catch (t: Throwable) {
                AppLog.put("loadPageSentences error on page $page: ${t.localizedMessage}", t)
            }
        }
    }

    /**
     * 句子视觉拓扑排序 (top 升序, left 升序)，消除流对象乱序导致的朗读起始跳行
     */
    private fun sortSentencesVisually(list: List<SentenceBox>): List<SentenceBox> {
        return list.sortedWith { a, b ->
            val aTop = a.rects.firstOrNull()?.top ?: 0f
            val bTop = b.rects.firstOrNull()?.top ?: 0f
            val aLeft = a.rects.firstOrNull()?.left ?: 0f
            val bLeft = b.rects.firstOrNull()?.left ?: 0f
            if (abs(aTop - bTop) < 8f) {
                aLeft.compareTo(bLeft)
            } else {
                aTop.compareTo(bTop)
            }
        }
    }

    /** 绘制当前页朗读高亮 */
    private fun drawHighlights(canvas: Canvas, pageWidth: Float, pageHeight: Float, displayedPage: Int) {
        try {
            if (highlightPage != displayedPage || highlights.isEmpty()) return
            if (!PdfViewGeometryBridge.isReady(pdfView)) return

            val size = PdfViewGeometryBridge.getPageSize(pdfView, displayedPage)
            if (size.width <= 0f || size.height <= 0f) return

            val points = pagePointSizes[displayedPage]
            val baseW = if (points != null && points.width > 0f) points.width else size.width
            val baseH = if (points != null && points.height > 0f) points.height else size.height
            if (baseW <= 0f || baseH <= 0f) return

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
        } catch (t: Throwable) {
            AppLog.put("drawHighlights failed: ${t.localizedMessage}", t)
        }
    }

    /** 点击处理：三分屏手势对齐 Legado 原生阅读操作，彻底取消正文点读防误触 */
    private fun handleTap(e: MotionEvent): Boolean {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return false

        val x = e.x
        val y = e.y

        val cx1 = w * 0.3f
        val cx2 = w * 0.7f
        val cy1 = h * 0.3f
        val cy2 = h * 0.7f

        // 1. 中间 30% ~ 70% 区域：呼出/隐藏 Legado 动作菜单
        if (x in cx1..cx2 && y in cy1..cy2) {
            onCenterClickListener?.invoke()
            return true
        }

        // 2. 左侧 0% ~ 30% 区域：上一页（单页）
        if (x < cx1) {
            prevPage()
            return true
        }

        // 3. 右侧 70% ~ 100% 区域：下一页（单页）
        if (x > cx2) {
            nextPage()
            return true
        }

        // 4. 上下边缘区域默认呼出菜单
        onCenterClickListener?.invoke()
        return true
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
        if (PdfViewGeometryBridge.isReady(pdfView) && currentPage < pdfView.pageCount - 1) {
            jumpTo(currentPage + 1)
        }
    }

    /** 跳转到上一页 */
    fun prevPage() {
        if (PdfViewGeometryBridge.isReady(pdfView) && currentPage > 0) {
            jumpTo(currentPage - 1)
        }
    }

    /** 跳转到指定页（0-indexed） */
    fun jumpTo(page: Int) {
        if (!PdfViewGeometryBridge.isReady(pdfView)) {
            currentPage = page
            return
        }
        val targetPage = page.coerceIn(0, (pdfView.pageCount - 1).coerceAtLeast(0))
        currentPage = targetPage
        pdfView.jumpTo(targetPage, true)
        loadPageSentences(targetPage)
    }

    /** 安全销毁并释放引用，避免生命周期并发冲突 */
    fun destroy() {
        try {
            coroutineScope.coroutineContext[Job]?.cancel()
            loadedFilePath = null
            isFileLoaded = false
            isFileLoading = false
            pdfView.recycle()
        } catch (_: Throwable) {}
    }
}

