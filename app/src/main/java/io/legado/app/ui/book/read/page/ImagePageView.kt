package io.legado.app.ui.book.read.page

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import io.legado.app.model.document.ocr.OcrDocumentHelper
import io.legado.app.model.document.ocr.OcrGeometryService
import io.legado.app.model.document.pdf.SentenceBox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min

/**
 * 图片排版、离线 OCR 与逐句点读高亮视图。
 *
 * 移植自 WiseMuse 图片点读系统：
 * 1. 原图居中渲染（自适应宽高比并保持像素清晰度）；
 * 2. 异步后台触发 ML Kit 离线中文 OCR 识别并聚合自然句 [SentenceBox]；
 * 3. 屏幕触摸坐标与原图精确换算，逆向命中句子并触发点读与半透明暖色高亮；
 * 4. 暴露点读、中心菜单呼出以及翻页回调。
 */
class ImagePageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())
    private var currentFile: File? = null
    private var currentBitmap: Bitmap? = null
    private var currentPageIndex: Int = 0

    // 缓存解析出的句子列表
    private var sentences = listOf<SentenceBox>()
    var currentSentenceIndex: Int = -1
        private set

    // 当前高亮句子的归一化矩形
    private val highlightNormRects = mutableListOf<RectF>()

    // 暖色半透明高亮画笔
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4DFFA726") // 30% 透明度暖橙黄色
        style = Paint.Style.FILL
    }

    // 正在加载进度条
    private val progressBar: ProgressBar = ProgressBar(context).apply {
        visibility = View.GONE
    }

    var onSentenceClickListener: ((SentenceBox, Int) -> Unit)? = null
    var onCenterClickListener: (() -> Unit)? = null
    var onPageTurnListener: ((isNext: Boolean) -> Unit)? = null

    // 手势检测
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            return handleTap(e.x, e.y)
        }


        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            if (e1 == null) return false
            val diffX = e2.x - e1.x
            val diffY = e2.y - e1.y
            if (kotlin.math.abs(diffX) > kotlin.math.abs(diffY) && kotlin.math.abs(diffX) > 120 && kotlin.math.abs(velocityX) > 200) {
                if (diffX < 0) {
                    onPageTurnListener?.invoke(true) // 下一页
                } else {
                    onPageTurnListener?.invoke(false) // 上一页
                }
                return true
            }
            return false
        }
    })

    init {
        setWillNotDraw(false)
        val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = android.view.Gravity.CENTER
        }
        addView(progressBar, lp)
    }

    /**
     * 打开并显示指定图片文件
     */
    fun openFile(file: File, pageIndex: Int = 0) {
        if (!file.exists()) return
        this.currentFile = file
        this.currentPageIndex = pageIndex
        this.currentSentenceIndex = -1
        this.highlightNormRects.clear()

        // 异步解码 Bitmap
        coroutineScope.launch(Dispatchers.IO) {
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val bmp = runCatching { BitmapFactory.decodeFile(file.absolutePath, options) }.getOrNull()
            withContext(Dispatchers.Main) {
                currentBitmap = bmp
                invalidate()
            }

            // 触发 OCR 识别
            withContext(Dispatchers.Main) {
                progressBar.visibility = View.VISIBLE
            }
            val ocrSentences = OcrDocumentHelper.recognizeImage(context, file, pageIndex)
            withContext(Dispatchers.Main) {
                sentences = ocrSentences
                progressBar.visibility = View.GONE
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)
    }

    private fun handleTap(x: Float, y: Float): Boolean {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return false

        // 1. 三分屏区域判断（中心呼出控制栏，两侧单页翻页）
        val cx1 = w * 0.3f
        val cx2 = w * 0.7f
        val cy1 = h * 0.3f
        val cy2 = h * 0.7f
        if (x in cx1..cx2 && y in cy1..cy2) {
            onCenterClickListener?.invoke()
            return true
        } else if (x < cx1) {
            onPageTurnListener?.invoke(false)
            return true
        } else if (x > cx2) {
            onPageTurnListener?.invoke(true)
            return true
        }

        val bmp = currentBitmap ?: return false
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        if (bw <= 0 || bh <= 0) return false

        val scale = min(w / bw, h / bh)
        val dispW = bw * scale
        val dispH = bh * scale
        val offsetX = (w - dispW) / 2f
        val offsetY = (h - dispH) / 2f

        // 2. 换算为归一化原图坐标 [0..1]
        val nx = ((x - offsetX) / dispW).coerceIn(0f, 1f)
        val ny = ((y - offsetY) / dispH).coerceIn(0f, 1f)

        if (sentences.isNotEmpty()) {
            val hit = OcrGeometryService.hitSentence(sentences, PointF(nx, ny))
            if (hit != null) {
                val index = sentences.indexOf(hit)
                highlightSentence(hit, index)
                onSentenceClickListener?.invoke(hit, index)
                return true
            }
        }

        // 3. 点击两侧触发翻页
        if (x < w * 0.25f) {
            onPageTurnListener?.invoke(false)
            return true
        } else if (x > w * 0.75f) {
            onPageTurnListener?.invoke(true)
            return true
        }

        return false
    }

    /**
     * 高亮指定句子
     */
    fun highlightSentence(sentence: SentenceBox, index: Int = -1) {
        currentSentenceIndex = if (index >= 0) index else sentences.indexOf(sentence)
        highlightNormRects.clear()
        highlightNormRects.addAll(sentence.rects)
        invalidate()
    }

    /**
     * 按索引高亮句子
     */
    fun highlightSentenceIndex(index: Int) {
        if (index in sentences.indices) {
            highlightSentence(sentences[index], index)
        }
    }

    /**
     * 清空高亮
     */
    fun clearHighlights() {
        currentSentenceIndex = -1
        highlightNormRects.clear()
        invalidate()
    }

    /**
     * 获取当前提取出的句子列表
     */
    fun getSentences(): List<SentenceBox> = sentences

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = currentBitmap ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        if (bw <= 0 || bh <= 0) return

        val scale = min(w / bw, h / bh)
        val dispW = bw * scale
        val dispH = bh * scale
        val offsetX = (w - dispW) / 2f
        val offsetY = (h - dispH) / 2f

        // 绘制图片居中
        val dstRect = RectF(offsetX, offsetY, offsetX + dispW, offsetY + dispH)
        canvas.drawBitmap(bmp, null, dstRect, null)

        // 绘制高亮矩形
        if (highlightNormRects.isNotEmpty()) {
            for (nr in highlightNormRects) {
                val screenRect = RectF(
                    offsetX + nr.left * dispW,
                    offsetY + nr.top * dispH,
                    offsetX + nr.right * dispW,
                    offsetY + nr.bottom * dispH
                )
                canvas.drawRoundRect(screenRect, 6f, 6f, highlightPaint)
            }
        }
    }
}
