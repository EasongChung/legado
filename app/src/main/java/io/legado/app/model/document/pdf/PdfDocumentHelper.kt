package io.legado.app.model.document.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import com.shockwave.pdfium.util.SizeF
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import io.legado.app.constant.AppLog
import java.io.File
import java.io.InputStream

/**
 * PDFBox 文档管理与解析助手。
 */
object PdfDocumentHelper {
    private const val TAG = "PdfDocumentHelper"
    private var isInitialized = false

    data class PageData(
        val chars: List<CharBox>,
        val pageSize: SizeF?
    )

    @Synchronized
    fun init(context: Context) {
        if (!isInitialized) {
            try {
                PDFBoxResourceLoader.init(context.applicationContext)
                isInitialized = true
            } catch (t: Throwable) {
                Log.e(TAG, "PDFBoxResourceLoader.init 失败", t)
            }
        }
    }

    /** 提取指定页的字符列表（页码 pageIndex 从 0 开始） */
    fun extractChars(file: File, pageIndex: Int): List<CharBox> {
        return extractPageData(file, pageIndex).chars
    }

    /** 提取指定页的字符列表与页面真实的物理点尺寸（CropBox） */
    fun extractPageData(file: File, pageIndex: Int): PageData {
        if (!file.exists()) return PageData(emptyList(), null)
        val doc = PDDocument.load(file)
        try {
            if (pageIndex < 0 || pageIndex >= doc.numberOfPages) return PageData(emptyList(), null)
            val page = doc.getPage(pageIndex)
            val cropBox = page.cropBox ?: page.mediaBox
            val size = if (cropBox != null) SizeF(cropBox.width, cropBox.height) else null

            val stripper = CharBoxStripper()
            stripper.startPage = pageIndex + 1
            stripper.endPage = pageIndex + 1
            stripper.getText(doc)
            return PageData(stripper.boxes, size)
        } catch (t: Throwable) {
            Log.e(TAG, "提取第 $pageIndex 页数据失败", t)
            return PageData(emptyList(), null)
        } finally {
            doc.close()
        }
    }

    /** 提取指定页构建好的句子列表（pageIndex 从 0 开始） */
    fun extractSentences(file: File, pageIndex: Int): List<SentenceBox> {
        val chars = extractChars(file, pageIndex)
        return TextPositionService.buildSentences(chars, pageIndex)
    }

    /** 获取总页数 */
    fun getPageCount(file: File): Int {
        if (!file.exists()) return 0
        val doc = PDDocument.load(file)
        try {
            return doc.numberOfPages
        } finally {
            doc.close()
        }
    }

    /**
     * 将 PDF 指定页渲染为 Bitmap，用于 OCR 兜底或图文混排识别。
     */
    fun renderPageToBitmap(file: File, pageIndex: Int, targetWidth: Int = 1200): Bitmap? {
        if (!file.exists()) return null
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        var page: PdfRenderer.Page? = null
        return try {
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            if (pageIndex < 0 || pageIndex >= renderer.pageCount) return null
            page = renderer.openPage(pageIndex)
            val origW = page.width
            val origH = page.height
            val scale = targetWidth.toFloat() / origW.coerceAtLeast(1)
            val w = (origW * scale).toInt().coerceAtLeast(1)
            val h = (origH * scale).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        } catch (t: Throwable) {
            AppLog.put("PdfRenderer 渲染第 $pageIndex 页位图失败", t)
            null
        } finally {
            try { page?.close() } catch (_: Throwable) {}
            try { renderer?.close() } catch (_: Throwable) {}
            try { pfd?.close() } catch (_: Throwable) {}
        }
    }

    /**
     * 提取 PDF 原生大纲（书签），返回页码（0-indexed）到书签标题的映射。
     */
    fun extractOutline(file: File): Map<Int, String> {
        val map = mutableMapOf<Int, String>()
        if (!file.exists()) return map
        try {
            val doc = PDDocument.load(file)
            doc.use { document ->
                val outline = document.documentCatalog?.documentOutline ?: return map
                fun traverse(item: com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem) {
                    try {
                        val title = item.title
                        val page = when (val dest = item.destination) {
                            is com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination -> dest.page
                            else -> {
                                val action = item.action
                                if (action is com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo) {
                                    (action.destination as? com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination)?.page
                                } else null
                            }
                        }
                        if (page != null && !title.isNullOrBlank()) {
                            val pageIdx = document.pages.indexOf(page)
                            if (pageIdx >= 0 && !map.containsKey(pageIdx)) {
                                map[pageIdx] = title.trim()
                            }
                        }
                    } catch (_: Throwable) {}
                    var child = item.firstChild
                    while (child != null) {
                        traverse(child)
                        child = child.nextSibling
                    }
                }
                var cur = outline.firstChild
                while (cur != null) {
                    traverse(cur)
                    cur = cur.nextSibling
                }
            }
        } catch (t: Throwable) {
            AppLog.put("PDF 大纲提取失败", t)
        }
        return map
    }
}
