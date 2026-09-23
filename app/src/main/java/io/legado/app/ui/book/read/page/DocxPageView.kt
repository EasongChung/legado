package io.legado.app.ui.book.read.page

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.MotionEvent
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout
import io.legado.app.constant.AppLog
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.model.document.docx.DocxDocumentData
import io.legado.app.model.document.docx.DocxHtmlConverter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/**
 * Word (.docx) 原文排版与单页浏览视图。
 *
 * 1. 采用 WebView 富文本引擎渲染通过 DocxHtmlConverter 转换的高保真 Word 原文排版（保留加粗、斜体、字号、颜色、对齐、表格与图片）；
 * 2. 深度融入 Legado 阅读器：自适应阅读背景色与夜间模式，彻底消除割裂感；
 * 3. 采用 Legado 标准单页浏览与三分屏手势：左侧 30% 上一页、右侧 30% 下一页、中间 40% 呼出动作菜单，取消点读防误触；
 * 4. 支持与 Legado TTS 联动：按句朗读与流畅的句子高亮跟随，读完当前页自动翻至下一页。
 */
class DocxPageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val webView: WebView = WebView(context)
    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())

    var onPageChangedListener: ((page: Int, total: Int) -> Unit)? = null
    var onPageTurnListener: ((isNext: Boolean) -> Unit)? = null
    var onCenterClickListener: (() -> Unit)? = null

    private var docxData: DocxDocumentData? = null
    var currentPage: Int = 0
        private set

    var currentSentenceIndex: Int = -1
        private set

    private var downX = 0f
    private var downY = 0f
    private var isClickCandidate = false

    init {
        addView(webView, LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        setupWebView()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        settings.displayZoomControls = false
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true

        // 避免滚动条干扰阅读
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false

        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.allowFileAccessFromFileURLs = true
        settings.allowUniversalAccessFromFileURLs = true

        applyThemeColor()

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                AppLog.put("DocxWebView: ${consoleMessage?.message()}")
                return super.onConsoleMessage(consoleMessage)
            }
        }
    }

    private fun applyThemeColor() {
        val isDark = AppConfig.isNightTheme
        val bgColor = if (isDark) Color.parseColor("#121212") else {
            ReadBookConfig.bgMeanColor.takeIf { it != 0 } ?: Color.parseColor("#F5F2E9")
        }
        setBackgroundColor(bgColor)
        webView.setBackgroundColor(bgColor)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                isClickCandidate = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(ev.x - downX) > 25 || abs(ev.y - downY) > 25) {
                    isClickCandidate = false
                }
            }
            MotionEvent.ACTION_UP -> {
                if (isClickCandidate) {
                    val w = width.toFloat()
                    val h = height.toFloat()
                    if (w > 0 && h > 0) {
                        val cx1 = w * 0.30f
                        val cx2 = w * 0.70f

                        // 1. 中间 40% 区域：呼出/隐藏 Legado 动作菜单
                        if (ev.x in cx1..cx2) {
                            onCenterClickListener?.invoke()
                            return true
                        }
                        // 2. 左侧 0% ~ 30% 区域：上一页
                        else if (ev.x < cx1) {
                            onPageTurnListener?.invoke(false)
                            return true
                        }
                        // 3. 右侧 70% ~ 100% 区域：下一页
                        else {
                            onPageTurnListener?.invoke(true)
                            return true
                        }
                    }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    fun openFile(file: File, initialPage: Int = 0) {
        this.currentPage = initialPage
        applyThemeColor()

        coroutineScope.launch(Dispatchers.IO) {
            try {
                val data = DocxHtmlConverter.convertToPages(file.absolutePath)
                docxData = data
                withContext(Dispatchers.Main) {
                    val pageIndex = initialPage.coerceIn(0, (data.pages.size - 1).coerceAtLeast(0))
                    renderPage(pageIndex)
                }
            } catch (t: Throwable) {
                AppLog.put("加载 Word 文档失败: ${t.localizedMessage}", t)
                withContext(Dispatchers.Main) {
                    val isDark = AppConfig.isNightTheme
                    val bgColor = if (isDark) "#121212" else "#F5F2E9"
                    val textColor = if (isDark) "#FF6666" else "#CC0000"
                    val errorHtml = DocxHtmlConverter.buildPageHtml(
                        "<div style='padding:20px;text-align:center;'><h3>Word 文档排版加载失败</h3><p>${t.localizedMessage}</p></div>",
                        bgColor, textColor, isDark
                    )
                    webView.loadDataWithBaseURL(null, errorHtml, "text/html", "UTF-8", null)
                }
            }
        }
    }

    /**
     * 翻页渲染指定页码
     */
    fun loadPage(pageIndex: Int) {
        val data = docxData ?: return
        if (pageIndex in data.pages.indices) {
            renderPage(pageIndex)
        }
    }

    private fun renderPage(pageIndex: Int) {
        val data = docxData ?: return
        if (pageIndex !in data.pages.indices) return

        this.currentPage = pageIndex
        this.currentSentenceIndex = -1

        val page = data.pages[pageIndex]
        val isDark = AppConfig.isNightTheme
        val bgColorInt = if (isDark) Color.parseColor("#121212") else {
            ReadBookConfig.bgMeanColor.takeIf { it != 0 } ?: Color.parseColor("#F5F2E9")
        }
        val bgColorHex = String.format("#%06X", 0xFFFFFF and bgColorInt)
        val textColorHex = if (isDark) "#CCCCCC" else "#2C3E50"

        applyThemeColor()
        val fullHtml = DocxHtmlConverter.buildPageHtml(page.htmlBody, bgColorHex, textColorHex, isDark)
        webView.loadDataWithBaseURL("file:///android_asset/", fullHtml, "text/html", "UTF-8", null)

        onPageChangedListener?.invoke(pageIndex, data.pages.size)
    }

    /**
     * 获取当前页提取的纯文本句子列表（用于 Legado TTS 朗读）
     */
    fun getCurrentSentences(): List<String> {
        val data = docxData ?: return emptyList()
        if (currentPage in data.pages.indices) {
            return data.pages[currentPage].sentences
        }
        return emptyList()
    }

    /**
     * 获取当前文档总页数
     */
    fun getPageCount(): Int {
        return docxData?.pages?.size ?: 0
    }

    /**
     * 通知 WebView 高亮指定句子/段落索引并平滑滚动
     */
    fun highlightIndex(index: Int) {
        currentSentenceIndex = index
        webView.evaluateJavascript("highlightIndex($index);", null)
    }

    fun destroy() {
        try {
            webView.stopLoading()
            webView.removeAllViews()
            webView.destroy()
        } catch (_: Throwable) {}
    }
}
