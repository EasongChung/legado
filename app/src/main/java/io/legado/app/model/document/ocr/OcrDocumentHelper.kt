package io.legado.app.model.document.ocr

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.net.Uri
import android.util.LruCache
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import io.legado.app.constant.AppLog
import io.legado.app.model.document.pdf.SentenceBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * ML Kit 离线中文 OCR 识别管道与缓存中枢。
 *
 * 1. 采用 `ChineseTextRecognizerOptions` 离线模型，无 GMS 依赖；
 * 2. 异步识别单张图片，将识别的文字块与行坐标归一化为 [0..1] 矩形；
 * 3. 经由 [OcrGeometryService] 聚合为自然句集合 [SentenceBox]；
 * 4. 维护 LRU 内存缓存，避免重复开销。
 */
object OcrDocumentHelper {

    private const val TAG = "OcrDocumentHelper"

    // 懒加载离线识别器
    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    // 页面 OCR 句子缓存 (文件路径 -> 句子列表)
    private val sentencesCache = LruCache<String, List<SentenceBox>>(100)

    /**
     * 识别图片文件，返回聚合后的句子列表。
     */
    suspend fun recognizeImage(context: Context, file: File, pageIndex: Int = 0): List<SentenceBox> {
        val cacheKey = "${file.absolutePath}_$pageIndex"
        sentencesCache.get(cacheKey)?.let {
            return it
        }

        return withContext(Dispatchers.IO) {
            try {
                if (!file.exists() || file.length() == 0L) {
                    return@withContext emptyList()
                }

                // 预先获取图片物理尺寸
                val boundsOptions = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeFile(file.absolutePath, boundsOptions)
                val imgWidth = boundsOptions.outWidth.toFloat()
                val imgHeight = boundsOptions.outHeight.toFloat()
                if (imgWidth <= 0f || imgHeight <= 0f) {
                    return@withContext emptyList()
                }

                val image = InputImage.fromFilePath(context, Uri.fromFile(file))
                val visionText = suspendCancellableCoroutine { cont ->
                    recognizer.process(image)
                        .addOnSuccessListener { r ->
                            cont.resume(r)
                        }
                        .addOnFailureListener { e ->
                            AppLog.put("ML Kit OCR 识别失败", e)
                            cont.resume(null)
                        }
                } ?: return@withContext emptyList()

                val ocrBlocks = mutableListOf<OcrBlock>()
                for (b in visionText.textBlocks) {
                    val blockBox = b.boundingBox ?: continue
                    val normBlockRect = RectF(
                        (blockBox.left / imgWidth).coerceIn(0f, 1f),
                        (blockBox.top / imgHeight).coerceIn(0f, 1f),
                        (blockBox.right / imgWidth).coerceIn(0f, 1f),
                        (blockBox.bottom / imgHeight).coerceIn(0f, 1f)
                    )

                    val ocrLines = mutableListOf<OcrLine>()
                    for (l in b.lines) {
                        val lineBox = l.boundingBox ?: continue
                        val normLineRect = RectF(
                            (lineBox.left / imgWidth).coerceIn(0f, 1f),
                            (lineBox.top / imgHeight).coerceIn(0f, 1f),
                            (lineBox.right / imgWidth).coerceIn(0f, 1f),
                            (lineBox.bottom / imgHeight).coerceIn(0f, 1f)
                        )
                        ocrLines.add(OcrLine(l.text, normLineRect))
                    }
                    ocrBlocks.add(OcrBlock(b.text, normBlockRect, ocrLines))
                }

                val sentences = OcrGeometryService.buildSentences(ocrBlocks, pageIndex)
                sentencesCache.put(cacheKey, sentences)
                sentences
            } catch (t: Throwable) {
                AppLog.put("OCR 流程处理异常", t)
                emptyList()
            }
        }
    }

    /**
     * 清理缓存
     */
    fun clearCache() {
        sentencesCache.evictAll()
    }
}
