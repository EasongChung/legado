package io.legado.app.ui.book.read.page

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout
import io.legado.app.model.document.docx.DocxHtmlConverter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Word (.docx) 原文排版与点击朗读视图。
 *
 * 利用原生 WebView 渲染通过 DocxHtmlConverter 生成的轻量流式排版 HTML，
 * 通过 JavascriptInterface 双向通信实现点击即读和高亮跟随。
 */
class DocxPageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val webView: WebView = WebView(context)
    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())

    var onSentenceClickListener: ((String) -> Unit)? = null
    var onCenterClickListener: (() -> Unit)? = null

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

        webView.setBackgroundColor(0) // 透明背景，继承 Legado 阅读主题底色

        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onSentenceClick(text: String) {
                post {
                    if (text.isNotBlank()) {
                        onSentenceClickListener?.invoke(text.trim())
                    }
                }
            }
        }, "DocReadBridge")
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                isClickCandidate = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (Math.abs(ev.x - downX) > 20 || Math.abs(ev.y - downY) > 20) {
                    isClickCandidate = false
                }
            }
            MotionEvent.ACTION_UP -> {
                if (isClickCandidate) {
                    val w = width.toFloat()
                    val h = height.toFloat()
                    if (w > 0 && h > 0) {
                        val cx1 = w * 0.35f
                        val cx2 = w * 0.65f
                        val cy1 = h * 0.35f
                        val cy2 = h * 0.65f
                        if (ev.x in cx1..cx2 && ev.y in cy1..cy2) {
                            onCenterClickListener?.invoke()
                        }
                    }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    fun openFile(file: File) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val html = DocxHtmlConverter.convert(file.absolutePath)
                withContext(Dispatchers.Main) {
                    webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    val errorHtml = "<html><body style='padding:20px;color:red;'><h3>加载 Word 文档失败</h3><p>${t.message}</p></body></html>"
                    webView.loadDataWithBaseURL(null, errorHtml, "text/html", "UTF-8", null)
                }
            }
        }
    }

    /** 通知前端高亮指定索引的段落/句子，并平滑居中滚动 */
    fun highlightIndex(index: Int) {
        webView.evaluateJavascript("highlightIndex($index);", null)
    }

    fun destroy() {
        webView.stopLoading()
        webView.removeAllViews()
        webView.destroy()
    }
}
