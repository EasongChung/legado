package io.legado.app.model.document.pdf

import android.content.Context
import android.util.Log
import com.shockwave.pdfium.util.SizeF
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
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
}
